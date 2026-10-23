# GKE RBAC so Composer's Airflow workers may launch KubernetesPodOperator pods
# (gate, recon, assert_run_balanced), and Workload Identity so those pods run
# under the narrow SA module.iam wrote for them, not the node's default.
#
# Adapted from beam-dataflow/terraform/modules/composer_rbac.
# Ported unchanged: this repo's DAG has fewer pod tasks (three vs. five) but the same
# RBAC shape.
#
# NOT covered here — flagged, not silently skipped: how a pod on *Composer's* GKE cluster
# submits a FlinkDeployment CR to the *separate* flink_gke cluster. Two real options,
# neither implemented in this pass: (a) the pod carries a second kubeconfig for
# flink_gke and shells out to `kubectl --context=flink-gke apply -f ...`, or (b) the
# Airflow GKE provider's `GKEStartPodOperator`/a custom operator targets the other
# cluster directly. Whichever is chosen needs its own Workload Identity binding against
# flink_gke, analogous to this module's binding against Composer's own cluster.

terraform {
  required_providers {
    google = {
      source = "hashicorp/google"
    }
    kubernetes = {
      source = "hashicorp/kubernetes"
    }
  }
}

variable "enabled" {
  type    = bool
  default = false
}

variable "namespace" {
  type        = string
  description = <<-EOT
    Composer's own Kubernetes namespace, e.g. composer-2-9-7-airflow-2-9-3-ab8412f5.
    Not exposed as a module output — read it from the cluster:
      kubectl get namespaces | grep composer
    Changes when the environment is recreated, which is why it is a variable.
  EOT
}

variable "service_account" {
  type    = string
  default = "default"
}

resource "kubernetes_role" "pod_launcher" {
  count = var.enabled ? 1 : 0

  metadata {
    name      = "airflow-pod-launcher"
    namespace = var.namespace
  }

  rule {
    api_groups = [""]
    resources  = ["pods"]
    verbs      = ["create", "get", "list", "watch", "delete", "patch"]
  }
  rule {
    api_groups = [""]
    resources  = ["pods/log", "pods/status"]
    verbs      = ["get", "list", "watch"]
  }
  rule {
    api_groups = [""]
    resources  = ["pods/exec"]
    verbs      = ["create", "get"]
  }
}

resource "kubernetes_role_binding" "pod_launcher" {
  count = var.enabled ? 1 : 0

  metadata {
    name      = "airflow-pod-launcher"
    namespace = var.namespace
  }

  role_ref {
    api_group = "rbac.authorization.k8s.io"
    kind      = "Role"
    name      = kubernetes_role.pod_launcher[0].metadata[0].name
  }

  subject {
    kind      = "ServiceAccount"
    name      = var.service_account
    namespace = var.namespace
  }
}

variable "project_id" {
  type    = string
  default = ""
}

variable "app_service_accounts" {
  type        = map(string)
  default     = {}
  description = "App name -> Google SA it should run as, e.g. { gate = ..., recon = ... }."
}

resource "kubernetes_service_account" "app" {
  for_each = var.enabled ? var.app_service_accounts : {}

  metadata {
    name      = "mig-${each.key}"
    namespace = var.namespace
    annotations = {
      "iam.gke.io/gcp-service-account" = each.value
    }
  }
}

resource "google_service_account_iam_member" "app_workload_identity" {
  for_each = var.enabled ? var.app_service_accounts : {}

  service_account_id = "projects/${var.project_id}/serviceAccounts/${each.value}"
  role               = "roles/iam.workloadIdentityUser"
  member             = "serviceAccount:${var.project_id}.svc.id.goog[${var.namespace}/mig-${each.key}]"
}

output "app_service_accounts" {
  description = "task-name -> Kubernetes SA, for the DAG's service_account_name."
  value       = { for k, v in kubernetes_service_account.app : k => v.metadata[0].name }
}

output "role_binding" {
  value = var.enabled ? kubernetes_role_binding.pod_launcher[0].metadata[0].name : ""
}
