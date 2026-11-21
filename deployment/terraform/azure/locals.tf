data "azurerm_client_config" "current" {}

data "azurerm_storage_account" "existing" {
  count               = var.create_storage_account ? 0 : 1
  name                = var.existing_storage_account_name
  resource_group_name = var.existing_storage_account_resource_group_name

  lifecycle {
    precondition {
      condition     = var.existing_storage_account_name != null && var.existing_storage_account_resource_group_name != null
      error_message = "create_storage_account = false requires existing_storage_account_name and existing_storage_account_resource_group_name."
    }
  }
}

# Storage account names are global, 3-24 lowercase letters and digits. The Log Analytics
# workspace uses the suffix too: destroy only soft-deletes a workspace, and a new one with the
# same name recovers the old one, which then silently drops data from a new data collection rule.
resource "random_string" "suffix" {
  length  = 6
  upper   = false
  special = false
}

locals {
  name                = "ma-${var.stage}-${var.location}"
  cluster_name        = "migration-aks-cluster-${var.stage}-${var.location}"
  resource_group_name = coalesce(var.resource_group_name, local.name)

  storage_account_name = var.create_storage_account ? substr("ma${replace(var.stage, "-", "")}${random_string.suffix.result}", 0, 24) : var.existing_storage_account_name
  storage_account_id   = var.create_storage_account ? azurerm_storage_account.migration[0].id : data.azurerm_storage_account.existing[0].id
  container_name       = var.storage_container_name

  # Azure tag names cannot contain / or other reserved characters, so these match the AWS
  # module's default_tags rather than its opensearch.org/migration-assistant tag.
  common_tags = merge(var.tags, {
    Project = "OpenSearch Migration Assistant"
    Stage   = var.stage
  })
}
