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
