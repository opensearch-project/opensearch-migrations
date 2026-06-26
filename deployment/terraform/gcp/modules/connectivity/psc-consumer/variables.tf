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
