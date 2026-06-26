output "endpoint_ip" {
  description = "The reserved internal IP of the PSC endpoint. Place this in the workflow user config cluster endpoint."
  value       = google_compute_address.endpoint.address
}

output "forwarding_rule_id" {
  description = "ID of the PSC consumer forwarding rule."
  value       = google_compute_forwarding_rule.endpoint.id
}
