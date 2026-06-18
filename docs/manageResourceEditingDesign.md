# Manage Resource Editing and Resubmission

`workflow manage --resource-view` is the resource-centered place to inspect deployed state, pending workflow rollout state, and saved config that has not been submitted yet. It also provides the schema-guided edit path for workflow YAML without requiring users to remember the full YAML structure.

The edit flow is intentionally indirect:

```text
manage UI
  -> pending workflow YAML
  -> config-processor validation and resource projection
  -> workflow submit
  -> MigrationRun.resolvedConfig
  -> migration CR specs
  -> workflow convergence
```

Live migration CR specs are not edited directly. The pending YAML remains the user-owned source for edits until the user submits a workflow.

## Ownership Boundaries

TypeScript owns workflow-config semantics. Python owns presentation, Kubernetes reads, and command orchestration.

The config-processor owns:

- user-schema parsing and validation;
- Zod refinements and unified-schema validation;
- JSON Schema descriptions, UI hints, and reference options;
- edit-state DTO construction;
- typed edit operations;
- strict resource projection for submit and stored `MigrationRun` data;
- loose resource projection for incomplete saved configs;
- validation diagnostics attached to stable config paths.

The Python/Textual manage code owns:

- building the live resource tree from Kubernetes CRs;
- calling config-processor commands through `ScriptRunner`;
- rendering generic DTOs;
- saving pending YAML through `WorkflowConfigStore`;
- delegating workflow submit/reset to the existing command paths.

Python does not parse workflow YAML into domain resources and does not translate the edit tree back into resources. The only best-effort workflow-config projection is the TS loose projection.

## Process Model

Manage uses one-shot Node invocations. There is no Node.js daemon.

TS runs at committed interaction boundaries:

- entering edit mode, to build the editable tree;
- committing an edit operation, such as scalar set, boolean toggle, union choice, add, or remove;
- debounced whole-config validation while a scalar modal is open;
- loading pending resource overlays for the non-edit resource view;
- immediately before submit.

TS does not run on cursor movement, normal manage polling refreshes, or every keystroke. `s` and `Ctrl+s` save the current draft YAML that TS already produced for the last committed operation.

A long-lived helper remains only a latency optimization. If added, it is a manage-scoped stdio child process with the same JSON contracts, not a cluster service and not a network daemon.

## Resource View Data Model

The resource view combines three projections:

- `deployed`: live Kubernetes CR specs;
- `submitted`: the latest submitted `MigrationRun.spec.resolvedConfig`;
- `pending`: the saved pending YAML in `WorkflowConfigStore`.

Submitted config is strict because it comes from a submitted workflow run. Pending config is loose because users often save incomplete YAML while building a workflow.

The load path is:

```text
build_resource_tree(namespace)
  -> live deployed CR ResourceNode objects
mark_not_configured_groups(workflow tree)
  -> live workflow skipped groups
ConfigEditService.load_resource_config_snapshots()
  -> submitted strict resolved config
  -> pending loose resolved config
apply_config_overlays()
  -> deployed/submitted/pending value comparison
  -> virtual pending-only resources
  -> source/target/Kafka virtual config rows
  -> diagnostics on affected resources
```

If a live workflow marks a group as `(not configured)` and pending config adds a resource to that group, the placeholder is cleared and the pending resource is rendered.

## Strict and Loose Projection

`resolveMigrationResources` exposes two validation modes:

```bash
index.js resolveMigrationResources --user-config <file|-> --validation-mode strict
index.js resolveMigrationResources --user-config <file|-> --validation-mode loose
```

Strict mode is the submit gate. It validates the entire user config, transforms it into workflow config, validates output, and then builds resolved migration resources. Invalid input exits non-zero.

Loose mode is for manage rendering. It first attempts the strict path. If strict projection succeeds, loose mode returns the normal resolved resource output with `projectionMode: "loose"` and `projectionComplete: true`. If strict projection fails, the TS projector walks known schema-owned scopes and returns resources with stable identity, known parameters, and diagnostics for missing or invalid pieces.

