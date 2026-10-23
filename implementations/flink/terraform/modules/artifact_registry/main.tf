# Artifact Registry for the Flink job image and the target-system-mock image.
#
# Renamed/adapted from beam-dataflow/terraform/modules/dataflow
# — same reasoning: Terraform owns the registry and naming, `make
# build-images` owns the contents (build artefacts change per commit; coupling a
# `docker build` into `terraform apply` couples deploys to infra changes).

variable "project_id" { type = string }
variable "region" { type = string }

resource "google_artifact_registry_repository" "this" {
  project       = var.project_id
  location      = var.region
  repository_id = "mig-flink"
  format        = "DOCKER"
  description   = "Flink job image (Dockerfile.flinkjob) and target-system-mock image"

  cleanup_policies {
    id     = "keep-recent"
    action = "KEEP"
    most_recent_versions {
      keep_count = 10
    }
  }
}

output "registry" {
  value = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.this.repository_id}"
}
