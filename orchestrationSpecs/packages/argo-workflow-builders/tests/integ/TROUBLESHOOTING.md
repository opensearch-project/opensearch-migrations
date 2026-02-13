# Integration Tests Troubleshooting Guide

## Common Issues and Solutions

### Setup Issues

#### "Cannot connect to Docker daemon"

**Symptom**: Tests fail during global setup with Docker connection errors.

**Solution**:
```bash
# Check Docker is running
docker ps

# If not running, start Docker Desktop (macOS/Windows) or Docker daemon (Linux)
sudo systemctl start docker  # Linux
```

#### "K3s container fails to start"

**Symptom**: Setup hangs or fails with "container exited unexpectedly".

**Causes**:
1. Insufficient resources (memory/CPU)
2. Port conflicts
3. Docker not in privileged mode

**Solutions**:
```bash
# Check Docker resources (Docker Desktop → Settings → Resources)
# Ensure at least 2GB RAM and 2 CPUs available

# Check for port conflicts
lsof -i :6443  # K3s API server port

# Verify Docker supports privileged containers
docker run --rm --privileged alpine echo "Privileged mode works"
```

#### "Argo controller not ready" timeout

**Symptom**: Setup times out waiting for Argo controller.

**Solutions**:
1. Increase timeout in `argoCluster.ts` (line ~70)
2. Check Docker image pull progress:
   ```bash
   docker logs $(docker ps -q --filter ancestor=rancher/k3s)
   ```
3. Verify network connectivity (Argo images need to be pulled)

### Test Execution Issues

#### "Kubeconfig not available"

**Symptom**: Tests fail with "Kubeconfig not available. Cluster may not be started."

**Causes**:
1. Global setup failed
2. Temp file was deleted
3. Running tests without Jest global setup

**Solutions**:
```bash
# Check if kubeconfig exists
ls -la /tmp/integ-test-kubeconfig.yaml

# Run with proper Jest config
npm run test:integ  # NOT: jest tests/integ/...

# Check Jest output for setup errors
npm run test:integ 2>&1 | grep -A 10 "globalSetup"
```

#### Workflow times out

**Symptom**: Test fails with "Workflow probe-xyz timed out after 60000ms".

**Debugging**:
```typescript
test("my test", async () => {
  try {
    const result = await submitProbe({ /* ... */ });
  } catch (err) {
    console.log("Timeout error:", err.message);
    // Check if workflow was created
    // kubectl get workflows -n integ-test
  }
});
```

**Common causes**:
1. Invalid expression syntax
2. Missing input parameters
3. Argo controller overloaded

**Solutions**:
```bash
# Check workflow status manually
export KUBECONFIG=/tmp/integ-test-kubeconfig.yaml
kubectl get workflows -n integ-test
kubectl describe workflow <workflow-name> -n integ-test

# Check Argo controller logs
kubectl logs -n argo deployment/workflow-controller
```

#### Workflow fails with "expression evaluation error"

**Symptom**: Workflow phase is "Failed" or "Error" with expression-related message.

**Debugging**:
```typescript
test("debug expression", async () => {
  const result = await submitProbe({
    inputs: { data: '{"key":"value"}' },
    expression: "jsonpath(inputs.parameters.data, '$.key')",
  });
  
  if (result.phase !== "Succeeded") {
    console.log("Workflow failed!");
    console.log("Phase:", result.phase);
    console.log("Message:", result.message);
    console.log("Node outputs:", result.nodeOutputs);
  }
});
```

**Common causes**:
1. Typo in expression
2. Wrong parameter reference (e.g., `workflow.parameters.x` vs `inputs.parameters.x`)
3. Type mismatch (e.g., calling `asInt()` on non-numeric string)

**Solutions**:
- Simplify expression to isolate the issue
- Check Argo expression documentation
- Verify parameter names match workflow definition

### Performance Issues

#### Tests are very slow

**Symptom**: Each test takes >10 seconds.

**Causes**:
1. Container images being pulled
2. K3s resource constraints
3. Multiple test workers

**Solutions**:
```bash
# Verify maxWorkers is 1 in jest.integ.config.js
# (Multiple workers cause resource contention)

# Check Docker resource allocation
docker stats

# Pre-pull images (if using container tests)
docker pull busybox:latest
```

#### Setup takes >5 minutes

**Symptom**: Global setup is extremely slow.

**Solutions**:
1. Check network speed (K3s and Argo images are ~200MB total)
2. Increase Docker resources
3. Use local image cache:
   ```bash
   # Pre-pull images
   docker pull rancher/k3s:v1.31.6-k3s1
   ```

### Test Failures

#### "Cannot find module" errors

**Symptom**: Import errors in test files.

**Solutions**:
```bash
# Verify file structure
ls -la tests/integ/infra/
ls -la tests/unit/

# Check import paths
# From tests/integ/contracts/: use "../infra/..."
# From tests/integ/modelValidation/: use "../../../src/..."

# Rebuild if needed
npm run type-check
```

#### Assertion failures

**Symptom**: Test runs but assertions fail.

**Debugging approach**:
```typescript
test("debug assertion", async () => {
  const result = await submitProbe({ /* ... */ });
  
  // Log everything
  console.log("Phase:", result.phase);
  console.log("Global outputs:", result.globalOutputs);
  console.log("Node outputs:", result.nodeOutputs);
  console.log("Duration:", result.duration);
  
  // Check specific values
  console.log("Result value:", result.globalOutputs.result);
  console.log("Result type:", typeof result.globalOutputs.result);
  console.log("Result length:", result.globalOutputs.result?.length);
  
  // Then write assertions based on actual values
});
```

