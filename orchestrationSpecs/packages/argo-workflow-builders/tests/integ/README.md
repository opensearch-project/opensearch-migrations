# Argo Workflow Builder Integration Tests

Integration tests that validate the `@opensearch-migrations/argo-workflow-builders` library against a real Argo Workflows instance running in a K3s testcontainer.

## Quick Start

```bash
npm run test:integ                          # All integration tests
npm run test:integ -- contracts/            # Contract tests only
npm run test:integ -- jsonpath.integ.test.ts # Specific file
npm run test:integ -- -t "regex"            # Tests matching pattern
npm run test:parity                         # Parity tests only
npm run test:parity:broken                  # Include known-broken parity tests
```

**Prerequisites**: Docker (with privileged container support), Node.js, npm.

First run pulls the K3s image (~200MB) and installs Argo (~2-3 min). Subsequent runs reuse cached images.

## Test Results

- [artifacts/parity-catalog.md](artifacts/parity-catalog.md) — Generated parity catalog (run `npm run test:integ` to create)

## Architecture

```
tests/integ/
├── infra/                     # Test infrastructure
│   ├── argoCluster.ts         # K3s + Argo lifecycle (v4.0.0, downloaded at test time)
│   ├── workflowRunner.ts      # Submit workflows and poll for results
│   ├── probeHelper.ts         # Build minimal probe workflows
│   ├── parityHelper.ts        # Parity test helpers
│   ├── parityReporter.mjs     # Generates artifacts/parity-catalog.md
│   ├── setup.ts / teardown.ts # Jest global setup/teardown
│   └── k8sClient.ts           # Kubernetes API helpers
├── contracts/                 # Argo runtime behavior documentation
├── parity/                    # Builder vs raw Argo expression comparison
├── modelValidation/           # Builder API end-to-end validation
└── containers/                # Container-specific tests (slower, optional)
```

### Test Categories

**Contract tests** (`contracts/`) — Document Argo's actual runtime behavior using raw expressions. Covers JSONPath, Sprig functions, expression evaluation, type conversion, operators, parameter pass-through, and conditional execution.

**Parity tests** (`parity/`) — Compare builder-generated expressions against raw Argo expressions to verify the builder produces equivalent results. Results are collected into the [parity catalog](artifacts/parity-catalog.md).

**Model validation tests** (`modelValidation/`) — Verify the builder API's type system and renderer produce workflows that run correctly in Argo.

**Container tests** (`containers/`) — Optional tests for container-specific features. Slower due to image pulls.

## Key Findings

### asInt() on Decimals
```typescript
asInt("42")     // ✅ Works
asInt("42.7")   // ❌ Errors (doesn't truncate)
int(asFloat("42.7"))  // ✅ Workaround → 42
```

### Regex Escaping
```typescript
sprig.regexMatch('\\d+', text)  // Need double backslashes in expressions
```

### JSONPath Type Coercion
- Numbers → `"42"` (string), Booleans → `"true"` (lowercase), Objects/Arrays → JSON strings, Null → `"null"`

See [QUICK_REFERENCE.md](QUICK_REFERENCE.md) for comprehensive code examples covering regex, Sprig functions, bracket notation, type conversion, and test patterns.

## Infrastructure

### `argoCluster.ts`
Manages K3s container (`rancher/k3s:v1.31.6-k3s1`) and Argo installation. Downloads the Argo v4.0.0 quick-start-minimal manifest at test time. Creates `integ-test` namespace with `test-runner` ServiceAccount (cluster-admin).

### `probeHelper.ts`
Builds minimal probe workflows using empty `steps: [[]]` templates for fast expression evaluation without container overhead (<1s per test).

- `submitProbe(config)` — Single expression evaluation
- `submitChainProbe(config)` — Multi-step parameter pass-through
- `submitRenderedWorkflow(rendered, overrides?)` — Builder API workflows

### `workflowRunner.ts`
Submits workflows via Kubernetes API and polls until terminal phase. Returns structured `WorkflowResult` with global outputs, per-node outputs, phase, and the raw workflow object for debugging.

## Writing New Tests

Add contract tests to `contracts/`, model validation tests to `modelValidation/`. See [QUICK_REFERENCE.md](QUICK_REFERENCE.md) for detailed examples of each pattern.

## Troubleshooting

| Problem | Solution |
|---------|----------|
| Tests hang during setup | Check Docker is running, verify network connectivity |
| "Kubeconfig not available" | Global setup failed — check Jest output for errors |
| RBAC errors | Verify `test-runner` ServiceAccount and ClusterRoleBinding exist |
| Workflow timeout | Check `result.raw.status.message` for Argo error details |

### Manual Cluster Access
```bash
export KUBECONFIG=/tmp/integ-test-kubeconfig.yaml
kubectl get workflows -n integ-test
kubectl describe workflow <name> -n integ-test
```

## Performance

- Tests use empty `steps: [[]]` templates — no container pulls, no pod scheduling
- All tests share one K3s cluster (global setup/teardown)
- Sequential execution (`maxWorkers: 1`)
- ~80 seconds for full suite after setup

## Upgrading Argo

Change the `argoVersion` constant in `argoCluster.ts` and run the full test suite.
