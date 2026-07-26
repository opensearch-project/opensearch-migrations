# We create only our (outbound) side. The peer must create the reciprocal
# peering from their VPC back to ours — peering is non-transitive and both
# directions must exist. Documented in docs/gcpPrivateNetworking.md.
resource "google_compute_network_peering" "outbound" {
  name         = "${var.name_prefix}-peer-out-${var.leg}"
  network      = var.local_vpc_self_link
  peer_network = var.peer_vpc_self_link
}
