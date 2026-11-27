locals {
  monitor_table_name  = "MigrationAssistant_CL"
  monitor_stream_name = "Custom-${local.monitor_table_name}"

  # The fields the chart's fluent-bit pipeline puts on every record (valuesAksMonitor.yaml).
  monitor_string_columns = ["log", "level", "namespace", "pod", "container", "workflow"]
}

resource "azurerm_log_analytics_workspace" "migration" {
  count = var.enable_azure_monitor_logs ? 1 : 0

  name                = "${local.name}-logs-${random_string.suffix.result}"
  location            = azurerm_resource_group.migration.location
  resource_group_name = azurerm_resource_group.migration.name
  sku                 = "PerGB2018"
  retention_in_days   = var.log_analytics_retention_in_days
  tags                = local.common_tags
}

resource "azurerm_log_analytics_workspace_table_custom_log" "migration" {
  count = var.enable_azure_monitor_logs ? 1 : 0

  name         = local.monitor_table_name
  workspace_id = azurerm_log_analytics_workspace.migration[0].id

  column {
    name = "TimeGenerated"
    type = "dateTime"
  }

  dynamic "column" {
    for_each = local.monitor_string_columns
    content {
      name = column.value
      type = "string"
    }
  }
}

# A Direct rule has its own logs ingestion endpoint, so no data collection endpoint is needed
# unless the workspace is reached over private link.
resource "azurerm_monitor_data_collection_rule" "migration" {
  count = var.enable_azure_monitor_logs ? 1 : 0

  name                = "${local.name}-logs"
  location            = azurerm_resource_group.migration.location
  resource_group_name = azurerm_resource_group.migration.name
  kind                = "Direct"

  destinations {
    log_analytics {
      workspace_resource_id = azurerm_log_analytics_workspace.migration[0].id
      name                  = "workspace"
    }
  }

  stream_declaration {
    stream_name = local.monitor_stream_name

    column {
      name = "TimeGenerated"
      type = "datetime"
    }

    dynamic "column" {
      for_each = local.monitor_string_columns
      content {
        name = column.value
        type = "string"
      }
    }
  }

  data_flow {
    streams       = [local.monitor_stream_name]
    destinations  = ["workspace"]
    output_stream = local.monitor_stream_name
    transform_kql = "source"
  }

  tags = local.common_tags

  depends_on = [azurerm_log_analytics_workspace_table_custom_log.migration]
}

# fluent-bit's azure_logs_ingestion output authenticates only with a client secret; it has no
# managed identity or Workload ID option.
resource "azuread_application_registration" "monitor" {
  count = var.enable_azure_monitor_logs ? 1 : 0

  display_name = "${local.name}-logs"
}

resource "azuread_service_principal" "monitor" {
  count = var.enable_azure_monitor_logs ? 1 : 0

  client_id = azuread_application_registration.monitor[0].client_id
}

resource "azuread_application_password" "monitor" {
  count = var.enable_azure_monitor_logs ? 1 : 0

  application_id = azuread_application_registration.monitor[0].id
  display_name   = "fluent-bit"
}

resource "azurerm_role_assignment" "monitor_publisher" {
  count = var.enable_azure_monitor_logs ? 1 : 0

  scope                = azurerm_monitor_data_collection_rule.migration[0].id
  role_definition_name = "Monitoring Metrics Publisher"
  principal_id         = azuread_service_principal.monitor[0].object_id
}

# The chart does not create this Secret, and fluent-bit pods cannot start without it, so it
# must exist before the release.
resource "kubernetes_namespace_v1" "migration" {
  count = var.enable_azure_monitor_logs ? 1 : 0

  metadata {
    name = var.namespace
  }
}

resource "kubernetes_secret_v1" "monitor" {
  count = var.enable_azure_monitor_logs ? 1 : 0

  metadata {
    name      = "azure-monitor-logs-credentials"
    namespace = kubernetes_namespace_v1.migration[0].metadata[0].name
  }

  data = {
    clientSecret = azuread_application_password.monitor[0].value
  }
}
