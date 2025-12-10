# Migration Assistant Helm Chart

A Helm chart for deploying the OpenSearch Migration Assistant with Argo Workflows orchestration.

## Overview

This chart deploys the Migration Assistant along with its supporting infrastructure components including:
- Argo Workflows for migration orchestration
- etcd for workflow coordination
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

---

## ⚠️ Security Considerations

### Kyverno Policies

This chart includes optional Kyverno policies that can significantly impact cluster security. These policies are **disabled by default** and require explicit opt-in.

#### Prerequisites for Kyverno Policies

To use any Kyverno policies, you must first enable Kyverno installation:

```yaml
conditionalPackageInstalls:
  kyverno: true
```

---

### 🚨 CRITICAL SECURITY WARNING: Local AWS Credentials Mounting Policy

> **⚠️ WARNING: This policy exposes host filesystem credentials to ALL pods in the cluster. Only enable this in isolated local development environments. NEVER enable this in production or shared clusters.**

#### What This Policy Does

When enabled, the `mountLocalAwsCreds` Kyverno policy creates a **ClusterPolicy** that automatically mutates **ALL pods** in the cluster to:

1. **Add a hostPath volume** that mounts the host machine's AWS credentials directory (default: `~/.aws`)
2. **Inject a volumeMount** into **every container** of every pod, mounting the credentials at `/root/.aws` (read-only)
3. **Add an annotation** `kyverno.io/aws-creds-mounted: "true"` to mark affected pods

#### Which Pods Receive Credentials

**ALL pods in the entire cluster** will receive the mounted AWS credentials. This includes:

- Migration Assistant pods (migration-console, reindex-from-snapshot, traffic-replayer, etc.)
- Argo Workflow pods
- Infrastructure pods (etcd, Kafka, Prometheus, etc.)
- System pods (kube-system namespace pods)
- Any other workloads running in the cluster

The policy uses a broad match rule:
```yaml
match:
  any:
    - resources:
        kinds:
          - Pod
```

This means there is **no namespace restriction** and **no label selector** - every pod created after the policy is applied will be mutated.

#### Security Implications

| Risk | Description |
|------|-------------|
| **Credential Exposure** | Your personal/machine AWS credentials become accessible to every container in the cluster |
| **Privilege Escalation** | Any compromised container can use your AWS credentials to access AWS resources |
| **Lateral Movement** | Attackers gaining access to any pod can immediately access AWS APIs with your permissions |
| **Audit Trail Pollution** | All AWS API calls will appear to come from your credentials, making incident investigation difficult |
| **Blast Radius** | A single container compromise affects your entire AWS account access |

#### How to Enable (Local Development Only)

```yaml
conditionalPackageInstalls:
  kyverno: true

kyvernoPolicies:
  mountLocalAwsCreds: true
  mountLocalAwsCredsPath: "~/.aws"  # Optional: customize the host path
```

#### How to Control/Restrict the Policy

Currently, the policy applies cluster-wide without restrictions. To limit exposure, consider:

1. **Use a dedicated AWS profile** with minimal permissions for local development
2. **Use temporary credentials** that expire quickly
3. **Run in an isolated cluster** (e.g., local minikube/kind) that has no other workloads
4. **Disable the policy** immediately after testing by setting `mountLocalAwsCreds: false`

#### Recommended Alternatives for Production

Instead of mounting host credentials, use proper Kubernetes-native AWS authentication:

| Method | Description |
|--------|-------------|
| **IAM Roles for Service Accounts (IRSA)** | Associate Kubernetes service accounts with IAM roles (EKS) |
| **Pod Identity** | Use EKS Pod Identity for simplified IAM integration |
| **Secrets Manager** | Store credentials in AWS Secrets Manager and inject via CSI driver |
| **Instance Profiles** | Use EC2 instance profiles for node-level credentials |

---

### Other Kyverno Policies

#### Zero Resource Requests Policy

When enabled (`kyvernoPolicies.zeroResourceRequests: true`), this policy sets resource requests to zero for pods, which can be useful for local development but should not be used in production.

---

## Values Reference

### Kyverno Policy Configuration

| Parameter | Description | Default |
|-----------|-------------|---------|
| `conditionalPackageInstalls.kyverno` | Enable Kyverno installation | `false` |
| `kyvernoPolicies.mountLocalAwsCreds` | Enable AWS credentials mounting policy | `false` |
| `kyvernoPolicies.mountLocalAwsCredsPath` | Host path to AWS credentials | `~/.aws` |
| `kyvernoPolicies.zeroResourceRequests` | Enable zero resource requests policy | `false` |

## Additional Documentation

- [Developer Guide](./DEVELOPER_GUIDE.md) - Development and debugging guidance
