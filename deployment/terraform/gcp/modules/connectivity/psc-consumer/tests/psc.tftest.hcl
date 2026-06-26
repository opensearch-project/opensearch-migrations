# Run from modules/connectivity/psc-consumer/ with: terraform test

provider "google" {
  project = "test-project"
}

variables {
  name_prefix        = "os-migration-test"
  region             = "us-central1"
  vpc_network        = "projects/p/global/networks/n"
  subnet_id          = "projects/p/regions/us-central1/subnetworks/s"
  service_attachment = "projects/producer/regions/us-central1/serviceAttachments/sa"
  leg                = "target"
}

run "plans_one_address_and_one_forwarding_rule" {
  command = plan

  assert {
    condition     = google_compute_forwarding_rule.endpoint.load_balancing_scheme == ""
    error_message = "PSC consumer forwarding rule must set load_balancing_scheme = \"\" to override the EXTERNAL default."
  }
  assert {
    condition     = google_compute_forwarding_rule.endpoint.target == var.service_attachment
    error_message = "Forwarding rule target must be the supplied service attachment."
  }
  assert {
    condition     = google_compute_address.endpoint.address_type == "INTERNAL"
    error_message = "Reserved address must be INTERNAL."
  }
}
