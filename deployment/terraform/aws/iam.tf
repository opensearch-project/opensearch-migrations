resource "aws_ecr_repository" "migration" {
  name         = local.ecr_name
  force_delete = true

  image_scanning_configuration {
    scan_on_push = true
  }

  tags = local.common_tags
}

data "aws_iam_policy_document" "pod_identity_assume_role" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole", "sts:TagSession"]

    principals {
      type        = "Service"
      identifiers = ["pods.eks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "migration_pods" {
  name                 = "ma-${var.stage}-${var.region}-migrations"
  description          = "Migration Assistant role assumed by workloads through EKS Pod Identity"
  assume_role_policy   = data.aws_iam_policy_document.pod_identity_assume_role.json
  permissions_boundary = var.permissions_boundary_arn

  tags = local.common_tags
}

data "aws_iam_policy_document" "migration_pods" {
  # ecr:GetAuthorizationToken is an account-level action that does not support
  # resource scoping; it must be granted on "*".
  statement {
    sid       = "EcrAuth"
    effect    = "Allow"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  # Pull and push are scoped to this module's ECR repository (mirrored/built MA
  # images). Replaces the previous AmazonEC2ContainerRegistryFullAccess managed
  # policy, which granted these on every repo in the account.
  statement {
    sid    = "EcrImages"
    effect = "Allow"
    actions = [
      "ecr:BatchGetImage",
      "ecr:GetDownloadUrlForLayer",
      "ecr:DescribeRepositories",
      "ecr:BatchCheckLayerAvailability",
      "ecr:CompleteLayerUpload",
      "ecr:InitiateLayerUpload",
      "ecr:PutImage",
      "ecr:UploadLayerPart",
    ]
    resources = [aws_ecr_repository.migration.arn]
  }

  # EFS filesystems are created at runtime (by the app / EKS), so their ARNs are
  # not known here; left on "*".
  statement {
    sid       = "ElasticFileSystem"
    effect    = "Allow"
    actions   = ["elasticfilesystem:ClientMount", "elasticfilesystem:ClientWrite"]
    resources = ["*"]
  }

  # The source/target clusters are supplied as runtime migration config; their ARNs
  # are not known to Terraform, so es:/aoss: access is granted on "*".
  statement {
    sid       = "OpenSearch"
    effect    = "Allow"
    actions   = ["es:ESHttp*", "aoss:APIAccessAll"]
    resources = ["*"]
  }

  # secretsmanager:ListSecrets is account-level and cannot be scoped. Get/Describe
  # target user-supplied secrets whose names are not known here, so also on "*".
  statement {
    sid    = "Secrets"
    effect = "Allow"
    actions = [
      "secretsmanager:GetSecretValue",
      "secretsmanager:DescribeSecret",
      "secretsmanager:ListSecrets",
    ]
    resources = ["*"]
  }

  # s3:ListAllMyBuckets is an account-level action that cannot be resource-scoped;
  # it only functions on "*".
  statement {
    sid       = "S3ListAllBuckets"
    effect    = "Allow"
    actions   = ["s3:ListAllMyBuckets"]
    resources = ["*"]
  }

  # Object and bucket operations remain on "*" rather than scoped to migrations-*.
  # The migration console reads/writes user-configured buckets whose names are not
  # under our control, notably the failed-document-stream bucket, which is supplied
  # per-migration and may use any name. The bucket the module itself relies on is
  # created/deleted by the Helm chart's defaultBucketConfiguration (app-managed,
  # named migrations-default-<account>-<stage>-<region>), not by Terraform. This
  # matches the CloudFormation deployment. A future enhancement can accept explicit
  # bucket ARNs (e.g. from the installer) to scope this down.
  statement {
    sid    = "Snapshots"
    effect = "Allow"
    actions = [
      "s3:GetObject",
      "s3:PutObject",
      "s3:ListBucket",
      "s3:DeleteObject",
      "s3:DeleteObjectVersion",
      "s3:ListBucketVersions",
      "s3:ListBucketMultipartUploads",
      "s3:AbortMultipartUpload",
      "s3:CreateBucket",
      "s3:DeleteBucket",
    ]
    resources = ["*"]
  }

  # logs:DescribeLogGroups only supports "*" (it enumerates across groups); the
  # rest are scoped to the Migration Assistant log group for this stage/region.
  statement {
    sid       = "CloudWatchLogsDescribe"
    effect    = "Allow"
    actions   = ["logs:DescribeLogGroups"]
    resources = ["*"]
  }

  statement {
    sid    = "CloudWatchLogs"
    effect = "Allow"
    actions = [
      "logs:PutLogEvents",
      "logs:DescribeLogStreams",
      "logs:CreateLogGroup",
      "logs:CreateLogStream",
    ]
    resources = [
      "arn:${data.aws_partition.current.partition}:logs:${var.region}:${data.aws_caller_identity.current.account_id}:log-group:/migration-assistant-${var.stage}-${var.region}*",
    ]
  }

  # cloudwatch:ListMetrics/GetMetricData do not support resource-level scoping.
  statement {
    sid       = "CloudWatchMetrics"
    effect    = "Allow"
    actions   = ["cloudwatch:ListMetrics", "cloudwatch:GetMetricData"]
    resources = ["*"]
  }

  # X-Ray Put* actions do not support resource-level scoping.
  statement {
    sid       = "XRayTraces"
    effect    = "Allow"
    actions   = ["xray:PutTraceSegments", "xray:PutTelemetryRecords"]
    resources = ["*"]
  }

  statement {
    sid       = "PassSnapshotRoles"
    effect    = "Allow"
    actions   = ["iam:PassRole"]
    resources = ["arn:${data.aws_partition.current.partition}:iam::${data.aws_caller_identity.current.account_id}:role/*"]
  }

  statement {
    sid    = "MigrationDashboards"
    effect = "Allow"
    actions = [
      "cloudwatch:PutDashboard",
      "cloudwatch:GetDashboard",
      "cloudwatch:DeleteDashboards",
    ]
    resources = ["arn:${data.aws_partition.current.partition}:cloudwatch::${data.aws_caller_identity.current.account_id}:dashboard/MA-*"]
  }

  # The private CA is created at runtime (by the app) for the capture-proxy TLS
  # path; its ARN is not known here, so acm-pca actions are granted on "*".
  statement {
    sid    = "PrivateCertificateAuthority"
    effect = "Allow"
    actions = [
      "acm-pca:IssueCertificate",
      "acm-pca:GetCertificate",
      "acm-pca:DescribeCertificateAuthority",
      "acm-pca:ListCertificateAuthorities",
      "acm-pca:CreateCertificateAuthority",
      "acm-pca:DeleteCertificateAuthority",
      "acm-pca:UpdateCertificateAuthority",
      "acm-pca:TagCertificateAuthority",
    ]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "migration_pods" {
  name   = "MigrationsPodPolicy"
  role   = aws_iam_role.migration_pods.id
  policy = data.aws_iam_policy_document.migration_pods.json
}

# The snapshot role/policy became count-gated (create_opensearch_service_snapshot_role).
# These moved blocks map the pre-count singleton addresses to index 0 so existing
# stacks (where the role was created unconditionally) do not destroy/recreate it.
moved {
  from = aws_iam_role.snapshot
  to   = aws_iam_role.snapshot[0]
}

moved {
  from = aws_iam_role_policy.snapshot
  to   = aws_iam_role_policy.snapshot[0]
}

data "aws_iam_policy_document" "snapshot_assume_role" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["es.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "snapshot" {
  count = var.create_opensearch_service_snapshot_role ? 1 : 0

  name                 = "ma-${var.stage}-${var.region}-snapshot"
  description          = "Allows Amazon OpenSearch Service to read and write Migration Assistant snapshots"
  assume_role_policy   = data.aws_iam_policy_document.snapshot_assume_role.json
  permissions_boundary = var.permissions_boundary_arn

  tags = local.common_tags
}

data "aws_iam_policy_document" "snapshot" {
  statement {
    sid       = "ListMigrationBuckets"
    effect    = "Allow"
    actions   = ["s3:ListBucket"]
    resources = ["arn:${data.aws_partition.current.partition}:s3:::migrations-*"]
  }

  statement {
    sid       = "ManageMigrationSnapshotObjects"
    effect    = "Allow"
    actions   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
    resources = ["arn:${data.aws_partition.current.partition}:s3:::migrations-*/*"]
  }
}

resource "aws_iam_role_policy" "snapshot" {
  count = var.create_opensearch_service_snapshot_role ? 1 : 0

  name   = "MigrationSnapshotPolicy"
  role   = aws_iam_role.snapshot[0].id
  policy = data.aws_iam_policy_document.snapshot.json
}

resource "aws_eks_pod_identity_association" "migration" {
  for_each = var.pod_identity_service_accounts

  cluster_name    = aws_eks_cluster.migration.name
  namespace       = var.namespace
  service_account = each.value
  role_arn        = aws_iam_role.migration_pods.arn

  depends_on = [
    aws_iam_role_policy.migration_pods,
  ]
}
