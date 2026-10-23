# Secret Manager — the Cloud SQL app-user password and (if the extract stays PGP-encrypted)
# the PGP private key. Adapted from beam-dataflow's secrets module.

variable "project_id" { type = string }
variable "region" { type = string }

variable "accessors" {
  type        = map(string)
  description = "secret name => service account email that may read it (roles/secretmanager.secretAccessor, per-secret)."
}

# Secrets whose VALUE Terraform itself knows and should store (today: the generated Cloud SQL
# password). Deliberately NOT for the PGP key and passphrase: those are supplied by whoever
# operates the extract, out of band, and must never pass through Terraform state.
#
# Until this existed, `cloudsql-app-password` was created EMPTY — a secret with no version — so
# pointing the orchestrator at Secret Manager would have failed at runtime
# with a "no versions" error rather than at plan time. Two variables, not one map, because a
# for_each cannot iterate a sensitive map's keys.
variable "versioned_secrets" {
  type        = set(string)
  default     = []
  description = "Names of secrets (a subset of the module's secret_names) to write a version for; the values come from secret_values."
}
variable "secret_values" {
  type        = map(string)
  default     = {}
  sensitive   = true
  description = "secret name => value, for the names in versioned_secrets."
}

variable "extra_secret_names" {
  type        = list(string)
  default     = []
  description = "Secrets beyond the three every lane has (implementations/flink-jdbc adds etlr-source-db and etlr-source-admin)."
}

locals {
  secret_names = concat(["cloudsql-app-password", "pgp-private-key", "pgp-passphrase"], var.extra_secret_names)
}

resource "google_secret_manager_secret" "this" {
  for_each = toset(local.secret_names)

  project   = var.project_id
  secret_id = each.value

  replication {
    user_managed {
      replicas {
        location = var.region
      }
    }
  }
}

resource "google_secret_manager_secret_version" "value" {
  for_each = var.versioned_secrets

  secret      = google_secret_manager_secret.this[each.key].id
  secret_data = var.secret_values[each.key]

  lifecycle {
    precondition {
      condition     = contains(local.secret_names, each.key) && contains(keys(var.secret_values), each.key)
      error_message = "versioned_secrets lists '${each.key}', which is either not a known secret or has no entry in secret_values."
    }
  }
}

resource "google_secret_manager_secret_iam_member" "accessor" {
  for_each = { for name, acct in var.accessors : name => acct if contains(local.secret_names, name) }

  project   = var.project_id
  secret_id = google_secret_manager_secret.this[each.key].secret_id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${each.value}"
}

output "secret_ids" {
  value = { for k, s in google_secret_manager_secret.this : k => s.secret_id }
}
