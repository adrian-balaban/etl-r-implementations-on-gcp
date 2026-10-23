# Project, billing link, APIs and the Terraform state bucket.
#
# Ported from beam-dataflow/terraform/modules/bootstrap
# — applied on its own first (`terraform apply -target=module.bootstrap`)
# with local state, because Terraform cannot store state in a bucket it has not created yet.

variable "project_id" { type = string }
variable "project_name" {
  type    = string
  default = "test-gcp-flink"
}
variable "billing_account" {
  type        = string
  description = "Must be an OPEN billing account (gcloud billing accounts list)."
}
variable "org_id" {
  type    = string
  default = ""
}
variable "folder_id" {
  type    = string
  default = ""
}
variable "region" { type = string }
variable "create_project" {
  type        = bool
  default     = true
  description = "False when pointing at a project that already exists."
}

resource "google_project" "this" {
  count = var.create_project ? 1 : 0

  project_id      = var.project_id
  name            = var.project_name
  billing_account = var.billing_account
  org_id          = var.org_id != "" ? var.org_id : null
  folder_id       = var.folder_id != "" ? var.folder_id : null

  auto_create_network = false
}

locals {
  # GKE + Flink Kubernetes Operator + Cloud SQL — no
  # dataflow.googleapis.com/dataform.googleapis.com/composer's Beam-runner deps, but
  # container.googleapis.com (GKE) and sqladmin.googleapis.com (Cloud SQL) instead.
  services = [
    "cloudresourcemanager.googleapis.com",
    "compute.googleapis.com",
    "storage.googleapis.com",
    "container.googleapis.com",
    "sqladmin.googleapis.com",
    "servicenetworking.googleapis.com",
    # composer.googleapis.com: only needed if module.composer (commented out in
    # envs/dev/main.tf — ) is restored. cloudscheduler.googleapis.com
    # replaces it as the default orchestrator trigger.
    "cloudscheduler.googleapis.com",
    "artifactregistry.googleapis.com",
    "secretmanager.googleapis.com",
    "managedkafka.googleapis.com",
    "cloudbuild.googleapis.com",
    "run.googleapis.com",
    "vpcaccess.googleapis.com",
    "monitoring.googleapis.com",
    "logging.googleapis.com",
    "iam.googleapis.com",
  ]
}

resource "google_project_service" "this" {
  for_each = toset(local.services)

  project = var.project_id
  service = each.value

  disable_on_destroy = false

  depends_on = [google_project.this]
}

resource "google_storage_bucket" "tfstate" {
  name     = "${var.project_id}-tfstate"
  project  = var.project_id
  location = var.region

  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"
  force_destroy               = false

  versioning {
    enabled = true
  }

  depends_on = [google_project_service.this]
}
