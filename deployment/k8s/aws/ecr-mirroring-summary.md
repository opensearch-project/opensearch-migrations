# Private ECR Mirroring — Implementation Summary

## What it does

Mirrors all public container images and helm charts to a private ECR registry,
enabling Migration Assistant deployment on isolated subnets without internet access.
Run `mirror-to-ecr.sh` from a machine with internet, then deploy on the air-gapped cluster.

## Files

| File | Purpose |
|------|---------|
| `scripts/private-ecr-manifest.sh` | Version-locked list of all images (47) and charts (11) |
| `scripts/mirror-to-ecr.sh` | Copies images via `crane` and charts via `helm push` to ECR |
| `scripts/generate-private-ecr-values.sh` | Generates helm values override pointing all repos to ECR |
| `aws-bootstrap.sh` | `--push-all-images-to-private-ecr` flag orchestrates the above |
| `installJob.yaml` | ECR auth for OCI chart pulls via pod identity |

## How it works

```
┌─────────────────────┐     crane copy       ┌──────────────────────┐
│  Public Registries  │ ──────────────────→  │   Private ECR        │
│  quay.io            │                      │   mirrored/quay.io/… │
│  docker.io          │     helm push        │   charts/…           │
│  registry.k8s.io    │ ──────────────────→  │                      │
│  ghcr.io            │                      │                      │
│  public.ecr.aws     │                      │                      │
└─────────────────────┘                      └──────────────────────┘
        ↑                                              ↓
   Run from machine                            EKS pods pull via
   with internet                               ECR VPC endpoints
```

### ECR repository structure

Images: `mirrored/<registry>/<path>:<tag>` — preserves the full original path.
```
mirrored/quay.io/jetstack/cert-manager-controller:v1.17.2
mirrored/docker.io/amazon/aws-cli:2.25.11
mirrored/registry.k8s.io/kube-state-metrics/kube-state-metrics:v2.15.0
```

Charts: `charts/<name>:<version>` as OCI artifacts.
```
charts/cert-manager:v1.17.2
charts/argo-workflows:0.47.1
charts/kube-prometheus-stack:72.0.0
```

## Usage

### End user (air-gapped deployment)

```bash
./deployment/k8s/aws/aws-bootstrap.sh \
  --deploy-import-vpc-cfn \
  --push-all-images-to-private-ecr \
  --create-vpc-endpoints \
  --build-chart-and-dashboards \
  --stack-name MA-Prod \
  --stage prod \
  --vpc-id vpc-xxx \
  --subnet-ids subnet-aaa,subnet-bbb \
  --region us-east-2 \
  --version 2.6.4
```

### Developer (step by step)

```bash
# 1. Mirror images (from machine with internet)
./scripts/mirror-to-ecr.sh 123456789012.dkr.ecr.us-east-2.amazonaws.com --region us-east-2

# 2. Generate values override
./scripts/generate-private-ecr-values.sh 123456789012.dkr.ecr.us-east-2.amazonaws.com > ecr-values.yaml

# 3. Deploy with the override
./aws-bootstrap.sh --skip-cfn-deploy --build-chart-and-dashboards \
  --helm-values ecr-values.yaml --stage dev --region us-east-2 --version 2.6.4
```

## Charts mirrored (11)

| Chart | Version | Source |
|-------|---------|--------|
| cert-manager | v1.17.2 | charts.jetstack.io |
| strimzi-kafka-operator | 0.47.0 | strimzi.io/charts |
| argo-workflows | 0.47.1 | argoproj.github.io |
| fluent-bit | 0.49.0 | fluent.github.io |
| kube-prometheus-stack | 72.0.0 | prometheus-community.github.io |
| etcd-operator | 0.4.2 | ghcr.io/aenix-io/charts |
| opentelemetry-operator | 0.86.4 | open-telemetry.github.io |
| localstack | 0.6.23 | localstack.github.io |
| grafana | 8.15.0 | grafana.github.io |
| jaeger | 3.2.0 | jaegertracing.github.io |
| kyverno | 3.5.2 | kyverno.github.io |

## Images mirrored (47)

Grouped by source chart. Full list in `private-ecr-manifest.sh`.

| Component | Images | Registries |
|-----------|--------|------------|
| cert-manager | 4 | quay.io |
| strimzi | 6 | quay.io |
| argo-workflows | 3 | quay.io |
| fluent-bit | 2 | cr.fluentbit.io, docker.io |
| kube-prometheus-stack | 9 | quay.io, registry.k8s.io, docker.io |
| etcd-operator | 2 | ghcr.io, gcr.io |
| otel collector | 1 | public.ecr.aws |
| opentelemetry-operator | 2 | ghcr.io, quay.io |
| grafana | 1 | docker.io |
| jaeger | 5 | docker.io |
| kyverno | 8 | reg.kyverno.io, registry.k8s.io, docker.io |
| localstack | 2 | docker.io |
| direct references | 2 | docker.io |

## Test results (2026-02-19, us-east-2)

Deployed on `MA-MIRROR-TEST` (create-VPC stack with NAT subnets).

| Step | Result |
|------|--------|
| `mirror-to-ecr.sh` — 47 images | ✅ 46/47 first pass (1 public ECR rate limit, succeeded on retry with auth) |
| `mirror-to-ecr.sh` — 11 charts | ✅ All pushed as OCI artifacts |
| `generate-private-ecr-values.sh` | ✅ Correct YAML with all image + chart overrides |
| Installer job ECR auth | ✅ `Login Succeeded` via pod identity |
| Sub-chart installs from OCI ECR | ✅ All 6 production charts deployed |
| Pod image pulls from mirrored ECR | ✅ 25/25 pods running, 0 ImagePullBackOff |
| CloudWatch dashboards | ✅ Both deployed |

## Bugs found and fixed

1. **OCI chart path missing chart name** — installer got `oci://ECR/charts:0.47.1` instead of `oci://ECR/charts/argo-workflows:0.47.1`. Fixed in `generate-private-ecr-values.sh`.
2. **cert-manager version prefix** — chart publishes as `v1.17.2` but values.yaml has `1.17.2`. Added version override in generated values.
3. **argo image tag mismatch** — manifest had `v3.7.9`, chart 0.47.1 uses `v3.7.8`. Fixed in manifest and values generator.
4. **aws-cli mirror path** — values referenced `mirrored/amazon/aws-cli` but mirror stored as `mirrored/docker.io/amazon/aws-cli`. Fixed values generator.
5. **Public ECR rate limits** — unauthenticated pulls limited to ~1 req/sec. Added `aws ecr-public get-login-password` auth to mirror script.
6. **otel-operator image tag** — chart 0.86.4 uses appVersion `0.122.0`, not `0.86.4`. Fixed in manifest.

## Prerequisites for air-gapped subnets

- ECR VPC endpoints: `ecr.api`, `ecr.dkr`, `s3` (use `--create-vpc-endpoints`)
- `crane` (auto-installed by mirror script if missing)
- `helm` 3.8+ (OCI support)
- AWS credentials with ECR push permissions on the mirroring machine
