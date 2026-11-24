# Migration Assistant Helm Chart

See the [Kafka observability guide](../../../dashboards/KAFKA_OBSERVABILITY.md)
for managed Kafka storage dashboards, opt-in alerts, CloudWatch dimensions,
and broker JMX health.

A Helm chart for deploying the OpenSearch Migration Assistant with Argo Workflows orchestration.

## Overview

This chart deploys the Migration Assistant along with its supporting infrastructure components including:
- Argo Workflows for migration orchestration
- Kafka (via Strimzi operator) for capture and replay
- OpenTelemetry for observability
- Prometheus stack for metrics
- Fluent Bit for log aggregation

During pod startup, the migration console runs a `workflow-schema-generator`
init container. It waits for the Strimzi OpenAPI endpoint to become available,
builds the unified migration workflow schema from the live Strimzi CRDs, and
writes both `workflowMigration.schema.json` and `sample.yaml` into a shared
volume mounted by the main console container. The console validates user
workflow configs against that live-cluster-derived schema instead of relying on
a schema baked into the container image.

## Prerequisites

- Kubernetes 1.19+
- Helm 3.0+

## Installation

```bash
helm install migration-assistant ./deployment/k8s/charts/aggregates/migrationAssistantWithArgo
```

## Upgrading

### Strimzi CRDs

`helm upgrade` never updates the CRDs in a chart's `crds/` directory, so before
the dependency installer upgrades the `strimzi-kafka-operator` chart it updates
the Strimzi CRDs itself
([`_strimziCrdUpgrade.tpl`](./templates/helpers/_strimziCrdUpgrade.tpl)):

1. If the existing CRDs still store `v1beta2` (Strimzi 0.49-0.51), it makes
   `v1` the storage version, rewrites every Strimzi resource so that it is
   stored as `v1`, and sets `status.storedVersions` to `[v1]`. This is what
   Strimzi's `v1-api-conversion.sh crd-upgrade` does. The Migration Assistant
   has always created its Strimzi resources through the `v1` API, so they need
   no field conversion.
2. It server-side applies the CRD files from the configured chart version, then
   checks that each CRD serves exactly the chart's versions.

The installer stops with an error, before it changes anything, if the CRDs come
from Strimzi older than 0.49 (no `v1` API). Upgrade through a release that uses
Strimzi 0.49-0.51 first. Once the CRDs are `v1`-only, rolling back to a release
that uses Strimzi 0.5x isn't supported, because those operators need
`v1beta2`.

### Kafka clusters that are already running

Strimzi 1.3 supports Kafka 4.2.0-4.3.2, and earlier releases created Kafka
4.0.0 clusters. After the upgrade, such a cluster keeps serving traffic, but
the operator reports `UnsupportedKafkaVersionException` and stops reconciling
it. Until the cluster is upgraded, `KafkaTopic` and `KafkaUser` changes aren't
reconciled reliably: the old entity operator keeps using the removed `v1beta2`
API, and it may restart. Choose one of these options:

- Finish or reset (`workflow reset`) capture-and-replay migrations before you
  upgrade. New workflow runs create Kafka 4.2.2 clusters.
- Submit the workflow again after you upgrade. The `KafkaCluster` version is a
  gated field, so approve the change when the workflow asks
  (`workflow approve change <resource>`, see
  [Reconfiguring workflows](../../../../../docs/reconfiguringWorkflows.md)). The
  workflow then applies `spec.kafka.version: 4.2.2` and
  `metadataVersion: 4.2-IV1`. Strimzi rolls the brokers to 4.2.2 one at a time
  on the current metadata version and then raises the metadata version.
  Persistent volumes, and the data on them, are kept. To upgrade the Strimzi
  cluster by hand instead, run
  `kubectl -n ma patch kafka <name> --type=merge -p '{"spec":{"kafka":{"version":"4.2.2","metadataVersion":"4.2-IV1"}}}'`.

## Configuration

See `values.yaml` for the full list of configurable parameters.

### Kyverno Configuration Options

| Parameter | Description | Default |
|-----------|-------------|---------|
| `conditionalPackageInstalls.kyverno` | Enable Kyverno installation | `false` |
| `kyvernoPolicies.mountLocalAwsCreds` | Enable AWS credentials mounting policy (local dev only) | `false` |
| `kyvernoPolicies.mountLocalAwsCredsPath` | Host path to AWS credentials | `~/.aws` |
| `kyvernoPolicies.zeroResourceRequests` | Enable zero resource requests policy | `false` |

## Kyverno Policies

This chart includes optional Kyverno policies for local development environments. Kyverno must be enabled first:

```yaml
conditionalPackageInstalls:
  kyverno: true
```

### Available Policies

**Mount Local AWS Credentials** (`kyvernoPolicies.mountLocalAwsCreds`)
- Automatically mounts host AWS credentials (`~/.aws`) to pods using the `migration-console-access-role` service account
- **⚠️ LOCAL DEVELOPMENT ONLY** - Never enable in production or shared clusters

**Zero Resource Requests** (`kyvernoPolicies.zeroResourceRequests`)
- Sets resource requests to zero for pods
- Useful for local development with limited resources
- Not recommended for production use

### Enabling Policies (Local Development)

```yaml
conditionalPackageInstalls:
  kyverno: true

kyvernoPolicies:
  mountLocalAwsCreds: true
  zeroResourceRequests: true
```

### Production Alternatives

For production environments, use proper Kubernetes-native cloud authentication
instead of mounting credentials:

**AWS / EKS:**
- **IAM Roles for Service Accounts (IRSA)** - EKS native IAM integration
- **EKS Pod Identity** - Simplified IAM for pods
- **AWS Secrets Manager** - Store credentials securely with CSI driver
- **EC2 Instance Profiles** - Node-level credentials

**GCP / GKE:**
- **Workload Identity** - GKE-native binding of a Kubernetes ServiceAccount to a
  Google Service Account; the chart's GKE overlay
  ([`valuesGke.yaml`](./valuesGke.yaml)) annotates the migration console and
  Argo workflow-executor ServiceAccounts with
  `iam.gke.io/gcp-service-account` so RFS / metadata / snapshot pods can read
  and write GCS without static credentials. Set `gcp.project=<your-gcp-project>`
  when installing with this overlay (see
  [deployment/terraform/gcp/README.md](../../../../terraform/gcp/README.md) for the
  full walkthrough that provisions the required GSA and IAM bindings).

## Additional Documentation

- [Developer Guide](./DEVELOPER_GUIDE.md) - Development and debugging guidance
