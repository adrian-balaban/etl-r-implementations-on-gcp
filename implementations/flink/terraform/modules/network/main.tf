# VPC for GKE (Flink) and Cloud SQL.
#
# Ported from beam-dataflow/terraform/modules/network
# — same reasoning, generalised past "Dataflow workers":
# no public IPs, Private Google Access, Cloud NAT for remaining egress. The inter-worker
# firewall rule is kept and retargeted from `dataflow` tags to `flink` tags — the exact
# class of failure (a job that starts and then never progresses, no useful error) that the
# beam-dataflow hit most often, and Flink TaskManagers need the same kind of open inter-node
# ports Dataflow workers did.

variable "project_id" { type = string }
variable "region" { type = string }
variable "subnet_cidr" {
  type    = string
  default = "10.20.0.0/20"
}
variable "pods_cidr" {
  type        = string
  default     = "10.24.0.0/14"
  description = "Secondary range for GKE pod IPs (VPC-native cluster)."
}
variable "services_cidr" {
  type        = string
  default     = "10.28.0.0/20"
  description = "Secondary range for GKE service IPs (VPC-native cluster)."
}

resource "google_compute_network" "this" {
  name                    = "mig-flink-vpc"
  project                 = var.project_id
  auto_create_subnetworks = false
}

resource "google_compute_subnetwork" "this" {
  name    = "mig-flink-subnet"
  project = var.project_id
  region  = var.region
  network = google_compute_network.this.id

  ip_cidr_range = var.subnet_cidr

  private_ip_google_access = true

  secondary_ip_range {
    range_name    = "gke-pods"
    ip_cidr_range = var.pods_cidr
  }
  secondary_ip_range {
    range_name    = "gke-services"
    ip_cidr_range = var.services_cidr
  }

  log_config {
    aggregation_interval = "INTERVAL_10_MIN"
    flow_sampling        = 0.5
    metadata             = "INCLUDE_ALL_METADATA"
  }
}

resource "google_compute_router" "this" {
  name    = "mig-flink-router"
  project = var.project_id
  region  = var.region
  network = google_compute_network.this.id
}

resource "google_compute_router_nat" "this" {
  name    = "mig-flink-nat"
  project = var.project_id
  region  = var.region
  router  = google_compute_router.this.name

  nat_ip_allocate_option             = "AUTO_ONLY"
  source_subnetwork_ip_ranges_to_nat = "LIST_OF_SUBNETWORKS"

  subnetwork {
    name                    = google_compute_subnetwork.this.id
    source_ip_ranges_to_nat = ["ALL_IP_RANGES"]
  }

  log_config {
    enable = true
    filter = "ERRORS_ONLY"
  }
}

# Flink JobManager <-> TaskManager RPC + data plane. Omitting this is the "job starts and
# then never progresses" failure  names from beam-dataflow's Dataflow
# experience — same failure mode, different process names.
resource "google_compute_firewall" "flink_internal" {
  name    = "mig-allow-flink-internal"
  project = var.project_id
  network = google_compute_network.this.name

  description = "JobManager/TaskManager RPC, data plane and blob server (required)."

  allow {
    protocol = "tcp"
    # 6123 RPC, 6124 data, 6125 blob server, 8081 REST — GKE pod-to-pod within the VPC.
    ports = ["6123-6125", "8081"]
  }

  source_tags = ["flink"]
  target_tags = ["flink"]
}

output "network_id" { value = google_compute_network.this.id }
output "subnet_id" { value = google_compute_subnetwork.this.id }
output "subnet_self_link" { value = google_compute_subnetwork.this.self_link }
output "pods_range_name" { value = "gke-pods" }
output "services_range_name" { value = "gke-services" }
