# =============================================================================
# Terraform — GCP infrastructure for mirador
#
# Resources provisioned:
#   - VPC + subnet (private cluster network)
#   - GKE Autopilot cluster (no node management, automatic scaling + upgrades)
#   - Cloud SQL for PostgreSQL 17 (managed DB — no StatefulSet needed in K8s)
#   - Memorystore Redis (managed cache — no Redis Deployment needed in K8s)
#   - IAM service accounts for Cloud SQL Auth Proxy (Workload Identity)
#
# Cloud SQL vs in-cluster PostgreSQL:
#   Using Cloud SQL means no StatefulSet to manage, no PVC re-attachment on node
#   failure, automated backups, and point-in-time recovery. The compute cost is
#   ~$7/month for db-f1-micro — far cheaper than the GKE Autopilot node cost.
#   When not in use, the instance can be stopped (gcloud sql instances patch
#   --activation-policy=NEVER) — only storage (~$0.17/GB/month) is billed.
#
# Memorystore Redis vs in-cluster Redis:
#   Managed Redis removes the risk of data loss on pod eviction. Cost is ~$16/month
#   for a 1 GB BASIC instance. It connects via Private Service Access (same VPC).
#
# Prerequisites:
#   gcloud auth application-default login
#   gcloud services enable container.googleapis.com sqladmin.googleapis.com \
#     redis.googleapis.com servicenetworking.googleapis.com --project=${var.project_id}
# =============================================================================

terraform {
  required_version = ">= 1.8"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 6.0"
    }
  }

  # Backend configuration is in backend.tf — bucket/prefix injected via
  # -backend-config at terraform init time (see .gitlab-ci.yml infra stage).
}

provider "google" {
  project = var.project_id
  region  = var.region
}

# =============================================================================
# VPC — private network for GKE + Cloud SQL + Memorystore
# =============================================================================

resource "google_compute_network" "vpc" {
  name                    = "mirador-vpc"
  auto_create_subnetworks = false

  # Deleting a VPC with dependent resources fails. Terraform handles this by
  # destroying dependent resources first in the correct order.
}

resource "google_compute_subnetwork" "subnet" {
  name          = "mirador-subnet"
  network       = google_compute_network.vpc.id
  region        = var.region
  ip_cidr_range = "10.0.0.0/20"

  # Secondary ranges for GKE pods and services (required by GKE Autopilot)
  secondary_ip_range {
    range_name    = "pods"
    ip_cidr_range = "10.1.0.0/16" # up to 65536 pod IPs
  }
  secondary_ip_range {
    range_name    = "services"
    ip_cidr_range = "10.2.0.0/20" # up to 4096 service IPs
  }

  private_ip_google_access = true # allows private GKE nodes to reach GCP APIs
}

# Private service access peering — required for Cloud SQL and Memorystore
# to be reachable from GKE pods via private IP.
resource "google_compute_global_address" "private_ip_range" {
  name          = "mirador-private-ip"
  purpose       = "VPC_PEERING"
  address_type  = "INTERNAL"
  prefix_length = 16
  network       = google_compute_network.vpc.id
}

resource "google_service_networking_connection" "private_vpc_connection" {
  network                 = google_compute_network.vpc.id
  service                 = "servicenetworking.googleapis.com"
  reserved_peering_ranges = [google_compute_global_address.private_ip_range.name]
}

# =============================================================================
# Cloud NAT — egress for private GKE nodes
#
# Private GKE Autopilot nodes have no public IP, so they cannot reach the
# public internet (Docker Hub, GitLab Container Registry, Maven Central, etc).
# Cloud NAT provides outbound NAT for the entire subnet — pulls, webhooks,
# and API calls leave through a shared public IP while nodes stay private.
# Without this, pods fail to pull images with ImagePullBackOff.
# =============================================================================
resource "google_compute_router" "nat_router" {
  name    = "mirador-nat-router"
  region  = var.region
  network = google_compute_network.vpc.id
}

resource "google_compute_router_nat" "nat" {
  name                               = "mirador-nat"
  router                             = google_compute_router.nat_router.name
  region                             = var.region
  nat_ip_allocate_option             = "AUTO_ONLY"
  source_subnetwork_ip_ranges_to_nat = "ALL_SUBNETWORKS_ALL_IP_RANGES"

  log_config {
    enable = true
    filter = "ERRORS_ONLY"
  }
}

# =============================================================================
# GKE Autopilot cluster
# =============================================================================

