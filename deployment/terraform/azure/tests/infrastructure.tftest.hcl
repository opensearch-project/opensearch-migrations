# Plan-time tests for the Azure deployment.
# Mock providers keep the tests local: no Azure credentials or cloud resources are required.

mock_provider "azurerm" {
  mock_data "azurerm_client_config" {
    defaults = {
      tenant_id       = "11111111-1111-1111-1111-111111111111"
      subscription_id = "22222222-2222-2222-2222-222222222222"
    }
  }

  mock_data "azurerm_storage_account" {
    defaults = {
      id = "/subscriptions/22222222-2222-2222-2222-222222222222/resourceGroups/existing-rg/providers/Microsoft.Storage/storageAccounts/existingaccount"
    }
  }

  # azurerm parses resource IDs at plan time, so the random mock defaults fail; supply valid ones.
  mock_resource "azurerm_resource_group" {
    defaults = {
      id = "/subscriptions/22222222-2222-2222-2222-222222222222/resourceGroups/ma-dev-eastus"
    }
  }

  mock_resource "azurerm_virtual_network" {
    defaults = {
      id = "/subscriptions/22222222-2222-2222-2222-222222222222/resourceGroups/ma-dev-eastus/providers/Microsoft.Network/virtualNetworks/ma-dev-eastus-vnet"
    }
  }

  mock_resource "azurerm_subnet" {
    defaults = {
      id = "/subscriptions/22222222-2222-2222-2222-222222222222/resourceGroups/ma-dev-eastus/providers/Microsoft.Network/virtualNetworks/ma-dev-eastus-vnet/subnets/ma-dev-eastus-aks"
    }
  }

  mock_resource "azurerm_storage_account" {
    defaults = {
      id = "/subscriptions/22222222-2222-2222-2222-222222222222/resourceGroups/ma-dev-eastus/providers/Microsoft.Storage/storageAccounts/madevabcdef"
    }
  }

  mock_resource "azurerm_user_assigned_identity" {
    defaults = {
      id           = "/subscriptions/22222222-2222-2222-2222-222222222222/resourceGroups/ma-dev-eastus/providers/Microsoft.ManagedIdentity/userAssignedIdentities/ma-dev-eastus-migration"
      client_id    = "33333333-3333-3333-3333-333333333333"
      principal_id = "44444444-4444-4444-4444-444444444444"
    }
  }

  mock_resource "azurerm_kubernetes_cluster" {
    defaults = {
      id              = "/subscriptions/22222222-2222-2222-2222-222222222222/resourceGroups/ma-dev-eastus/providers/Microsoft.ContainerService/managedClusters/migration-aks-cluster-dev-eastus"
      oidc_issuer_url = "https://eastus.oic.prod-aks.azure.com/11111111-1111-1111-1111-111111111111/55555555-5555-5555-5555-555555555555/"
      identity        = { principal_id = "66666666-6666-6666-6666-666666666666" }
    }
  }

  mock_resource "azurerm_log_analytics_workspace" {
    defaults = {
      id = "/subscriptions/22222222-2222-2222-2222-222222222222/resourceGroups/ma-dev-eastus/providers/Microsoft.OperationalInsights/workspaces/ma-dev-eastus-logs"
    }
  }

  mock_resource "azurerm_monitor_data_collection_rule" {
    defaults = {
      id                      = "/subscriptions/22222222-2222-2222-2222-222222222222/resourceGroups/ma-dev-eastus/providers/Microsoft.Insights/dataCollectionRules/ma-dev-eastus-logs"
      logs_ingestion_endpoint = "https://ma-dev-eastus-logs-abcd-eastus.logs.z1.ingest.monitor.azure.com"
      immutable_id            = "dcr-00000000000000000000000000000000"
    }
  }
}

mock_provider "azuread" {
  mock_resource "azuread_application_registration" {
    defaults = {
      id        = "/applications/88888888-8888-8888-8888-888888888888"
      client_id = "99999999-9999-9999-9999-999999999999"
    }
  }

  mock_resource "azuread_service_principal" {
    defaults = {
      object_id = "77777777-7777-7777-7777-777777777777"
    }
  }
}
mock_provider "helm" {}
mock_provider "kubernetes" {}
mock_provider "random" {}

