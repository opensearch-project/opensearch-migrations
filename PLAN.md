# Plan: Argo Artifact Support for Workflow Outputs

## Problem

Workflow output parameters (e.g. `statusOutput`, `overriddenPhase`, `monitorResult`) are stored
inline in the Workflow CR in etcd via `outputs.parameters[].valueFrom.path`. This causes two
problems:

1. **etcd size limit** — Outputs can be ~2MB. etcd has a 1MB object limit. Multiple steps with
   large outputs bloat the CR until it can't be saved.
2. **Node eviction** — If a node is reclaimed (Karpenter spot, preemption), the pod disappears
   before the Argo wait container reads the file. The output is lost.

## Solution

Use Argo's `outputs.artifacts` instead of `outputs.parameters` for large outputs. Artifacts are
uploaded to S3 by the wait container; only a small S3 key reference is stored in the Workflow CR.
Downstream steps receive artifacts as input files mounted at a path. The Python CLI retrieves
artifact content via the Argo Server artifact API (`GET /api/v1/workflows/{ns}/{name}/artifacts/{nodeId}/{artifactName}`).

## Current State

- `containerBuilder.ts` has `addPathOutput(name, path, typeToken)` → emits `outputs.parameters`
  with `valueFrom.path`
- `argoResourceRenderer.ts` `formatTemplate` renders `outputs` via `formatOutputParameters` — only
  handles parameters, no artifact support
- `templateBodyBuilder.ts` `getFullTemplateScope()` returns `{ inputs, outputs, body, ... }` where
  `outputs` is `OutputParametersRecord` (parameters only)
- Python `tree_utils.py` `get_step_status_output()` reads `node.outputs.parameters` for
  `statusOutput`
- Python `manage_injections.py` filters `outputs.parameters` for `statusOutput`/`overriddenPhase`
- No `artifactRepository` is configured in the Argo Workflows Helm values
- An S3 bucket already exists (`migrations-default-{account}-{stage}-{region}`) with ConfigMap
  `migrations-default-s3-config` providing endpoint/region/bucket

---

## Layer 1: Helm — Configure Argo Artifact Repository

### 1.1 Add `artifactRepository` to Argo Workflows values

**File:** `deployment/k8s/charts/aggregates/migrationAssistantWithArgo/values.yaml`

**Change:** Under `charts.argo-workflows.values`, add:

```yaml
controller:
  # existing controller config...
  workflowDefaults:
    spec:
      archiveLogs: true
artifactRepository:
  archiveLogs: true
  s3:
    bucket: '{{- printf "migrations-default-%s-%s-%s" ... }}'
    endpoint: localstack.{{ .Release.Namespace }}.svc.cluster.local:4566
    insecure: true
    keyFormat: "argo-artifacts/{{workflow.name}}/{{pod.name}}"
    accessKeySecret:
      name: localstack-creds
      key: accessKey
    secretKeySecret:
      name: localstack-creds
      key: secretKey
```

**Problem:** The Argo Helm chart's `artifactRepository` value is static YAML — it can't use Helm
template expressions for the bucket name. We need to either:
- (a) Create a ConfigMap with the artifact repository config and reference it, or
- (b) Use a fixed bucket name convention, or
- (c) Template it via a Helm helper that generates the Argo controller configmap directly

**Decision:** Use approach (c) — add a template that patches the `argo-cm` ConfigMap post-install
with the artifact repository config, using the same bucket name from `migrations-default-s3-config`.

**Test:** After `helm template`, verify the generated ConfigMap/Job contains the correct S3 config.
Manual: deploy to local k8s, submit a workflow with an artifact output, verify the artifact appears
in the S3 bucket.

### 1.2 Override for EKS (no LocalStack endpoint)

**File:** `deployment/k8s/charts/aggregates/migrationAssistantWithArgo/valuesEks.yaml`

**Change:** Override `artifactRepository.s3` to remove `endpoint`/`insecure` and use IAM-based
auth (no accessKeySecret/secretKeySecret).