resource "google_container_cluster" "autopilot" {
  name     = var.cluster_name
  location = var.region

  # Autopilot: Google manages nodes, scaling, and upgrades automatically.
  # No node pool configuration needed — resource requests in Deployment manifests
  # drive the node provisioning.
  enable_autopilot = true

  network    = google_compute_network.vpc.id
  subnetwork = google_compute_subnetwork.subnet.id

  ip_allocation_policy {
    cluster_secondary_range_name  = "pods"
    services_secondary_range_name = "services"
  }

  # Private cluster — nodes have no public IP. The control plane is reachable
  # via a private endpoint. Set master_authorized_networks_config if you want
  # to restrict access to specific IP ranges (e.g. office VPN + CI runner).
  private_cluster_config {
    enable_private_nodes    = true
    enable_private_endpoint = false # public control plane endpoint for kubectl from CI
    master_ipv4_cidr_block  = "172.16.0.0/28"
  }

  # Workload Identity: pods authenticate to GCP APIs using Kubernetes service
  # accounts mapped to GCP service accounts — no key files needed.
  workload_identity_config {
    workload_pool = "${var.project_id}.svc.id.goog"
  }

  release_channel {
    # REGULAR: receives new Kubernetes versions ~2-3 months after release.
    # RAPID: earliest new versions. STABLE: 2-3 months after REGULAR.
    channel = "REGULAR"
  }

  # Disabled in dev so `terraform destroy` / recreate is possible between tests.
  # Flip to true once the cluster hosts production workloads.
  deletion_protection = false
}

# =============================================================================
# Cloud SQL — managed PostgreSQL 17
# =============================================================================

resource "google_sql_database_instance" "postgres" {
  name             = "mirador-db"
  database_version = "POSTGRES_17"
  region           = var.region

  settings {
    tier = var.db_tier

    # Private IP only — the instance is NOT reachable from the public internet.
    # Connection is via the VPC peering established above.
    ip_configuration {
      ipv4_enabled    = false # disable public IP — private IP only
      private_network = google_compute_network.vpc.id
    }

    backup_configuration {
      enabled                        = true
      start_time                     = "03:00" # 03:00 UTC — off-peak
      point_in_time_recovery_enabled = true    # enables WAL-based PITR
      transaction_log_retention_days = 7
      backup_retention_settings {
        retained_backups = 7 # keep 7 daily backups
      }
    }

    maintenance_window {
      day          = 7 # Sunday
      hour         = 4 # 04:00 UTC
      update_track = "stable"
    }

    # Database flags
    database_flags {
      name  = "log_min_duration_statement"
      value = "1000" # log queries slower than 1 second (slow query log)
    }
  }

  # Disabled in dev so `terraform destroy` can tear down cleanly between tests.
  # Flip to true before any production-grade deployment to protect data.
  deletion_protection = false

  depends_on = [google_service_networking_connection.private_vpc_connection]
}

resource "google_sql_database" "mirador" {
  name     = var.db_name
  instance = google_sql_database_instance.postgres.name
}

resource "google_sql_user" "app_user" {
  name     = var.db_user
  instance = google_sql_database_instance.postgres.name
  password = var.db_password
}

# =============================================================================
# IAM — Cloud SQL Auth Proxy via Workload Identity
# =============================================================================

# GCP service account used by the Cloud SQL Auth Proxy sidecar
resource "google_service_account" "sql_proxy" {
  account_id   = "mirador-sql-proxy"
  display_name = "Mirador Cloud SQL Auth Proxy"
}

resource "google_project_iam_member" "sql_proxy_role" {
  project = var.project_id
  role    = "roles/cloudsql.client"
  member  = "serviceAccount:${google_service_account.sql_proxy.email}"
}

# Bind the Kubernetes service account (app/mirador-backend) to the GCP SA
# so pods using that KSA automatically get Cloud SQL Client permissions.
resource "google_service_account_iam_member" "workload_identity_binding" {
  service_account_id = google_service_account.sql_proxy.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "serviceAccount:${var.project_id}.svc.id.goog[app/mirador-backend]"

  depends_on = [google_container_cluster.autopilot]
}

# =============================================================================
# Memorystore — managed Redis 7
# =============================================================================

resource "google_redis_instance" "cache" {
  name           = "mirador-redis"
  tier           = var.redis_tier
  memory_size_gb = var.redis_memory_size_gb
  region         = var.region
  redis_version  = "REDIS_7_2"
  display_name   = "Mirador Redis Cache"

  # Connect via private services access so pods reach Redis via VPC private IP
  authorized_network = google_compute_network.vpc.id
  connect_mode       = "PRIVATE_SERVICE_ACCESS"

  depends_on = [google_service_networking_connection.private_vpc_connection]
}
