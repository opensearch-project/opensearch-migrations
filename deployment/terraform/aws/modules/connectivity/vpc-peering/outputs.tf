output "peering_connection_id" {
  description = "ID of the VPC peering connection. The peer needs this to accept (cross-account/region) and to add their reciprocal route."
  value       = aws_vpc_peering_connection.target.id
}

output "peering_accept_status" {
  description = "Acceptance status of the peering. For cross-account/region peers this stays 'pending-acceptance' until the peer accepts."
  value       = aws_vpc_peering_connection.target.accept_status
}
