# flink-jdbc — dev environment on GCP (spec docs/superpowers/specs/2026-10-20-flink-jdbc-design.md, "GCP deployment").
#
# Every module is the Flink lane's, called by relative path (one-way reuse, like the Maven reactor; nothing is
# copied). What is new here: a second Cloud SQL instance (the Source System), the read-only reader's password in
# Secret Manager (etlr-source-db), and the source-seeder Cloud Run Job.
#
# Apply order (and why there are three applies) is docs/runbook-gcp.md. Everything that bills is OFF by default;
# the TTL guard is ON. Names start with mig-flink because the reused modules name things that way (see
# ) — so this environment must not share a project with a live Flink lane.

terraform {
  required_version = ">= 1.9"

  required_providers {
    google      = { source = "hashicorp/google", version = "~> 6.0" }
    google-beta = { source = "hashicorp/google-beta", version = "~> 6.0" }
    time        = { source = "hashicorp/time", version = "~> 0.12" }
    kubernetes  = { source = "hashicorp/kubernetes", version = "~> 2.30" }
    helm        = { source = "hashicorp/helm", version = "~> 2.14" }
    random      = { source = "hashicorp/random", version = "~> 3.6" }
  }
  # Local state on purpose (see backend.tf.example and docs/runbook-gcp.md).
}

provider "google" {
  project = var.project_id
  region  = var.region
}

provider "google-beta" {
  project = var.project_id
  region  = var.region
}

data "google_client_config" "this" {}

data "google_container_cluster" "flink" {
  count = var.enable_flink_cluster ? 1 : 0

  name     = module.flink_gke.gke_cluster_name
  location = var.region
  project  = var.project_id
}

provider "kubernetes" {
  host                   = try("https://${data.google_container_cluster.flink[0].endpoint}", "https://localhost")
  token                  = data.google_client_config.this.access_token
  cluster_ca_certificate = try(base64decode(data.google_container_cluster.flink[0].master_auth[0].cluster_ca_certificate), "")
}

provider "helm" {
  kubernetes {
    host                   = try("https://${data.google_container_cluster.flink[0].endpoint}", "https://localhost")
    token                  = data.google_client_config.this.access_token
    cluster_ca_certificate = try(base64decode(data.google_container_cluster.flink[0].master_auth[0].cluster_ca_certificate), "")
  }
}

# ── variables ─────────────────────────────────────────────────────────────────

variable "project_id" {
  type        = string
  description = "A project dedicated to this lane (no default on purpose: say which project you are about to bill)."
}
variable "region" {
  type    = string
  default = "europe-west1"
}
variable "billing_account" {
  type        = string
  description = "An OPEN billing account id."
}
variable "org_id" {
  type    = string
  default = ""
}
variable "create_project" {
  type    = bool
  default = false
}
variable "enable_cloudsql" {
  type        = bool
  default     = false
  description = "Both Cloud SQL instances (lane + Source System), db-custom-1-3840 each. Off by default, unlike the Flink lane: every billable part of this environment is."
}
variable "enable_kafka" {
  type        = bool
  default     = false
  description = "Managed Kafka is billed per vCPU-hour."
}
variable "enable_flink_cluster" {
  type        = bool
  default     = false
  description = "The GKE node pool bills continuously."
}
variable "enable_scheduler" {
  type        = bool
  default     = false
  description = "Cloud Scheduler + the orchestrator Cloud Run Job. Needs the orchestrator image pushed first."
}
variable "enable_seeder" {
  type        = bool
  default     = false
  description = "The source-seeder Cloud Run Job. Free when idle, but its image must exist before the apply (a missing image fails and taints the job)."
}
variable "kafka_bootstrap" {
  type        = string
  default     = ""
  description = "Managed Kafka's real bootstrap address — not derivable from state. After the cluster exists: gcloud managed-kafka clusters describe mig-flink-kafka --location=<region> --format='value(bootstrapAddress)'."
}

