# GCS buckets — replaces the local ./work bind mount.
#
# Ported and reduced from beam-dataflow's storage module: only `landing` (extracts,
# .FLG/.CHS/bundle — gate's input) and `recon` (evidence: table dumps, recon
# reports) exist here. No `json-out` (this lane's Load edge is the Flink job's Kafka sink,
# not a GCS staging bucket — ) and no `dataflow-*` (no Dataflow in this repo).

variable "project_id" { type = string }
variable "region" { type = string }
variable "force_destroy" {
  type        = bool
  default     = false
  description = "Only ever true in dev. A migration's landing bucket is evidence."
}
variable "landing_retention_days" {
  type        = number
  default     = 90
  description = "Encrypted source extracts. Long enough to re-run a migration wave."
}

locals {
  buckets = {
    landing = {
      # Extracts land here, PGP-optional bundle: ACCOUNT.tar.gz + ACCOUNT.FLG
      # (harness/bundle.py's local shape) under extraction/<run_id>/.
      lifecycle_days = var.landing_retention_days
      versioning     = true
    }
    recon = {
      # Table dumps and reconciliation reports — the audit trail. Kept longest.
      lifecycle_days = 365
      versioning     = true
    }
  }
}

resource "google_storage_bucket" "this" {
  for_each = local.buckets

  name     = "${var.project_id}-${each.key}"
  project  = var.project_id
  location = var.region

  force_destroy               = var.force_destroy
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"

  versioning {
    enabled = each.value.versioning
  }

  dynamic "lifecycle_rule" {
    for_each = each.value.lifecycle_days > 0 ? [each.value.lifecycle_days] : []
    content {
      condition {
        age = lifecycle_rule.value
      }
      action {
        type = "Delete"
      }
    }
  }

  lifecycle_rule {
    condition {
      age                = 7
      with_state         = "ARCHIVED"
      num_newer_versions = 3
    }
    action {
      type = "Delete"
    }
  }
}

output "buckets" {
  value = { for k, b in google_storage_bucket.this : k => b.name }
}

output "landing_bucket" { value = google_storage_bucket.this["landing"].name }
output "recon_bucket" { value = google_storage_bucket.this["recon"].name }
