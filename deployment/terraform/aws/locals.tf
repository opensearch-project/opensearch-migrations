data "aws_availability_zones" "available" {
  state = "available"
}

data "aws_caller_identity" "current" {}

data "aws_partition" "current" {}

data "aws_vpc" "existing" {
  count = var.create_vpc ? 0 : 1
  id    = var.existing_vpc_id
}

data "aws_route_table" "existing_subnet" {
  for_each = !var.create_vpc && length(var.existing_route_table_ids) == 0 ? toset(var.existing_subnet_ids) : toset([])

  subnet_id = each.value
}

locals {
  cluster_name    = "migration-eks-cluster-${var.stage}-${var.region}"
  ecr_name        = "migration-ecr-${var.stage}-${var.region}"
  selected_azs    = length(var.availability_zones) == 2 ? var.availability_zones : slice(data.aws_availability_zones.available.names, 0, 2)
  vpc_id          = var.create_vpc ? aws_vpc.migration[0].id : var.existing_vpc_id
  vpc_cidr        = var.create_vpc ? var.vpc_cidr : data.aws_vpc.existing[0].cidr_block
  cluster_subnets = var.create_vpc ? values(aws_subnet.private)[*].id : var.existing_subnet_ids
  route_table_ids = var.create_vpc ? values(aws_route_table.private)[*].id : (
    length(var.existing_route_table_ids) > 0 ? var.existing_route_table_ids : distinct(values(data.aws_route_table.existing_subnet)[*].id)
  )

  # Isolated (air-gapped) new-VPC deployment: no NAT gateway, full private-endpoint set.
  isolated_new_vpc = var.create_vpc && var.isolated

  # Base endpoints always created for a new VPC. An isolated VPC additionally needs the
  # AWS-API endpoints the cluster would otherwise reach over the internet (matches the
  # working isolated-VPC deployment path: s3, ecr.api, ecr.dkr, logs, monitoring,
  # elasticfilesystem, sts, eks-auth).
  base_new_vpc_endpoints     = toset(["s3", "ecr.api", "ecr.dkr", "logs", "elasticfilesystem"])
  isolated_extra_endpoints   = toset(["monitoring", "sts", "eks-auth"])
  required_new_vpc_endpoints = local.isolated_new_vpc ? setunion(local.base_new_vpc_endpoints, local.isolated_extra_endpoints) : local.base_new_vpc_endpoints
  enabled_vpc_endpoints      = var.create_vpc ? setunion(local.required_new_vpc_endpoints, var.vpc_endpoints) : var.vpc_endpoints
  interface_vpc_endpoints    = setsubtract(local.enabled_vpc_endpoints, toset(["s3"]))

  common_tags = {
    "opensearch.org/migration-assistant" = var.stage
  }
}