**Test:** `helm template` with EKS values produces artifact config without LocalStack endpoint.

---

## Layer 2: TypeScript Builders — Artifact Output Support

### 2.1 Add `OutputArtifactDef` type

**File:** `orchestrationSpecs/packages/argo-workflow-builders/src/models/parameterSchemas.ts`

**Test first** (`tests/unit/params.test.ts`):
```typescript
it('OutputArtifactDef stores name and path', () => {
    const def: OutputArtifactDef = { name: 'statusOutput', path: '/tmp/status-output.txt' };
    expect(def.name).toBe('statusOutput');
    expect(def.path).toBe('/tmp/status-output.txt');
});
```

**Change:** Add after `OutputParamDef`:
```typescript
export type OutputArtifactDef = {
    name: string;
    path: string;
    archive?: { none: {} };
};

export type OutputArtifactsRecord = Record<string, OutputArtifactDef>;
```

### 2.2 Carry artifact outputs through `TemplateBodyBuilder`

**File:** `orchestrationSpecs/packages/argo-workflow-builders/src/models/templateBodyBuilder.ts`

**Test first** (`tests/unit/selfStep.test.ts` or new `tests/unit/artifactOutput.test.ts`):
```typescript
it('getFullTemplateScope includes outputArtifacts when set', () => {
    // Build a minimal container template with addArtifactOutput
    // Assert getFullTemplateScope().outputArtifacts has the entry
});
```

**Change:**
- Add `outputArtifacts: OutputArtifactsRecord` field to `TemplateBodyBuilder` constructor
- Thread it through `rebind` / `retryableRebind` signatures
- Include in `getFullTemplateScope()` return: `outputArtifacts: this.outputArtifacts`

### 2.3 Add `addArtifactOutput` to `ContainerBuilder`

**File:** `orchestrationSpecs/packages/argo-workflow-builders/src/models/containerBuilder.ts`

**Test first** (`tests/unit/podConfig.test.ts` — add a new describe block):
```typescript
describe('Artifact Outputs', () => {
    it('should render artifact output in template', () => {
        const wf = WorkflowBuilder.create({ k8sResourceName: 'test-artifact', serviceAccountName: 'sa' })
            .addTemplate('test', t => t
                .addContainer(c => c
                    .addImageInfo('alpine', 'IfNotPresent')
                    .addCommand(['echo', 'hello'])
                    .addResources(EXAMPLE_RESOURCES)
                    .addArtifactOutput('result', '/tmp/result.txt')
                )
            )
            .getFullScope();

        const rendered = renderWorkflowTemplate(wf);
        const template = rendered.spec.templates.find((t: any) => t.name === 'test');
        expect(template.outputs.artifacts).toEqual([
            { name: 'result', path: '/tmp/result.txt', archive: { none: {} } }
        ]);
    });
});
```

**Change:** Add method:
```typescript
addArtifactOutput(name: string, path: string): ContainerBuilder<...> {
    // Store in a new outputArtifacts record, similar to how addPathOutput works
}
```

### 2.4 Render artifact outputs in `argoResourceRenderer.ts`

**File:** `orchestrationSpecs/packages/argo-workflow-builders/src/renderers/argoResourceRenderer.ts`

**Test first** (the test from 2.3 already covers this — `renderWorkflowTemplate` calls
`formatTemplate` which must now emit `outputs.artifacts`).

**Change:** In `formatTemplate`, after the existing `outputs: formatOutputParameters(...)` line:
```typescript
const outputArtifacts = formatOutputArtifacts(template.outputArtifacts);
// Merge into the outputs object:
outputs: {
    ...formatOutputParameters(template.outputs),
    ...(outputArtifacts ? { artifacts: outputArtifacts } : {})
}
```

Add `formatOutputArtifacts`:
```typescript
function formatOutputArtifacts(artifacts: OutputArtifactsRecord | undefined) {
    if (!artifacts || Object.keys(artifacts).length === 0) return undefined;
    return Object.values(artifacts).map(a => ({
        name: a.name,
        path: a.path,
        archive: a.archive ?? { none: {} }
    }));
}
```

