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

run "no_dns_by_default" {
  command = plan

  assert {
    condition     = length(google_dns_managed_zone.psc) == 0
    error_message = "No managed zone may be created when dns_name is unset."
  }
  assert {
    condition     = length(google_dns_record_set.psc) == 0
    error_message = "No record set may be created when dns_name is unset."
  }
  assert {
    condition     = output.endpoint_fqdn == null
    error_message = "endpoint_fqdn must be null when dns_name is unset."
  }
}

run "creates_private_zone_and_record_when_dns_name_set" {
  command = plan

  variables {
    dns_name = "myservice-myproj.example.com"
  }

  assert {
    condition     = length(google_dns_managed_zone.psc) == 1
    error_message = "Setting dns_name must create exactly one managed zone."
  }
  assert {
    condition     = google_dns_managed_zone.psc[0].visibility == "private"
    error_message = "The managed zone must be private."
  }
  assert {
    condition     = google_dns_managed_zone.psc[0].dns_name == "example.com."
    error_message = "Zone domain must be derived from dns_name's parent (example.com.) when dns_zone_domain is unset."
  }
  assert {
    condition     = google_dns_record_set.psc[0].name == "myservice-myproj.example.com."
    error_message = "Record set name must be the trailing-dot FQDN."
  }
  assert {
    condition     = google_dns_record_set.psc[0].type == "A"
    error_message = "Record set must be an A record."
  }
  assert {
    condition     = output.endpoint_fqdn == "myservice-myproj.example.com"
    error_message = "endpoint_fqdn must echo dns_name when DNS is enabled."
  }
}