Loose output uses the same outer DTO as strict output and may include `consoleResources` for source, target, and Kafka virtual rows:

```json
{
  "formatVersion": 1,
  "workflowName": "migration-workflow",
  "projectionMode": "loose",
  "projectionComplete": false,
  "validation": {
    "mode": "loose",
    "valid": false,
    "errors": ["..."],
    "diagnostics": [
      {
        "severity": "required",
        "path": ["traffic", "proxies", "cap", "proxyConfig"],
        "message": "Invalid input: expected object, received undefined"
      }
    ]
  },
  "resources": [
    {
      "apiVersion": "migrations.opensearch.org/v1alpha1",
      "kind": "CaptureProxy",
      "name": "cap",
      "parameters": {"dependsOn": ["cap-topic"]},
      "projectionComplete": false,
      "diagnostics": [
        {
          "severity": "required",
          "path": ["traffic", "proxies", "cap", "proxyConfig"],
          "message": "Invalid input: expected object, received undefined"
        }
      ]
    }
  ],
  "consoleResources": {
    "formatVersion": 1,
    "sources": [],
    "targets": [],
    "kafkas": [],
    "consumerGroups": []
  }
}
```

Loose projection currently covers:

- workflow-managed Kafka clusters;
- captured traffic topics from proxies and S3 sources;
- capture proxies with partial `proxyConfig`;
- traffic replayers with partial `replayerConfig`;
- data snapshots when snapshot identity is present;
- snapshot migrations when source, target, snapshot, and migration identity are present;
- virtual source, target, and Kafka console resources.

Source and target endpoint completeness is surfaced in loose console resources even when the strict schema path does not emit that diagnostic. This keeps `endpoint: <required>` from appearing healthy in the non-edit resource view.

## Edit Mode

`workflow manage --resource-view` binds `e` to enter config edit mode. Edit mode replaces the resource tree with a generic `Workflow Config Edit` tree from `EditStateV1`.

The config-processor commands are:

```bash
index.js editConfig state --pending-config <file|->
index.js editConfig apply --pending-config <file|-> --operation <json-file|->
```

Python sends typed operations and renders the returned DTO. It does not know concrete schema structure beyond generic row types, hints, diagnostics, and command metadata.

Current operation types are:

```ts
type EditOperation =
  | { op: "set"; path: string[]; value: unknown }
  | { op: "removeConfig"; path: string[] }
  | { op: "add"; path: string[]; value: unknown };
```

The edit DTO carries:

- row path and label;
- value kind (`object`, `record`, `array`, `union`, `enum`, `boolean`, `scalar`, `command`);
- schema descriptions;
- required flags;
- diagnostics;
- aggregate status counts;
- UI hints;
- reference options;
- union variants;
- add/remove command metadata.

Schema hints are authored in `userSchemas.ts` with `.uiHint(...)`, exported to JSON Schema as `x-ui-hint`, copied into `EditStateV1.inputHint`, and rendered generically by Python.

Reference hints drive picker-style editing for source cluster, target cluster, Kafka cluster, and captured traffic references.

## Key Bindings

Resource view:

| Key | Behavior |
| --- | --- |
| `e` | Enter config edit mode |
| `s` | Submit saved workflow config |
| `v` | Cycle value mode |
| `m` | Toggle terminal mouse handling so text can be selected, then restore it |
| `r` | Refresh |
| `q` | Quit |
| `Left` / `Right` | Collapse or expand selected tree row |
| `Enter` | Activate the selected row, including approval confirmations |

Edit mode:

| Key | Behavior |
| --- | --- |
| `Enter` | Edit scalar, toggle boolean, open union picker, or start command row |
| `a` | Start selected synthetic add row |
| `s` / `Ctrl+s` | Save pending YAML |
| `Esc` | Confirm discard if dirty, otherwise leave edit mode |
| `Del` / `Backspace` | Confirm and remove a removable config entry |
| `v` | Cycle value mode |
| `t` | Cycle status mode |
| `m` | Toggle terminal mouse handling so text can be selected, then restore it |
| `Left` / `Right` | Collapse or expand selected tree row |
| `Space` | Toggle boolean rows |
| `?` | Show selected field/resource description |

