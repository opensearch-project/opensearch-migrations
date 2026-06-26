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
