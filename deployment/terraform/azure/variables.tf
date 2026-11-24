variable "subscription_id" {
  description = "Azure subscription in which to create the Migration Assistant infrastructure. Default null uses ARM_SUBSCRIPTION_ID or the Azure CLI's current subscription."
  type        = string
  default     = null
}

variable "location" {
  description = "Azure region in which to create the Migration Assistant infrastructure."
  type        = string
  default     = "eastus"
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

variable "resource_group_name" {
  description = "Name of the resource group the module creates. Default null uses ma-<stage>-<location>."
  type        = string
  default     = null
}

variable "kubernetes_version" {
  description = "AKS Kubernetes version. The chart requires 1.35 or later."
  type        = string
  default     = "1.35"
}

variable "vnet_cidr" {
  description = "Address space of the virtual network the module creates."
  type        = string
  default     = "10.212.0.0/16"

  validation {
    condition     = can(cidrhost(var.vnet_cidr, 0))
    error_message = "vnet_cidr must be a valid CIDR block."
  }
}

variable "aks_subnet_cidr" {
  description = "Address range of the AKS node subnet. It must be inside vnet_cidr. Pods use Azure CNI overlay addresses, not this range."
  type        = string
  default     = "10.212.0.0/20"

  validation {
    condition     = can(cidrhost(var.aks_subnet_cidr, 0))
    error_message = "aks_subnet_cidr must be a valid CIDR block."
  }
}

variable "node_vm_size" {
  description = "VM size of the AKS node pool. Standard_D4ps_v5 (arm64) also works; the published images are multi-architecture."
  type        = string
  default     = "Standard_D4s_v5"
}

variable "node_min_count" {
  description = "Minimum number of nodes in the autoscaling node pool."
  type        = number
  default     = 3
}

variable "node_max_count" {
  description = "Maximum number of nodes in the autoscaling node pool."
  type        = number
  default     = 6
}

variable "create_storage_account" {
  description = "Create the storage account that holds snapshots and workflow artifacts. When false, existing_storage_account_name and existing_storage_account_resource_group_name are required."
  type        = bool
  default     = true
}

variable "existing_storage_account_name" {
  description = "Existing storage account to use when create_storage_account is false."
  type        = string
  default     = null
}

variable "existing_storage_account_resource_group_name" {
  description = "Resource group of existing_storage_account_name."
  type        = string
  default     = null
}

variable "storage_container_name" {
  description = "Blob container for snapshots and workflow artifacts. Terraform creates it in a storage account it creates; in an existing account, the chart creates it if it is missing."
  type        = string
  default     = "migration-snapshots"
}

variable "namespace" {
  description = "Kubernetes namespace and Helm release name for the Migration Assistant."
  type        = string
  default     = "ma"
}

variable "workload_identity_service_accounts" {
  description = "Kubernetes service accounts in namespace that receive a federated credential for the workload identity."
  type        = set(string)
  default = [
    "migrations-service-account",
    "argo-workflow-executor",
    "argo-test-workflow-executor",
    "migration-console-access-role",
    "argo-server",
  ]
}

variable "enable_azure_monitor_logs" {
  description = <<-EOT
    Ship workload logs to Azure Monitor Logs through valuesAksMonitor.yaml. Creates a Log
    Analytics workspace, a custom table, a data collection rule, and a Microsoft Entra
    application whose client secret fluent-bit uses, stored in Terraform state. Default false
    keeps logs on the shared logs volume.
  EOT
  type        = bool
  default     = false
}

variable "log_analytics_retention_in_days" {
  description = "Retention of the Log Analytics workspace when enable_azure_monitor_logs is true."
  type        = number
  default     = 30
}

variable "deploy_helm" {
  description = "Install the Migration Assistant Helm chart after the cluster is ready."
  type        = bool
  default     = false
}

variable "migration_assistant_version" {
  description = "Public ECR image tag used when deploy_helm is true: a release later than 3.4.2, which cannot backfill on AKS. The tag must exist for all Migration Assistant images in public.ecr.aws/opensearchproject/*."
  type        = string
  default     = null
}

variable "helm_timeout_seconds" {
  description = "How long Terraform waits for the Helm release to become ready."
  type        = number
  default     = 1500
}

variable "tags" {
  description = "Additional tags applied to every Azure resource the module creates."
  type        = map(string)
  default     = {}
}
