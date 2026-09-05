data "aws_iam_policy_document" "eks_cluster_assume_role" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole", "sts:TagSession"]

    principals {
      type        = "Service"
      identifiers = ["eks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "eks_cluster" {
  name                 = "ma-${var.stage}-${var.region}-eks-cluster"
  description          = "EKS Auto Mode cluster role for OpenSearch Migration Assistant"
  assume_role_policy   = data.aws_iam_policy_document.eks_cluster_assume_role.json
  permissions_boundary = var.permissions_boundary_arn

  tags = local.common_tags
}

locals {
  eks_cluster_managed_policies = toset([
    "arn:${data.aws_partition.current.partition}:iam::aws:policy/AmazonEKSClusterPolicy",
    "arn:${data.aws_partition.current.partition}:iam::aws:policy/AmazonEKSComputePolicy",
    "arn:${data.aws_partition.current.partition}:iam::aws:policy/AmazonEKSBlockStoragePolicy",
    "arn:${data.aws_partition.current.partition}:iam::aws:policy/AmazonEKSLoadBalancingPolicy",
    "arn:${data.aws_partition.current.partition}:iam::aws:policy/AmazonEKSNetworkingPolicy",
  ])
}

resource "aws_iam_role_policy_attachment" "eks_cluster" {
  for_each = local.eks_cluster_managed_policies

  role       = aws_iam_role.eks_cluster.name
  policy_arn = each.value
}

data "aws_iam_policy_document" "eks_node_assume_role" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "eks_node" {
  name                 = "ma-${var.stage}-${var.region}-eks-node"
  description          = "Node role used by EKS Auto Mode"
  assume_role_policy   = data.aws_iam_policy_document.eks_node_assume_role.json
  permissions_boundary = var.permissions_boundary_arn

  tags = local.common_tags
}

locals {
  eks_node_managed_policies = toset([
    "arn:${data.aws_partition.current.partition}:iam::aws:policy/AmazonEKSWorkerNodePolicy",
    "arn:${data.aws_partition.current.partition}:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly",
  ])
}

resource "aws_iam_role_policy_attachment" "eks_node" {
  for_each = local.eks_node_managed_policies

  role       = aws_iam_role.eks_node.name
  policy_arn = each.value
}

resource "aws_security_group" "eks_control_plane" {
  name_prefix = "${local.cluster_name}-control-plane-"
  description = "EKS control-plane security group"
  vpc_id      = local.vpc_id

  egress {
    description = "Allow all outbound traffic"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(local.common_tags, {
    Name = "${local.cluster_name}-control-plane"
  })
}

resource "aws_eks_cluster" "migration" {
  name                          = local.cluster_name
  role_arn                      = aws_iam_role.eks_cluster.arn
  version                       = var.kubernetes_version
  bootstrap_self_managed_addons = false

  access_config {
    authentication_mode                         = "API"
    bootstrap_cluster_creator_admin_permissions = true
  }

  compute_config {
    enabled       = true
    node_pools    = ["system", "general-purpose"]
    node_role_arn = aws_iam_role.eks_node.arn
  }

  kubernetes_network_config {
    ip_family = "ipv4"

    elastic_load_balancing {
      enabled = true
    }
  }

  storage_config {
    block_storage {
      enabled = true
    }
  }

  vpc_config {
    endpoint_private_access = var.cluster_endpoint_private_access
    endpoint_public_access  = var.cluster_endpoint_public_access
    public_access_cidrs     = var.cluster_public_access_cidrs
    security_group_ids      = [aws_security_group.eks_control_plane.id]
    subnet_ids              = local.cluster_subnets
  }

  tags = local.common_tags

  lifecycle {
    precondition {
      condition     = var.create_vpc || (var.existing_vpc_id != null && length(var.existing_subnet_ids) >= 2)
      error_message = "existing_vpc_id and at least two existing_subnet_ids are required when create_vpc is false."
    }

    precondition {
      condition     = var.cluster_endpoint_public_access || var.cluster_endpoint_private_access
      error_message = "At least one EKS cluster endpoint must be enabled."
    }
  }

  depends_on = [
    aws_iam_role_policy_attachment.eks_cluster,
    aws_iam_role_policy_attachment.eks_node,
    aws_route.private_ipv4,
    aws_route.private_ipv6,
  ]
}

resource "aws_eks_access_entry" "account_readonly" {
  cluster_name  = aws_eks_cluster.migration.name
  principal_arn = "arn:${data.aws_partition.current.partition}:iam::${data.aws_caller_identity.current.account_id}:root"
  type          = "STANDARD"
}

resource "aws_eks_access_policy_association" "account_readonly" {
  cluster_name  = aws_eks_cluster.migration.name
  principal_arn = aws_eks_access_entry.account_readonly.principal_arn
  policy_arn    = "arn:${data.aws_partition.current.partition}:eks::aws:cluster-access-policy/AmazonEKSViewPolicy"

  access_scope {
    type = "cluster"
  }
}
