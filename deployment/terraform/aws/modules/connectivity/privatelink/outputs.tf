output "endpoint_id" {
  description = "ID of the interface VPC endpoint."
  value       = aws_vpc_endpoint.target.id
}

output "endpoint_dns" {
  description = "AWS-generated regional DNS name of the interface endpoint. Callers can use this directly when no custom dns_name is set. Known only after apply."
  value       = try(aws_vpc_endpoint.target.dns_entry[0].dns_name, null)
}

output "endpoint_state" {
  description = "Endpoint state. 'pendingAcceptance' means the provider must accept the connection before it is usable; 'available' means ready."
  value       = aws_vpc_endpoint.target.state
}

output "endpoint_fqdn" {
  description = "The FQDN that resolves to the endpoint via the private hosted zone; null when dns_name is unset (use endpoint_dns instead). Put this (or endpoint_dns) in the workflow target/source cluster endpoint config."
  value       = var.dns_name
}
