# Run from modules/connectivity/vpc-peering/ with: terraform test

provider "google" {
  project = "test-project"
}

variables {
  name_prefix         = "os-migration-test"
  leg                 = "source"
  local_vpc_self_link = "projects/p/global/networks/local"
  peer_vpc_self_link  = "projects/peer/global/networks/remote"
}

run "plans_outbound_peering" {
  command = plan

  assert {
    condition     = google_compute_network_peering.outbound.network == var.local_vpc_self_link
    error_message = "Peering must be created from our local VPC."
  }
  assert {
    condition     = google_compute_network_peering.outbound.peer_network == var.peer_vpc_self_link
    error_message = "Peering must point at the supplied peer VPC."
  }
}
