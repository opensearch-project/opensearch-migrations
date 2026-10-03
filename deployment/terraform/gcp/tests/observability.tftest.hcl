# Plan-time tests for the observability wiring (Cloud Logging).
# No cloud credentials required: command = plan only.
# Run from deployment/terraform/gcp/ with: terraform test
#
# Guards the logging changes against silent breakage in future edits or a
# TF/provider version bump:
#   - the node service account keeps roles/logging.logWriter (fluent-bit ships
#     workload logs to Cloud Logging via Workload Identity)
#   - the GKE cluster name/location are passed to the Helm release so the
#     fluent-bit stackdriver output can tag the k8s_container resource

variables {
  project = "test-project"
  # Dummy token so the google provider skips Application Default Credentials
  # lookup — these are plan-only tests and must run without cloud credentials
  # (e.g. in CI).
  access_token = "test-token-not-used"
}

# Mock the zones data source to avoid GCP API calls during testing.
override_data {
  target = data.google_compute_zones.available
  values = {
    names = ["us-central1-a", "us-central1-b"]
  }
}

run "node_sa_has_log_writer_by_default" {
  command = plan

  assert {
    condition     = contains(var.node_iam_roles, "roles/logging.logWriter")
    error_message = "node_iam_roles must include roles/logging.logWriter so fluent-bit can write to Cloud Logging."
  }

  # The role must actually be bound to the node SA, not just present in the list.
  assert {
    condition = anytrue([
      for m in google_project_iam_member.node_iam_roles :
      m.role == "roles/logging.logWriter"
    ])
    error_message = "A google_project_iam_member granting roles/logging.logWriter to the node SA must be planned."
  }
}

run "one_iam_binding_per_configured_role" {
  command = plan

  # One google_project_iam_member is planned per node_iam_roles entry (so the
  # logging.logWriter grant added for Cloud Logging is actually bound, not
  # dropped by a future refactor of the count/for_each wiring).
  assert {
    condition     = length(google_project_iam_member.node_iam_roles) == length(var.node_iam_roles)
    error_message = "Expected one node IAM binding per role in node_iam_roles."
  }
}

run "cluster_name_and_location_passed_to_helm_release" {
  command = plan

  # The fluent-bit stackdriver output needs the cluster name + location for the
  # k8s_container resource; Terraform threads them into the Helm release.
  assert {
    condition = anytrue([
      for s in helm_release.migration_assistant.set : s.name == "gcp.clusterName"
    ])
    error_message = "helm_release must set gcp.clusterName so the stackdriver output can tag logs with the cluster."
  }
  assert {
    condition = anytrue([
      for s in helm_release.migration_assistant.set : s.name == "gcp.clusterLocation"
    ])
    error_message = "helm_release must set gcp.clusterLocation so the stackdriver output can tag logs with the location."
  }
}
