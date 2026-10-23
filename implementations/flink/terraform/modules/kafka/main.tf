# Managed Service for Apache Kafka — replaces redpanda.
#
# Ported ~1:1 from beam-dataflow/terraform/modules/kafka.
# Locally this is redpanda on PLAINTEXT (podman-compose.yml). Here it is a managed cluster
# reached over SASL_SSL/OAUTHBEARER using the worker service account — the one part of the
# Kafka switch that is a genuine client-config change, not a URL swap.

variable "project_id" { type = string }
variable "region" { type = string }
variable "enabled" {
  type        = bool
  default     = false
  description = "Managed Kafka is billed per vCPU-hour; off unless the Kafka sink is being exercised."
}
variable "subnet_id" { type = string }

# Same three topics as local-development-podman/podman-compose.yml (target-system-target,
# -confirmations, -rejections — ).
variable "topics" {
  type = map(object({
    topic_id   = string
    partitions = number
  }))
  default = {
    target = {
      topic_id   = "target-system-target"
      partitions = 12
    }
    confirmations = {
      # One event per accepted TARGET row, consumed once per run by a fresh consumer
      # group (recon's bounded-endOffsets read) — a single partition is what that read
      # assumes and all it needs.
      topic_id   = "target-system-confirmations"
      partitions = 1
    }
    rejections = {
      topic_id   = "target-system-rejections"
      partitions = 1
    }
  }
  description = "Topics on the cluster. Keys are local labels used to address outputs; topic_id is the real Kafka topic name."
}

resource "google_managed_kafka_cluster" "this" {
  count    = var.enabled ? 1 : 0
  provider = google-beta

  project    = var.project_id
  location   = var.region
  cluster_id = "mig-flink-kafka"

  capacity_config {
    vcpu_count   = 3
    memory_bytes = 3221225472
  }

  gcp_config {
    access_config {
      network_configs {
        subnet = var.subnet_id
      }
    }
  }
}

resource "google_managed_kafka_topic" "this" {
  for_each = var.enabled ? var.topics : {}
  provider = google-beta

  project  = var.project_id
  location = var.region
  cluster  = google_managed_kafka_cluster.this[0].cluster_id
  topic_id = each.value.topic_id

  partition_count    = each.value.partitions
  replication_factor = 3

  configs = {
    "retention.ms"      = "604800000" # 7 days — long enough to replay a failed migration wave
    "cleanup.policy"    = "delete"
    "max.message.bytes" = "1048576"
  }
}

# The bootstrap address is an INPUT, not a derived value — deliberately.
#
# This output used to be built by string interpolation:
#   bootstrap.<cluster_id>.<region>.managedkafka.<project>.cloud.goog:9092
# That hostname does not exist. What Google actually issues looks like
#   bootstrap-y14f83k1s20-ujm.62aaf3ab.europe-west1.managedkafka.s.cloud.goog:9092
# (an opaque per-cluster id and a hash — not derivable from anything Terraform knows), and the
# google provider (v6.50) does not export it from google_managed_kafka_cluster or offer a data
# source for it. So the derived name was a guess that could only fail at runtime: the
# target-system-mock died at startup with "No resolvable bootstrap urls given", which reads as
# a networking problem and is not one.
#
# Get the real one after the cluster exists and pass it in:
#   gcloud managed-kafka clusters describe <cluster_id> --location=<region> \
#     --format='value(bootstrapAddress)'
variable "bootstrap_address" {
  type        = string
  default     = ""
  description = "Managed Kafka's real bootstrap address (host:port), from `gcloud managed-kafka clusters describe ... --format='value(bootstrapAddress)'`. Not derivable — see the note above."
}

output "bootstrap_servers" {
  value = var.enabled ? var.bootstrap_address : ""
}
output "cluster_id" {
  value = var.enabled ? google_managed_kafka_cluster.this[0].cluster_id : ""
}
output "topics" {
  value = { for k, v in var.topics : k => var.enabled ? google_managed_kafka_topic.this[k].topic_id : "" }
}
