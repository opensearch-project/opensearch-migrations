output "endpoint_ip" {
  description = "The reserved internal IP of the PSC endpoint. Place this in the workflow user config cluster endpoint."
  value       = google_compute_address.endpoint.address
}

output "forwarding_rule_id" {
  description = "ID of the PSC consumer forwarding rule."
  value       = google_compute_forwarding_rule.endpoint.id
}

output "endpoint_fqdn" {
  description = "The FQDN that resolves to the PSC endpoint via the private DNS zone; null when dns_name is unset (connect by endpoint_ip instead)."
  value       = var.dns_name
}
