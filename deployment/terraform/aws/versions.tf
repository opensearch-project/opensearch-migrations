terraform {
  required_version = ">= 1.6"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    helm = {
      source  = "hashicorp/helm"
      version = ">= 3.0"
    }
  }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = merge(var.tags, {
      Project = "OpenSearch Migration Assistant"
      Stage   = var.stage
    })
  }
}

data "aws_eks_cluster_auth" "migration" {
  name       = aws_eks_cluster.migration.name
  depends_on = [aws_eks_cluster.migration]
}

provider "helm" {
  kubernetes = {
    host                   = aws_eks_cluster.migration.endpoint
    token                  = data.aws_eks_cluster_auth.migration.token
    cluster_ca_certificate = base64decode(aws_eks_cluster.migration.certificate_authority[0].data)
  }
}
