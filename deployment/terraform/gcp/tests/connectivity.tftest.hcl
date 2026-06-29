# Plan-time tests. No cloud credentials required: command = plan only.
# Run from deployment/terraform/gcp/ with: terraform test

variables {
  project = "test-project"
}

# Mock the data source to avoid GCP API calls during testing
override_data {
  target = data.google_compute_zones.available
  values = {
    names = ["us-central1-a", "us-central1-b"]
  }
}

run "baseline_plan_is_valid_with_defaults" {
  command = plan

  assert {
    condition     = var.source_connectivity.mode == "none"
    error_message = "source_connectivity must default to none."
  }
  assert {
    condition     = var.target_connectivity.mode == "none"
    error_message = "target_connectivity must default to none."
  }
}

run "rejects_invalid_source_mode" {
  command = plan

  variables {
    source_connectivity = { mode = "carrier_pigeon" }
  }

  expect_failures = [
    var.source_connectivity,
  ]
}

run "psc_consumer_requires_service_attachment" {
  command = plan

  variables {
    source_connectivity = { mode = "psc_consumer" }
  }

  expect_failures = [
    var.source_connectivity,
  ]
}

run "psc_consumer_target_plans_endpoint" {
  command = plan

  variables {
    target_connectivity = {
      mode               = "psc_consumer"
      service_attachment = "projects/producer/regions/us-south1/serviceAttachments/sa"
    }
  }

  assert {
    condition     = length(module.target_connectivity_psc) == 1
    error_message = "psc_consumer target must instantiate exactly one psc-consumer module."
  }
}

run "none_mode_plans_no_connectivity_modules" {
  command = plan

  assert {
    condition     = length(module.source_connectivity_psc) == 0 && length(module.target_connectivity_psc) == 0
    error_message = "Default none mode must create no connectivity modules."
  }
}

run "none_mode_outputs_null_private_endpoints" {
  command = plan

  assert {
    condition     = output.source_private_endpoint == null && output.target_private_endpoint == null
    error_message = "none mode must output null for both private endpoints."
  }
}

run "private_google_access_enabled_by_default" {
  command = plan

  assert {
    condition     = var.gcs_connectivity.mode == "private_google_access"
    error_message = "gcs_connectivity must default to private_google_access."
  }
  assert {
    condition     = google_compute_subnetwork.migration_subnet[0].private_ip_google_access == true
    error_message = "Subnet must enable private_ip_google_access in the default mode."
  }
}

run "gcs_none_mode_disables_private_access" {
  command = plan

  variables {
    gcs_connectivity = { mode = "none" }
  }

  assert {
    condition     = google_compute_subnetwork.migration_subnet[0].private_ip_google_access == false
    error_message = "mode = none must leave private_ip_google_access off."
  }
}

run "gcs_rejects_psc_google_apis_for_now" {
  command = plan

  variables {
    gcs_connectivity = { mode = "psc_google_apis" }
  }

  expect_failures = [
    var.gcs_connectivity,
  ]
}

run "private_endpoint_defaults_false_no_regression" {
  command = plan

  assert {
    condition     = google_container_cluster.migration_standard.private_cluster_config[0].enable_private_endpoint != true
    error_message = "enable_private_endpoint must default to false to preserve current behavior."
  }
}

run "private_endpoint_can_be_enabled" {
  command = plan

  variables {
    enable_private_endpoint = true
  }

  assert {
    condition     = google_container_cluster.migration_standard.private_cluster_config[0].enable_private_endpoint == true
    error_message = "enable_private_endpoint must be settable to true."
  }
}

run "psc_consumer_target_with_dns_exposes_fqdn" {
  command = plan

  variables {
    target_connectivity = {
      mode               = "psc_consumer"
      service_attachment = "projects/producer/regions/us-central1/serviceAttachments/sa"
      dns_name           = "myservice-myproj.example.com"
    }
  }

  assert {
    condition     = output.target_private_fqdn == "myservice-myproj.example.com"
    error_message = "target_private_fqdn must surface the configured dns_name."
  }
}

run "none_mode_outputs_null_fqdns" {
  command = plan

  assert {
    condition     = output.source_private_fqdn == null && output.target_private_fqdn == null
    error_message = "none mode must output null for both private FQDNs."
  }
}
