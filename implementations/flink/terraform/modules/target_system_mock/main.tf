# target-system-mock on Cloud Run — replaces the podman-compose service.
#
# Adapted from beam-dataflow/terraform/modules/target_system_mock.
# This lane's mock is Kafka-only (mig.mock.TargetSystemMock — no loader-app, so no
# `POST /v1/accounts` path or the injected-failure knob that existed to exercise a
# loader's retry logic — ), so `failure_rate` and the public
# `roles/run.invoker` grant for an external loader are dropped; `/__admin/*` (stats,
# reset, suppress-next-confirmation) stays for the same debugging/negative-test use as
# locally (SettleLoopIT's scenarios), reached over the connector or by a Composer pod.

variable "project_id" { type = string }
variable "region" { type = string }
variable "image" {
  type        = string
  description = "Fully qualified image, e.g. europe-west1-docker.pkg.dev/<p>/mig-flink/target-system-mock:latest"
}
variable "enabled" {
  type        = bool
  default     = true
  description = "Scale-to-zero, so leaving it on costs nothing while idle."
}
variable "kafka_bootstrap" {
  type        = string
  description = "Managed Kafka bootstrap servers — the mock's only intake is this broker."
}
variable "target_topic" {
  type    = string
  default = "target-system-target"
}
variable "confirmation_topic" {
  type    = string
  default = "target-system-confirmations"
}
variable "rejection_topic" {
  type    = string
  default = "target-system-rejections"
}
variable "max_instances" {
  type        = number
  default     = 1
  description = "Cloud Run max instances for the mock. Keep at 1 unless you also stop trusting its in-memory counters (see the scaling block)."
}
variable "vpc_connector_id" {
  type        = string
  description = "The Serverless VPC Access connector the mock egresses through to reach the VPC-internal broker — required, this mock has no other intake."
}
variable "service_account" {
  type        = string
  description = "Dedicated target-system-mock SA holding roles/managedkafka.client."
}

resource "google_cloud_run_v2_service" "this" {
  count = var.enabled ? 1 : 0

  name     = "mig-flink-target-system-mock"
  project  = var.project_id
  location = var.region

  deletion_protection = false
  # No external ingress needed: nothing outside the VPC calls this service's HTTP
  # surface except a human hitting /__admin/* through a port-forward or a Composer pod
  # on the same VPC — both work with INGRESS_TRAFFIC_INTERNAL_ONLY.
  ingress = "INGRESS_TRAFFIC_INTERNAL_ONLY"

  template {
    service_account = var.service_account

    scaling {
      # min_instance_count=0 left beam-dataflow's mock cold on the
      # Kafka path — nothing wakes a consumer thread the way an HTTP request wakes a
      # scale-to-zero instance, so a whole run went unsettled past its timeout. This
      # mock has NO other intake, so it is pinned warm always, not
      # conditionally — the bug that mattered elsewhere is structural here.
      min_instance_count = 1
      # ONE instance, deliberately. The mock's counters are in-memory and
      # per process, and its consumer group COMMITS offsets: a second instance joins the group,
      # takes over on a rebalance, and from then on each process holds only part of the picture.
      # That is the leading explanation for the stats that did not add up on the 2026-09-25 run
      # (252 received after six 50-message runs). One instance makes the counters mean what they say.
      max_instance_count = var.max_instances
    }

    vpc_access {
      connector = var.vpc_connector_id
      egress    = "PRIVATE_RANGES_ONLY"
    }

    containers {
      image = var.image
      ports { container_port = 8080 }

      env {
        name  = "TARGET_SYSTEM_PORT"
        value = "8080"
      }
      env {
        name  = "KAFKA_BOOTSTRAP"
        value = var.kafka_bootstrap
      }
      env {
        name  = "TARGET_SYSTEM_TARGET_TOPIC"
        value = var.target_topic
      }
      env {
        name  = "TARGET_SYSTEM_CONFIRMATION_TOPIC"
        value = var.confirmation_topic
      }
      env {
        name  = "TARGET_SYSTEM_REJECTION_TOPIC"
        value = var.rejection_topic
      }
      # Tells KafkaClients.java to wire SASL_SSL/OAUTHBEARER instead of PLAINTEXT
      # — the branch cut at portage time, restored here.
      env {
        name  = "KAFKA_SECURITY_PROTOCOL"
        value = "SASL_SSL"
      }

      resources {
        limits = {
          cpu    = "1"
          memory = "4Gi" # 512Mi cannot hold the idempotency map of a multi-million-record run (heap is 70%)
        }
      }
    }
  }
}

output "url" {
  value = var.enabled ? google_cloud_run_v2_service.this[0].uri : ""
}
