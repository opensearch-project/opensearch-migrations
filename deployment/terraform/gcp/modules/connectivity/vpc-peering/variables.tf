variable "name_prefix" {
  description = "Prefix for resource names."
  type        = string
}

variable "leg" {
  description = "Leg label (source, target)."
  type        = string
}

variable "local_vpc_self_link" {
  description = "Self-link of our migration VPC."
  type        = string
}

variable "peer_vpc_self_link" {
  description = "Self-link of the peer VPC to connect to."
  type        = string
}