Synthetic add rows expose add actions and never expose delete bindings.

## Status and Color Rules

Rows use one effective status color, selected by highest priority from the row and relevant descendants.

Priority:

1. `error` / `blocked`
2. `required`
3. `gated`
4. `warning`
5. `changed`
6. normal deployed state

Resource-view rollout colors:

- green: saved pending config differs from submitted config and is not submitted yet;
- grey: submitted config differs from deployed CR state and is still rolling out;
- default foreground: deployed state matches the submitted projection and there is no newer saved config difference;
- yellow/red/magenta: validation or policy diagnostics override rollout labels for that row.

The edit tree propagates aggregate status counts upward through branches. The resource tree expands resources and ancestors that contain config diffs or diagnostics. Resource-row labels show the highest-priority diagnostic when present, and diagnostic detail lines appear beneath the resource.

## Value Modes

Resource view has four value modes:

| Mode | Meaning |
| --- | --- |
| `All` | Show deployed, submitted/pending-rollout, and to-submit values on the same changed field line |
| `Deployed` | Show only values present in live CRs |
| `Pending` | Show values from the current submitted workflow projection |
| `To Submit` | Show values from saved pending YAML |

In `All`, changed fields render as phase-separated segments:

```text
listenPort: deployed=9200 | pending=9201 | to-submit=9202
```

Pending-only resources disappear in `Deployed` and `Pending` modes, then appear in `To Submit` mode. Resources marked for deletion appear in modes where they still exist and disappear in modes where the projected resource is absent.

## Non-Edit Resource View Examples

Loose pending config with missing source endpoint and missing proxy config:

```text
Migration Status
├── Workflow Configuration
│   └── Sources
│       └── ○ aux-source (Pending Config) (required)
│           └── required: sourceClusters.aux-source.endpoint: Required field is missing.
└── Live Traffic Migration
    ├── Capture
    │   └── ○ cap (Pending Config) (required)
    │       ├── required: traffic.proxies.cap.proxyConfig: Invalid input: expected object, received undefined
    │       └── Depends on: cap-topic
    └── Buffer
        └── ○ default (Pending Config) (to submit)
            ├── version: deployed=<absent> | pending=<absent> | to-submit=4.0.0
            └── ○ cap-topic (Pending Config) (to submit)
                ├── topicName: deployed=<absent> | pending=<absent> | to-submit=cap
                ├── partitions: deployed=<absent> | pending=<absent> | to-submit=1
                └── replicas: deployed=<absent> | pending=<absent> | to-submit=1
```

Same saved config in `Deployed` mode:

```text
Migration Status
├── Capture
│   └── (not configured)
└── Replay
    └── (not configured)
```

Same saved config in `To Submit` mode:

```text
Migration Status
├── Workflow Configuration
│   └── Sources
│       └── ○ aux-source (Pending Config) (required)
└── Live Traffic Migration
    ├── Capture
    │   └── ○ cap (Pending Config) (required)
    └── Buffer
        └── ○ default (Pending Config) (to submit)
            └── ○ cap-topic (Pending Config) (to submit)
```

Submitted workflow still rolling out while saved config has another edit:

```text
Migration Status
└── Live Traffic Migration
    └── Replay
        └── ▶ replay-a
            ├── speedupFactor: deployed=1.0 | pending=1.5 | to-submit=2.0
            ├── tupleMaxFileSizeMb: deployed=128 | pending=128 | to-submit=256
            └── Workflow progress:
                └── Waiting for: replay-a
```

## Submit Semantics

Submit always uses strict validation.

When the user submits from manage:

