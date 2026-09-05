# Plan-time tests for the additive AWS Terraform deployment.
# Mock providers keep the tests local: no AWS credentials or cloud resources are required.

mock_provider "aws" {
  mock_data "aws_availability_zones" {
    defaults = {
      names = ["us-east-1a", "us-east-1b", "us-east-1c"]
    }
  }

  mock_data "aws_caller_identity" {
    defaults = {
      account_id = "123456789012"
      arn        = "arn:aws:iam::123456789012:user/terraform-test"
    }
  }

  mock_data "aws_partition" {
    defaults = {
      partition = "aws"
    }
  }

  mock_data "aws_vpc" {
    defaults = {
      cidr_block = "10.20.0.0/16"
    }
  }

  # aws_iam_policy_document is provider-computed. Supply syntactically valid
  # JSON so aws_iam_role validation still runs under the mocked provider.
  mock_data "aws_iam_policy_document" {
    defaults = {
      json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}"
    }
  }

  # ipv6_cidr_block is provider-computed; without a valid default the mock emits
  # a random string and the cidrsubnet() calls in network.tf fail to plan.
  mock_resource "aws_vpc" {
    defaults = {
      ipv6_cidr_block = "2600:1f18::/56"
    }
  }

  # The IAM role ARN feeds aws_eks_cluster.compute_config.node_role_arn, which is
  # ARN-validated at plan time; the random mock default fails that check.
  mock_resource "aws_iam_role" {
    defaults = {
      arn = "arn:aws:iam::123456789012:role/mock-migration-role"
    }
  }

  # dns_entry is computed (empty until apply); supply one so the privatelink
  # module's Route 53 record (dns_entry[0]) resolves under the mock.
  mock_resource "aws_vpc_endpoint" {
    defaults = {
      dns_entry = [{ dns_name = "vpce-mock.example.aws", hosted_zone_id = "Z0MOCK" }]
    }
  }
}

mock_provider "helm" {}

run "new_vpc_matches_cloudformation_topology" {
  command = plan

  assert {
    condition     = length(aws_vpc.migration) == 1
    error_message = "The default deployment must create one VPC."
  }

  assert {
    condition     = length(aws_subnet.private) == 2 && length(aws_subnet.public) == 2
    error_message = "The default deployment must create two private and two public subnets."
  }

  assert {
    condition     = length(aws_nat_gateway.migration) == 2
    error_message = "The default deployment must create one NAT gateway per availability zone."
  }

  assert {
    condition     = length(aws_vpc_endpoint.s3) == 1 && length(aws_vpc_endpoint.interface) == 4
    error_message = "A new VPC must include the S3, ECR API, ECR Docker, CloudWatch Logs, and EFS endpoints."
  }
}

run "eks_auto_mode_and_pod_identity_are_enabled" {
  command = plan

  assert {
    condition     = aws_eks_cluster.migration.compute_config[0].enabled == true
    error_message = "EKS Auto Mode compute must be enabled."
  }

  assert {
    condition = toset(aws_eks_cluster.migration.compute_config[0].node_pools) == toset([
      "system",
      "general-purpose",
    ])
    error_message = "Both built-in EKS Auto Mode node pools must be enabled."
  }

  assert {
    condition     = length(aws_eks_pod_identity_association.migration) == length(var.pod_identity_service_accounts)
    error_message = "Every configured Migration Assistant service account must receive a Pod Identity association."
  }
}

run "existing_vpc_is_additive_and_endpoints_are_opt_in" {
  command = plan

  variables {
    create_vpc          = false
    existing_vpc_id     = "vpc-0123456789abcdef0"
    existing_subnet_ids = ["subnet-0123456789abcdef0", "subnet-abcdef01234567890"]
    vpc_endpoints       = ["s3", "sts", "eks-auth"]
  }

  assert {
    condition     = length(aws_vpc.migration) == 0 && length(aws_subnet.private) == 0 && length(aws_nat_gateway.migration) == 0
    error_message = "Existing-VPC mode must not create or replace VPC, subnet, or NAT resources."
  }

  assert {
    condition     = length(aws_vpc_endpoint.s3) == 1 && toset(keys(aws_vpc_endpoint.interface)) == toset(["sts", "eks-auth"])
    error_message = "Existing-VPC mode must create only explicitly selected endpoints."
  }
}

run "helm_requires_an_image_version" {
  command = plan

  variables {
    deploy_helm = true
  }

  expect_failures = [
    helm_release.migration_assistant[0],
  ]
}

