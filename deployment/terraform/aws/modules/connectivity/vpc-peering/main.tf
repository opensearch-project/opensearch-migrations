# We request the peering from our (migration) VPC. When the peer is in another
# account or region, the peer must ACCEPT the connection before it becomes active;
# peering is non-transitive and both sides must have routes. auto_accept only works
# for same-account, same-region peers, so it is set accordingly.
resource "aws_vpc_peering_connection" "target" {
  vpc_id        = var.local_vpc_id
  peer_vpc_id   = var.peer_vpc_id
  peer_owner_id = var.peer_account_id
  peer_region   = var.peer_region
  auto_accept   = var.peer_account_id == null && var.peer_region == null

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-peer-${var.leg}"
  })
}

# Routes from the migration private subnets to the peer CIDR. The peer must add the
# reciprocal route back to the migration VPC CIDR for traffic to flow both ways.
resource "aws_route" "to_peer" {
  count = length(var.private_route_table_ids)

  route_table_id            = var.private_route_table_ids[count.index]
  destination_cidr_block    = var.peer_cidr
  vpc_peering_connection_id = aws_vpc_peering_connection.target.id
}