check "kafka_bootstrap_provided" {
  assert {
    condition     = !var.enable_kafka || var.kafka_bootstrap != ""
    error_message = "enable_kafka=true but kafka_bootstrap is empty: the mock and the orchestrator get no broker. After the cluster exists: gcloud managed-kafka clusters describe mig-flink-kafka --location=<region> --format='value(bootstrapAddress)', then -var=kafka_bootstrap=<that>."
  }
}

variable "enable_ttl_guard" {
  type        = bool
  default     = true
  description = "ON by default: after ttl_hours a scheduled job deletes GKE, both Cloud SQL instances, Managed Kafka, the VPC connector and the mock, and pauses the trigger (../flink/gcp/ttl/sweep.sh)."
}
variable "ttl_hours" {
  type    = number
  default = 3
}
variable "ttl_dry_run" {
  type    = bool
  default = false
}
variable "run_id" {
  type        = string
  default     = ""
  description = "Empty = discover every run the source vouched for (gate-jdbc --discover) that has no done/ or failed/ marker."
}
variable "flink_image_tag" {
  type    = string
  default = "latest"
}
variable "gke_node_zones" {
  type    = list(string)
  default = []
}
variable "cloudsql_name_suffix" {
  type        = string
  default     = ""
  description = "Cloud SQL keeps a deleted instance's name reserved for up to a week. Set e.g. \"-b\" to re-apply within that window."
}

# ── modules ───────────────────────────────────────────────────────────────────

module "bootstrap" {
  source = "../../../../flink/terraform/modules/bootstrap"

  project_id      = var.project_id
  billing_account = var.billing_account
  org_id          = var.org_id
  region          = var.region
  create_project  = var.create_project
}

module "network" {
  source = "../../../../flink/terraform/modules/network"

  project_id = var.project_id
  region     = var.region
  depends_on = [module.bootstrap]
}

module "storage" {
  source = "../../../../flink/terraform/modules/storage"

  project_id    = var.project_id
  region        = var.region
  force_destroy = true # dev only
  depends_on    = [module.bootstrap]
}

# The lane's database: the Flink lane's schema plus sql/postgres-init-jdbc.sql (applied by `seed.sh init`).
module "cloudsql" {
  source = "../../../../flink/terraform/modules/cloudsql"

  project_id          = var.project_id
  region              = var.region
  network_id          = module.network.network_id
  deletion_protection = false
  enabled             = var.enable_cloudsql
  instance_name       = "mig-flink-jdbc-lane${var.cloudsql_name_suffix}"
  depends_on          = [module.bootstrap]
}

# The Source System: private IP, same VPC, smallest tier. It reuses the lane instance's peering (one per VPC).
module "cloudsql_source" {
  source = "../../../../flink/terraform/modules/cloudsql"

  project_id          = var.project_id
  region              = var.region
  network_id          = module.network.network_id
  deletion_protection = false
  enabled             = var.enable_cloudsql
  instance_name       = "mig-flink-jdbc-src${var.cloudsql_name_suffix}"
  manage_peering      = false
  database_name       = "source"
  depends_on          = [module.bootstrap, module.cloudsql]
}

# etlr_reader is created by `seed.sh init` with SQL (a Cloud SQL API user would be a cloudsqlsuperuser member, not
# read-only). Terraform only generates and stores its password.
resource "random_password" "etlr_reader" {
  length  = 32
  special = false
}

module "iam" {
  source = "../../../../flink/terraform/modules/iam"

  project_id       = var.project_id
  buckets          = module.storage.buckets
  kafka_cluster_id = module.kafka.cluster_id
}

module "secrets" {
  source = "../../../../flink/terraform/modules/secrets"

  project_id         = var.project_id
  region             = var.region
  extra_secret_names = ["etlr-source-db", "etlr-source-admin"]

  # The orchestrator and the seeder both run as flink-job.
  accessors = {
    cloudsql-app-password = module.iam.service_accounts["flink-job"]
    etlr-source-db        = module.iam.service_accounts["flink-job"]
    etlr-source-admin     = module.iam.service_accounts["flink-job"]
  }

