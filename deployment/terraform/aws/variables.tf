variable "region" {
  description = "AWS region in which to create the Migration Assistant infrastructure."
  type        = string
  default     = "us-east-1"
}

variable "stage" {
  description = "Short identifier used in resource names. Use a unique value for each deployment in a region."
  type        = string
  default     = "dev"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{0,15}$", var.stage))
    error_message = "stage must start with a lowercase letter, contain only lowercase letters, numbers, or hyphens, and be at most 16 characters."
  }
}

variable "kubernetes_version" {
  description = "EKS Kubernetes control-plane version."
  type        = string
  default     = "1.35"
}

variable "create_vpc" {
  description = "Create a dual-stack VPC and two private/public subnet pairs. When false, existing_vpc_id and existing_subnet_ids are required."
  type        = bool
  default     = true
}

variable "permissions_boundary_arn" {
  description = <<-EOT
    Optional IAM permissions boundary ARN applied to every IAM role this module
    creates (cluster, node, workload, and snapshot roles). Default null applies no
    boundary. Set this when the account requires a permissions boundary on
    created roles (some organizations deny iam:CreateRole unless the request
    includes a specific boundary).
  EOT
  type        = string
  default     = null
}

variable "create_opensearch_service_snapshot_role" {
  description = <<-EOT
    Create the IAM role that Amazon OpenSearch Service assumes to read/write S3
    snapshots (trusted principal es.amazonaws.com). Default true preserves current
    behavior. Set false when the migration SOURCE is not Amazon OpenSearch Service
    (e.g. self-managed Elasticsearch/OpenSearch), which registers its S3 snapshot
    repository without assuming an AWS role, so this role is unused.
  EOT
  type        = bool
  default     = true
}

variable "isolated" {
  description = <<-EOT
    Deploy into an isolated (air-gapped) network for a fully private migration. Only
    applies when create_vpc is true. When true, no NAT gateway is created (private
    subnets have no outbound internet route) and the full set of service VPC endpoints
    is created so the cluster can reach AWS APIs privately: s3, ecr.api, ecr.dkr, logs,
    monitoring, elasticfilesystem, sts, eks-auth. Default false preserves current
    behavior (NAT gateway + the base endpoint set). Mirrors the working isolated-VPC
    deployment path.
  EOT
  type        = bool
  default     = false
}

variable "vpc_cidr" {
  description = "IPv4 CIDR for the VPC created by this module."
  type        = string
  default     = "10.212.0.0/16"

  validation {
    condition     = can(cidrhost(var.vpc_cidr, 0))
    error_message = "vpc_cidr must be a valid IPv4 CIDR block."
  }
}

variable "availability_zones" {
  description = "Two availability zones for a newly created VPC. The first two available zones are used when empty."
  type        = list(string)
  default     = []

  validation {
    condition     = length(var.availability_zones) == 0 || length(var.availability_zones) == 2
    error_message = "availability_zones must be empty or contain exactly two zones."
  }
}

variable "existing_vpc_id" {
  description = "Existing VPC ID used when create_vpc is false."
  type        = string
  default     = null
}

variable "existing_subnet_ids" {
  description = "At least two existing subnets in distinct availability zones used when create_vpc is false. Private subnets are recommended."
  type        = list(string)
  default     = []
}

variable "existing_route_table_ids" {
  description = "Route table IDs for an optional S3 gateway endpoint in an existing VPC. When empty, Terraform discovers the route table associated with each supplied subnet."
  type        = list(string)
  default     = []
}

variable "vpc_endpoints" {
  description = <<-EOT
    VPC endpoints to create. For a new VPC, s3, ecr.api, ecr.dkr, logs, and
    elasticfilesystem are always created to match the CloudFormation deployment;
    values here add optional endpoints. For an existing VPC, only values listed
    here are created. Valid values: s3, ecr.api, ecr.dkr, logs, monitoring,
    elasticfilesystem, sts, eks-auth.
  EOT
  type        = set(string)
  default     = []

  validation {
    condition = length(setsubtract(
      var.vpc_endpoints,
      toset(["s3", "ecr.api", "ecr.dkr", "logs", "monitoring", "elasticfilesystem", "sts", "eks-auth"])
    )) == 0
    error_message = "vpc_endpoints contains an unsupported endpoint name."
  }
}

