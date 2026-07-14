terraform {
  required_version = ">= 1.6"
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = ">= 5.0"
    }
    google-beta = {
      source  = "hashicorp/google-beta"
      version = ">= 5.0"
    }
    kubernetes = {
      source  = "hashicorp/kubernetes"
      version = ">= 2.0"
    }
    helm = {
      source  = "hashicorp/helm"
      version = ">= 3.0"
    }
    random = {
      source  = "hashicorp/random"
      version = ">= 3.0"
    }
  }
}

moved {
  from = google_compute_network.migration_vpc
  to   = google_compute_network.migration_vpc[0]
}

moved {
  from = google_compute_subnetwork.migration_subnet
  to   = google_compute_subnetwork.migration_subnet[0]
}

provider "google" {
  project      = var.project
  region       = var.region
  access_token = var.access_token != null ? var.access_token : null
}

provider "google-beta" {
  project      = var.project
  region       = var.region
  access_token = var.access_token != null ? var.access_token : null
}

data "google_client_config" "default" {}

provider "kubernetes" {
  host                   = "https://${google_container_cluster.migration_standard.endpoint}"
  token                  = data.google_client_config.default.access_token
  cluster_ca_certificate = base64decode(google_container_cluster.migration_standard.master_auth[0].cluster_ca_certificate)
}

provider "helm" {
  kubernetes = {
    host                   = "https://${google_container_cluster.migration_standard.endpoint}"
    token                  = data.google_client_config.default.access_token
    cluster_ca_certificate = base64decode(google_container_cluster.migration_standard.master_auth[0].cluster_ca_certificate)
  }
}

resource "random_id" "suffix" {
  byte_length = 4
}

locals {
  name             = "os-migration-${random_id.suffix.hex}"
  bucket_name      = local.name
  node_locations   = length(var.node_locations) > 0 ? var.node_locations : slice(data.google_compute_zones.available.names, 0, min(var.max_zones, length(data.google_compute_zones.available.names)))
  vpc_network      = var.create_vpc ? google_compute_network.migration_vpc[0].id : data.google_compute_network.existing[0].id
  vpc_name         = var.create_vpc ? google_compute_network.migration_vpc[0].name : data.google_compute_network.existing[0].name
  subnet_id        = var.create_vpc ? google_compute_subnetwork.migration_subnet[0].id : data.google_compute_subnetwork.existing_subnet[0].id
  cluster_name     = google_container_cluster.migration_standard.name
  cluster_endpoint = google_container_cluster.migration_standard.endpoint
  cluster_location = google_container_cluster.migration_standard.location
}

data "google_compute_zones" "available" {
  region = var.region
  status = "UP"
}

data "google_compute_network" "existing" {
  count = var.create_vpc ? 0 : 1
  name  = var.existing_vpc_name
}

data "google_compute_subnetwork" "existing_subnet" {
  count  = var.create_vpc ? 0 : 1
  name   = var.existing_subnet_name
  region = var.region
}

# VPC (optional - use existing or create new)
resource "google_compute_network" "migration_vpc" {
  count                   = var.create_vpc ? 1 : 0
  name                    = "${local.name}-vpc"
  auto_create_subnetworks = false
}

resource "google_compute_subnetwork" "migration_subnet" {
  count                    = var.create_vpc ? 1 : 0
  name                     = "${local.name}-subnet"
  network                  = google_compute_network.migration_vpc[0].id
  region                   = var.region
  ip_cidr_range            = var.subnet_cidr
  private_ip_google_access = var.gcs_connectivity.mode == "private_google_access"
  secondary_ip_range {
    range_name    = "pods"
    ip_cidr_range = var.pods_cidr
  }
  secondary_ip_range {
    range_name    = "services"
    ip_cidr_range = var.services_cidr
  }
}

# Cloud Router + NAT for private nodes
resource "google_compute_router" "migration_router" {
  count   = var.create_vpc ? 1 : 0
  name    = "${local.name}-router"
  network = local.vpc_network
  region  = var.region

  depends_on = [google_compute_network.migration_vpc]
}

resource "google_compute_router_nat" "migration_nat" {
  count                              = var.create_vpc ? 1 : 0
  name                               = "${local.name}-nat"
  router                             = google_compute_router.migration_router[0].name
  region                             = var.region
  nat_ip_allocate_option             = "AUTO_ONLY"
  source_subnetwork_ip_ranges_to_nat = "ALL_SUBNETWORKS_ALL_IP_RANGES"
}

