# Test Automation Layer

This library provides integration testing for Kubernetes-based migrations between Elasticsearch and OpenSearch clusters.

## Prerequisites

- Kubernetes cluster (for example: Minikube) with a local registry
- Python 3.11+ with pipenv installed

## Setup

```bash
cd libraries/testAutomation
pipenv install --deploy
mkdir -p ./reports
```

## Usage

**Basic test with defaults (ES_5.6 → OS_2.19):**
```bash
pipenv run app
```

**Test specific version combinations:**
```bash
pipenv run app --source-version=ES_7.10 --target-version=OS_2.19 \
  --test-reports-dir='./reports' --copy-logs

# Test all source versions against a target
pipenv run app --source-version=all --target-version=OS_3.1 \
  --test-reports-dir='./reports' --copy-logs
```

Supported versions:
- **Sources:** `ES_1.5`, `ES_2.4`, `ES_5.6`, `ES_6.8`, `ES_7.10`, `OS_1.3`, `OS_2.19`
- **Targets:** `OS_1.3`, `OS_2.19`, `OS_3.1`

**Run specific tests:**
```bash
pipenv run app --test-ids=0001 --source-version=ES_7.10 --target-version=OS_2.19
pipenv run app --test-ids=0001,0004 --source-version=ES_7.10 --target-version=OS_2.19
```

## Development Mode

The `--dev` flag combines options for fast iteration: `--skip-delete`, `--reuse-clusters`, `--keep-workflows`.

```bash
# First run - deploys Helm chart and creates clusters
pipenv run app --dev --source-version=ES_7.10 --target-version=OS_2.19 --test-ids=0001

# Subsequent runs - reuses existing deployment and clusters
pipenv run app --dev --source-version=ES_7.10 --target-version=OS_2.19 --test-ids=0001

# Cleanup when done
pipenv run app --delete-only
```

**Individual flags (can be used without `--dev`):**

- `--skip-delete`: Keeps the Migration Assistant Helm deployment and namespace after tests complete
- `--reuse-clusters`: Reuses existing source/target clusters matching the naming pattern (e.g., `target-opensearch-2-19-*`). On first run, creates clusters and leaves them running; subsequent runs reuse them.
- `--keep-workflows`: Preserves Argo workflows for debugging instead of deleting them

Note: `--skip-delete` and `--reuse-clusters` are disabled when testing multiple version combinations (`--source-version=all`).

## Output Options

```bash
# Copy container logs from all pods in the namespace to ./logs directory
# (collected via FluentBit from /shared-logs-output in the migration console)
pipenv run app --copy-logs --source-version=ES_7.10 --target-version=OS_2.19

# Save test reports (JSON with pass/fail, duration, errors) to a directory
pipenv run app --test-reports-dir='./reports' --source-version=ES_7.10 --target-version=OS_2.19

# View summary table of existing reports without running tests
pipenv run app --output-reports-summary-only --test-reports-dir='./reports'
```

## Cleanup

```bash
# Delete entire deployment (namespace, Helm releases, clusters, all resources)
pipenv run app --delete-only

# Delete only source/target clusters (keeps Migration Assistant deployment)
pipenv run app --delete-clusters-only
```

## Troubleshooting

1. Check cluster status: `minikube status`
2. Review container logs in `./logs` (requires `--copy-logs`)
3. Inspect test reports in `./reports`
4. Check pod status: `kubectl get pods -n ma`
5. Clean up and retry: `pipenv run app --delete-only`
