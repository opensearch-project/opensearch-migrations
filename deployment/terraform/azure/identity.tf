resource "azurerm_user_assigned_identity" "migration" {
  name                = "${local.name}-migration"
  location            = azurerm_resource_group.migration.location
  resource_group_name = azurerm_resource_group.migration.name
  tags                = local.common_tags
}

resource "azurerm_federated_identity_credential" "migration" {
  for_each = var.workload_identity_service_accounts

  name                      = each.value
  user_assigned_identity_id = azurerm_user_assigned_identity.migration.id
  issuer                    = azurerm_kubernetes_cluster.migration.oidc_issuer_url
  subject                   = "system:serviceaccount:${var.namespace}:${each.value}"
  audience                  = ["api://AzureADTokenExchange"]
}

# The console, workflow pods, Argo artifacts and the container jobs all read and write blobs
# with the workload identity.
resource "azurerm_role_assignment" "migration_blob_data" {
  scope                = local.storage_account_id
  role_definition_name = "Storage Blob Data Contributor"
  principal_id         = azurerm_user_assigned_identity.migration.principal_id
}
