variable "access_token" {
  description = "GCP access token (omit to use ADC)"
  type        = string
  default     = null
}

variable "project" {
  description = <<-EOT
    GCP project ID (required). Set via one of:
      - export TF_VAR_project=$GCP_PROJECT_ID
      - terraform apply -var="project=my-project"
      - Add project = "my-project" to terraform.tfvars
  EOT
  type        = string
}

variable "region" {
  description = "GCP region"
  type        = string
  default     = "us-central1"
}

variable "node_machine_type" {
  description = "GKE node machine type (standard clusters only)"
  type        = string
  default     = "e2-standard-4"
}

variable "node_count" {
  description = "Initial node count per zone (standard clusters only)"
  type        = number
  default     = 2
}

variable "kafka_brokers" {
  description = "Comma-separated list of Kafka bootstrap brokers"
  type        = string
  default     = ""
}

variable "kafka_auth_type" {
  description = "Kafka authentication type"
  type        = string
  default     = "scram-sha-512"
}

variable "deploy_kafka" {
  description = "Deploy a dedicated GKE node pool for in-cluster Kafka brokers. Set to false when using external Kafka (MSK, Confluent Cloud, Aiven) configured via kafka_brokers."
  type        = bool
  default     = true
}

variable "kafka_broker_count" {
  description = "Number of Kafka broker nodes. Sizing: brokers = max(3, ceil(retention_gb / kafka_disk_size_gb))"
  type        = number
  default     = 3
}

variable "kafka_machine_type" {
  description = "GCE machine type for Kafka broker nodes. Kafka is I/O and network intensive; e2-standard-4 suits most workloads up to ~200MB/s aggregate throughput."
  type        = string
  default     = "e2-standard-4"
}

variable "kafka_disk_size_gb" {
  description = "Persistent disk size per Kafka broker in GB. Sizing: retention_gb = write_throughput_mb_s * backfill_hours * 3.6 * replication_factor. Divide by broker count for per-broker."
  type        = number
  default     = 200
}

variable "kafka_disk_type" {
  description = "Disk type for Kafka brokers (pd-ssd recommended for production throughput)"
  type        = string
  default     = "pd-ssd"
}

variable "create_vpc" {
  description = "Create a new VPC, or use an existing one"
  type        = bool
  default     = true
}

variable "existing_vpc_name" {
  description = "Name of an existing VPC to use (when create_vpc = false)"
  type        = string
  default     = null
}

variable "existing_subnet_name" {
  description = "Name of an existing subnet to use (when create_vpc = false)"
  type        = string
  default     = null
}

variable "subnet_cidr" {
  description = "IP range for the VPC subnet"
  type        = string
  default     = "10.0.0.0/16"
}

variable "pods_cidr" {
  description = "IP range for GKE pods (secondary range)"
  type        = string
  default     = "10.1.0.0/16"
}

variable "services_cidr" {
  description = "IP range for GKE services (secondary range)"
  type        = string
  default     = "10.2.0.0/20"
}

variable "master_ipv4_cidr_block" {
  description = "IP range for the GKE control plane (private clusters)"
  type        = string
  default     = "10.3.0.0/28"
}