variables {
  location = "eastus"
  stage    = "dev"
}

run "cluster_enables_workload_identity" {
  command = plan

  assert {
    condition     = azurerm_kubernetes_cluster.migration.oidc_issuer_enabled && azurerm_kubernetes_cluster.migration.workload_identity_enabled
    error_message = "The AKS cluster must enable the OIDC issuer and Workload ID."
  }

  assert {
    condition     = azurerm_kubernetes_cluster.migration.network_profile[0].network_plugin == "azure" && azurerm_kubernetes_cluster.migration.network_profile[0].network_plugin_mode == "overlay"
    error_message = "The AKS cluster must use Azure CNI overlay."
  }

  assert {
    condition     = azurerm_kubernetes_cluster.migration.default_node_pool[0].vm_size == "Standard_D4s_v5"
    error_message = "The node pool must default to Standard_D4s_v5."
  }
}

run "federated_credentials_cover_the_chart_service_accounts" {
  command = plan

  assert {
    condition = toset([for c in azurerm_federated_identity_credential.migration : c.subject]) == toset([
      "system:serviceaccount:ma:migrations-service-account",
      "system:serviceaccount:ma:argo-workflow-executor",
      "system:serviceaccount:ma:argo-test-workflow-executor",
      "system:serviceaccount:ma:migration-console-access-role",
      "system:serviceaccount:ma:argo-server",
    ])
    error_message = "Federated credentials must cover every service account the chart annotates for Workload ID."
  }

  assert {
    condition     = alltrue([for c in azurerm_federated_identity_credential.migration : length(c.audience) == 1 && contains(c.audience, "api://AzureADTokenExchange")])
    error_message = "Federated credentials must use the Workload ID token audience."
  }
}

run "storage_account_and_container_created_by_default" {
  command = plan

  assert {
    condition     = length(azurerm_storage_account.migration) == 1 && length(azurerm_storage_container.migration) == 1
    error_message = "The storage account and container must be created by default."
  }

  assert {
    condition     = azurerm_role_assignment.migration_blob_data.role_definition_name == "Storage Blob Data Contributor"
    error_message = "The workload identity must have Storage Blob Data Contributor on the storage account."
  }
}

run "existing_storage_account_is_used_without_creating_one" {
  command = plan

  variables {
    create_storage_account                       = false
    existing_storage_account_name                = "existingaccount"
    existing_storage_account_resource_group_name = "existing-rg"
    deploy_helm                                  = true
    migration_assistant_version                  = "3.4.2"
  }

  assert {
    condition     = length(azurerm_storage_account.migration) == 0 && length(azurerm_storage_container.migration) == 0
    error_message = "An existing storage account must not be created or given a Terraform-managed container."
  }

  assert {
    condition     = anytrue([for s in helm_release.migration_assistant[0].set : s.name == "azureStorageConfiguration.create" && s.value == "true"])
    error_message = "With an existing storage account, the chart must create the container if it is missing."
  }
}

run "existing_storage_account_requires_its_name" {
  command = plan

  variables {
    create_storage_account = false
  }

  expect_failures = [data.azurerm_storage_account.existing]
}

run "helm_requires_an_image_version" {
  command = plan

  variables {
    deploy_helm = true
  }

  expect_failures = [helm_release.migration_assistant[0]]
}

run "helm_passes_identity_and_storage_to_the_chart" {
  command = plan

  variables {
    deploy_helm                 = true
    migration_assistant_version = "3.4.2"
  }

  assert {
    condition     = length(helm_release.migration_assistant[0].values) == 1 && helm_release.migration_assistant[0].create_namespace
    error_message = "Without Azure Monitor, the release must use only valuesAks.yaml and create its namespace."
  }

  assert {
    condition = alltrue([
      for name in ["azure.workloadIdentityClientId", "azureStorageConfiguration.accountName", "azureStorageConfiguration.containerName"] :
      anytrue([for s in helm_release.migration_assistant[0].set : s.name == name])
    ])
    error_message = "The release must receive the workload identity client ID and the storage account and container."
  }

  assert {
    condition     = anytrue([for s in helm_release.migration_assistant[0].set : s.name == "azureStorageConfiguration.create" && s.value == "false"])
    error_message = "The chart must not create the container Terraform owns."
  }

  assert {
    condition     = !anytrue([for s in helm_release.migration_assistant[0].set : startswith(s.name, "azure.monitor.")])
    error_message = "Without Azure Monitor, no azure.monitor values may be set."
  }
}