# Firewall rules
resource "google_compute_firewall" "migration_ingress" {
  count   = var.create_vpc ? 1 : 0
  name    = "${local.name}-ingress"
  network = local.vpc_name

  allow {
    protocol = "tcp"
    ports    = var.allowed_ingress_ports
  }

  source_ranges = var.allowed_ingress_cidrs
  target_tags   = ["migration"]

  depends_on = [google_compute_network.migration_vpc]
}

# GKE cluster
resource "google_container_cluster" "migration_standard" {
  provider = google-beta

  name     = local.name
  location = var.region

  node_locations = local.node_locations

  network    = local.vpc_network
  subnetwork = local.subnet_id

  ip_allocation_policy {
    cluster_secondary_range_name  = "pods"
    services_secondary_range_name = "services"
  }

  release_channel {
    channel = var.release_channel
  }

  deletion_protection   = false
  enable_shielded_nodes = true

  workload_identity_config {
    workload_pool = "${var.project}.svc.id.goog"
  }

  private_cluster_config {
    enable_private_nodes    = true
    enable_private_endpoint = var.enable_private_endpoint
    master_ipv4_cidr_block  = var.master_ipv4_cidr_block
  }

  master_authorized_networks_config {
    dynamic "cidr_blocks" {
      for_each = var.master_authorized_cidrs
      content {
        cidr_block   = cidr_blocks.value
        display_name = "cidr-${cidr_blocks.key}"
      }
    }
  }

  # Default node pool configured inline
  node_pool {
    name               = "default-pool"
    initial_node_count = var.node_count

    node_config {
      machine_type    = var.node_machine_type
      disk_size_gb    = var.node_disk_size
      disk_type       = var.node_disk_type
      service_account = google_service_account.migration_nodes.email
      oauth_scopes    = var.node_oauth_scopes
    }

    management {
      auto_repair  = true
      auto_upgrade = true
    }

    autoscaling {
      min_node_count = var.node_min_count
      max_node_count = var.node_max_count
    }
  }

  depends_on = [google_compute_router_nat.migration_nat]
}


# IAM service account for GKE nodes
resource "google_service_account" "migration_nodes" {
  account_id   = "${replace(local.name, "-", "")}-node-sa"
  display_name = "GKE Node Service Account - ${local.name}"
}

# Grant IAM roles to the node SA (for GCS snapshot access, etc.)
resource "google_project_iam_member" "node_iam_roles" {
  count   = length(var.node_iam_roles)
  project = var.project
  role    = var.node_iam_roles[count.index]
  member  = "serviceAccount:${google_service_account.migration_nodes.email}"
}

# Workload Identity binding for the migrations service account
resource "google_service_account_iam_member" "migrations_sa_wi" {
  service_account_id = google_service_account.migration_nodes.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "serviceAccount:${var.project}.svc.id.goog[${var.workload_identity_namespace}/migrations-service-account]"
  depends_on         = [google_container_cluster.migration_standard]
}

resource "google_service_account_iam_member" "additional_migrations_sa_wi" {
  for_each = toset(var.additional_workload_identity_service_accounts)

  service_account_id = google_service_account.migration_nodes.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "serviceAccount:${var.project}.svc.id.goog[${var.workload_identity_namespace}/${each.value}]"

  depends_on = [google_container_cluster.migration_standard]
}

# Cloud Storage bucket for migration snapshots
resource "google_storage_bucket" "migration_snapshots" {
  name          = local.bucket_name
  location      = var.region
  force_destroy = true

  uniform_bucket_level_access = true
}

