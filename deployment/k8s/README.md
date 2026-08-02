# Kubernetes Deployment

Audience: This document is meant for **DEVELOPERS** looking to build/maintain a Kubernetes deployment of the Migration
Assistant tools from this (opensearch-migrations) repository to support customers that want to Migrate their
configurations and data from a source cluster to a target cluster. End-users should consult
the [project's wiki](https://github.com/opensearch-project/opensearch-migrations/wiki) for detailed instructions to
deploy and operate a production environment.

This README focuses on the helm installation of the [Migration Assistant](charts/aggregates/migrationAssistantWithArgo)
chart, which will install the migration console and resources required for it to perform migrations (e.g. Argo
Workflows, metrics collectors, etc).

**Notice**: The user is responsible for the cost of any underlying infrastructure required to operate the solution. We
welcome feedback and contributions to optimize costs.

## Quick Start

### AWS EKS Deployment

If you're looking to use EKS as your Kubernetes cluster, follow the
[instructions here](aws/README.md).

### AWS CDK Resources

If you're looking for the CDK resources for ECS deployments, read
[AWS CDK Deployment Resources](../cdk/opensearch-service-migration/README.md).

### GKE

If you're looking to use Google Kubernetes Engine (GKE) as your Kubernetes cluster,
the [GCP Terraform module](../terraform/gcp/README.md) provisions a GKE cluster, a GCS bucket for snapshots, and the
Google Service Account / Workload Identity bindings required by the migration workflows.

After `terraform apply` succeeds and you've fetched cluster credentials with
`gcloud container clusters get-credentials`, install the Helm chart using the GKE overlay
[valuesGke.yaml](charts/aggregates/migrationAssistantWithArgo/valuesGke.yaml):

```bash
helm install --create-namespace -n ma ma \
  charts/aggregates/migrationAssistantWithArgo \
  --values charts/aggregates/migrationAssistantWithArgo/valuesGke.yaml \
  --set gcp.project=<your-gcp-project>
```

GCS snapshot repositories are configured per-workflow at workflow-submission time (via the `gcs` snapshot type in the
workflow config) rather than at Helm-install time, in the same way S3 repositories are configured for EKS deployments.

### Migration Assistant Solution

Details regarding the deployment of the Migration Assistant solution can be found in
[Migration Assistant Solution Deployment](../migration-assistant-solution/README.md).

### Local Deployment with kind

For a local deployment with kind, read the instructions provided in the [kind deployment guide](kind-guide.md).

## Helm Charts

In the [helm charts overview](charts/README.md) you will find information about the available helm charts and their
purpose, as well as some limitations and information to be aware of.