run "azure_monitor_logs_off_by_default" {
  command = plan

  assert {
    condition = (
      length(azurerm_log_analytics_workspace.migration) == 0 &&
      length(azurerm_monitor_data_collection_rule.migration) == 0 &&
      length(azuread_application_registration.monitor) == 0 &&
      length(kubernetes_secret_v1.monitor) == 0
    )
    error_message = "Azure Monitor resources must not be created unless enable_azure_monitor_logs is true."
  }
}

run "azure_monitor_logs_use_a_direct_rule_and_the_chart_secret" {
  command = plan

  variables {
    enable_azure_monitor_logs   = true
    deploy_helm                 = true
    migration_assistant_version = "3.4.2"
  }

  assert {
    condition     = azurerm_monitor_data_collection_rule.migration[0].kind == "Direct" && azurerm_monitor_data_collection_rule.migration[0].data_collection_endpoint_id == null
    error_message = "The data collection rule must be Direct, with its own ingestion endpoint and no data collection endpoint."
  }

  assert {
    condition     = azurerm_log_analytics_workspace_table_custom_log.migration[0].name == "MigrationAssistant_CL"
    error_message = "The custom table must be MigrationAssistant_CL."
  }

  assert {
    condition     = azurerm_role_assignment.monitor_publisher[0].role_definition_name == "Monitoring Metrics Publisher"
    error_message = "The Entra application must be able to publish to the data collection rule."
  }

  assert {
    condition     = kubernetes_secret_v1.monitor[0].metadata[0].name == "azure-monitor-logs-credentials" && contains(keys(kubernetes_secret_v1.monitor[0].data), "clientSecret")
    error_message = "The client secret must be in the azure-monitor-logs-credentials Secret under clientSecret, where valuesAksMonitor.yaml reads it."
  }

  assert {
    condition     = length(helm_release.migration_assistant[0].values) == 2 && !helm_release.migration_assistant[0].create_namespace
    error_message = "With Azure Monitor, the release must add valuesAksMonitor.yaml and use the namespace Terraform created for the Secret."
  }

  assert {
    condition = alltrue([
      for name in ["azure.monitor.tenantId", "azure.monitor.clientId", "azure.monitor.dceUrl", "azure.monitor.dcrImmutableId", "azure.monitor.tableName"] :
      anytrue([for s in helm_release.migration_assistant[0].set : s.name == name])
    ])
    error_message = "The release must receive every azure.monitor value that azure-metadata requires."
  }
}

# Azure rejects tag names containing < > % & \ ? / at apply time, which a plan does not catch.
run "tag_names_avoid_azure_reserved_characters" {
  command = plan

  variables {
    tags = { CostCenter = "migrations" }
  }

  assert {
    condition     = alltrue([for k in keys(azurerm_resource_group.migration.tags) : !can(regex("[<>%&\\\\?/]", k))])
    error_message = "Tag names must not contain the characters Azure reserves: < > % & \\ ? /"
  }

  assert {
    condition     = azurerm_resource_group.migration.tags["Stage"] == "dev" && azurerm_resource_group.migration.tags["CostCenter"] == "migrations"
    error_message = "Resources must carry the Stage tag and the caller's tags."
  }
}

run "stage_rejects_invalid_names" {
  command = plan

  variables {
    stage = "Dev_1"
  }

  expect_failures = [var.stage]
}

run "node_max_count_must_cover_min_count" {
  command = plan

  variables {
    node_min_count = 4
    node_max_count = 2
  }

  expect_failures = [azurerm_kubernetes_cluster.migration]
}