### 2.5 Render artifact inputs for step-to-step passing

When a downstream step references an artifact output from an upstream step, Argo needs:
```yaml
arguments:
  artifacts:
    - name: statusOutput
      from: "{{steps.runConsole.outputs.artifacts.statusOutput}}"
```

And the receiving template needs:
```yaml
inputs:
  artifacts:
    - name: statusOutput
      path: /tmp/statusOutput  # where to mount it
```

**Test first** (`tests/unit/workflowStep.test.ts` — new test):
```typescript
it('should pass artifact from one step to another', () => {
    const wf = WorkflowBuilder.create({ k8sResourceName: 'test-artifact-pass', serviceAccountName: 'sa' })
        .addTemplate('producer', t => t
            .addContainer(c => c
                .addImageInfo('alpine', 'IfNotPresent')
                .addCommand(['sh', '-c', 'echo hello > /tmp/out.txt'])
                .addResources(EXAMPLE_RESOURCES)
                .addArtifactOutput('data', '/tmp/out.txt')
            )
        )
        .addTemplate('consumer', t => t
            .addArtifactInput('data', '/tmp/input.txt')
            .addContainer(c => c
                .addImageInfo('alpine', 'IfNotPresent')
                .addCommand(['cat', '/tmp/input.txt'])
                .addResources(EXAMPLE_RESOURCES)
            )
        )
        .addTemplate('main', t => t
            .addSteps(s => s
                .addStep('produce', INTERNAL, 'producer', c => c.register({}))
                .addStep('consume', INTERNAL, 'consumer', c => c.register({
                    // artifact passing syntax TBD
                }))
            )
        )
        .getFullScope();

    const rendered = renderWorkflowTemplate(wf);
    // Assert consumer step has arguments.artifacts referencing producer output
});
```

**Files to change:**
- `templateBuilder.ts` — Add `addArtifactInput(name, mountPath)` method
- `taskBuilder.ts` / step registration — Support passing artifact references in `register()`
- `argoResourceRenderer.ts` — Render `arguments.artifacts` on steps/tasks, render
  `inputs.artifacts` on templates

**Note:** This is the most complex sub-task. If step-to-step artifact passing is not needed
immediately (i.e., the consuming step can read from a well-known S3 key instead), this can be
deferred. The critical path is 2.1–2.4 (producing artifacts) + Layer 4 (Python reading them).

### 2.6 Update snapshot tests

**Test:** Run `npm test -- --updateSnapshot` in `migration-workflow-templates` after changing
templates. The `outputMatch.test.ts` file-snapshot tests will capture the new artifact output
fields in the rendered YAML.

---

## Layer 3: Migration Workflow Templates — Use Artifact Outputs

### 3.1 Convert `migrationConsole.ts` outputs to artifacts

**File:** `orchestrationSpecs/packages/migration-workflow-templates/src/workflowTemplates/migrationConsole.ts`

**Test:** The existing `outputMatch.test.ts` snapshot test covers this. After the change, update
snapshots and verify the rendered YAML has `outputs.artifacts` instead of (or alongside)
`outputs.parameters` for `statusOutput` and `overriddenPhase`.

**Change:** In `runMigrationCommandForStatus` template:
```typescript
// Before:
.addPathOutput("statusOutput", "/tmp/status-output.txt", typeToken<string>())
.addPathOutput("overriddenPhase", "/tmp/phase-output.txt", typeToken<string>())

// After:
.addArtifactOutput("statusOutput", "/tmp/status-output.txt")
.addArtifactOutput("overriddenPhase", "/tmp/phase-output.txt")
```

### 3.2 Convert `testMigrationWithWorkflowCli.ts` monitor output to artifact

**File:** `orchestrationSpecs/packages/migration-workflow-templates/src/workflowTemplates/testMigrationWithWorkflowCli.ts`

**Test:** Snapshot test via `outputMatch.test.ts`.