variable "master_authorized_cidrs" {
  description = "List of CIDR blocks authorized to access the GKE control plane"
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "max_zones" {
  description = "Maximum number of zones for regional GKE node placement"
  type        = number
  default     = 2

  validation {
    condition     = var.max_zones > 0
    error_message = "max_zones must be greater than 0."
  }
}

variable "node_locations" {
  description = "Optional explicit GKE node zones. Defaults to the first max_zones available zones in the region."
  type        = list(string)
  default     = []
}

# Node pool
variable "node_disk_size" {
  description = "Node boot disk size in GB"
  type        = number
  default     = 50
}

variable "node_disk_type" {
  description = "Node boot disk type (pd-standard, pd-ssd)"
  type        = string
  default     = "pd-standard"
}

variable "node_oauth_scopes" {
  description = "OAuth scopes for GKE node service account"
  type        = list(string)
  default     = ["https://www.googleapis.com/auth/cloud-platform"]
}

variable "node_min_count" {
  description = "Minimum node count per zone (autoscaling)"
  type        = number
  default     = 1
}

variable "node_max_count" {
  description = "Maximum node count per zone (autoscaling)"
  type        = number
  default     = 5
}

variable "release_channel" {
  description = "GKE release channel (RAPID, REGULAR, STABLE)"
  type        = string
  default     = "REGULAR"
}

# Firewall
variable "allowed_ingress_ports" {
  description = "TCP ports allowed from internet to migration nodes"
  type        = list(string)
  default     = ["443", "9200", "9300"]
}

variable "allowed_ingress_cidrs" {
  description = "CIDR blocks allowed to reach migration nodes"
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "node_iam_roles" {
  description = "GCP IAM roles granted to the GKE node service account"
  type        = list(string)
  default     = ["roles/storage.admin", "roles/artifactregistry.reader"]
}

variable "workload_identity_namespace" {
  description = "Kubernetes namespace containing Migration Assistant service accounts"
  type        = string
  default     = "ma"
}

variable "additional_workload_identity_service_accounts" {
  description = "Additional Kubernetes service accounts allowed to impersonate the GCP migration service account"
  type        = list(string)
  default     = ["migration-console-access-role", "argo-workflow-executor", "argo-workflow-controller", "argo-controller", "argo-test-workflow-executor"]
}

variable "source_connectivity" {
  description = "Private connectivity for the source-read leg. mode = none (default) | psc_consumer | vpc_peering."
  type = object({
    mode = optional(string, "none")
    # psc_consumer:
    service_attachment  = optional(string)
    allow_global_access = optional(bool, false)
    # vpc_peering:
    peer_vpc_self_link = optional(string)
    # psc_consumer DNS (optional):
    dns_name        = optional(string)
    dns_zone_domain = optional(string)
  })
  default = { mode = "none" }

  validation {
    condition     = contains(["none", "psc_consumer", "vpc_peering"], var.source_connectivity.mode)
    error_message = "source_connectivity.mode must be one of: none, psc_consumer, vpc_peering."
  }

  validation {
    condition     = var.source_connectivity.mode != "psc_consumer" || try(var.source_connectivity.service_attachment, null) != null
    error_message = "source_connectivity.service_attachment is required when mode = psc_consumer."
  }

  validation {
    condition     = var.source_connectivity.mode != "vpc_peering" || try(var.source_connectivity.peer_vpc_self_link, null) != null
    error_message = "source_connectivity.peer_vpc_self_link is required when mode = vpc_peering."
  }
}

variable "target_connectivity" {
  description = "Private connectivity for the target-write leg. mode = none (default) | psc_consumer | vpc_peering."
  type = object({
    mode = optional(string, "none")
    # psc_consumer:
    service_attachment  = optional(string)
    allow_global_access = optional(bool, false)
    # vpc_peering:
    peer_vpc_self_link = optional(string)
    # psc_consumer DNS (optional):
    dns_name        = optional(string)
    dns_zone_domain = optional(string)
  })
  default = { mode = "none" }

  validation {
    condition     = contains(["none", "psc_consumer", "vpc_peering"], var.target_connectivity.mode)
    error_message = "target_connectivity.mode must be one of: none, psc_consumer, vpc_peering."
  }

  validation {
    condition     = var.target_connectivity.mode != "psc_consumer" || try(var.target_connectivity.service_attachment, null) != null
    error_message = "target_connectivity.service_attachment is required when mode = psc_consumer."
  }

  validation {
    condition     = var.target_connectivity.mode != "vpc_peering" || try(var.target_connectivity.peer_vpc_self_link, null) != null
    error_message = "target_connectivity.peer_vpc_self_link is required when mode = vpc_peering."
  }
}

variable "gcs_connectivity" {
  description = "Private path for snapshot traffic to Cloud Storage. mode = private_google_access (default) routes GKE->GCS over Google's private network; none disables it. (psc_google_apis, for VPC Service Controls perimeters, is reserved for a future release.)"
  type = object({
    mode = optional(string, "private_google_access")
  })
  default = { mode = "private_google_access" }

  validation {
    condition     = contains(["private_google_access", "none"], var.gcs_connectivity.mode)
    error_message = "gcs_connectivity.mode must be one of: private_google_access, none. (psc_google_apis is not yet implemented.)"
  }
}

variable "enable_private_endpoint" {
  description = "Give the GKE control plane a private endpoint only (no public IP). Default false preserves current behavior. When true, operator access requires IAP/bastion/VPN (see docs/gcpPrivateNetworking.md) and master_authorized_cidrs must be narrowed to internal ranges. We do not provision a bastion."
  type        = bool
  default     = false
}
