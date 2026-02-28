# Migration Assistant Helm Chart

A Helm chart for deploying the OpenSearch Migration Assistant with Argo Workflows orchestration.

## Overview

This chart deploys the Migration Assistant along with its supporting infrastructure components including:
- Argo Workflows for migration orchestration
- Kafka (via Strimzi operator) for capture and replay
- OpenTelemetry for observability
- Prometheus stack for metrics
- Fluent Bit for log aggregation

## Prerequisites

- Kubernetes 1.19+
- Helm 3.0+

## Installation

```bash
helm install migration-assistant ./deployment/k8s/charts/aggregates/migrationAssistantWithArgo
```

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

For production environments, use proper Kubernetes-native AWS authentication instead of mounting credentials:
- **IAM Roles for Service Accounts (IRSA)** - EKS native IAM integration
- **EKS Pod Identity** - Simplified IAM for pods
- **AWS Secrets Manager** - Store credentials securely with CSI driver
- **EC2 Instance Profiles** - Node-level credentials

## Additional Documentation

- [Developer Guide](./DEVELOPER_GUIDE.md) - Development and debugging guidance
