# GKE + Flink Kubernetes Operator — replaces `flink-jobmanager`/`flink-taskmanager` in
# podman-compose.yml.
#
# NEW module — beam-dataflow has no equivalent.
#
# COST WARNING, same shape as module.composer's: a GKE cluster with node pools always on
# bills continuously. `enabled` defaults to false for the same reason `enable_composer`
# does in beam-dataflow.

variable "project_id" { type = string }
variable "region" { type = string }
variable "network_id" { type = string }
variable "subnet_id" { type = string }
variable "pods_range_name" { type = string }
variable "services_range_name" { type = string }
variable "enabled" {
  type        = bool
  default     = false
  description = "GKE nodes bill continuously, unlike Dataflow's per-job billing. Off by default."
}
variable "node_machine_type" {
  type    = string
  default = "e2-standard-4" # 4 vCPU, 16 GB — one TaskManager + JobManager fit comfortably
}
variable "node_zones" {
  type        = list(string)
  default     = []
  description = "Zones that get node VMs, e.g. [\"europe-west1-c\"]. Empty = GKE chooses (all zones of the region, one node per zone per pool). The control plane stays regional either way. Use it when a zone has no capacity: Compute Engine reports a stock-out as `does not have enough resources available` on cluster creation (seen 2026-10-17 in europe-west1)."
}
variable "min_node_count" {
  type        = number
  default     = 1
  description = " mitigation: keep this at the minimum that can run one job, or drive the cluster to 0 nodes between runs from the DAG."
}
variable "max_node_count" {
  type    = number
  default = 3
}
variable "flink_kubernetes_operator_version" {
  type        = string
  default     = "1.15.0"
  description = "Verified 2026-09-08: 1.15.0 is the latest release and is documented as fully validated against Apache Flink 2.2 (flink.apache.org release notes) — matches this repo's flink=2.2.1. Chart confirmed reachable at downloads.apache.org/flink/flink-kubernetes-operator-1.15.0/index.yaml."
}

resource "google_container_cluster" "this" {
  count = var.enabled ? 1 : 0

  name     = "mig-flink-gke"
  project  = var.project_id
  location = var.region

  network    = var.network_id
  subnetwork = var.subnet_id

  # VPC-native, using the secondary ranges module.network reserved.
  ip_allocation_policy {
    cluster_secondary_range_name  = var.pods_range_name
    services_secondary_range_name = var.services_range_name
  }

  # Workload Identity: Flink job pods impersonate module.iam's flink-job SA to reach Cloud
  # SQL, Secret Manager and Managed Kafka, exactly the pattern module.composer_rbac gives
  # Composer pods in beam-dataflow.
  workload_identity_config {
    workload_pool = "${var.project_id}.svc.id.goog"
  }

  # remove_default_node_pool + a separately managed node pool below: the default pool
  # cannot be sized/tainted the way a dedicated Flink pool needs, and deleting it here
  # (rather than never creating it) is the documented Terraform pattern for GKE.
  remove_default_node_pool = true
  initial_node_count       = 1
  node_locations           = length(var.node_zones) > 0 ? var.node_zones : null

  deletion_protection = false # dev only, mirrors cloudsql/bigquery force_destroy convention

  release_channel {
    channel = "REGULAR"
  }
}

resource "google_container_node_pool" "flink" {
  count = var.enabled ? 1 : 0

  name     = "flink-pool"
  project  = var.project_id
  location = var.region
  cluster  = google_container_cluster.this[0].name

  autoscaling {
    min_node_count = var.min_node_count
    max_node_count = var.max_node_count
  }

  node_config {
    machine_type = var.node_machine_type
    # No public IP; nodes reach GCR/Secret Manager/Cloud SQL via the Private Google
    # Access module.network already enables on the subnet.
    tags = ["flink"] # matches module.network's firewall rule source/target tags

    workload_metadata_config {
      mode = "GKE_METADATA" # required for Workload Identity
    }

    oauth_scopes = ["https://www.googleapis.com/auth/cloud-platform"]
  }

  management {
    auto_repair  = true
    auto_upgrade = true
  }
}

