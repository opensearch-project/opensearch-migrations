# Manually Testing Approval Gates

This is a developer reference for exercising the approval-gate TUI/CLI **by hand**, without
running a full migration pipeline. It reaches both step-approval gates
(`captureproxysetup`, `documentbackfill`) in seconds.

> Automated coverage already exists: the `Test0003ApprovalGateIntegration` integration test
> (`migrationConsole/lib/integ_test/integ_test/test_cases/basic_tests.py`) runs the migration with
> `skip-approvals=false`, blocks on the real `evaluatemetadata` gate, approves it via
> `workflow approve step --all`, and verifies the migration completes. Use that for CI/regression
> coverage. The workflow below is purely a fast, manual dev aid — copy it into a file to run it.

## Why a standalone workflow

The TUI discovers gates from **running Argo Workflow nodes** (`templateName: waitforuserapproval`),
not just from `ApprovalGate` CRDs. So `kubectl apply`-ing gate CRDs alone shows nothing — you need a
workflow that actually reaches `waitforuserapproval` nodes. This workflow does exactly that while
short-circuiting all the real work:

- Creates the two step gates up front in the `Created` phase, exactly as `configProcessor` does
  before a real run (apply, then a separate `status` subresource patch — Kubernetes strips the
  `status` field on a normal write).
- Uses the real `resource-management:waitforuserapproval` template for the gate steps, so the TUI
  recognizes the nodes exactly as in production.
- Uses `document-bulk-load:donothing` for every upstream step (kafka, proxy, snapshot, metadata),
  so it needs no container images and completes those steps instantly.

## The workflow

Save this as `devApprovalGateTest.yaml`:

```yaml
# Dev-only workflow for testing the appearance of approval gates in the TUI without
# running a full migration pipeline.
apiVersion: argoproj.io/v1alpha1
kind: Workflow
metadata:
  name: dev-approval-gate-test
  namespace: ma
  labels:
    migrations.opensearch.org/dev-test: "approval-gates"
spec:
  serviceAccountName: argo-workflow-executor
  entrypoint: test-approval-gates

  templates:
    - name: test-approval-gates
      steps:
        # Create both step approval gates upfront with phase=Created,
        # mirroring what configProcessor does before a real migration run.
        # Both can be created in parallel since they are independent.
        - - name: create-proxy-gate
            template: create-approval-gate
            arguments:
              parameters:
                - name: gateName
                  value: captureproxysetup.dev-test-proxy
          - name: create-backfill-gate
            template: create-approval-gate
            arguments:
              parameters:
                - name: gateName
                  value: documentbackfill.dev-test-backfill

        # Proxy setup: skip all real work, go straight to the approval gate.
        - - name: mock-proxy-setup
            templateRef:
              name: document-bulk-load
              template: donothing

        - - name: approve-proxy-setup
            templateRef:
              name: resource-management
              template: waitforuserapproval
            arguments:
              parameters:
                - name: resourceName
                  value: captureproxysetup.dev-test-proxy

        # Snapshot + metadata: skip all real work, go straight to the backfill gate.
        - - name: mock-snapshot-and-metadata
            templateRef:
              name: document-bulk-load
              template: donothing

        - - name: approve-backfill
            templateRef:
              name: resource-management
              template: waitforuserapproval
            arguments:
              parameters:
                - name: resourceName
                  value: documentbackfill.dev-test-backfill

    # Creates an ApprovalGate CRD with status.phase=Created.
    # `action: apply` creates or no-ops if it already exists.
    # The status subresource is set in a follow-up patch so it survives
    # Kubernetes stripping unknown fields on the main resource write.
    - name: create-approval-gate
      inputs:
        parameters:
          - name: gateName
      steps:
        - - name: apply
            template: apply-approval-gate
            arguments:
              parameters:
                - name: gateName
                  value: "{{inputs.parameters.gateName}}"
        - - name: set-created-phase
            template: patch-approval-gate-status
            arguments:
              parameters:
                - name: gateName
                  value: "{{inputs.parameters.gateName}}"
                - name: phase
                  value: Created

    - name: apply-approval-gate
      inputs:
        parameters:
          - name: gateName
      resource:
        action: apply
        manifest: |
          apiVersion: migrations.opensearch.org/v1alpha1
          kind: ApprovalGate
          metadata:
            name: "{{inputs.parameters.gateName}}"
            labels:
              migrations.opensearch.org/dev-test: "approval-gates"
          spec: {}

    - name: patch-approval-gate-status
      inputs:
        parameters:
          - name: gateName
          - name: phase
      resource:
        action: patch
        flags:
          - --type
          - merge
          - --subresource=status
        manifest: |
          apiVersion: migrations.opensearch.org/v1alpha1
          kind: ApprovalGate
          metadata:
            name: "{{inputs.parameters.gateName}}"
          status:
            phase: "{{inputs.parameters.phase}}"
```

## Running it

Submit from the host (`argo` CLI or plain `kubectl` both work):

```bash
kubectl create -f devApprovalGateTest.yaml
# or, if you have the Argo CLI:
argo submit -n ma devApprovalGateTest.yaml --watch
```

The workflow image is not bundled in the migration-console pod, so to submit from inside it, copy
the file in or pipe it via stdin:

```bash
kubectl exec -n ma migration-console-0 -- kubectl create -f - < devApprovalGateTest.yaml
```

Open the TUI (the workflow uses the fixed name `dev-approval-gate-test`, so pass it explicitly —
`workflow manage` defaults to `migration-workflow`):

```bash
workflow manage --workflow-name dev-approval-gate-test
```

Approve from the TUI, or via CLI:

```bash
workflow approve step --list
workflow approve step captureproxysetup.dev-test-proxy   # reached within seconds
workflow approve step documentbackfill.dev-test-backfill # reached after the first approval
```

Because the workflow uses a fixed name, delete it before resubmitting:

```bash
kubectl delete workflow -n ma dev-approval-gate-test
```

Clean up the workflow and its gates afterwards:

```bash
kubectl delete workflow -n ma dev-approval-gate-test
kubectl delete approvalgates -n ma -l migrations.opensearch.org/dev-test=approval-gates
```
