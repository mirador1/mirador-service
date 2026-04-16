# =============================================================================
# Kafka — Google Cloud Managed Service for Apache Kafka
#
# Google Cloud offers a native managed Kafka service (GA since 2024):
# https://cloud.google.com/managed-kafka/docs
#
# Why native managed Kafka over alternatives?
# ─────────────────────────────────────────────
# - Fully Kafka-compatible API (same bootstrap protocol, same Spring Kafka config)
#   → zero code changes to the application
# - Managed by Google: auto-scaling, patching, HA, multi-zone replication
# - No dependency on a third-party vendor (Confluent, MSK, etc.)
# - Billed per vCPU-hour and storage-GB: ~$0.16/vCPU-hour + $0.12/GB-month
#   (a 3-broker cluster with 3 vCPUs each ≈ $35/day — dev: use 1 vCPU / broker)
# - VPC-native: brokers are in your VPC, reachable from GKE pods via private IP
#
# Alternative: Google Cloud Pub/Sub (NOT Kafka-compatible)
# ─────────────────────────────────────────────────────────
# Pub/Sub is native GCP and fully serverless (scales to zero, $0.04/GB ingress).
# But it uses a different API — all Spring Kafka code would need to be rewritten
# using the Spring Cloud GCP Pub/Sub starter. Good choice for a greenfield project;
# not suitable here without significant refactoring.
#
# Enable the API first:
#   gcloud services enable managedkafka.googleapis.com --project=${var.project_id}
# =============================================================================

variable "kafka_enabled" {
  description = "Deploy Google Cloud Managed Kafka cluster"
  type        = bool
  default     = false
  # Set to true once managedkafka.googleapis.com is enabled in your project.
  # Until then, the in-cluster Kafka Deployment (deploy/kubernetes/stateful/kafka.yaml) is used.
}

variable "kafka_vcpus_per_broker" {
  description = "vCPUs per Kafka broker (minimum 3). Use 3 for dev, 6+ for production."
  type        = number
  default     = 3
  # Cost reference: 3 vCPU @ $0.16/h = $0.48/h per broker
  # 3-broker cluster = $1.44/h ≈ $35/day
}

variable "kafka_memory_gb_per_broker" {
  description = "Memory in GB per broker (must be 1 GB per vCPU)"
  type        = number
  default     = 3
}

# =============================================================================
# Google Cloud Managed Kafka cluster
# Uncomment once var.kafka_enabled = true and the API is enabled.
# =============================================================================
#
# resource "google_managed_kafka_cluster" "mirador" {
#   count    = var.kafka_enabled ? 1 : 0
#   cluster_id = "mirador-kafka"
#   location   = var.region
#   project    = var.project_id
#
#   capacity {
#     memory_bytes = var.kafka_memory_gb_per_broker * 1073741824 * 3  # 3 brokers
#     vcpu_count   = var.kafka_vcpus_per_broker * 3
#   }
#
#   gcp_config {
#     access_config {
#       network_configs {
#         subnet = "projects/${var.project_id}/regions/${var.region}/subnetworks/mirador-subnet"
#       }
#     }
#   }
#
#   labels = {
#     "app" = "mirador"
#   }
# }
#
# resource "google_managed_kafka_topic" "customer_request" {
#   count     = var.kafka_enabled ? 1 : 0
#   cluster   = google_managed_kafka_cluster.mirador[0].cluster_id
#   topic_id  = "customer.request"
#   location  = var.region
#   project   = var.project_id
#   partition_count    = 3
#   replication_factor = 3
# }
#
# resource "google_managed_kafka_topic" "customer_reply" {
#   count     = var.kafka_enabled ? 1 : 0
#   cluster   = google_managed_kafka_cluster.mirador[0].cluster_id
#   topic_id  = "customer.reply"
#   location  = var.region
#   project   = var.project_id
#   partition_count    = 3
#   replication_factor = 3
# }
#
# resource "google_managed_kafka_topic" "customer_events" {
#   count     = var.kafka_enabled ? 1 : 0
#   cluster   = google_managed_kafka_cluster.mirador[0].cluster_id
#   topic_id  = "customer.events"
#   location  = var.region
#   project   = var.project_id
#   partition_count    = 3
#   replication_factor = 3
# }
#
# output "kafka_bootstrap_servers" {
#   description = "Managed Kafka bootstrap endpoint — set as KAFKA_BOOTSTRAP_SERVERS in ConfigMap"
#   value       = var.kafka_enabled ? google_managed_kafka_cluster.mirador[0].bootstrap_address : "kafka.infra.svc.cluster.local:9092"
# }

# =============================================================================
# Migration path: in-cluster → Managed Kafka
# ─────────────────────────────────────────────────────────────────────────────
# 1. Set kafka_enabled = true in terraform.tfvars and run terraform apply
# 2. Add KAFKA_SECURITY_PROTOCOL=SASL_SSL to deploy/kubernetes/backend/configmap.yaml
#    (Google Managed Kafka requires SASL/PLAIN over TLS for authentication)
# 3. Set KAFKA_BOOTSTRAP_SERVERS to the output kafka_bootstrap_servers value
# 4. Store KAFKA_SASL_USERNAME (service account email) + KAFKA_SASL_PASSWORD
#    (HMAC key) as Kubernetes Secrets
# 5. Remove deploy/kubernetes/stateful/kafka.yaml from the kubectl apply loop in .gitlab-ci.yml
# 6. Delete the in-cluster Kafka Deployment: kubectl delete deployment kafka -n infra
# =============================================================================
