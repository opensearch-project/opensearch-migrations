# Optional per-leg private connectivity to the source and target clusters.
# Both legs default to mode = "none" (no resources, no change to a public-path
# deployment). See source_connectivity / target_connectivity in variables.tf.
#
# Operator responsibilities not automated here (kept minimal by design):
#   - privatelink: the provider must add this account/VPC to the endpoint service's
#     allow-list and ACCEPT the endpoint connection if acceptance is required; until
#     then endpoint_state is "pendingAcceptance" and the FQDN does not resolve to a
#     working endpoint. Use the provider's canonical hostname for dns_name so the
#     target TLS certificate validates.
#   - vpc_peering: the peer must ACCEPT the connection (cross-account/region) and add
#     the reciprocal route back to the migration VPC CIDR. Peer CIDRs must not overlap
#     var.vpc_cidr.
#   - the endpoint service / peer VPC must be available in the migration AZs.

module "source_connectivity_privatelink" {
  count  = var.source_connectivity.mode == "privatelink" ? 1 : 0
  source = "./modules/connectivity/privatelink"

  name_prefix               = local.cluster_name
  leg                       = "source"
  vpc_id                    = local.vpc_id
  vpc_cidr                  = local.vpc_cidr
  subnet_ids                = local.cluster_subnets
  vpc_endpoint_service_name = var.source_connectivity.vpc_endpoint_service_name
  dns_name                  = var.source_connectivity.dns_name
  dns_zone_domain           = var.source_connectivity.dns_zone_domain
  tags                      = local.common_tags
}

module "target_connectivity_privatelink" {
  count  = var.target_connectivity.mode == "privatelink" ? 1 : 0
  source = "./modules/connectivity/privatelink"

  name_prefix               = local.cluster_name
  leg                       = "target"
  vpc_id                    = local.vpc_id
  vpc_cidr                  = local.vpc_cidr
  subnet_ids                = local.cluster_subnets
  vpc_endpoint_service_name = var.target_connectivity.vpc_endpoint_service_name
  dns_name                  = var.target_connectivity.dns_name
  dns_zone_domain           = var.target_connectivity.dns_zone_domain
  tags                      = local.common_tags
}

module "source_connectivity_peering" {
  count  = var.source_connectivity.mode == "vpc_peering" ? 1 : 0
  source = "./modules/connectivity/vpc-peering"

  name_prefix             = local.cluster_name
  leg                     = "source"
  local_vpc_id            = local.vpc_id
  peer_vpc_id             = var.source_connectivity.peer_vpc_id
  peer_cidr               = var.source_connectivity.peer_cidr
  peer_account_id         = var.source_connectivity.peer_account_id
  peer_region             = var.source_connectivity.peer_region
  private_route_table_ids = local.route_table_ids
  tags                    = local.common_tags
}

module "target_connectivity_peering" {
  count  = var.target_connectivity.mode == "vpc_peering" ? 1 : 0
  source = "./modules/connectivity/vpc-peering"

  name_prefix             = local.cluster_name
  leg                     = "target"
  local_vpc_id            = local.vpc_id
  peer_vpc_id             = var.target_connectivity.peer_vpc_id
  peer_cidr               = var.target_connectivity.peer_cidr
  peer_account_id         = var.target_connectivity.peer_account_id
  peer_region             = var.target_connectivity.peer_region
  private_route_table_ids = local.route_table_ids
  tags                    = local.common_tags
}
