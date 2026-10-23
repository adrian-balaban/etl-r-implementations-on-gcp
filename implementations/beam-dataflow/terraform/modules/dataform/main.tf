# Dataform repository.
#
# Two paths, selected by whether git_remote_url is set:
#
#   * unlinked (default, git_remote_url == ""): the Dataform *service* only executes
#     workflows for git-linked repositories, so on this path nothing here executes models.
#     The .sqlx files in dataform/definitions/ are instead compiled and run by
#     local/scripts/run_dataform.py, both locally and on GCP, where Composer launches it
#     as the `dataform-runner` pod (Dockerfile.dataform, KubernetesPodOperator in
#     composer/dags/etlr_beam.py). git_remote_settings below is not emitted and the
#     release config is count = 0.
#
#   * linked (git_remote_url + git_token_secret_version set — see
#      for the full setup): the repository
#     pulls dataform/definitions/*.sqlx from the GitHub remote, the release config compiles
#     it on the cron below, and the managed Dataform operators
#     (DataformCreateCompilationResultOperator / DataformCreateWorkflowInvocationOperator)
#     execute it. Reading the git token and acting as var.service_account both require the
#     grants at the bottom of this file, for the Dataform *service agent* — not
#     var.service_account itself.
#
# What repository_id is still here for on the unlinked path:
#   * deploy_dataform.sh reads it to name the target it reports.
#
# The models themselves do not change between the two paths, which is why the local
# runner was built around the real CLI rather than around hand-written SQL.

variable "project_id" { type = string }
variable "region" { type = string }
variable "service_account" { type = string }
variable "git_remote_url" {
  type        = string
  default     = ""
  description = "HTTPS URL of the repo holding dataform/. Empty leaves the repository unlinked."
}
variable "git_branch" {
  type    = string
  default = "main"
}
variable "git_token_secret_version" {
  type        = string
  default     = ""
  description = "Secret Manager version holding the git PAT, e.g. projects/p/secrets/s/versions/1"
}

resource "google_dataform_repository" "this" {
  provider = google-beta

  name            = "etlr-beam"
  project         = var.project_id
  region          = var.region
  service_account = var.service_account

  dynamic "git_remote_settings" {
    for_each = var.git_remote_url != "" ? [1] : []
    content {
      url                                 = var.git_remote_url
      default_branch                      = var.git_branch
      authentication_token_secret_version = var.git_token_secret_version
    }
  }

  workspace_compilation_overrides {
    default_database = var.project_id
  }
}

# A release config compiles the repository on a schedule. It only exists on the linked
# path (count = 0 while git_remote_url is empty, which is the default). The nightly cron
# below is independent of any DAG run — it exists so a broken model surfaces even between
# runs. Per-run compilation is separate: composer/dags/etlr_beam.py's "managed" Dataform
# execution mode calls DataformCreateCompilationResultOperator itself, scoped to that run's
# runId, rather than reusing this release config's compilation result.
resource "google_dataform_repository_release_config" "main" {
  provider = google-beta
  count    = var.git_remote_url != "" ? 1 : 0

  project    = var.project_id
  region     = var.region
  repository = google_dataform_repository.this.name

  name          = "main"
  git_commitish = var.git_branch
  cron_schedule = "0 3 * * *"
  time_zone     = "Europe/Bucharest"

  code_compilation_config {
    default_database = var.project_id
    default_schema   = "etlr_transformation"
    default_location = var.region
    assertion_schema = "etlr_recon"
  }
}

# Consumed by deploy_dataform.sh to name the repository it reports on. Not an execution
# handle on the unlinked path — see the header.
output "repository_id" { value = google_dataform_repository.this.name }

# ── Linked-path IAM: the Dataform *service agent* reads the git token ──────────────────
#
# git_remote_settings above stores which secret version to read, but it is the Dataform
# service agent (service-<PROJECT_NUMBER>@gcp-sa-dataform.iam.gserviceaccount.com) that
# actually reads it when compiling from git — not var.service_account, the account the
# repository *runs* models as. Granting the token to var.service_account instead is a
# no-op for compilation and was the H7-adjacent gap: seed_secrets.sh wrote
# dataform-git-token, but nothing granted the agent access to it, so the linked path could
# never authenticate even with both vars set. Both resources below are only meaningful
# once git_token_secret_version is non-empty, so they are gated the same as the release
# config.

data "google_project" "this" {
  project_id = var.project_id
}

# Ensures the agent identity exists before granting to it — first enablement of
# dataform.googleapis.com can otherwise race the IAM binding.
resource "google_project_service_identity" "dataform" {
  provider = google-beta
  project  = var.project_id
  service  = "dataform.googleapis.com"
}

locals {
  dataform_agent = "serviceAccount:service-${data.google_project.this.number}@gcp-sa-dataform.iam.gserviceaccount.com"
}

# 1. Let the agent read the git PAT.
resource "google_secret_manager_secret_iam_member" "agent_reads_git_token" {
  count = var.git_token_secret_version != "" ? 1 : 0

  project   = var.project_id
  secret_id = "dataform-git-token"
  role      = "roles/secretmanager.secretAccessor"
  member    = local.dataform_agent

  depends_on = [google_project_service_identity.dataform]
}

# 2. Let the agent act as var.service_account, which is what the repository (and its
# release config / workflow invocations) runs models as.
resource "google_service_account_iam_member" "agent_acts_as_runner" {
  count = var.git_token_secret_version != "" ? 1 : 0

  service_account_id = "projects/${var.project_id}/serviceAccounts/${var.service_account}"
  role               = "roles/iam.serviceAccountTokenCreator"
  member             = local.dataform_agent

  depends_on = [google_project_service_identity.dataform]
}
