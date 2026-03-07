# ReportPortal Integration Guide

## Overview

ReportPortal is an OSS test reporting service that aggregates test results from Argo Workflows, providing dashboards, trend analysis, and flaky test detection. Every test workflow automatically uploads results to ReportPortal when it's installed.

## Quick Start

```bash
# 1. Install ReportPortal
./argo/reportportal/install.sh

# 2. Access the UI
kubectl -n reportportal port-forward svc/reportportal-ui 8080:8080 &
open http://localhost:8080
# Login: superadmin / erebus

# 3. Run a test — results upload automatically
argo submit -n ma argo/examples/run-single-test.yaml --watch

# 4. View results in ReportPortal UI → opensearch_migrations project → Launches
```

## Installation

### Prerequisites

- Kubernetes cluster (minikube or EKS) with Argo Workflows installed
- Helm 3.x
- `kubectl` configured to the target cluster

### Install on Minikube

```bash
./argo/reportportal/install.sh
```

This script:
1. Adds the ReportPortal Helm repo
2. Installs ReportPortal with lightweight resource config (~2GB RAM total)
3. Creates the `opensearch_migrations` project
4. Creates the `reportportal-token` secret in the `ma` namespace

### Install on EKS (Production)

```bash
# Use production values with more resources
helm upgrade --install reportportal reportportal/reportportal \
  -n reportportal --create-namespace \
  --wait --timeout 15m \
  -f argo/reportportal/values-production.yaml

# Create project and token (same as install.sh does)
# See install.sh for the API calls
```

### Verify Installation

```bash
# All pods should be Running
kubectl get pods -n reportportal

# Expected pods:
# reportportal-api          Running
# reportportal-uat          Running
# reportportal-ui           Running
# reportportal-index        Running
# reportportal-jobs         Running
# reportportal-postgresql   Running
# reportportal-rabbitmq     Running
# reportportal-minio        Running
# opensearch-cluster-master Running
```

## How It Works

### Automatic Upload Flow

Every test workflow has a ReportPortal upload step in its exit handler. This runs after the test completes (pass or fail):

```
Test Workflow
├── main (run tests, produce reports)
└── onExit: cleanup-handler
    ├── cleanup steps...
    └── upload-results → reportportal-upload/upload-json-reports
        └── Reads test reports from S3 artifacts
        └── Creates a Launch in ReportPortal with test items
```

### Upload Templates

Two upload templates exist in `argo/workflows/upload-results.yaml`:

| Template | Input Artifact | Used By |
|----------|---------------|---------|
| `upload-json-reports` | `test-reports` (JSON) | k8s-local-test, k8s-matrix-test |
| `upload-junit` | `junit-xml` (XML) | argo-ci-pipeline, all AWS integ tests |

### Graceful Degradation

The upload steps are designed to be non-disruptive:
- If ReportPortal is **not installed**, the upload step checks connectivity and exits cleanly (exit 0)
- If there are **no test reports**, the step exits cleanly
- If the **RP_TOKEN secret** doesn't exist, the step exits cleanly
- Upload failures **never** cause the parent workflow to fail

## Viewing Results

### Access the UI

```bash
kubectl -n reportportal port-forward svc/reportportal-ui 8080:8080 &
open http://localhost:8080
```

Login: `superadmin` / `erebus`

### Navigate to Results

1. Click **opensearch_migrations** project in the left sidebar
2. Click **Launches** to see all test runs
3. Each launch corresponds to one workflow execution

### Launch Attributes

Every launch is tagged with attributes for filtering:

| Attribute | Example | Description |
|-----------|---------|-------------|
| `source` | `ES_7.10` | Source cluster version |
| `target` | `OS_2.19` | Target cluster version |
| `workflow` | `test-suite-q82dg` | Argo workflow name |

### Filtering Results

- **By version**: Filter launches by `source` or `target` attribute
- **By status**: Filter by PASSED/FAILED
- **By date**: Use the date picker to see results over time

### Dashboards

Create custom dashboards for:
- **CI Health**: Pass rate trends across all test types
- **Version Matrix**: Pass/fail by source×target version combination
- **Flaky Tests**: Tests that alternate between pass and fail

## Workflow Integration Details

### k8s-local-test (JSON reports)

The test automation framework produces JSON reports at `./reports/test-report-*.json`. These are saved as S3 artifacts and uploaded to ReportPortal via `upload-json-reports`.

Each JSON report contains:
```json
{
  "summary": {
    "source_version": "ES_7.10",
    "target_version": "OS_2.19"
  },
  "tests": [
    {"name": "Test0001SingleDocumentBackfill", "result": "passed", "duration": 215.8},
    {"name": "Test0002...", "result": "passed", "duration": 382.1}
  ]
}
```

