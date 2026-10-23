# test-gcp-flink — dev environment.
#
# Modelled on beam-dataflow/terraform/envs/dev/main.tf —
# same apply order, same "expensive parts default off" convention. Apply order matters;
# see docs/runbook-gcp.md. Terraform cannot create its own state
# bucket, so module.bootstrap is applied first with local state, then state is migrated.
#
# Three components are expensive even idle and default to OFF: Composer (~$300-400/month),
# Managed Kafka (billed per vCPU-hour), and the Flink GKE cluster (a node pool that bills
# continuously, unlike Dataflow's per-job billing — ).

terraform {
  required_version = ">= 1.9"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 6.0"
    }
    google-beta = {
      source  = "hashicorp/google-beta"
      version = "~> 6.0"
    }
    time = {
      source  = "hashicorp/time"
      version = "~> 0.12"
    }
    kubernetes = {
      source  = "hashicorp/kubernetes"
      version = "~> 2.30"
    }
    helm = {
      source  = "hashicorp/helm"
      version = "~> 2.14"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }

  # backend "gcs" — commented for a local-state run. The bucket name is per-project, so hard-coding one
  # project's bucket here is what made this block wrong for any other project.
  # backend "gcs" {
  #   bucket = "<project>-tfstate"
  #   prefix = "envs/dev"
  # }
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

# Credentials for whichever GKE cluster is live: the Flink cluster (always, when
# enabled) or Composer's own (for composer_rbac). Both providers below wrap in try()
# the same way beam-dataflow's kubernetes provider does — a cluster that does not
# exist yet (flag off) must not break `terraform plan`.
data "google_container_cluster" "flink" {
  count = var.enable_flink_cluster ? 1 : 0

  name     = module.flink_gke.gke_cluster_name
  location = var.region
  project  = var.project_id
}

# data "google_container_cluster" "composer" — commented out with module.composer above
#: it referenced module.composer.gke_cluster_name, which no longer
# exists in this file's active resource graph. Restore alongside module.composer.
#
# data "google_container_cluster" "composer" {
#   count = var.enable_composer && var.composer_pod_namespace != "" ? 1 : 0
#
#   name     = module.composer.gke_cluster_name
#   location = var.region
#   project  = var.project_id
# }

provider "kubernetes" {
  host = try(
    "https://${data.google_container_cluster.flink[0].endpoint}",
    "https://localhost"
  )
  token = data.google_client_config.this.access_token
  cluster_ca_certificate = try(
    base64decode(data.google_container_cluster.flink[0].master_auth[0].cluster_ca_certificate),
    ""
  )
}

provider "helm" {
  kubernetes {
    host = try(
      "https://${data.google_container_cluster.flink[0].endpoint}",
      "https://localhost"
    )
    token = data.google_client_config.this.access_token
    cluster_ca_certificate = try(
      base64decode(data.google_container_cluster.flink[0].master_auth[0].cluster_ca_certificate),
      ""
    )
  }
}

# ── variables ─────────────────────────────────────────────────────────────────

