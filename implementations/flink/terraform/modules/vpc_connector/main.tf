# Serverless VPC Access connector — lets the Cloud Run target-system-mock reach the
# VPC-internal Managed Kafka broker.
#
# Ported 1:1 from beam-dataflow/terraform/modules/vpc_connector.
# Gated on the same flag as Kafka: the connector only exists when the broker does, so a
# no-Kafka run pays nothing for it.

variable "project_id" { type = string }
variable "region" { type = string }
variable "network_id" {
  type        = string
  description = "Self link / id of the mig-flink-vpc network the connector attaches to."
}
variable "ip_cidr_range" {
  type        = string
  default     = "10.20.144.0/28"
  description = "A /28 inside mig-flink-vpc, non-overlapping with mig-flink-subnet (10.20.0.0/20) or its GKE secondary ranges (10.24.0.0/14, 10.28.0.0/20)."
}
variable "enabled" {
  type        = bool
  default     = false
  description = "Only needed when Managed Kafka is on; off otherwise to avoid idle cost."
}

resource "google_vpc_access_connector" "this" {
  count    = var.enabled ? 1 : 0
  provider = google-beta

  name          = "mig-flink-vpc-connector"
  project       = var.project_id
  region        = var.region
  network       = var.network_id
  ip_cidr_range = var.ip_cidr_range

  min_throughput = 200
  max_throughput = 300
}

output "connector_id" {
  value = var.enabled ? google_vpc_access_connector.this[0].id : ""
}