variable "source_connectivity" {
  description = <<-EOT
    Private connectivity for the source-read leg. mode = none (default, reach the
    source over its public endpoint / out-of-band VPN/DX) | privatelink (consumer
    interface endpoint to the source's VPC endpoint service) | vpc_peering (peer the
    migration VPC with the source VPC). Managed OpenSearch sources typically expose
    privatelink; a customer-owned source VPC typically uses vpc_peering.
  EOT
  type = object({
    mode = optional(string, "none")
    # privatelink:
    vpc_endpoint_service_name = optional(string)
    # vpc_peering:
    peer_vpc_id     = optional(string)
    peer_cidr       = optional(string)
    peer_account_id = optional(string)
    peer_region     = optional(string)
    # private DNS (optional, privatelink only):
    dns_name        = optional(string)
    dns_zone_domain = optional(string)
  })
  default = { mode = "none" }

  validation {
    condition     = contains(["none", "privatelink", "vpc_peering"], var.source_connectivity.mode)
    error_message = "source_connectivity.mode must be one of: none, privatelink, vpc_peering."
  }

  validation {
    condition     = var.source_connectivity.mode != "privatelink" || try(var.source_connectivity.vpc_endpoint_service_name, null) != null
    error_message = "source_connectivity.vpc_endpoint_service_name is required when mode = privatelink."
  }

  validation {
    condition     = var.source_connectivity.mode != "vpc_peering" || (try(var.source_connectivity.peer_vpc_id, null) != null && try(var.source_connectivity.peer_cidr, null) != null)
    error_message = "source_connectivity.peer_vpc_id and peer_cidr are required when mode = vpc_peering."
  }
}

variable "target_connectivity" {
  description = <<-EOT
    Private connectivity for the target-write leg. Same modes as source_connectivity.
    A managed OpenSearch target almost always exposes privatelink (a VPC endpoint
    service); vpc_peering fits a target in a peered AWS VPC.
  EOT
  type = object({
    mode = optional(string, "none")
    # privatelink:
    vpc_endpoint_service_name = optional(string)
    # vpc_peering:
    peer_vpc_id     = optional(string)
    peer_cidr       = optional(string)
    peer_account_id = optional(string)
    peer_region     = optional(string)
    # private DNS (optional, privatelink only):
    dns_name        = optional(string)
    dns_zone_domain = optional(string)
  })
  default = { mode = "none" }

  validation {
    condition     = contains(["none", "privatelink", "vpc_peering"], var.target_connectivity.mode)
    error_message = "target_connectivity.mode must be one of: none, privatelink, vpc_peering."
  }

  validation {
    condition     = var.target_connectivity.mode != "privatelink" || try(var.target_connectivity.vpc_endpoint_service_name, null) != null
    error_message = "target_connectivity.vpc_endpoint_service_name is required when mode = privatelink."
  }

  validation {
    condition     = var.target_connectivity.mode != "vpc_peering" || (try(var.target_connectivity.peer_vpc_id, null) != null && try(var.target_connectivity.peer_cidr, null) != null)
    error_message = "target_connectivity.peer_vpc_id and peer_cidr are required when mode = vpc_peering."
  }
}

variable "cluster_endpoint_public_access" {
  description = "Expose the EKS Kubernetes API through a public endpoint."
  type        = bool
  default     = true
}

variable "cluster_endpoint_private_access" {
  description = "Expose the EKS Kubernetes API through a private VPC endpoint."
  type        = bool
  default     = true
}

variable "cluster_public_access_cidrs" {
  description = "CIDRs permitted to reach the public EKS Kubernetes API endpoint. Narrow this for production deployments."
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "namespace" {
  description = "Kubernetes namespace used by Migration Assistant and its EKS Pod Identity associations."
  type        = string
  default     = "ma"
}

variable "pod_identity_service_accounts" {
  description = "Kubernetes service accounts that assume the shared Migration Assistant IAM role through EKS Pod Identity."
  type        = set(string)
  default = [
    "build-images-service-account",
    "argo-workflow-executor",
    "migrations-service-account",
    "migration-console-access-role",
    "otel-collector",
    "argo-test-workflow-executor",
    "ack-cloudwatch-controller",
  ]
}

variable "deploy_helm" {
  description = "Install the repository's Migration Assistant Helm chart after the EKS cluster is ready."
  type        = bool
  default     = false
}

variable "migration_assistant_version" {
  description = "Public ECR image tag used when deploy_helm is true, for example 3.3.4. The tag must exist for all Migration Assistant images in public.ecr.aws/opensearchproject/*."
  type        = string
  default     = null
}

variable "use_custom_karpenter_node_pool" {
  description = "Configure the chart's custom EKS Auto Mode NodePool instead of relying only on the built-in general-purpose pool."
  type        = bool
  default     = true
}

variable "helm_timeout_seconds" {
  description = "Maximum time to wait for the Migration Assistant Helm release."
  type        = number
  default     = 1500
}

variable "tags" {
  description = "Additional tags applied to AWS resources managed by this module."
  type        = map(string)
  default     = {}
}