# The Flink Kubernetes Operator itself — installed via Helm, not a native Terraform
# resource (no google_* resource models a Helm release; the `helm` provider is the
# standard bridge, same relationship module.composer_rbac has to the `kubernetes`
# provider in beam-dataflow's envs/dev/main.tf).
#
# ? — : NOT verified against Flink 2.2.1's own compatibility matrix in
# this pass. Flink 2.2.1's own container image already IS what variant-flink-*-batch-job
# ships (Dockerfile.flinkjob, module below), so the operator only needs to schedule that
# image as a FlinkDeployment — but the operator version pinned above must still be
# checked against the Flink CRD version it expects before a real apply.
resource "helm_release" "flink_operator" {
  count = var.enabled ? 1 : 0

  name       = "flink-kubernetes-operator"
  repository = "https://downloads.apache.org/flink/flink-kubernetes-operator-${var.flink_kubernetes_operator_version}/"
  chart      = "flink-kubernetes-operator"
  version    = var.flink_kubernetes_operator_version
  namespace  = "flink-operator"

  create_namespace = true

  # The chart's admission webhook needs cert-manager (Certificate/Issuer CRDs) for
  # cert-manager.io/inject-ca-from — not installed in this cluster and out of scope for
  # a fixture-scale dev deploy. Disabling it skips webhook validation of FlinkDeployment
  # CRs; the operator's reconciler still works without it.
  set {
    name  = "webhook.create"
    value = "false"
  }

  depends_on = [google_container_node_pool.flink]
}

variable "orchestrator_service_account" {
  type        = string
  default     = ""
  description = "flink-job's Google SA email — granted cluster-admin over the flink-operator namespace so the orchestrator (module.scheduler) can kubectl apply/wait/delete FlinkDeployment CRs. Empty skips the binding (no orchestrator wired yet)."
}

# roles/container.developer (module.iam) lets the SA call the GKE control-plane API; it
# does NOT by itself grant Kubernetes RBAC to operate on FlinkDeployment custom
# resources inside the cluster. This ClusterRoleBinding is the other half.
resource "kubernetes_cluster_role_binding" "orchestrator" {
  count = var.enabled && var.orchestrator_service_account != "" ? 1 : 0

  metadata {
    name = "mig-flink-orchestrator-admin"
  }

  role_ref {
    api_group = "rbac.authorization.k8s.io"
    kind      = "ClusterRole"
    name      = "cluster-admin"
  }

  subject {
    kind      = "User"
    name      = var.orchestrator_service_account
    api_group = "rbac.authorization.k8s.io"
  }

  depends_on = [helm_release.flink_operator]
}

# ── Workload Identity for the Flink pods ─────────────────────────────────────
#
# The node pool runs with GKE_METADATA (workload_metadata_config above), so a pod's calls
# to the metadata server resolve through Workload Identity rather than the node's service
# account. Without the two halves below, they resolve to nothing at all.
#
# This did not matter until the SQL variant gained a Kafka sink. Cloud SQL is reached with
# a password over private IP, and gs:// reads went through the node identity — so the
# 2026-09-09 deploy passed with no Workload Identity wiring whatsoever. Managed Kafka is
# different: GcpToken asks the metadata server for an access token and wraps it in the
# JWT-shaped value OAUTHBEARER wants, and an unmapped KSA has no
# identity to hand back.
#
# The KSA itself is created by the operator's Helm chart (`flink`, the name every
# FlinkDeployment in gcp/flink-deployments/ names as `serviceAccount:`), so this annotates
# the existing object rather than creating a second one.
resource "google_service_account_iam_member" "flink_workload_identity" {
  count = var.enabled && var.flink_job_service_account != "" ? 1 : 0

  service_account_id = "projects/${var.project_id}/serviceAccounts/${var.flink_job_service_account}"
  role               = "roles/iam.workloadIdentityUser"
  member             = "serviceAccount:${var.project_id}.svc.id.goog[flink-operator/flink]"
}

resource "kubernetes_annotations" "flink_ksa" {
  count = var.enabled && var.flink_job_service_account != "" ? 1 : 0

  api_version = "v1"
  kind        = "ServiceAccount"
  metadata {
    name      = "flink"
    namespace = "flink-operator"
  }
  annotations = {
    "iam.gke.io/gcp-service-account" = var.flink_job_service_account
  }
  force = true

  depends_on = [helm_release.flink_operator]
}

variable "flink_job_service_account" {
  type        = string
  default     = ""
  description = "flink-job's Google SA email, bound to the flink-operator/flink KSA via Workload Identity so the Flink pods can authenticate to Managed Kafka. Empty skips both halves."
}

output "gke_cluster_name" {
  value = var.enabled ? google_container_cluster.this[0].name : ""
}
output "gke_cluster_endpoint" {
  value     = var.enabled ? google_container_cluster.this[0].endpoint : ""
  sensitive = true
}