**Change:** In `monitorWorkflow` template:
```typescript
// Before:
.addPathOutput("monitorResult", "/tmp/outputs/monitorResult", typeToken<string>(), ...)

// After:
.addArtifactOutput("monitorResult", "/tmp/outputs/monitorResult")
```

**Note:** The `evaluateWorkflowResult` template receives `monitorResult` as an input parameter.
This needs to change to receive it as an artifact input (Layer 2.5) or the monitor script needs
to write to S3 directly and pass just the key as a parameter.

---

## Layer 4: Python CLI — Read Artifacts via Argo API

### 4.1 Add artifact content fetcher

**File:** `migrationConsole/lib/console_link/console_link/workflow/services/workflow_service.py`

**Test first** (`tests/workflow-tests/test_workflow_service.py`):
```python
def test_get_artifact_content_success(self, mock_api_class):
    """Test fetching artifact content from Argo API."""
    service = WorkflowService()
    with patch('requests.get') as mock_get:
        mock_get.return_value.status_code = 200
        mock_get.return_value.text = "snapshot completed successfully"
        result = service.get_artifact_content(
            workflow_name="my-wf",
            node_id="node-abc",
            artifact_name="statusOutput",
            namespace="ma",
            argo_server="http://argo:2746"
        )
        assert result == "snapshot completed successfully"
        mock_get.assert_called_once_with(
            "http://argo:2746/api/v1/workflows/ma/my-wf/artifacts/node-abc/statusOutput",
            headers=ANY, verify=True
        )

def test_get_artifact_content_returns_none_on_404(self, mock_api_class):
    service = WorkflowService()
    with patch('requests.get') as mock_get:
        mock_get.return_value.status_code = 404
        result = service.get_artifact_content(
            workflow_name="my-wf", node_id="node-abc",
            artifact_name="statusOutput", namespace="ma",
            argo_server="http://argo:2746"
        )
        assert result is None
```

**Change:** Add method to `WorkflowService`:
```python
def get_artifact_content(self, workflow_name, node_id, artifact_name,
                         namespace, argo_server, token=None, insecure=False):
    headers = self._prepare_headers(token)
    url = f"{argo_server}/api/v1/workflows/{namespace}/{workflow_name}/artifacts/{node_id}/{artifact_name}"
    resp = requests.get(url, headers=headers, verify=not insecure)
    return resp.text if resp.status_code == 200 else None
```

### 4.2 Update `get_step_status_output` to check artifacts

**File:** `migrationConsole/lib/console_link/console_link/workflow/tree_utils.py`

**Test first** (`tests/test_tree_filtering.py` or new `tests/test_tree_utils.py`):
```python
def test_get_step_status_output_from_artifact():
    """When statusOutput is an artifact (no value in parameters), return artifact marker."""
    workflow_data = {
        "status": {
            "nodes": {
                "node-1": {
                    "outputs": {
                        "parameters": [],
                        "artifacts": [
                            {"name": "statusOutput", "s3": {"key": "argo-artifacts/wf/pod/statusOutput"}}
                        ]
                    },
                    "children": []
                }
            }
        }
    }
    result = get_step_status_output(workflow_data, "node-1")
    # Returns a sentinel or the artifact metadata so the caller knows to fetch it
    assert result is not None
    assert result.artifact_name == "statusOutput"
    assert result.node_id == "node-1"
```

**Change:** After checking `outputs.parameters`, also check `outputs.artifacts`:
```python
# Check artifacts
artifacts = outputs.get("artifacts", [])
for artifact in artifacts:
    if artifact.get("name") == "statusOutput":
        return ArtifactRef(node_id=current_node_id, artifact_name="statusOutput")
```

Introduce a small dataclass:
```python
@dataclass
class ArtifactRef:
    node_id: str
    artifact_name: str
```

### 4.3 Update `status.py` to resolve artifact references

**File:** `migrationConsole/lib/console_link/console_link/workflow/commands/status.py`

