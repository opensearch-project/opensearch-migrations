terraform {
  required_version = ">= 1.6"

  required_providers {
    azurerm = {
      source = "hashicorp/azurerm"
      # 5.9 added the Direct kind and logs_ingestion_endpoint on azurerm_monitor_data_collection_rule.
      version = "~> 5.9"
    }
    azuread = {
      source  = "hashicorp/azuread"
      version = "~> 3.0"
    }
    helm = {
      source  = "hashicorp/helm"
      version = ">= 3.0"
    }
    kubernetes = {
      source  = "hashicorp/kubernetes"
      version = ">= 2.0"
    }
    random = {
      source  = "hashicorp/random"
      version = ">= 3.0"
    }
  }
}

provider "azurerm" {
  features {}
  subscription_id = var.subscription_id
}

provider "helm" {
  kubernetes = {
    host                   = azurerm_kubernetes_cluster.migration.kube_config[0].host
    client_certificate     = base64decode(azurerm_kubernetes_cluster.migration.kube_config[0].client_certificate)
    client_key             = base64decode(azurerm_kubernetes_cluster.migration.kube_config[0].client_key)
    cluster_ca_certificate = base64decode(azurerm_kubernetes_cluster.migration.kube_config[0].cluster_ca_certificate)
  }
}

provider "kubernetes" {
  host                   = azurerm_kubernetes_cluster.migration.kube_config[0].host
  client_certificate     = base64decode(azurerm_kubernetes_cluster.migration.kube_config[0].client_certificate)
  client_key             = base64decode(azurerm_kubernetes_cluster.migration.kube_config[0].client_key)
  cluster_ca_certificate = base64decode(azurerm_kubernetes_cluster.migration.kube_config[0].cluster_ca_certificate)
}
