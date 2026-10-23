# Cloud Scheduler + Cloud Run Job — replaces Composer as the orchestrator. NEW module: the
# alternative the plan named as the revisable choice, now the default instead of a footnote — Composer costs ~$300-400/month
# even idle (module.composer, commented out in envs/dev/main.tf).
#
# COST, verified against cloud.google.com/run/pricing: Cloud Run Jobs
# bill instance-based, with a documented MINIMUM OF 1 MINUTE per execution regardless of
# how quickly the container exits. At a 1-minute schedule, that is effectively continuous
# billing — NOT "a few cents": ~$47-52/month for a 1 vCPU / 1 GiB orchestrator running
# 1440 executions/day. Still far below Composer, but budget for it, not for "cents".
# Cloud Scheduler itself is free (first 3 jobs/month/billing-account).
#
# Shape: `google_cloud_scheduler_job` fires an HTTP request every minute (schedule
# "* * * * *") at the Cloud Run Jobs Admin API's `:run` endpoint, authenticated with an
# OIDC token from a dedicated SA holding `roles/run.invoker` on the job. The job itself
# (`orchestrator`, image TBD — gcp/docker/Dockerfile.orchestrator, not written in this
# pass) is what used to be the DAG's task sequence: check `landing/extraction/*/ACCOUNT.FLG`
# for an unprocessed run, and if one exists, run gate -> the three Flink jobs ->
# recon -> exit non-zero on failure, in one Cloud Run Job execution.
#
# Traded away versus Composer, explicitly:
#   - no Airflow UI / task-level retry — a whole orchestrator run either completes or
#     fails as one unit; a mid-sequence failure re-processes from gate on the
#     next minute's trigger, not from the failed step alone.
#   - no native `GCSObjectExistenceSensor` — the orchestrator itself polls GCS at the
#     top of each run, which is what makes the 1-minute period the effective "sensor".
#   - Cloud Run Jobs cap execution time (default 10 min, configurable up to 24h) —
#     comfortably above this repo's fixture-scale runs, unverified at migration volume.
#
# Overlapping executions — HANDLED, in the orchestrator image rather than here. Cloud
# Scheduler fires every minute whether or not the previous execution finished, and
# `google_cloud_run_v2_job` has no equivalent of a Kubernetes CronJob's `concurrencyPolicy`,
# so this cannot be expressed in Terraform at all. gcp/orchestrator/run.sh takes a GCS
# object lock with `x-goog-if-generation-match:0` (atomic create-if-absent) as its first
# action and releases it on exit; a losing execution exits 0 immediately, and a lock older
# than LOCK_TTL_SECONDS is force-broken so a killed execution cannot wedge the lane.

variable "vpc_connector_id" {
  type        = string
  default     = ""
  description = "Serverless VPC Access connector the orchestrator egresses through to reach Cloud SQL (private IP) and Managed Kafka. Empty = no VPC egress, which only works for a lane with neither."
}
variable "project_id" { type = string }
variable "region" { type = string }
variable "enabled" {
  type        = bool
  default     = false
  description = "Off by default, matching every other component's convention — the orchestrator image (gcp/docker/Dockerfile.orchestrator) does not exist yet either."
}
variable "schedule" {
  type        = string
  default     = "* * * * *"
  description = "Every minute — the replacement for Composer's GCSObjectExistenceSensor poke_interval."
}
variable "image" {
  type        = string
  description = "Orchestrator image — runs the gate -> flink_intake -> flink_curate_enrich -> flink_produce -> recon sequence in one Cloud Run Job execution."
}
variable "service_account" {
  type        = string
  description = "SA the Cloud Run Job executes as — needs the same grants module.composer's service_account had (Cloud SQL client, Kafka client, Secret Manager, GKE access to submit FlinkDeployments)."
}
variable "scheduler_invoker_service_account" {
  type        = string
  description = "Separate SA Cloud Scheduler uses to invoke the job — least privilege: this one needs only roles/run.invoker on the job, not the job's own runtime grants."
}
variable "env_vars" {
  type        = map(string)
  default     = {}
  description = "Same variables module.composer's env_variables carried (FLINK_GKE_CLUSTER, CLOUDSQL_JDBC_URL, KAFKA_BOOTSTRAP, ...) — the orchestrator reads these instead of Airflow's Composer-managed env."
}
variable "timeout_seconds" {
  type    = number
  default = 1800 # 30 min — generous for a fixture-scale run; unverified at migration volume
}
variable "landing_bucket" {
  type        = string
  description = "Mounted at /mnt/gcs via Cloud Run's native GCS volume mount — gcp/orchestrator/run.sh's gate step reads/writes here so Gate's existing filesystem code runs unchanged."
}