run "default_new_vpc_is_not_isolated" {
  command = plan

  # No-regression guard: with isolated unset (default false), the new VPC keeps its
  # NAT gateways and the base 5-endpoint set, identical to prior behavior.
  assert {
    condition     = length(aws_nat_gateway.migration) == 2
    error_message = "Non-isolated new VPC must keep its NAT gateways."
  }

  assert {
    condition     = length(aws_route.private_ipv4) == 2 && length(aws_route.private_ipv6) == 2
    error_message = "Non-isolated new VPC must keep private egress routes."
  }

  assert {
    condition = length(aws_vpc_endpoint.s3) == 1 && toset(keys(aws_vpc_endpoint.interface)) == toset([
      "ecr.api", "ecr.dkr", "logs", "elasticfilesystem",
    ])
    error_message = "Non-isolated new VPC must create only the base endpoint set."
  }
}

run "isolated_new_vpc_has_no_nat_and_full_endpoints" {
  command = plan

  variables {
    isolated = true
  }

  # Isolated: no NAT gateway, no private egress routes.
  assert {
    condition     = length(aws_nat_gateway.migration) == 0 && length(aws_eip.nat) == 0
    error_message = "Isolated VPC must not create NAT gateways or their EIPs."
  }

  assert {
    condition     = length(aws_route.private_ipv4) == 0 && length(aws_route.private_ipv6) == 0
    error_message = "Isolated VPC private subnets must have no internet egress route."
  }

  # Isolated: the full 8-endpoint set (S3 gateway + 7 interface endpoints) so the
  # cluster reaches AWS APIs privately, matching the working isolated-VPC path.
  assert {
    condition = length(aws_vpc_endpoint.s3) == 1 && toset(keys(aws_vpc_endpoint.interface)) == toset([
      "ecr.api", "ecr.dkr", "logs", "monitoring", "elasticfilesystem", "sts", "eks-auth",
    ])
    error_message = "Isolated VPC must create the full service-endpoint set (s3 + 7 interface endpoints)."
  }
}

run "connectivity_defaults_to_none_no_resources" {
  command = plan

  # No-regression guard: both legs default to none, so no connectivity module
  # resources are planned.
  assert {
    condition     = length(module.source_connectivity_privatelink) == 0 && length(module.target_connectivity_privatelink) == 0
    error_message = "No privatelink endpoints must be planned when connectivity mode is none."
  }

  assert {
    condition     = length(module.source_connectivity_peering) == 0 && length(module.target_connectivity_peering) == 0
    error_message = "No VPC peering must be planned when connectivity mode is none."
  }

  assert {
    condition     = var.source_connectivity.mode == "none" && var.target_connectivity.mode == "none"
    error_message = "Both connectivity legs must default to none."
  }
}

run "target_privatelink_plans_interface_endpoint" {
  command = plan

  variables {
    target_connectivity = {
      mode                      = "privatelink"
      vpc_endpoint_service_name = "com.amazonaws.vpce.us-east-1.vpce-svc-0123456789abcdef0"
      dns_name                  = "target.example.com"
    }
  }

  # One privatelink module on the target leg; source leg stays empty.
  assert {
    condition     = length(module.target_connectivity_privatelink) == 1 && length(module.source_connectivity_privatelink) == 0
    error_message = "Target privatelink must plan exactly one endpoint on the target leg only."
  }

  # Interface endpoint with private DNS disabled (custom FQDN handled by Route 53).
  assert {
    condition     = module.target_connectivity_privatelink[0].endpoint_fqdn == "target.example.com"
    error_message = "Target privatelink must expose the configured FQDN."
  }
}

run "source_vpc_peering_plans_connection_and_routes" {
  command = plan

  variables {
    source_connectivity = {
      mode        = "vpc_peering"
      peer_vpc_id = "vpc-0aaaaaaaaaaaaaaaa"
      peer_cidr   = "10.99.0.0/16"
    }
  }

  # One peering module on the source leg; target leg stays empty.
  assert {
    condition     = length(module.source_connectivity_peering) == 1 && length(module.target_connectivity_peering) == 0
    error_message = "Source vpc_peering must plan exactly one peering on the source leg only."
  }
}

run "privatelink_requires_service_name" {
  command = plan

  variables {
    target_connectivity = {
      mode = "privatelink"
    }
  }

  expect_failures = [
    var.target_connectivity,
  ]
}

run "snapshot_role_created_by_default" {
  command = plan

  # No-regression guard: the snapshot role and its policy exist by default, and the
  # helm release wires the role ARN into defaultBucketConfiguration.snapshotRoleArn.
  assert {
    condition     = length(aws_iam_role.snapshot) == 1 && length(aws_iam_role_policy.snapshot) == 1
    error_message = "The snapshot role and policy must be created by default."
  }

  assert {
    condition     = var.create_opensearch_service_snapshot_role == true
    error_message = "create_opensearch_service_snapshot_role must default to true."
  }
}

run "snapshot_role_omitted_when_disabled" {
  command = plan

  variables {
    create_opensearch_service_snapshot_role = false
  }

  assert {
    condition     = length(aws_iam_role.snapshot) == 0 && length(aws_iam_role_policy.snapshot) == 0
    error_message = "The snapshot role and policy must not be created when disabled."
  }
}
