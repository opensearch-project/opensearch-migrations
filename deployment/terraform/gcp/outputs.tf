output "cluster_name" {
  description = "GKE cluster name"
  value       = local.cluster_name
}

output "cluster_location" {
  description = "GKE cluster location"
  value       = local.cluster_location
}

output "source_private_endpoint" {
  description = "Private endpoint for the source leg. For psc_consumer this is the reserved internal IP to place in your workflow user config; null when mode = none or vpc_peering (use the cluster's own URL over the peered route)."
  value       = length(module.source_connectivity_psc) > 0 ? module.source_connectivity_psc[0].endpoint_ip : null
}

output "target_private_endpoint" {
  description = "Private endpoint for the target leg. For psc_consumer this is the reserved internal IP to place in your workflow user config; null when mode = none or vpc_peering."
  value       = length(module.target_connectivity_psc) > 0 ? module.target_connectivity_psc[0].endpoint_ip : null
}

output "source_peering_state" {
  description = "State of the source-leg VPC peering (null unless mode = vpc_peering)."
  value       = length(module.source_connectivity_peering) > 0 ? module.source_connectivity_peering[0].peering_state : null
}

output "target_peering_state" {
  description = "State of the target-leg VPC peering (null unless mode = vpc_peering)."
  value       = length(module.target_connectivity_peering) > 0 ? module.target_connectivity_peering[0].peering_state : null
}

output "source_private_fqdn" {
  description = "Hostname that resolves to the source PSC endpoint over the private DNS zone; null unless mode = psc_consumer with dns_name set. Use this (not the IP) as the source cluster endpoint for valid TLS."
  value       = length(module.source_connectivity_psc) > 0 ? module.source_connectivity_psc[0].endpoint_fqdn : null
}

output "target_private_fqdn" {
  description = "Hostname that resolves to the target PSC endpoint over the private DNS zone; null unless mode = psc_consumer with dns_name set. Use this (not the IP) as the target cluster endpoint for valid TLS."
  value       = length(module.target_connectivity_psc) > 0 ? module.target_connectivity_psc[0].endpoint_fqdn : null
}