resource "google_cloud_run_v2_job" "orchestrator" {
  count = var.enabled ? 1 : 0

  name     = "mig-flink-orchestrator"
  project  = var.project_id
  location = var.region

  deletion_protection = false

  template {
    template {
      service_account = var.service_account
      timeout         = "${var.timeout_seconds}s"

      # Without this the orchestrator can start, take its lock, extract the bundle and
      # submit all three Flink jobs — and then fail at `recon`, which connects to Cloud SQL
      # on its PRIVATE IP and settles against Managed Kafka's VPC-internal bootstrap; neither
      # is reachable from Cloud Run's default egress. PRIVATE_RANGES_ONLY keeps everything
      # else (GCS, the GKE control plane's public endpoint, gcloud) on the normal path. The
      # target-system-mock already does exactly this; the orchestrator, written later and never
      # run, did not — found by reading it against the mock before its first execution.
      dynamic "vpc_access" {
        for_each = var.vpc_connector_id == "" ? [] : [1]
        content {
          connector = var.vpc_connector_id
          egress    = "PRIVATE_RANGES_ONLY"
        }
      }
      max_retries = 0 # the orchestrator's own idempotency is what makes a retry safe, not Cloud Run's

      containers {
        image = var.image

        dynamic "env" {
          for_each = var.env_vars
          content {
            name  = env.key
            value = env.value
          }
        }

        resources {
          limits = {
            cpu    = "1"
            memory = "1Gi"
          }
        }

        volume_mounts {
          name       = "landing"
          mount_path = "/mnt/gcs"
        }
      }

      # Native GCS FUSE volume — works inside Cloud Run Jobs' gVisor sandbox, unlike a
      # manually shelled-out `gcsfuse` process.
      volumes {
        name = "landing"
        gcs {
          bucket    = var.landing_bucket
          read_only = false
        }
      }
    }
  }
}

resource "google_cloud_run_v2_job_iam_member" "scheduler_invoker" {
  count = var.enabled ? 1 : 0

  project  = var.project_id
  location = var.region
  name     = google_cloud_run_v2_job.orchestrator[0].name
  role     = "roles/run.invoker"
  member   = "serviceAccount:${var.scheduler_invoker_service_account}"
}

resource "google_cloud_scheduler_job" "orchestrator" {
  count = var.enabled ? 1 : 0

  name     = "mig-flink-orchestrator-trigger"
  project  = var.project_id
  region   = var.region
  schedule = var.schedule
  # Same time zone convention as the region — a schedule string alone is ambiguous
  # without one, and Cloud Scheduler defaults to UTC silently if this is omitted.
  time_zone = "Etc/UTC"

  http_target {
    http_method = "POST"
    uri         = "https://${var.region}-run.googleapis.com/apis/run.googleapis.com/v1/namespaces/${var.project_id}/jobs/${google_cloud_run_v2_job.orchestrator[0].name}:run"

    oauth_token {
      service_account_email = var.scheduler_invoker_service_account
    }
  }

  retry_config {
    retry_count = 0 # the orchestrator's own next-minute trigger is the retry, not Scheduler's
  }

  depends_on = [google_cloud_run_v2_job_iam_member.scheduler_invoker]
}

output "job_name" {
  value = var.enabled ? google_cloud_run_v2_job.orchestrator[0].name : ""
}
output "scheduler_job_name" {
  value = var.enabled ? google_cloud_scheduler_job.orchestrator[0].name : ""
}