1. save pending YAML;
2. run strict config-processor validation/projection;
3. run credential and secret checks;
4. show validation, policy, and gated/impossible-change failures;
5. replace the running workflow through the existing submit path.

Loose projection never makes a config submittable. It only makes incomplete saved work visible and navigable.

## Source, Target, and Kafka Virtual Resources

Source, target, and direct Kafka client configs are virtual resources in manage. They are not Kubernetes CRs and are not reset like real migration resources.

Virtual resources exist so users can see configuration changes that affect consumers:

- source endpoint/auth/version changes;
- target endpoint/auth/version changes;
- direct Kafka client changes;
- workflow-managed Kafka cluster config changes.

Delete semantics differ:

- deleting an unreferenced source/target/Kafka config removes pending YAML only;
- deleting a referenced virtual config is blocked by validation/reference diagnostics;
- real deployed resources can require reset/recreate depending on policy and lifecycle state.

## Maintainability Guide

Adding a new user-facing field:

1. Add the field, description, constraints, refinements, and UI hints in `userSchemas.ts`.
2. Add or update config transformation logic in `migrationConfigTransformer.ts` only if the workflow-ready config changes.
3. Add resource projection metadata or explicit resolved-resource mapping if the field affects a migration CR spec.
4. Add a display-field hint or update the temporary Python `SPEC_DISPLAY_FIELDS` table if the field should be visible before display metadata is generated from TS.
5. Add TS validation/projection tests and focused Python rendering tests only when the rendered DTO shape changes.

Adding a new resource type:

1. Add strict projection in `resolvedMigrationResources.ts`.
2. Add loose identity and best-effort projection in the same TS module.
3. Add console/virtual projection if the resource is not a Kubernetes CR.
4. Add `RESOURCE_KIND_TO_PLURAL` and section/group placement in Python.
5. Add focused tests for strict projection, loose incomplete projection, and resource-tree visibility modes.

Adding a new validation constraint:

1. Prefer Zod/refinement logic in `userSchemas.ts`.
2. Ensure `validationForConfig` emits a path-specific diagnostic.
3. Attach diagnostics to loose projected resources through path prefixes.
4. Add one TS test for the diagnostic and one UI/resource-tree test if the diagnostic should be visible outside edit mode.

The rough edge that remains is display metadata. Python still has `SPEC_DISPLAY_FIELDS` for resource details. That is a temporary presentation table. The durable shape is generated TS metadata that identifies user-visible projected fields, their labels, descriptions, and change restrictions.

## Tests

Current focused coverage includes:

- strict resolved resource projection;
- loose resolved resource projection for incomplete config;
- loose CLI behavior without non-zero exit on validation errors;
- pending snapshot loading with `--validation-mode loose`;
- virtual pending-only resources in non-edit resource view;
- resource diagnostics rendered in the Textual tree;
- value-mode visibility for pending-only resources;
- add-row bindings that do not expose delete;
- scalar/boolean edit interactions;
- save and submit wiring.

Useful next tests are:

- source/target virtual changes repeated only under affected consumers;
- group/section color propagation for resource-view diagnostics;
- strict submit blocking when loose pending projection is incomplete;
- policy preview rendering for gated, blocked, and non-decreasing invariant failures;
- canonical inverse generation from `MigrationRun` when pending YAML is absent.

## Remaining Work

The current implementation is maintainable because schema semantics stay in TS and Python remains a renderer/orchestrator. The important remaining work is to remove the last presentation-specific duplication:

- generate resource display metadata from TS instead of `SPEC_DISPLAY_FIELDS`;
- return `editable`, `removable`, `renameable`, and `addable` metadata from TS instead of keeping small Python path checks;
- add resource-focused edit entry so pressing `e` on a resource opens the relevant subtree;
- add a scoped YAML editor for complex nested values;
- add duplicate/rename operations;
- wire strict preview and policy summary directly into the submit confirmation;
- persist original input config provenance in new `MigrationRun` records so inverse rendering is canonical and non-lossy.