  # Only when the instances exist: with Cloud SQL off their passwords are null and a version cannot be written.
  versioned_secrets = var.enable_cloudsql ? ["cloudsql-app-password", "etlr-source-db", "etlr-source-admin"] : []
  secret_values = var.enable_cloudsql ? {
    "cloudsql-app-password" = module.cloudsql.app_password
    "etlr-source-db"        = random_password.etlr_reader.result
    "etlr-source-admin"     = module.cloudsql_source.app_password
  } : {}

  depends_on = [module.bootstrap]
}

module "artifact_registry" {
  source = "../../../../flink/terraform/modules/artifact_registry"

  project_id = var.project_id
  region     = var.region
  depends_on = [module.bootstrap]
}

module "kafka" {
  source = "../../../../flink/terraform/modules/kafka"

  project_id        = var.project_id
  region            = var.region
  enabled           = var.enable_kafka
  subnet_id         = module.network.subnet_id
  bootstrap_address = var.kafka_bootstrap
}

# Needed by everything on Cloud Run that reaches a private IP: the seeder and the orchestrator (both databases),
# the mock and the orchestrator (Kafka).
module "vpc_connector" {
  source = "../../../../flink/terraform/modules/vpc_connector"

  project_id = var.project_id
  region     = var.region
  network_id = module.network.network_id
  enabled    = var.enable_cloudsql || var.enable_kafka
  depends_on = [module.bootstrap]
}

module "target_system_mock" {
  source = "../../../../flink/terraform/modules/target_system_mock"

  project_id = var.project_id
  region     = var.region
  enabled    = var.enable_kafka
  image      = "${module.artifact_registry.registry}/target-system-mock:${var.flink_image_tag}"

  kafka_bootstrap    = try(module.kafka.bootstrap_servers, "")
  target_topic       = try(module.kafka.topics["target"], "target-system-target")
  confirmation_topic = try(module.kafka.topics["confirmations"], "target-system-confirmations")
  rejection_topic    = try(module.kafka.topics["rejections"], "target-system-rejections")
  service_account    = module.iam.service_accounts["target-system-mock"]
  vpc_connector_id   = module.vpc_connector.connector_id
  depends_on         = [module.bootstrap]
}

module "flink_gke" {
  source     = "../../../../flink/terraform/modules/flink_gke"
  node_zones = var.gke_node_zones

  project_id                   = var.project_id
  region                       = var.region
  network_id                   = module.network.network_id
  subnet_id                    = module.network.subnet_id
  pods_range_name              = module.network.pods_range_name
  services_range_name          = module.network.services_range_name
  enabled                      = var.enable_flink_cluster
  orchestrator_service_account = module.iam.service_accounts["flink-job"]
  flink_job_service_account    = module.iam.service_accounts["flink-job"]
  depends_on                   = [module.bootstrap]
}

module "ttl_guard" {
  source = "../../../../flink/terraform/modules/ttl_guard"

  project_id = var.project_id
  region     = var.region
  enabled    = var.enable_ttl_guard
  ttl_hours  = var.ttl_hours
  dry_run    = var.ttl_dry_run
  bucket     = module.storage.landing_bucket
  image      = "${module.artifact_registry.registry}/orchestrator:${var.flink_image_tag}"
  depends_on = [module.bootstrap]
}

module "scheduler" {
  source = "../../../../flink/terraform/modules/scheduler"

  project_id                        = var.project_id
  region                            = var.region
  enabled                           = var.enable_scheduler
  image                             = "${module.artifact_registry.registry}/orchestrator:${var.flink_image_tag}"
  service_account                   = module.iam.service_accounts["flink-job"]
  scheduler_invoker_service_account = module.iam.service_accounts["flink-job"]
  landing_bucket                    = module.storage.landing_bucket
  vpc_connector_id                  = try(module.vpc_connector.connector_id, "")

