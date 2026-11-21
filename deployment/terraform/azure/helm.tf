locals {
  chart_dir = "${path.module}/../../k8s/charts/aggregates/migrationAssistantWithArgo"

  monitor_helm_values = var.enable_azure_monitor_logs ? [
    {
      name  = "azure.monitor.tenantId"
      value = data.azurerm_client_config.current.tenant_id
    },
    {
      name  = "azure.monitor.clientId"
      value = azuread_application_registration.monitor[0].client_id
    },
    {
      name  = "azure.monitor.dceUrl"
      value = azurerm_monitor_data_collection_rule.migration[0].logs_ingestion_endpoint
    },
    {
      name  = "azure.monitor.dcrImmutableId"
      value = azurerm_monitor_data_collection_rule.migration[0].immutable_id
    },
    {
      name  = "azure.monitor.tableName"
      value = local.monitor_table_name
    },
  ] : []
}

resource "helm_release" "migration_assistant" {
  count = var.deploy_helm ? 1 : 0

  name             = var.namespace
  namespace        = var.namespace
  create_namespace = !var.enable_azure_monitor_logs
  chart            = local.chart_dir
  timeout          = var.helm_timeout_seconds
  wait             = true
  cleanup_on_fail  = true

  values = concat(
    [file("${local.chart_dir}/valuesAks.yaml")],
    var.enable_azure_monitor_logs ? [file("${local.chart_dir}/valuesAksMonitor.yaml")] : [],
  )

  set = concat([
    {
      name  = "azure.workloadIdentityClientId"
      value = azurerm_user_assigned_identity.migration.client_id
    },
    {
      name  = "azureStorageConfiguration.accountName"
      value = local.storage_account_name
    },
    {
      name  = "azureStorageConfiguration.containerName"
      value = local.container_name
    },
    {
      # Terraform owns the container when it creates the account.
      name  = "azureStorageConfiguration.create"
      value = tostring(!var.create_storage_account)
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
  ], local.monitor_helm_values)

  lifecycle {
    precondition {
      condition     = var.migration_assistant_version != null && try(trimspace(var.migration_assistant_version), "") != ""
      error_message = "migration_assistant_version is required when deploy_helm is true."
    }
  }

  # The release's workloads and its Helm lifecycle hooks use the cluster, the workload identity
  # and its federated credentials, blob access, and (with Azure Monitor) the log credentials.
  # Terraform destroys dependencies only after their dependents, so listing them here keeps them
  # in place for the duration of `helm uninstall`; without these edges a destroy can remove them
  # while the uninstall hooks are still running.
  depends_on = [
    azurerm_kubernetes_cluster.migration,
    azurerm_role_assignment.aks_subnet_network_contributor,
    azurerm_federated_identity_credential.migration,
    azurerm_role_assignment.migration_blob_data,
    azurerm_storage_container.migration,
    azurerm_role_assignment.monitor_publisher,
    kubernetes_secret_v1.monitor,
  ]
}
