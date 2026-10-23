# Cloud SQL for PostgreSQL — replaces podman's `postgres:16`.
#
# NEW module — beam-dataflow has no equivalent (it targets BigQuery). The whole
# argument is that this repo's Flink jobs travel unmodified: `flink-connector-jdbc-postgres:
# 4.1.0-2.2` already speaks Postgres wire protocol to podman's container, so it speaks it
# identically to Cloud SQL — only the JDBC URL, credentials and network path change.
#
# Private IP only (no public IP, no Cloud SQL Auth Proxy): GKE pods reach it directly over
# VPC peering, the same way workers reach anything else in this VPC (module.network).

#  closed the cost inventory with: "Cloud SQL ramane singura resursa fara
# flag enable_* — de adaugat o cale de oprire daca bugetul minim conteaza; nefacut in acest
# pas." This is that flag. Every other module in this Terraform gates on one; without it,
# any `terraform apply` on envs/dev bills ~$50/month whether or not a single job ever runs,
# which is the entire "minimum budget" line of table.
variable "enabled" {
  type        = bool
  default     = true
  description = "false creates no Cloud SQL resources at all — the lane then has no database, so only set it false when tearing the environment down to its floor."
}

variable "project_id" { type = string }
variable "region" { type = string }
variable "network_id" {
  type        = string
  description = "module.network.network_id — Cloud SQL private IP peers with this VPC."
}
variable "tier" {
  type        = string
  default     = "db-custom-1-3840" # 1 vCPU, 3.75 GB — the smallest custom tier, dev-sized
  description = "Bump for a real migration volume; sized for the fixture-scale runs this repo proves parity on."
}
variable "database_version" {
  type    = string
  default = "POSTGRES_16" # matches podman's postgres:16 (sql/postgres-init.sql)
}
variable "deletion_protection" {
  type    = bool
  default = false # dev only, mirrors bigquery/storage force_destroy=true convention
}

# The names below default to the literal values this module always used, so implementations/flink plans exactly as
# before. implementations/flink-jdbc instantiates the module twice (lane + Source System) and overrides them.
variable "instance_name" {
  type    = string
  default = "mig-flink-pg"
}
variable "peering_range_name" {
  type    = string
  default = "mig-flink-cloudsql-range"
}
variable "manage_peering" {
  type        = bool
  default     = true
  description = "false for a second instance on a VPC whose Service Networking peering another instance of this module already created (one peering per VPC; a second google_service_networking_connection would replace the first one's ranges)."
}
variable "database_name" {
  type    = string
  default = "mig"
}
variable "app_user_name" {
  type    = string
  default = "mig"
}

# Cloud SQL private IP requires an allocated IP range and a service networking
# connection to the VPC — this is the one-time peering setup, not per-instance.
resource "google_compute_global_address" "private_ip_range" {
  count         = var.enabled && var.manage_peering ? 1 : 0
  name          = var.peering_range_name
  project       = var.project_id
  purpose       = "VPC_PEERING"
  address_type  = "INTERNAL"
  prefix_length = 16
  network       = var.network_id
}

resource "google_service_networking_connection" "this" {
  count                   = var.enabled && var.manage_peering ? 1 : 0
  network                 = var.network_id
  service                 = "servicenetworking.googleapis.com"
  reserved_peering_ranges = [google_compute_global_address.private_ip_range[0].name]
}

resource "google_sql_database_instance" "this" {
  count            = var.enabled ? 1 : 0
  name             = var.instance_name
  project          = var.project_id
  region           = var.region
  database_version = var.database_version

  deletion_protection = var.deletion_protection

  settings {
    tier    = var.tier
    edition = "ENTERPRISE" # db-custom-* tiers require ENTERPRISE explicitly — new projects default to ENTERPRISE_PLUS, which only accepts db-perf-optimized-* tiers

    ip_configuration {
      ipv4_enabled    = false
      private_network = var.network_id
      # No public IP, no authorized networks — GKE pods reach this over the VPC peering
      # google_service_networking_connection.this establishes, same as any other
      # VPC-internal service (Managed Kafka, below).
    }

    backup_configuration {
      enabled    = true
      start_time = "03:00"
    }
  }

  depends_on = [google_service_networking_connection.this]
}

# One database, one app user (password in Secret Manager — module.secrets). The three
# schemas (etlr_extraction, etlr_transformation, etlr_recon) are created by sql/postgres-init.sql
# equivalent, applied out-of-band.
resource "google_sql_database" "mig" {
  count    = var.enabled ? 1 : 0
  name     = var.database_name
  project  = var.project_id
  instance = google_sql_database_instance.this[0].name
}

resource "random_password" "app_user" {
  count   = var.enabled ? 1 : 0
  length  = 32
  special = false # simplest to embed in a JDBC URL without percent-encoding surprises
}

resource "google_sql_user" "app" {
  count    = var.enabled ? 1 : 0
  name     = var.app_user_name
  project  = var.project_id
  instance = google_sql_database_instance.this[0].name
  password = random_password.app_user[0].result
}

# Outputs stay readable with enabled=false (empty strings, not errors) so envs/dev still
# plans — the modules that consume them (scheduler, flink_gke) are themselves flagged off in
# that configuration.
output "app_password" {
  value     = one(random_password.app_user[*].result)
  sensitive = true
}
output "instance_connection_name" { value = one(google_sql_database_instance.this[*].connection_name) }
output "private_ip_address" { value = one(google_sql_database_instance.this[*].private_ip_address) }
output "database_name" { value = one(google_sql_database.mig[*].name) }
output "app_user" { value = one(google_sql_user.app[*].name) }

# JDBC URL a Flink job's `flink-connector-jdbc-postgres` 'url' option reads directly —
# same shape as the `jdbc:postgresql://localhost:PORT/mig` this repo's ITs already use.
output "jdbc_url" {
  value = var.enabled ? "jdbc:postgresql://${one(google_sql_database_instance.this[*].private_ip_address)}:5432/${one(google_sql_database.mig[*].name)}" : ""
}