  env_vars = {
    RUN_ID             = var.run_id
    GCP_PROJECT        = var.project_id
    GCP_REGION         = var.region
    FLINK_GKE_CLUSTER  = module.flink_gke.gke_cluster_name
    FLINK_IMAGE_TAG    = var.flink_image_tag
    GCS_LANDING_BUCKET = module.storage.landing_bucket

    CLOUDSQL_JDBC_URL            = module.cloudsql.jdbc_url
    CLOUDSQL_APP_USER            = module.cloudsql.app_user
    CLOUDSQL_APP_PASSWORD_SECRET = module.secrets.secret_ids["cloudsql-app-password"]

    SOURCE_JDBC_URL               = module.cloudsql_source.jdbc_url
    SOURCE_READER_USER            = "etlr_reader"
    SOURCE_READER_PASSWORD_SECRET = module.secrets.secret_ids["etlr-source-db"]

    KAFKA_BOOTSTRAP                  = try(module.kafka.bootstrap_servers, "")
    KAFKA_SECURITY_PROTOCOL          = var.enable_kafka ? "SASL_SSL" : "PLAINTEXT"
    TARGET_SYSTEM_TARGET_TOPIC       = try(module.kafka.topics["target"], "target-system-target")
    TARGET_SYSTEM_CONFIRMATION_TOPIC = try(module.kafka.topics["confirmations"], "target-system-confirmations")
  }
  depends_on = [module.bootstrap]
}

# The source-seeder (spec "Seeding the source"): one execution per action, inside the VPC. For example:
#   gcloud run jobs execute mig-flink-jdbc-source-seeder --region=<region> --args=fixture,run-gcp-fixture-001 --wait
resource "google_cloud_run_v2_job" "source_seeder" {
  count = var.enable_seeder && var.enable_cloudsql ? 1 : 0

  name                = "mig-flink-jdbc-source-seeder"
  project             = var.project_id
  location            = var.region
  deletion_protection = false

  template {
    template {
      service_account = module.iam.service_accounts["flink-job"]
      timeout         = "1800s"
      max_retries     = 0

      vpc_access {
        connector = module.vpc_connector.connector_id
        egress    = "PRIVATE_RANGES_ONLY"
      }

      containers {
        image = "${module.artifact_registry.registry}/source-seeder:${var.flink_image_tag}"

        env {
          name  = "SOURCE_HOST"
          value = module.cloudsql_source.private_ip_address
        }
        env {
          name  = "LANE_HOST"
          value = module.cloudsql.private_ip_address
        }
        env {
          name = "SOURCE_ADMIN_PASSWORD"
          value_source {
            secret_key_ref {
              secret  = module.secrets.secret_ids["etlr-source-admin"]
              version = "latest"
            }
          }
        }
        env {
          name = "READER_PASSWORD"
          value_source {
            secret_key_ref {
              secret  = module.secrets.secret_ids["etlr-source-db"]
              version = "latest"
            }
          }
        }
        env {
          name = "LANE_PASSWORD"
          value_source {
            secret_key_ref {
              secret  = module.secrets.secret_ids["cloudsql-app-password"]
              version = "latest"
            }
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

      volumes {
        name = "landing"
        gcs {
          bucket    = module.storage.landing_bucket
          read_only = false
        }
      }
    }
  }

  depends_on = [module.secrets]
}

# ── outputs ───────────────────────────────────────────────────────────────────

output "landing_bucket" { value = module.storage.landing_bucket }
output "artifact_registry" { value = module.artifact_registry.registry }
output "lane_jdbc_url" { value = module.cloudsql.jdbc_url }
output "source_jdbc_url" { value = module.cloudsql_source.jdbc_url }
output "flink_gke_cluster_name" { value = module.flink_gke.gke_cluster_name }
output "orchestrator_job_name" { value = module.scheduler.job_name }
output "scheduler_job_name" { value = module.scheduler.scheduler_job_name }
output "seeder_job_name" { value = one(google_cloud_run_v2_job.source_seeder[*].name) }
output "ttl_expires_at" { value = module.ttl_guard.expires_at }
output "kafka_bootstrap" { value = module.kafka.bootstrap_servers }
output "target_system_mock_url" { value = module.target_system_mock.url }