**Test first** (`tests/workflow-tests/test_status.py`):
```python
def test_status_resolves_artifact_output(self):
    """When statusOutput is an artifact, status command fetches content from Argo API."""
    # Mock workflow data with artifact output
    # Mock WorkflowService.get_artifact_content to return "snapshot done"
    # Assert the displayed tree shows "snapshot done"
```

**Change:** In `_display_workflow_with_tree` or in `display_workflow_tree`, when
`get_step_status_output` returns an `ArtifactRef`, call
`WorkflowService.get_artifact_content(...)` to fetch the actual content.

### 4.4 Update `manage_injections.py` slim node builder

**File:** `migrationConsole/lib/console_link/console_link/workflow/tui/manage_injections.py`

**Test first** (`tests/workflow-tests/test_manage_injections.py`):
```python
def test_slim_node_preserves_artifact_outputs():
    """Slim node builder keeps artifact entries for statusOutput/overriddenPhase."""
    # Existing test data already has artifacts: [{"name": "main-logs", ...}]
    # Add a test with artifacts: [{"name": "statusOutput", "s3": {"key": "..."}}]
    # Assert slim_nodes[node_id]["outputs"]["artifacts"] contains the statusOutput entry
```

**Change:** In the streaming parser, alongside the parameter filter, also keep relevant artifacts:
```python
"outputs": {
    "parameters": [p for p in node.get("outputs", {}).get("parameters", []) if
                   p['name'] in ('statusOutput', 'overriddenPhase')],
    "artifacts": [a for a in node.get("outputs", {}).get("artifacts", []) if
                  a['name'] in ('statusOutput', 'overriddenPhase')]
}
```

### 4.5 Update `get_node_phase` to handle artifact-based `overriddenPhase`

**File:** `migrationConsole/lib/console_link/console_link/workflow/tree_utils.py`

**Test first:**
```python
def test_get_node_phase_from_artifact():
    """When overriddenPhase is an artifact, return the artifact ref for lazy resolution."""
    node = {
        "phase": "Failed",
        "outputs": {
            "parameters": [],
            "artifacts": [{"name": "overriddenPhase", "s3": {"key": "..."}}]
        }
    }
    phase = get_node_phase(node)
    # Should still return "Failed" since we can't synchronously fetch the artifact here
    # OR: accept an optional resolver callback
    assert phase == "Failed"
```

**Decision:** `overriddenPhase` is always a tiny string (e.g. "Checked"). Keep it as a parameter
output — only convert `statusOutput` (the large one) to an artifact. This avoids needing lazy
resolution in the phase display path.

---

## Implementation Order

1. **2.1** `OutputArtifactDef` type (red → green → refactor)
2. **2.2** Thread artifacts through `TemplateBodyBuilder` (red → green → refactor)
3. **2.3** `addArtifactOutput` on `ContainerBuilder` (red → green → refactor)
4. **2.4** Render artifacts in `argoResourceRenderer` (red → green → refactor)
5. **3.1** Convert `statusOutput` in `migrationConsole.ts` (snapshot test update)
6. **3.2** Convert `monitorResult` in `testMigrationWithWorkflowCli.ts` (snapshot test update)
7. **1.1** Helm artifact repository config (manual test on local k8s)
8. **1.2** EKS values override (helm template test)
9. **4.1** Python artifact content fetcher (unit test)
10. **4.2** `get_step_status_output` artifact fallback (unit test)
11. **4.3** Status command artifact resolution (unit test)
12. **4.4** TUI slim node artifact support (unit test)

Steps 2.5 (step-to-step artifact passing) and 4.5 (`overriddenPhase`) are deferred — see
decisions in their sections.

## Key Decision: Keep `overriddenPhase` as a Parameter

`overriddenPhase` is always a tiny string like `"Checked"`. It's read synchronously in
`get_node_phase()` which is called during tree rendering. Converting it to an artifact would
require async resolution in the display path. Not worth the complexity — keep as
`addPathOutput` (parameter).

Only `statusOutput` and `monitorResult` need to become artifacts (they carry the large payloads).
