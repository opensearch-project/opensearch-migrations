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