# Dedicated node pool for Kafka brokers (Strimzi-managed).
# Tainted so only Kafka pods schedule here. Skipped when deploy_kafka = false
# (external Kafka configured via kafka_brokers variable).
resource "google_container_node_pool" "kafka" {
  count = var.deploy_kafka ? 1 : 0

  name     = "kafka-pool"
  cluster  = google_container_cluster.migration_standard.name
  location = var.region

  node_count = var.kafka_broker_count

  node_locations = local.node_locations

  node_config {
    machine_type    = var.kafka_machine_type
    disk_size_gb    = var.kafka_disk_size_gb
    disk_type       = var.kafka_disk_type
    service_account = google_service_account.migration_nodes.email
    oauth_scopes    = var.node_oauth_scopes

    labels = {
      "opensearch-migrations/role" = "kafka"
    }

    taint {
      key    = "opensearch-migrations/role"
      value  = "kafka"
      effect = "NO_SCHEDULE"
    }
  }

  management {
    auto_repair  = true
    auto_upgrade = true
  }
}

# ── Optional per-leg private connectivity (mode = none by default) ──────────

module "source_connectivity_psc" {
  count  = var.source_connectivity.mode == "psc_consumer" ? 1 : 0
  source = "./modules/connectivity/psc-consumer"

  name_prefix         = local.name
  leg                 = "source"
  region              = var.region
  vpc_network         = local.vpc_network
  subnet_id           = local.subnet_id
  service_attachment  = var.source_connectivity.service_attachment
  allow_global_access = var.source_connectivity.allow_global_access
  dns_name            = var.source_connectivity.dns_name
  dns_zone_domain     = var.source_connectivity.dns_zone_domain
}

module "target_connectivity_psc" {
  count  = var.target_connectivity.mode == "psc_consumer" ? 1 : 0
  source = "./modules/connectivity/psc-consumer"

  name_prefix         = local.name
  leg                 = "target"
  region              = var.region
  vpc_network         = local.vpc_network
  subnet_id           = local.subnet_id
  service_attachment  = var.target_connectivity.service_attachment
  allow_global_access = var.target_connectivity.allow_global_access
  dns_name            = var.target_connectivity.dns_name
  dns_zone_domain     = var.target_connectivity.dns_zone_domain
}

module "source_connectivity_peering" {
  count  = var.source_connectivity.mode == "vpc_peering" ? 1 : 0
  source = "./modules/connectivity/vpc-peering"

  name_prefix         = local.name
  leg                 = "source"
  local_vpc_self_link = local.vpc_network
  peer_vpc_self_link  = var.source_connectivity.peer_vpc_self_link
}

module "target_connectivity_peering" {
  count  = var.target_connectivity.mode == "vpc_peering" ? 1 : 0
  source = "./modules/connectivity/vpc-peering"

  name_prefix         = local.name
  leg                 = "target"
  local_vpc_self_link = local.vpc_network
  peer_vpc_self_link  = var.target_connectivity.peer_vpc_self_link
}

# Migration Assistant Helm release (includes Argo, Strimzi, migration console)
resource "helm_release" "migration_assistant" {
  name             = "ma"
  namespace        = "ma"
  create_namespace = true
  chart            = "${path.module}/../../k8s/charts/aggregates/migrationAssistantWithArgo"

  values = [
    file("${path.module}/../../k8s/charts/aggregates/migrationAssistantWithArgo/valuesGke.yaml")
  ]

  set = [
    {
      name  = "gcp.project"
      value = var.project
    },
    {
      name  = "gcp.serviceAccountEmail"
      value = google_service_account.migration_nodes.email
    },
    {
      name  = "gcsBucketConfiguration.bucketName"
      value = google_storage_bucket.migration_snapshots.name
    },
    {
      name  = "images.migrationConsole.repository"
      value = "us-central1-docker.pkg.dev/${var.project}/migrations/migration-console"
    },
    {
      name  = "images.migrationConsole.tag"
      value = "latest"
    },
    {
      name  = "images.installer.repository"
      value = "us-central1-docker.pkg.dev/${var.project}/migrations/migration-console"
    },
    {
      name  = "images.installer.tag"
      value = "latest"
    },
    {
      name  = "images.reindexFromSnapshot.repository"
      value = "us-central1-docker.pkg.dev/${var.project}/migrations/reindex-from-snapshot"
    },
    {
      name  = "images.reindexFromSnapshot.tag"
      value = "latest"
    },
    {
      name  = "migrationConfig.bucket_name"
      value = google_storage_bucket.migration_snapshots.name
    },
    {
      name  = "migrationConfig.pod_replicas"
      value = tostring(var.node_count)
    }
  ]

  depends_on = [
    google_container_cluster.migration_standard,
    google_container_node_pool.kafka
  ]
}
