output "resource_group_name" {
  description = "Resource group holding the Migration Assistant resources."
  value       = azurerm_resource_group.migration.name
}

output "cluster_name" {
  description = "AKS cluster name."
  value       = azurerm_kubernetes_cluster.migration.name
}

output "oidc_issuer_url" {
  description = "AKS OIDC issuer used by the workload identity's federated credentials."
  value       = azurerm_kubernetes_cluster.migration.oidc_issuer_url
}

output "kubeconfig_command" {
  description = "Ready-to-run Azure CLI command for kubectl access."
  value       = "az aks get-credentials --resource-group ${azurerm_resource_group.migration.name} --name ${azurerm_kubernetes_cluster.migration.name}"
}

output "workload_identity_client_id" {
  description = "Client ID of the managed identity federated to the chart's service accounts; the chart's azure.workloadIdentityClientId."
  value       = azurerm_user_assigned_identity.migration.client_id
}

output "storage_account_name" {
  description = "Storage account for snapshots and workflow artifacts; the chart's azureStorageConfiguration.accountName."
  value       = local.storage_account_name
}

output "storage_container_name" {
  description = "Blob container for snapshots and workflow artifacts."
  value       = local.container_name
}

output "snapshot_repo_uri" {
  description = "repoPathUri prefix for snapshot repositories in the workflow configuration."
  value       = local.storage_account_name == null ? null : "azure://${local.storage_account_name}/${local.container_name}"
}

output "logs_ingestion_endpoint" {
  description = "Logs ingestion endpoint of the data collection rule, or null when enable_azure_monitor_logs is false."
  value       = var.enable_azure_monitor_logs ? azurerm_monitor_data_collection_rule.migration[0].logs_ingestion_endpoint : null
}

output "log_analytics_workspace_id" {
  description = "Log Analytics workspace receiving workload logs, or null when enable_azure_monitor_logs is false."
  value       = var.enable_azure_monitor_logs ? azurerm_log_analytics_workspace.migration[0].id : null
}

output "helm_release_status" {
  description = "Migration Assistant Helm release status, or null when deploy_helm is false."
  value       = var.deploy_helm ? helm_release.migration_assistant[0].status : null
}
