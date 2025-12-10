# Implementation Plan

[Overview]
Update fullMigrationWithClusters.yaml to use the migration console image instead of alpine for container templates.

The `fullMigrationWithClusters.yaml` workflow template currently uses `alpine:latest` in two templates (`generate-cluster-names` and `generate-migration-configs`). The `generate-migration-configs` template also installs `jq` via `apk add --no-cache jq`. Since the migration console image already has `jq` installed, we should use it instead of alpine to maintain consistency with other workflow templates and avoid unnecessary package installation at runtime.

[Types]
No type changes required.

This change only involves YAML configuration updates to reference existing image parameters from the configmap.

[Files]
Single file modification required.

**Modified Files:**
- `migrationConsole/lib/integ_test/testWorkflows/fullMigrationWithClusters.yaml`
  - Update `generate-cluster-names` template to use migration console image
  - Update `generate-migration-configs` template to use migration console image
  - Remove `apk add --no-cache jq` command from `generate-migration-configs` since jq is pre-installed

[Functions]
No function changes required.

This is a YAML configuration change only.

[Classes]
No class changes required.

This is a YAML configuration change only.

[Dependencies]
No dependency changes required.

The migration console image already includes `jq` and all required shell utilities.

[Testing]
Verify the workflow template still functions correctly.

- The workflow should be tested by running the integration tests that use `fullMigrationWithClusters.yaml`
- Verify that `generate-cluster-names` template correctly generates cluster names
- Verify that `generate-migration-configs` template correctly generates migration configurations with `jq`

[Implementation Order]
Single-step implementation.

1. **Update `generate-cluster-names` template** (lines ~115-135):
   - Change `image: alpine:latest` to use the migration console image via configmap reference
   - The template already has access to `workflow.parameters.migration-image-configmap`

2. **Update `generate-migration-configs` template** (lines ~175-230):
   - Change `image: alpine:latest` to use the migration console image via configmap reference
   - Remove the `apk add --no-cache jq > /dev/null 2>&1` line since jq is pre-installed on migration console

**Specific Changes:**

For `generate-cluster-names` template, change:
```yaml
container:
  image: alpine:latest
```
to:
```yaml
container:
  image: "{{workflow.parameters.migration-console-image}}"
  imagePullPolicy: "{{workflow.parameters.migration-console-pull-policy}}"
```

And add workflow parameters:
```yaml
- name: migration-console-image
  valueFrom:
    configMapKeyRef:
      name: "{{workflow.parameters.migration-image-configmap}}"
      key: migrationConsoleImage
- name: migration-console-pull-policy
  valueFrom:
    configMapKeyRef:
      name: "{{workflow.parameters.migration-image-configmap}}"
      key: migrationConsolePullPolicy
```

For `generate-migration-configs` template, apply the same image changes and remove:
```yaml
apk add --no-cache jq > /dev/null 2>&1
