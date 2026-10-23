# Service accounts, one per component, least privilege.
#
# Adapted from beam-dataflow/terraform/modules/iam. Same
# separation principle: the Flink job's SA can read the landing bucket and write Cloud
# SQL, but cannot read recon's evidence bucket; recon can read everything and write only
# its own reports.

variable "project_id" { type = string }
variable "buckets" { type = map(string) }

variable "kafka_cluster_id" {
  type        = string
  default     = ""
  description = "Managed Kafka cluster id, or empty when Kafka is off — gates the roles/managedkafka.client grants below."
}

locals {
  accounts = {
    flink-job          = "GKE workload identity for the three Flink jobs (intake/curate/produce, or the DataStream job)"
    gate               = "Gate pod: .FLG/.CHS check before Flink runs"
    recon-service      = "Reconciliation service (read-mostly)"
    target-system-mock = "Target System mock on Cloud Run (Kafka-only intake, publishes confirmations)"
    composer-runner    = "Cloud Composer environment service account"
  }

  project_roles = merge({
    # flink-job also runs the scheduler orchestrator (module.scheduler).
    # — it submits FlinkDeployment CRs to the flink_gke cluster the same way
    # composer-runner would have, so it needs the same grant.
    "roles/container.developer"          = ["composer-runner", "flink-job"]
    "roles/secretmanager.secretAccessor" = ["flink-job", "composer-runner"]
    "roles/artifactregistry.reader"      = ["flink-job", "composer-runner"]
    "roles/cloudsql.client"              = ["flink-job", "recon-service", "composer-runner"]
    "roles/monitoring.viewer"            = ["composer-runner"]
    }, var.kafka_cluster_id == "" ? {} : {
    # Managed Kafka has no cluster-level IAM resource (provider exposes only cluster,
    # topic, acl — verified the same way beam-dataflow verified it, via
    # `terraform providers schema -json`, not assumed). Project-level client role,
    # gated on kafka_cluster_id so a no-Kafka apply creates no grants.
    "roles/managedkafka.client" = ["flink-job", "recon-service", "target-system-mock"]
  })

  bucket_roles = {
    landing = {
      # objectAdmin, not objectViewer: the orchestrator (running as flink-job) writes
      # the untarred extract to work/<run_id>/ via gate — objectViewer would
      # deny the write and every run would fail at the first Flink job with no data
      #.
      flink-job = "roles/storage.objectAdmin"
      gate      = "roles/storage.objectAdmin"
    }
    recon = {
      recon-service = "roles/storage.objectAdmin"
      flink-job     = "roles/storage.objectViewer"
    }
  }
}

resource "google_service_account" "this" {
  for_each = local.accounts

  project      = var.project_id
  account_id   = "mig-flink-${each.key}"
  display_name = each.value
}

locals {
  role_bindings = flatten([
    for role, accts in local.project_roles : [
      for acct in accts : { role = role, account = acct }
    ]
  ])
}

resource "google_project_iam_member" "this" {
  for_each = { for b in local.role_bindings : "${b.role}/${b.account}" => b }

  project = var.project_id
  role    = each.value.role
  member  = "serviceAccount:${google_service_account.this[each.value.account].email}"
}

locals {
  bucket_role_bindings = flatten([
    for bucket, accts in local.bucket_roles : [
      for acct, role in accts : { bucket = bucket, account = acct, role = role }
    ]
  ])
}

resource "google_storage_bucket_iam_member" "this" {
  for_each = { for b in local.bucket_role_bindings : "${b.bucket}/${b.account}" => b }

  bucket = var.buckets[each.value.bucket]
  role   = each.value.role
  member = "serviceAccount:${google_service_account.this[each.value.account].email}"
}

output "service_accounts" {
  value = { for k, sa in google_service_account.this : k => sa.email }
}