In ReportPortal, this becomes:
- **Launch**: `k8s-local-ES_7.10-OS_2.19`
  - **Test Item**: `Test0001SingleDocumentBackfill` → PASSED
  - **Test Item**: `Test0002...` → PASSED

### k8s-matrix-test (Aggregated JSON)

The matrix test collects child workflow results into `aggregated-reports/matrix-results.json` and uploads them.

### AWS Integ Tests (JUnit XML)

AWS integration tests produce JUnit XML via `--junitxml` flag. These are uploaded via `upload-junit` which uses ReportPortal's import API.

### CI Pipeline (JUnit XML)

The CI pipeline's gradle test stripes produce JUnit XML at `build/test-results/`. These are uploaded via `upload-junit`.

## Troubleshooting

### Upload step shows "No JSON report files found"

The test step didn't produce reports, or the artifact wasn't passed correctly. Check:
```bash
# Check if the test step produced reports
argo logs -n ma <workflow-name> | grep "reports/"

# Check if artifacts were saved to S3
argo get -n ma <workflow-name> -o json | jq '.status.nodes[].outputs.artifacts'
```

### Upload step shows "ReportPortal not reachable"

ReportPortal isn't installed or the service is down:
```bash
# Check RP pods
kubectl get pods -n reportportal

# Check RP API health
kubectl exec -n ma deploy/argo-server -- curl -s http://reportportal-api.reportportal.svc:8585/health
```

### Upload step shows "No RP_TOKEN set"

The token secret doesn't exist:
```bash
# Check if secret exists
kubectl get secret reportportal-token -n ma

# Re-create it
kubectl -n ma create secret generic reportportal-token \
  --from-literal=token="<your-token>" \
  --dry-run=client -o yaml | kubectl apply -f -
```

### Results don't appear in ReportPortal UI

1. Check the upload step logs: `argo logs -n ma <workflow> | grep -i report`
2. Verify the project exists: Go to RP UI → Admin → Projects → `opensearch_migrations`
3. Check the API token is valid: The JWT token expires after 24 hours. Re-run `install.sh` to refresh.

### ReportPortal pods crashing

```bash
# Check pod logs
kubectl logs -n reportportal <pod-name>

# Common issues:
# - UAT: "Password not set" → values-minikube.yaml needs RP_INITIAL_ADMIN_PASSWORD
# - Analyzer: ImagePullBackOff → disabled by default, enable only if needed
# - PostgreSQL: OOM → increase memory in values-minikube.yaml
```

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│ Argo Workflows (ma namespace)                           │
│                                                         │
│  k8s-local-test ──→ test-reports (JSON) ──┐             │
│  k8s-matrix-test ──→ aggregated-reports ──┤             │
│  argo-ci-pipeline ──→ junit-xml ──────────┤             │
│  aws-integ-* ──→ test-results (XML) ──────┤             │
│                                           │             │
│  ┌──────────────────────────────────┐     │             │
│  │ reportportal-upload template     │◄────┘             │
│  │  upload-json-reports             │                   │
│  │  upload-junit                    │                   │
│  └──────────┬───────────────────────┘                   │
│             │ HTTP POST                                 │
└─────────────┼───────────────────────────────────────────┘
              │
┌─────────────▼───────────────────────────────────────────┐
│ ReportPortal (reportportal namespace)                   │
│                                                         │
│  API (8585) ──→ PostgreSQL ──→ OpenSearch               │
│  UAT (9999) ──→ Auth/tokens                             │
│  UI  (8080) ──→ Browser access                          │
│  RabbitMQ   ──→ Async processing                        │
│  MinIO      ──→ Attachment storage                      │
└─────────────────────────────────────────────────────────┘
```

## Workflows with ReportPortal Hooks

| Workflow | Upload Template | Launch Name |
|----------|----------------|-------------|
| `k8s-local-test` | `upload-json-reports` | `k8s-local-{source}-{target}` |
| `k8s-matrix-test` | `upload-json-reports` | `k8s-matrix-nightly` |
| `argo-ci-pipeline` | `upload-junit` | `ci-{branch}` |
| `default-integ-pipeline` | `upload-junit` | `integ-{stage}` |
| `full-es68-e2e` | `upload-junit` | `full-es68-e2e` |
| `eks-integ-pipeline` | `upload-junit` | `eks-integ-{stage}` |
| `eks-byos-integ-pipeline` | `upload-junit` | `eks-byos-{stage}` |
| `eks-solutions-cfn-test` | `upload-junit` | `eks-solutions-cfn-test` |
| `solutions-cfn-test` | `upload-junit` | `solutions-cfn-test` |
| `eks-isolated-deploy` | `upload-junit` | `eks-isolated-deploy` |
