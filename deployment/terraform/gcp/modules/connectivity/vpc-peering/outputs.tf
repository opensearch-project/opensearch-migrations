output "peering_state" {
  description = "State of our side of the peering (ACTIVE once the peer creates the reciprocal side)."
  value       = google_compute_network_peering.outbound.state
}

output "local_vpc_self_link" {
  description = "Our VPC self-link — the peer needs this for their reciprocal peering."
  value       = var.local_vpc_self_link
}
