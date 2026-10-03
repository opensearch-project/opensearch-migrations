variable "name_prefix" {
  description = "Prefix for resource names (e.g. the migration cluster name)."
  type        = string
}

variable "leg" {
  description = "Leg label used to disambiguate resource names (source, target)."
  type        = string
}

variable "vpc_id" {
  description = "VPC the interface endpoint lives in."
  type        = string
}

variable "vpc_cidr" {
  description = "IPv4 CIDR of the VPC, used for the endpoint security group ingress rule."
  type        = string
}

variable "subnet_ids" {
  description = "Subnets (across the migration AZs) to place the interface endpoint ENIs in. Must be in Availability Zones the endpoint service offers."
  type        = list(string)
}

variable "vpc_endpoint_service_name" {
  description = "The provider's VPC endpoint service name to connect to (e.g. com.amazonaws.vpce.us-east-1.vpce-svc-0123...). Obtain this from the target's managed-service provider."
  type        = string
}

variable "dns_name" {
  description = <<-EOT
    Optional FQDN to resolve to the interface endpoint via a Route 53 private
    hosted zone (e.g. myservice.example.com). When null, no zone is created and
    callers connect via the endpoint's AWS-generated DNS name. Use the provider's
    canonical hostname so the target's TLS certificate validates.
  EOT
  type        = string
  default     = null

  validation {
    condition     = var.dns_name == null || length(split(".", var.dns_name)) >= 3
    error_message = "dns_name must be a host under a domain with at least 3 labels (e.g. host.example.com) so a valid parent zone can be derived."
  }
}

variable "dns_zone_domain" {
  description = "Optional private hosted-zone domain for dns_name (e.g. example.com). When null and dns_name is set, the parent domain is derived by stripping dns_name's first label."
  type        = string
  default     = null
}

variable "tags" {
  description = "Tags applied to created resources."
  type        = map(string)
  default     = {}
}
