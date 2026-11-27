resource "azurerm_resource_group" "migration" {
  name     = local.resource_group_name
  location = var.location
  tags     = local.common_tags
}

resource "azurerm_virtual_network" "migration" {
  name                = "${local.name}-vnet"
  location            = azurerm_resource_group.migration.location
  resource_group_name = azurerm_resource_group.migration.name
  address_space       = [var.vnet_cidr]
  tags                = local.common_tags
}

resource "azurerm_subnet" "aks" {
  name                 = "${local.name}-aks"
  resource_group_name  = azurerm_resource_group.migration.name
  virtual_network_name = azurerm_virtual_network.migration.name
  address_prefixes     = [var.aks_subnet_cidr]
}
