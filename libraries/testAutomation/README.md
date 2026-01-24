# Test Automation Layer

This library provides integration testing for Kubernetes-based migrations between Elasticsearch and OpenSearch clusters.

## Prerequisites

- Minikube running with a local registry
- Python 3.x with pipenv installed

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
# Single migration path
pipenv run app --source-version=ES_7.10 --target-version=OS_2.19 \
  --test-reports-dir='./reports' --copy-logs

# All sources to OpenSearch 3.1
pipenv run app --source-version=all --target-version=OS_3.1 \
  --test-reports-dir='./reports' --copy-logs
```

Supported versions:
- **Sources:** `ES_1.5`, `ES_2.4`, `ES_5.6`, `ES_6.8`, `ES_7.10`, `OS_1.3`, `OS_2.19`
- **Targets:** `OS_1.3`, `OS_2.19`, `OS_3.1`

**Run specific tests:**
```bash
# Single test
pipenv run app --test-ids=0001 --source-version=ES_7.10 --target-version=OS_2.19

# Multiple tests
pipenv run app --test-ids=0001,0004 --source-version=ES_7.10 --target-version=OS_2.19
```

**Development mode** (skips cleanup, reuses clusters, keeps workflows):
```bash
# Fast iteration - run repeatedly without cleanup overhead
pipenv run app --dev --source-version=ES_7.10 --target-version=OS_2.19 --test-ids=0001
```

**Additional options:**
```bash
# Reuse existing clusters only
pipenv run app --reuse-clusters --source-version=ES_7.10 --target-version=OS_2.19

# Keep workflows for debugging
pipenv run app --keep-workflows --source-version=ES_7.10 --target-version=OS_2.19 \
  --test-reports-dir='./reports'

# Copy logs locally
pipenv run app --copy-logs --source-version=ES_7.10 --target-version=OS_2.19

# Skip deletion after tests (for inspection)
pipenv run app --skip-delete --source-version=ES_7.10 --target-version=OS_2.19

# View existing report summary
pipenv run app --output-reports-summary-only --test-reports-dir='./reports'
```

**Cleanup:**
```bash
# Delete all resources
pipenv run app --delete-only

# Delete clusters only
pipenv run app --delete-clusters-only
```

## Common Workflows

**Quick single version test:**
```bash
pipenv run app --source-version=ES_7.10 --target-version=OS_3.1 \
  --test-reports-dir='./reports' --copy-logs
```

**Comprehensive testing (all sources):**
```bash
pipenv run app --source-version=all --target-version=OS_3.1 \
  --test-reports-dir='./reports' --copy-logs
```

**Development iteration:**
```bash
# First run - sets up clusters
pipenv run app --dev --source-version=ES_7.10 --target-version=OS_2.19 --test-ids=0001

# Subsequent runs - reuses clusters
pipenv run app --dev --source-version=ES_7.10 --target-version=OS_2.19 --test-ids=0001

# Cleanup when done
pipenv run app --delete-only
```

**Testing multiple scenarios:**
```bash
# Different sources to same target
pipenv run app --source-version=ES_6.8 --target-version=OS_3.1 --test-reports-dir='./reports'
pipenv run app --source-version=ES_7.10 --target-version=OS_3.1 --test-reports-dir='./reports'

# Same source to different targets
pipenv run app --source-version=ES_7.10 --target-version=OS_2.19 --test-reports-dir='./reports'
pipenv run app --source-version=ES_7.10 --target-version=OS_3.1 --test-reports-dir='./reports'
```

## Troubleshooting

If tests fail or hang:

1. Check minikube status: `minikube status`
2. Review test logs in the `./logs` directory (if `--copy-logs` was used)
3. Inspect test reports in `./reports` directory
4. Clean up and retry: `pipenv run app --delete-only`
