resource "helm_release" "migration_assistant" {
  count = var.deploy_helm ? 1 : 0

  name             = var.namespace
  namespace        = var.namespace
  create_namespace = true
  chart            = "${path.module}/../../k8s/charts/aggregates/migrationAssistantWithArgo"
  timeout          = var.helm_timeout_seconds
  wait             = true
  cleanup_on_fail  = true

  values = [
    file("${path.module}/../../k8s/charts/aggregates/migrationAssistantWithArgo/valuesEks.yaml"),
  ]

  set = [
    {
      name  = "stageName"
      value = var.stage
    },
    {
      name  = "aws.region"
      value = var.region
    },
    {
      name  = "aws.account"
      value = data.aws_caller_identity.current.account_id
    },
    {
      name  = "defaultBucketConfiguration.snapshotRoleArn"
      value = var.create_opensearch_service_snapshot_role ? aws_iam_role.snapshot[0].arn : ""
    },
    {
      name  = "cluster.useCustomKarpenterNodePool"
      value = tostring(var.use_custom_karpenter_node_pool)
    },
    {
      name  = "images.captureProxy.repository"
      value = "public.ecr.aws/opensearchproject/opensearch-migrations-traffic-capture-proxy"
    },
    {
      name  = "images.captureProxy.tag"
      value = var.migration_assistant_version
    },
    {
      name  = "images.trafficReplayer.repository"
      value = "public.ecr.aws/opensearchproject/opensearch-migrations-traffic-replayer"
    },
    {
      name  = "images.trafficReplayer.tag"
      value = var.migration_assistant_version
    },
    {
      name  = "images.reindexFromSnapshot.repository"
      value = "public.ecr.aws/opensearchproject/opensearch-migrations-reindex-from-snapshot"
    },
    {
      name  = "images.reindexFromSnapshot.tag"
      value = var.migration_assistant_version
    },
    {
      name  = "images.migrationConsole.repository"
      value = "public.ecr.aws/opensearchproject/opensearch-migrations-console"
    },
    {
      name  = "images.migrationConsole.tag"
      value = var.migration_assistant_version
    },
    {
      name  = "images.installer.repository"
      value = "public.ecr.aws/opensearchproject/opensearch-migrations-console"
    },
    {
      name  = "images.installer.tag"
      value = var.migration_assistant_version
    },
  ]

  lifecycle {
    precondition {
      condition     = var.migration_assistant_version != null && try(trimspace(var.migration_assistant_version), "") != ""
      error_message = "migration_assistant_version is required when deploy_helm is true."
    }
  }

  depends_on = [
    aws_eks_access_policy_association.account_readonly,
    aws_eks_pod_identity_association.migration,
  ]
}