variable "project_id" {
  type = string
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
variable "enable_composer" {
  type        = bool
  default     = false
  description = "Cloud Composer 2 costs ~$300-400/month even idle. module.composer is commented out in this file — this variable is kept, unused, so re-enabling only needs uncommenting the module block, not re-adding the variable."
}
variable "enable_scheduler" {
  type        = bool
  default     = false
  description = "Cloud Scheduler (1-minute period) + a Cloud Run Job orchestrator — the default replacement for Composer. Costs cents, not hundreds of dollars, but the orchestrator image does not exist yet."
}
variable "enable_kafka" {
  type        = bool
  default     = false
  description = "Managed Kafka is billed per vCPU-hour."
}
variable "enable_flink_cluster" {
  type        = bool
  default     = false
  description = "GKE node pool for the Flink Kubernetes Operator bills continuously, unlike Dataflow's per-job billing."
}
variable "composer_pod_namespace" {
  type    = string
  default = ""
}
variable "kafka_bootstrap" {
  type        = string
  default     = ""
  description = "Managed Kafka's real bootstrap address. Not derivable from Terraform state (the provider does not export it), so it is a second-stage input: create the cluster, read it with `gcloud managed-kafka clusters describe <id> --location=<region> --format='value(bootstrapAddress)'`, pass it here."
}

# A warning, not an error: on the FIRST apply the cluster does not exist yet, so an empty value
# is expected and blocking on it would make the cluster impossible to create. On every apply
# after that, an empty value with Kafka on means the mock and the orchestrator get no broker.
check "kafka_bootstrap_provided" {
  assert {
    condition     = !var.enable_kafka || var.kafka_bootstrap != ""
    error_message = "enable_kafka=true but kafka_bootstrap is empty: the target-system-mock and the orchestrator will be given no broker address. After the cluster exists: gcloud managed-kafka clusters describe <cluster_id> --location=<region> --format='value(bootstrapAddress)', then -var=kafka_bootstrap=<that>."
  }
}

variable "enable_ttl_guard" {
  type        = bool
  default     = true
  description = "ON by default, unlike every other enable_* flag, because it is the only one that limits HOW LONG something bills. After ttl_hours a scheduled job deletes GKE, Cloud SQL, Managed Kafka, the VPC connector and the mock (gcp/ttl/sweep.sh). Set false for a long-lived environment; see ."
}
variable "ttl_hours" {
  type        = number
  default     = 3
  description = "Hours from apply until the TTL guard sweeps the environment."
}
variable "ttl_dry_run" {
  type        = bool
  default     = false
  description = "true: the sweeper only logs what it would delete. Use this the first time on a real project — it has never run against real GCP."
}

variable "run_id" {
  type        = string
  default     = ""
  description = "Empty (the default) = the orchestrator DISCOVERS every extraction/<run_id>/incoming/ACCOUNT.FLG without a done/ or failed/ marker. Set a value to pin the job to exactly one run — how the 2026-09-25 deploy ran, with run-gcp-001."
}
variable "flink_image_tag" {
  type    = string
  default = "latest"
}

# ── modules ───────────────────────────────────────────────────────────────────

module "bootstrap" {
  source = "../../modules/bootstrap"

  project_id      = var.project_id
  billing_account = var.billing_account
  org_id          = var.org_id
  region          = var.region
  create_project  = var.create_project
}

module "network" {
  source = "../../modules/network"

  project_id = var.project_id
  region     = var.region

  depends_on = [module.bootstrap]
}

module "storage" {
  source = "../../modules/storage"

  project_id    = var.project_id
  region        = var.region
  force_destroy = true # dev only

  depends_on = [module.bootstrap]
}

variable "enable_cloudsql" {
  type        = bool
  default     = true
  description = ": the last module that used to have no off switch. true is the sensible default (the lane needs a database); false takes the environment to a genuine ~$0 floor."
}

module "cloudsql" {
  source = "../../modules/cloudsql"

  project_id          = var.project_id
  region              = var.region
  network_id          = module.network.network_id
  deletion_protection = false # dev only

  depends_on = [module.bootstrap]
  enabled    = var.enable_cloudsql
}

module "iam" {
  source = "../../modules/iam"

  project_id       = var.project_id
  buckets          = module.storage.buckets
  kafka_cluster_id = module.kafka.cluster_id
}

module "secrets" {
  source = "../../modules/secrets"

  project_id = var.project_id
  region     = var.region

  # The orchestrator (Cloud Run Job) runs as the flink-job service account, so that is who reads
  # the password and — for an encrypted delivery — the PGP key material.
  accessors = {
    cloudsql-app-password = module.iam.service_accounts["flink-job"]
    pgp-private-key       = module.iam.service_accounts["flink-job"]
    pgp-passphrase        = module.iam.service_accounts["flink-job"]
  }

  # Terraform generates the Cloud SQL password, so it also stores it — the secret used to be
  # created empty. The PGP secrets are NOT versioned here: they come from whoever operates the
  # extract, out of band, and must not pass through Terraform state.
  versioned_secrets = ["cloudsql-app-password"]
  secret_values     = { "cloudsql-app-password" = module.cloudsql.app_password }

  depends_on = [module.bootstrap]
}

module "artifact_registry" {
  source = "../../modules/artifact_registry"

  project_id = var.project_id
  region     = var.region

  depends_on = [module.bootstrap]
}

module "kafka" {
  source = "../../modules/kafka"

  project_id = var.project_id
  region     = var.region
  enabled    = var.enable_kafka
  subnet_id  = module.network.subnet_id

  bootstrap_address = var.kafka_bootstrap
}

module "vpc_connector" {
  source = "../../modules/vpc_connector"

  project_id = var.project_id
  region     = var.region
  network_id = module.network.network_id
  enabled    = var.enable_kafka

  depends_on = [module.bootstrap]
}

module "target_system_mock" {
  source = "../../modules/target_system_mock"

  project_id = var.project_id
  region     = var.region
  enabled    = var.enable_kafka # this mock's only intake is Kafka — no point running it without a broker
  image      = "${module.artifact_registry.registry}/target-system-mock:${var.flink_image_tag}"

  kafka_bootstrap    = try(module.kafka.bootstrap_servers, "")
  target_topic       = try(module.kafka.topics["target"], "target-system-target")
  confirmation_topic = try(module.kafka.topics["confirmations"], "target-system-confirmations")
  rejection_topic    = try(module.kafka.topics["rejections"], "target-system-rejections")
  service_account    = module.iam.service_accounts["target-system-mock"]
  vpc_connector_id   = module.vpc_connector.connector_id

  depends_on = [module.bootstrap]
}

variable "gke_node_zones" {
  type        = list(string)
  default     = []
  description = "Restrict the Flink GKE node VMs to these zones (empty = GKE decides). The workaround for a regional Compute Engine stock-out; see modules/flink_gke."
}

module "flink_gke" {
  source     = "../../modules/flink_gke"
  node_zones = var.gke_node_zones

  project_id          = var.project_id
  region              = var.region
  network_id          = module.network.network_id
  subnet_id           = module.network.subnet_id
  pods_range_name     = module.network.pods_range_name
  services_range_name = module.network.services_range_name
  enabled             = var.enable_flink_cluster

  orchestrator_service_account = module.iam.service_accounts["flink-job"]
  flink_job_service_account    = module.iam.service_accounts["flink-job"]

  depends_on = [module.bootstrap]
}

# module.composer / module.composer_rbac — COMMENTED OUT, not deleted.
# Composer costs ~$300-400/month even idle; module.scheduler below (Cloud Scheduler,
# 1-minute period, + a Cloud Run Job orchestrator) replaces it as the default, at a cost
# of cents rather than hundreds of dollars. The modules themselves
# (terraform/modules/composer, terraform/modules/composer_rbac) are untouched and still
# `terraform validate`-clean on their own — uncomment this block (and the
# `enable_composer`/`composer_pod_namespace` variables above, never removed) to go back
# to DAG-based orchestration; nothing else in this file depends on Composer existing.
#
# module "composer" {
#   source = "../../modules/composer"
#
#   project_id      = var.project_id
#   region          = var.region
#   enabled         = var.enable_composer
#   service_account = module.iam.service_accounts["composer-runner"]
#   network_id      = module.network.network_id
#   subnet_id       = module.network.subnet_id
#   buckets         = module.storage.buckets
#
#   flink_image_tag                      = var.flink_image_tag
#   flink_gke_cluster_name               = module.flink_gke.gke_cluster_name
#   cloudsql_jdbc_url                    = module.cloudsql.jdbc_url
#   cloudsql_app_user                    = module.cloudsql.app_user
#   kafka_bootstrap                      = try(module.kafka.bootstrap_servers, "")
#   kafka_topic                          = try(module.kafka.topics["target"], "target-system-target")
#   target_system_confirmation_bootstrap = try(module.kafka.bootstrap_servers, "")
#   target_system_confirmation_topic     = try(module.kafka.topics["confirmations"], "target-system-confirmations")
# }
#
# module "composer_rbac" {
#   source = "../../modules/composer_rbac"
#
#   enabled   = var.enable_composer && var.composer_pod_namespace != ""
#   namespace = var.composer_pod_namespace
#
#   project_id = var.project_id
#
#   depends_on = [module.composer]
#
#   app_service_accounts = {
#     gate = module.iam.service_accounts["gate"]
#     recon        = module.iam.service_accounts["recon-service"]
#   }
# }

module "ttl_guard" {
  source = "../../modules/ttl_guard"

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
  source = "../../modules/scheduler"

  project_id = var.project_id
  region     = var.region
  enabled    = var.enable_scheduler
  image      = "${module.artifact_registry.registry}/orchestrator:${var.flink_image_tag}"

  service_account                   = module.iam.service_accounts["flink-job"]
  scheduler_invoker_service_account = module.iam.service_accounts["flink-job"]
  landing_bucket                    = module.storage.landing_bucket
  vpc_connector_id                  = try(module.vpc_connector.connector_id, "")

  env_vars = {
    # RUN_ID is optional. Empty => the orchestrator discovers pending runs from the bucket (one
    # subprocess each, so a failure in one cannot leak its lock or abort the others); a value pins
    # the job to that single run. Either way every stage is idempotent, the lock and the
    # done/failed markers are per run_id, and the attempt cap stops a persistent failure.
    RUN_ID = var.run_id

    GCP_PROJECT       = var.project_id
    GCP_REGION        = var.region
    FLINK_GKE_CLUSTER = module.flink_gke.gke_cluster_name
    FLINK_IMAGE_TAG   = var.flink_image_tag
    CLOUDSQL_JDBC_URL = module.cloudsql.jdbc_url
    CLOUDSQL_APP_USER = module.cloudsql.app_user
    # The password is fetched from Secret Manager by the orchestrator at run time
    # (CLOUDSQL_APP_PASSWORD_SECRET), not handed to the job as a plain environment variable
    #. It was a documented shortcut for a short-lived environment; it also
    # meant the value sat in the Cloud Run Job's spec for anyone with run.jobs.get to read.
    CLOUDSQL_APP_PASSWORD_SECRET         = module.secrets.secret_ids["cloudsql-app-password"]
    GCS_LANDING_BUCKET                   = module.storage.landing_bucket
    GCS_RECON_BUCKET                     = module.storage.recon_bucket
    KAFKA_BOOTSTRAP                      = try(module.kafka.bootstrap_servers, "")
    KAFKA_TOPIC                          = try(module.kafka.topics["target"], "target-system-target")
    KAFKA_SECURITY_PROTOCOL              = var.enable_kafka ? "SASL_SSL" : "PLAINTEXT"
    TARGET_SYSTEM_CONFIRMATION_BOOTSTRAP = try(module.kafka.bootstrap_servers, "")
    TARGET_SYSTEM_CONFIRMATION_TOPIC     = try(module.kafka.topics["confirmations"], "target-system-confirmations")
    # run.sh reads TARGET_SYSTEM_TARGET_TOPIC (the same name target-system-mock and the
    # DataStream variant use); KAFKA_TOPIC above is kept for continuity with the model
    # repo's env_file. Both point at the same topic — named twice, deliberately, rather
    # than relying on run.sh's fallback matching the Terraform-created topic by luck.
    TARGET_SYSTEM_TARGET_TOPIC = try(module.kafka.topics["target"], "target-system-target")
  }

  depends_on = [module.bootstrap]
}

# ── outputs ───────────────────────────────────────────────────────────────────

output "buckets" { value = module.storage.buckets }
output "service_accounts" { value = module.iam.service_accounts }
output "artifact_registry" { value = module.artifact_registry.registry }
output "cloudsql_jdbc_url" { value = module.cloudsql.jdbc_url }
# Needed to apply sql/postgres-init.sql from a pod on the VPC — the instance has no public
# IP and this environment runs no Auth Proxy, so the private address and the generated
# password are the only way in (and the only way to dump tables for an evidence pack).
output "cloudsql_private_ip" { value = module.cloudsql.private_ip_address }
output "cloudsql_instance_connection_name" { value = module.cloudsql.instance_connection_name }
output "flink_gke_cluster_name" { value = module.flink_gke.gke_cluster_name }
# output "composer_dag_bucket" { value = module.composer.dag_bucket }
# output "composer_airflow_uri" { value = module.composer.airflow_uri }
output "ttl_expires_at" { value = module.ttl_guard.expires_at }
output "scheduler_job_name" { value = module.scheduler.scheduler_job_name }
output "orchestrator_job_name" { value = module.scheduler.job_name }
output "kafka_bootstrap" { value = module.kafka.bootstrap_servers }
output "target_system_mock_url" { value = module.target_system_mock.url }
output "cloudsql_app_password" {
  value     = module.cloudsql.app_password
  sensitive = true
}

# Paste into a job's env to point it at this environment — mirrors beam-dataflow's
# env_file output and the reasoning behind it: Terraform state
# is the one place these values cannot drift from what was actually built.
output "env_file" {
  value = <<-EOT
    GCS_LANDING_BUCKET=${module.storage.landing_bucket}
    GCS_RECON_BUCKET=${module.storage.recon_bucket}
    CLOUDSQL_JDBC_URL=${module.cloudsql.jdbc_url}
    CLOUDSQL_APP_USER=${module.cloudsql.app_user}
    KAFKA_BOOTSTRAP=${try(module.kafka.bootstrap_servers, "")}
    KAFKA_SECURITY_PROTOCOL=SASL_SSL
    TARGET_SYSTEM_CONFIRMATION_TOPIC=target-system-confirmations
  EOT
}
