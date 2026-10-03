locals {
  subnet_layout = {
    for index, az in local.selected_azs : az => {
      index        = index
      private_cidr = cidrsubnet(var.vpc_cidr, 4, index)
      public_cidr  = cidrsubnet(var.vpc_cidr, 4, index + 2)
    }
  }
}

resource "aws_vpc" "migration" {
  count = var.create_vpc ? 1 : 0

  cidr_block                       = var.vpc_cidr
  assign_generated_ipv6_cidr_block = true
  enable_dns_hostnames             = true
  enable_dns_support               = true

  tags = merge(local.common_tags, {
    Name = "migration-assistant-vpc-${var.stage}"
  })
}

resource "aws_internet_gateway" "migration" {
  count = var.create_vpc ? 1 : 0

  vpc_id = aws_vpc.migration[0].id

  tags = merge(local.common_tags, {
    Name = "migration-assistant-igw-${var.stage}"
  })
}

resource "aws_egress_only_internet_gateway" "migration" {
  count = var.create_vpc ? 1 : 0

  vpc_id = aws_vpc.migration[0].id

  tags = merge(local.common_tags, {
    Name = "migration-assistant-eigw-${var.stage}"
  })
}

resource "aws_subnet" "public" {
  for_each = var.create_vpc ? local.subnet_layout : {}

  vpc_id                          = aws_vpc.migration[0].id
  availability_zone               = each.key
  cidr_block                      = each.value.public_cidr
  ipv6_cidr_block                 = cidrsubnet(aws_vpc.migration[0].ipv6_cidr_block, 8, each.value.index + 2)
  assign_ipv6_address_on_creation = true
  map_public_ip_on_launch         = true

  tags = merge(local.common_tags, {
    Name                     = "migration-assistant-public-subnet-${each.value.index + 1}-${var.stage}"
    "kubernetes.io/role/elb" = "1"
  })
}

resource "aws_subnet" "private" {
  for_each = var.create_vpc ? local.subnet_layout : {}

  vpc_id                          = aws_vpc.migration[0].id
  availability_zone               = each.key
  cidr_block                      = each.value.private_cidr
  ipv6_cidr_block                 = cidrsubnet(aws_vpc.migration[0].ipv6_cidr_block, 8, each.value.index)
  assign_ipv6_address_on_creation = true

  tags = merge(local.common_tags, {
    Name                                          = "migration-assistant-private-subnet-${each.value.index + 1}-${var.stage}"
    "kubernetes.io/cluster/${local.cluster_name}" = "shared"
    "kubernetes.io/role/internal-elb"             = "1"
  })
}

# NAT gateways provide private-subnet egress. Skipped for an isolated VPC, where private
# subnets must have no outbound internet route (traffic to AWS APIs goes via VPC endpoints).
resource "aws_eip" "nat" {
  for_each = local.isolated_new_vpc ? {} : (var.create_vpc ? local.subnet_layout : {})

  domain = "vpc"

  tags = merge(local.common_tags, {
    Name = "migration-assistant-nat-eip-${each.value.index + 1}-${var.stage}"
  })

  depends_on = [aws_internet_gateway.migration]
}

resource "aws_nat_gateway" "migration" {
  for_each = local.isolated_new_vpc ? {} : (var.create_vpc ? local.subnet_layout : {})

  allocation_id = aws_eip.nat[each.key].id
  subnet_id     = aws_subnet.public[each.key].id

  tags = merge(local.common_tags, {
    Name = "migration-assistant-nat-${each.value.index + 1}-${var.stage}"
  })

  depends_on = [aws_internet_gateway.migration]
}

resource "aws_route_table" "public" {
  for_each = var.create_vpc ? local.subnet_layout : {}

  vpc_id = aws_vpc.migration[0].id

  tags = merge(local.common_tags, {
    Name = "migration-assistant-public-rt-${each.value.index + 1}-${var.stage}"
  })
}

resource "aws_route" "public_ipv4" {
  for_each = var.create_vpc ? local.subnet_layout : {}

  route_table_id         = aws_route_table.public[each.key].id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.migration[0].id
}

resource "aws_route" "public_ipv6" {
  for_each = var.create_vpc ? local.subnet_layout : {}

  route_table_id              = aws_route_table.public[each.key].id
  destination_ipv6_cidr_block = "::/0"
  gateway_id                  = aws_internet_gateway.migration[0].id
}

resource "aws_route_table_association" "public" {
  for_each = var.create_vpc ? local.subnet_layout : {}

  route_table_id = aws_route_table.public[each.key].id
  subnet_id      = aws_subnet.public[each.key].id
}

resource "aws_route_table" "private" {
  for_each = var.create_vpc ? local.subnet_layout : {}

  vpc_id = aws_vpc.migration[0].id

  tags = merge(local.common_tags, {
    Name = "migration-assistant-private-rt-${each.value.index + 1}-${var.stage}"
  })
}

# Private-subnet egress routes. Omitted for an isolated VPC so private subnets have no
# route to the internet; AWS-API traffic flows through the VPC endpoints instead.
resource "aws_route" "private_ipv4" {
  for_each = local.isolated_new_vpc ? {} : (var.create_vpc ? local.subnet_layout : {})

  route_table_id         = aws_route_table.private[each.key].id
  destination_cidr_block = "0.0.0.0/0"
  nat_gateway_id         = aws_nat_gateway.migration[each.key].id
}

resource "aws_route" "private_ipv6" {
  for_each = local.isolated_new_vpc ? {} : (var.create_vpc ? local.subnet_layout : {})

  route_table_id              = aws_route_table.private[each.key].id
  destination_ipv6_cidr_block = "::/0"
  egress_only_gateway_id      = aws_egress_only_internet_gateway.migration[0].id
}

resource "aws_route_table_association" "private" {
  for_each = var.create_vpc ? local.subnet_layout : {}

  route_table_id = aws_route_table.private[each.key].id
  subnet_id      = aws_subnet.private[each.key].id
}

resource "aws_security_group" "vpc_endpoints" {
  count = length(local.interface_vpc_endpoints) > 0 ? 1 : 0

  name_prefix = "${local.cluster_name}-vpce-"
  description = "Allow HTTPS from the Migration Assistant VPC to interface endpoints"
  vpc_id      = local.vpc_id

  ingress {
    description = "HTTPS from the VPC"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = [local.vpc_cidr]
  }

  egress {
    description = "All outbound traffic"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(local.common_tags, {
    Name = "${local.cluster_name}-vpc-endpoints"
  })
}

resource "aws_vpc_endpoint" "s3" {
  count = contains(local.enabled_vpc_endpoints, "s3") ? 1 : 0

  vpc_id            = local.vpc_id
  service_name      = "com.amazonaws.${var.region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = local.route_table_ids

  tags = merge(local.common_tags, {
    Name = "${local.cluster_name}-s3"
  })
}

resource "aws_vpc_endpoint" "interface" {
  for_each = local.interface_vpc_endpoints

  vpc_id              = local.vpc_id
  service_name        = "com.amazonaws.${var.region}.${each.value}"
  vpc_endpoint_type   = "Interface"
  private_dns_enabled = true
  subnet_ids          = local.cluster_subnets
  security_group_ids  = [aws_security_group.vpc_endpoints[0].id]

  tags = merge(local.common_tags, {
    Name = "${local.cluster_name}-${replace(each.value, ".", "-")}"
  })
}
