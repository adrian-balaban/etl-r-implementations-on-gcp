# Cloud Composer 2 — the DAG orchestrator.
#
# Adapted from beam-dataflow/terraform/modules/composer.
# COST WARNING unchanged: ~$300-400/month even idle (the GKE Autopilot cluster and the
# Airflow DB exist regardless of whether a DAG is running) — `enabled` defaults to false.

variable "project_id" { type = string }
variable "region" { type = string }
variable "enabled" {
  type    = bool
  default = false
}
variable "service_account" { type = string }
variable "network_id" { type = string }
variable "subnet_id" { type = string }
variable "buckets" { type = map(string) }
variable "airflow_version" {
  type    = string
  default = "composer-2.9.7-airflow-2.9.3"
}

variable "flink_image_tag" {
  type        = string
  default     = "latest"
  description = "Tag make build-images published for the Flink job image and target-system-mock — a git SHA, plus -dirty on an unclean tree."
}
variable "flink_gke_cluster_name" { type = string }
variable "cloudsql_jdbc_url" { type = string }
variable "cloudsql_app_user" { type = string }
variable "kafka_bootstrap" {
  type    = string
  default = ""
}
variable "kafka_topic" {
  type    = string
  default = "target-system-target"
}
variable "target_system_confirmation_bootstrap" {
  type    = string
  default = ""
}
variable "target_system_confirmation_topic" {
  type    = string
  default = "target-system-confirmations"
}

data "google_project" "this" {
  count      = var.enabled ? 1 : 0
  project_id = var.project_id
}

# Composer 2 prerequisite — same failure mode as beam-dataflow: Error 400 failedPrecondition without this grant, made before
# environment creation.
resource "google_project_iam_member" "composer_service_agent_ext" {
  count   = var.enabled ? 1 : 0
  project = var.project_id
  role    = "roles/composer.ServiceAgentV2Ext"
  member  = "serviceAccount:service-${data.google_project.this[0].number}@cloudcomposer-accounts.iam.gserviceaccount.com"
}

resource "google_project_service" "gke" {
  count              = var.enabled ? 1 : 0
  project            = var.project_id
  service            = "container.googleapis.com"
  disable_on_destroy = false
}

resource "google_composer_environment" "this" {
  count      = var.enabled ? 1 : 0
  depends_on = [google_project_iam_member.composer_service_agent_ext, google_project_service.gke]

  name    = "mig-flink-composer"
  project = var.project_id
  region  = var.region

  config {
    software_config {
      image_version = var.airflow_version

      env_variables = {
        GCP_REGION         = var.region
        GCS_LANDING_BUCKET = var.buckets["landing"]
        GCS_RECON_BUCKET   = var.buckets["recon"]

        # The FlinkDeployment CRs get applied against this cluster (composer/dags reads
        # this to build kubectl/kubernetes-provider calls scoped to it) — same "Terraform
        # owns these vars, an out-of-band gcloud update gets reverted" caution as the
        # beam-dataflow's MIG_TARGET_SYSTEM_URL incident.
        FLINK_GKE_CLUSTER                    = var.flink_gke_cluster_name
        FLINK_IMAGE_TAG                      = var.flink_image_tag
        CLOUDSQL_JDBC_URL                    = var.cloudsql_jdbc_url
        CLOUDSQL_APP_USER                    = var.cloudsql_app_user
        KAFKA_BOOTSTRAP                      = var.kafka_bootstrap
        KAFKA_SECURITY_PROTOCOL              = var.kafka_bootstrap != "" ? "SASL_SSL" : "PLAINTEXT"
        KAFKA_TOPIC                          = var.kafka_topic
        TARGET_SYSTEM_CONFIRMATION_BOOTSTRAP = var.target_system_confirmation_bootstrap
        TARGET_SYSTEM_CONFIRMATION_TOPIC     = var.target_system_confirmation_topic
      }

      airflow_config_overrides = {
        secrets-backend = "airflow.providers.google.cloud.secrets.secret_manager.CloudSecretManagerBackend"
      }
    }

    node_config {
      service_account = var.service_account
      network         = var.network_id
      subnetwork      = var.subnet_id
    }

    workloads_config {
      scheduler {
        cpu        = 0.5
        memory_gb  = 2
        storage_gb = 1
        count      = 1
      }
      web_server {
        cpu        = 0.5
        memory_gb  = 2
        storage_gb = 1
      }
      worker {
        cpu        = 0.5
        memory_gb  = 2
        storage_gb = 1
        min_count  = 1
        max_count  = 3
      }
    }

    environment_size = "ENVIRONMENT_SIZE_SMALL"

    private_environment_config {
      enable_private_endpoint = false
    }
  }
}

output "gke_cluster_name" {
  value = var.enabled ? element(split("/", google_composer_environment.this[0].config[0].gke_cluster), length(split("/", google_composer_environment.this[0].config[0].gke_cluster)) - 1) : ""
}
output "dag_bucket" {
  value = var.enabled ? google_composer_environment.this[0].config[0].dag_gcs_prefix : ""
}
output "airflow_uri" {
  value = var.enabled ? google_composer_environment.this[0].config[0].airflow_uri : ""
}
