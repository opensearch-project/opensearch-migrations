variable "name_prefix" {
  description = "Prefix for resource names (e.g. the migration cluster name)."
  type        = string
}

variable "region" {
  description = "GCP region for the endpoint."
  type        = string
}

variable "vpc_network" {
  description = "Self-link or name of the VPC network the endpoint lives in."
  type        = string
}

variable "subnet_id" {
  description = "Self-link of the subnet for the reserved internal address."
  type        = string
}

variable "service_attachment" {
  description = "The producer's PSC service-attachment URI to connect to."
  type        = string
}

variable "allow_global_access" {
  description = "Allow access to the PSC endpoint from any region."
  type        = bool
  default     = false
}

variable "leg" {
  description = "Leg label used to disambiguate resource names (e.g. source, target)."
  type        = string
}

variable "dns_name" {
  description = "Optional FQDN to resolve to the PSC endpoint IP via a private Cloud DNS zone (e.g. myservice-myproj.example.com). When null, no DNS zone is created and callers connect by IP."
  type        = string
  default     = null

  validation {
    condition     = var.dns_name == null || length(split(".", var.dns_name)) >= 3
    error_message = "dns_name must be a host under a domain with at least 3 labels (e.g. host.example.com) so a valid parent zone can be derived. Managed-service endpoints meet this; an apex like example.com is not supported."
  }
}

variable "dns_zone_domain" {
  description = "Optional managed-zone domain for dns_name (e.g. example.com). When null and dns_name is set, the parent domain is derived by stripping dns_name's first label."
  type        = string
  default     = null
}
