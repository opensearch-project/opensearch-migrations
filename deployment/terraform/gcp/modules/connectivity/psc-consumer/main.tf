resource "google_compute_address" "endpoint" {
  name         = "${var.name_prefix}-psc-${var.leg}"
  region       = var.region
  subnetwork   = var.subnet_id
  address_type = "INTERNAL"
}

resource "google_compute_forwarding_rule" "endpoint" {
  name                    = "${var.name_prefix}-psc-${var.leg}"
  region                  = var.region
  load_balancing_scheme   = "" # MUST be empty to target a service attachment (overrides EXTERNAL default)
  target                  = var.service_attachment
  network                 = var.vpc_network
  ip_address              = google_compute_address.endpoint.id
  allow_psc_global_access = var.allow_global_access
}

locals {
  dns_enabled = var.dns_name != null
  # Managed-zone domain: explicit override, else parent of dns_name (strip first label).
  zone_domain = var.dns_zone_domain != null ? var.dns_zone_domain : (
    local.dns_enabled ? join(".", slice(split(".", var.dns_name), 1, length(split(".", var.dns_name)))) : null
  )
}

resource "google_dns_managed_zone" "psc" {
  count       = local.dns_enabled ? 1 : 0
  name        = "${var.name_prefix}-psc-${var.leg}"
  dns_name    = "${local.zone_domain}."
  description = "Private zone routing ${var.dns_name} to the PSC endpoint for the ${var.leg} leg."
  visibility  = "private"

  private_visibility_config {
    networks {
      network_url = var.vpc_network
    }
  }
}

resource "google_dns_record_set" "psc" {
  count        = local.dns_enabled ? 1 : 0
  name         = "${var.dns_name}."
  type         = "A"
  ttl          = 60
  managed_zone = google_dns_managed_zone.psc[0].name
  rrdatas      = [google_compute_address.endpoint.address]
}
