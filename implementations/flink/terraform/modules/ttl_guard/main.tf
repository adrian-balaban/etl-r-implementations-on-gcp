# TTL guard — a teardown that does not depend on anyone being awake.
#
# Cloud Scheduler (hourly) runs a Cloud Run Job that executes gcp/ttl/sweep.sh. Once the
# environment's expiry — a timestamp Terraform writes into the landing bucket at apply time — has
# passed, the sweeper deletes what bills by the hour (GKE, Cloud SQL, Managed Kafka, the VPC
# connector, the mock) and pauses the orchestrator's trigger.
#
# WHY IT EXISTS: the first real GCP attempt (2026-09-24) overran its own 3-hour hard stop by ~20
# hours. Nothing in the repo bounded how long a stack could leak; the `enable_*` flags limit WHAT
# can bill, not FOR HOW LONG. A rule that needs a person at the right moment is a suggestion.
#
# WHAT IT IS NOT: a `terraform destroy`. It uses gcloud, so it needs no state, no Terraform and no
# laptop — the point of a kill switch — and it leaves Terraform's state stale (a later plan will want
# to re-create what was swept). It leaves alone what costs nothing idle: buckets, secrets, the VPC,
# Cloud Run jobs.
#
# EXTENDING: re-applying does NOT extend the TTL (the expiry is a time_static, fixed when created).
# To extend by hand:  echo $(( $(date +%s) + 7200 )) | gsutil cp - gs://<bucket>/ttl/expires-at
# To re-arm from Terraform: change ttl_hours (it is a trigger) or `terraform taint`
# module.ttl_guard.time_static.armed_at.
#
# UNVERIFIED ON GCP: written and `terraform validate`d; its script is behaviour-tested against stub
# gcloud/gsutil (TtlSweeperBehaviourTest) but has never run against a real project. The first time it
# is enabled, run it with dry_run = true and read the log before trusting it with a delete.

terraform {
  required_providers {
    time = {
      source  = "hashicorp/time"
      version = "~> 0.12"
    }
  }
}

variable "project_id" { type = string }
variable "region" { type = string }
variable "enabled" {
  type        = bool
  default     = true
  description = "false creates nothing. A guard that is off protects nothing — see envs/dev for the default."
}
variable "ttl_hours" {
  type        = number
  default     = 6
  description = "Hours from apply until the environment is swept. Provisioning alone can take ~40 minutes (Kafka ~22, Cloud SQL ~10), so keep this well above that."

  validation {
    condition     = var.ttl_hours >= 1
    error_message = "ttl_hours must be at least 1 — a shorter TTL would sweep an environment while it is still provisioning."
  }
}
variable "dry_run" {
  type        = bool
  default     = false
  description = "true: the sweeper only logs what it WOULD delete. Use it the first time this is enabled on a real project."
}
variable "bucket" {
  type        = string
  description = "Bucket that holds ttl/expires-at (the landing bucket)."
}
variable "image" {
  type        = string
  description = "An image containing /app/sweep.sh — the orchestrator image, which already has gcloud and gsutil."
}
variable "name_prefix" {
  type        = string
  default     = "mig-flink"
  description = "The sweeper only touches resources whose name starts with this."
}
variable "schedule" {
  type    = string
  default = "17 * * * *" # hourly, off the :00 mark where scheduled jobs pile up
}

resource "time_static" "armed_at" {
  count = var.enabled ? 1 : 0

  # Changing ttl_hours re-arms the timer; a plain re-apply does not move the expiry.
  triggers = { ttl_hours = tostring(var.ttl_hours) }
}

resource "google_storage_bucket_object" "expires_at" {
  count = var.enabled ? 1 : 0

  bucket  = var.bucket
  name    = "ttl/expires-at"
  content = "${time_static.armed_at[0].unix + var.ttl_hours * 3600}\n"
}

resource "google_service_account" "sweeper" {
  count = var.enabled ? 1 : 0

  project      = var.project_id
  account_id   = "mig-flink-ttl-sweeper"
  display_name = "TTL sweeper — deletes the billable parts of the environment after its expiry"
}

# Broad on purpose and narrow in use: it must be able to delete these kinds of resources, and the
# script only ever touches names under name_prefix. Nothing here can create anything.
resource "google_project_iam_member" "sweeper" {
  for_each = var.enabled ? toset([
    "roles/container.admin",
    "roles/cloudsql.admin",
    "roles/managedkafka.admin",
    "roles/run.admin",
    "roles/cloudscheduler.admin",
    "roles/vpcaccess.admin",
  ]) : toset([])

  project = var.project_id
  role    = each.value
  member  = "serviceAccount:${google_service_account.sweeper[0].email}"
}

resource "google_storage_bucket_iam_member" "sweeper_bucket" {
  count = var.enabled ? 1 : 0

  bucket = var.bucket
  role   = "roles/storage.objectAdmin" # reads expires-at, writes swept-at
  member = "serviceAccount:${google_service_account.sweeper[0].email}"
}

resource "google_cloud_run_v2_job" "sweeper" {
  count = var.enabled ? 1 : 0

  name                = "mig-flink-ttl-sweeper"
  project             = var.project_id
  location            = var.region
  deletion_protection = false

  template {
    template {
      service_account = google_service_account.sweeper[0].email
      timeout         = "900s"
      max_retries     = 0 # the next hourly firing is the retry

      containers {
        image   = var.image
        command = ["/app/sweep.sh"]

        env {
          name  = "PROJECT"
          value = var.project_id
        }
        env {
          name  = "REGION"
          value = var.region
        }
        env {
          name  = "TTL_BUCKET"
          value = var.bucket
        }
        env {
          name  = "NAME_PREFIX"
          value = var.name_prefix
        }
        env {
          name  = "DRY_RUN"
          value = var.dry_run ? "1" : "0"
        }
      }
    }
  }
}

resource "google_cloud_run_v2_job_iam_member" "sweeper_invoker" {
  count = var.enabled ? 1 : 0

  project  = var.project_id
  location = var.region
  name     = google_cloud_run_v2_job.sweeper[0].name
  role     = "roles/run.invoker"
  member   = "serviceAccount:${google_service_account.sweeper[0].email}"
}

resource "google_cloud_scheduler_job" "sweeper" {
  count = var.enabled ? 1 : 0

  name      = "mig-flink-ttl-sweeper-trigger"
  project   = var.project_id
  region    = var.region
  schedule  = var.schedule
  time_zone = "Etc/UTC"

  http_target {
    http_method = "POST"
    uri         = "https://${var.region}-run.googleapis.com/apis/run.googleapis.com/v1/namespaces/${var.project_id}/jobs/${google_cloud_run_v2_job.sweeper[0].name}:run"

    oauth_token {
      service_account_email = google_service_account.sweeper[0].email
    }
  }

  retry_config {
    retry_count = 0
  }

  depends_on = [google_cloud_run_v2_job_iam_member.sweeper_invoker]
}

output "expires_at_epoch" {
  value       = var.enabled ? time_static.armed_at[0].unix + var.ttl_hours * 3600 : null
  description = "When the sweeper will start deleting, as a Unix timestamp."
}
output "expires_at" {
  value = var.enabled ? timeadd(time_static.armed_at[0].rfc3339, "${var.ttl_hours}h") : null
}
