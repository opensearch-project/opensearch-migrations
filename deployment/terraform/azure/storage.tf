resource "azurerm_storage_account" "migration" {
  count = var.create_storage_account ? 1 : 0

  name                     = local.storage_account_name
  resource_group_name      = azurerm_resource_group.migration.name
  location                 = azurerm_resource_group.migration.location
  account_tier             = "Standard"
  account_replication_type = "LRS"
  min_tls_version          = "TLS1_2"

  allow_nested_items_to_be_public = false

  tags = local.common_tags
}

resource "azurerm_storage_container" "migration" {
  count = var.create_storage_account ? 1 : 0

  name                  = local.container_name
  storage_account_id    = azurerm_storage_account.migration[0].id
  container_access_type = "private"
}