### CI/CD Issues

#### Tests fail in CI but pass locally

**Common causes**:
1. CI doesn't support privileged containers
2. Insufficient resources in CI
3. Network restrictions

**Solutions**:

For GitHub Actions:
```yaml
# .github/workflows/test.yml
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-node@v3
      - run: npm ci
      - run: npm test  # Unit tests
      - run: npm run test:integ  # Integration tests
        # Ensure Docker is available (it is by default in ubuntu-latest)
```

For environments without privileged containers:
```typescript
// tests/integ/infra/setup.ts
export default async function globalSetup() {
  // Check if Docker supports privileged mode
  try {
    await startCluster();
  } catch (err) {
    console.warn("Skipping integration tests: Docker privileged mode not available");
    process.env.SKIP_INTEG_TESTS = "true";
  }
}

// In test files
beforeAll(() => {
  if (process.env.SKIP_INTEG_TESTS) {
    console.log("Skipping integration tests");
    return;
  }
});
```

### Cleanup Issues

#### Temp files not cleaned up

**Symptom**: `/tmp/integ-test-kubeconfig.yaml` persists after tests.

**Solution**:
```bash
# Manual cleanup
rm -f /tmp/integ-test-kubeconfig.yaml /tmp/integ-test-meta.json

# Verify teardown is running
npm run test:integ 2>&1 | grep -A 5 "globalTeardown"
```

#### K3s container not stopped

**Symptom**: K3s container still running after tests.

**Solution**:
```bash
# Check for running K3s containers
docker ps --filter ancestor=rancher/k3s

# Stop manually if needed
docker stop $(docker ps -q --filter ancestor=rancher/k3s)

# Verify teardown is called
# Check jest.integ.config.js has globalTeardown configured
```

## Manual Cluster Access

For debugging, you can access the cluster manually:

```bash
# Set kubeconfig
export KUBECONFIG=/tmp/integ-test-kubeconfig.yaml

# List workflows
kubectl get workflows -n integ-test

# Describe a workflow
kubectl describe workflow <workflow-name> -n integ-test

# Get workflow YAML
kubectl get workflow <workflow-name> -n integ-test -o yaml

# Check Argo controller
kubectl get pods -n argo
kubectl logs -n argo deployment/workflow-controller

# Delete stuck workflows
kubectl delete workflow --all -n integ-test
```

## Debugging Workflow Expressions

### Test expressions in isolation

```typescript
test("isolate expression", async () => {
  // Start with simplest possible expression
  let result = await submitProbe({
    expression: "'hello'",
  });
  expect(result.globalOutputs.result).toBe("hello");
  
  // Add complexity incrementally
  result = await submitProbe({
    inputs: { x: "5" },
    expression: "inputs.parameters.x",
  });
  expect(result.globalOutputs.result).toBe("5");
  
  // Add function call
  result = await submitProbe({
    inputs: { x: "5" },
    expression: "asInt(inputs.parameters.x)",
  });
  console.log("asInt result:", result.globalOutputs.result);
  
  // Add arithmetic
  result = await submitProbe({
    inputs: { x: "5" },
    expression: "string(asInt(inputs.parameters.x) * 2)",
  });
  expect(result.globalOutputs.result).toBe("10");
});
```

### Check parameter references

```typescript
test("verify parameter access", async () => {
  const result = await submitProbe({
    inputs: {
      param1: "value1",
      param2: "value2",
    },
    expression: "inputs.parameters.param1 + ',' + inputs.parameters.param2",
  });
  
  expect(result.globalOutputs.result).toBe("value1,value2");
});
```

## Getting Help

### Check logs

```bash
# Jest output
npm run test:integ 2>&1 | tee test-output.log

# Docker logs
docker logs $(docker ps -q --filter ancestor=rancher/k3s) 2>&1 | tee k3s.log

# Argo controller logs
export KUBECONFIG=/tmp/integ-test-kubeconfig.yaml
kubectl logs -n argo deployment/workflow-controller --tail=100
```

### Verify environment

```bash
# Check Docker
docker version
docker info | grep -i "operating system"

# Check Node.js
node --version
npm --version

# Check dependencies
npm list @testcontainers/k3s
npm list @kubernetes/client-node

# Check test files
find tests/integ -name "*.test.ts" -type f
```

### Reset everything

```bash
# Stop all K3s containers
docker stop $(docker ps -q --filter ancestor=rancher/k3s)
docker rm $(docker ps -aq --filter ancestor=rancher/k3s)

# Clean temp files
rm -f /tmp/integ-test-*

# Reinstall dependencies
rm -rf node_modules package-lock.json
npm install

# Run tests
npm run test:integ
```

## Known Limitations

1. **Privileged containers required**: K3s needs privileged mode. Won't work in:
   - Rootless Docker
   - Some CI environments with security restrictions
   - Docker-in-Docker without `--privileged`

2. **Sequential execution only**: Tests must run with `maxWorkers: 1` due to shared cluster.

3. **No parallel test runs**: Can't run multiple `npm run test:integ` simultaneously.

4. **Resource requirements**: Needs ~1GB RAM and 1-2 CPUs for K3s + Argo.

## Still Having Issues?

1. Check the main README: `tests/integ/README.md`
2. Review the implementation plan: `argoBuilderIntegTestPlan.md`
3. Look at working test examples in `tests/integ/contracts/`
4. Check Argo Workflows documentation: https://argo-workflows.readthedocs.io/
