variable "name_prefix" {
  description = "Prefix for resource names."
  type        = string
}

variable "leg" {
  description = "Leg label (source, target)."
  type        = string
}

variable "local_vpc_id" {
  description = "VPC ID of the migration VPC (the requester side of the peering)."
  type        = string
}

variable "peer_vpc_id" {
  description = "VPC ID of the peer (the source or target network to connect to)."
  type        = string
}

variable "peer_cidr" {
  description = "IPv4 CIDR of the peer VPC, used for the route added to the migration private route tables. Must not overlap the migration VPC CIDR."
  type        = string
}

variable "peer_account_id" {
  description = "Optional AWS account ID of the peer VPC when it is in a different account. Null for same-account peering."
  type        = string
  default     = null
}

variable "peer_region" {
  description = "Optional region of the peer VPC when it is in a different region. Null for same-region peering."
  type        = string
  default     = null
}

variable "private_route_table_ids" {
  description = "The migration VPC's private route table IDs. A route to peer_cidr is added to each so the cluster can reach the peer."
  type        = list(string)
}

variable "tags" {
  description = "Tags applied to created resources."
  type        = map(string)
  default     = {}
}
