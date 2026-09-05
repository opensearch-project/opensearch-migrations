# Workflow Manage Web Parity Inventory

Status: Phases 0-7 complete; logs remain for Phase 8

This inventory identifies how required `workflow manage` capabilities reach the native web
application. A checked ownership item means that the implementation path and test home are
known. It does not mean that the web capability is complete; completion remains governed by
the phase acceptance criteria in
[Workflow Manage Native Web Conversion Plan](manageWebConversionPlan.md).

## Capability Ownership

| Owned | Capability | Current semantic source | Native owner | Delivery phase | Test home |
| --- | --- | --- | --- | --- | --- |
| [x] | Resource status | `resource_tree.py`, `manage_tree_status.py` | `ManageStateService` | 1 and 3 | Python application and API fixtures |
| [x] | Workflow progress | `tree_utils.py`, Argo workflow data | `ManageStateService` | 1 and 3 | Python application fixtures |
| [x] | Deployed/submitted/pending comparison | `apply_config_overlays`, `ConfigEditService` | `ManageStateService` | 1 and 3 | Python application and React detail tests |
| [x] | Schema-guided editing | config-processor `EditStateV1` and `EditOperation` | `ConfigDraftService` | 4 | Cross-language fixtures and component tests |
| [x] | External references | `ConfigEditService` external-resource methods | Config API and React pickers | 4 | Python API and React picker tests |
| [x] | Draft save/discard | `WorkflowConfigStore` | `ConfigDraftService` | 4 | Python service and API conflict tests |
| [x] | Review and submit | config-processor validation and `submit_saved_config` | Review service and operation manager | 6 | Python API and browser flow |
| [x] | Approval | approval-node detection and `approve_step` | `ApprovalService` | 7 | Exact-target service and browser tests |
| [x] | Reset | reset planning and execution helpers | `ResetService` | 7 | Plan-token and integration tests |
| [x] | Managed output | `show.py`, artifact stores, CR status refs | `OutputService` | 5 | Store-specific service and viewer tests |
| [x] | Active work | cluster observation and action completion | `OperationManager` | 6 | State-transition and retention tests |
| [x] | Logs | resource label resolution, Kubernetes logs, Stern follow | `LogStreamService` | 8 | Provider, cancellation, and browser tests |
| [x] | Navigation/filtering | stable semantic resource identities | React tree state | 3 | Component and Playwright tests |

Capabilities are attached to exact semantic node IDs by the application layer. A keyboard
binding, Rich label, ancestor walk, or terminal command is never the sole owner of a native
capability.

## Extraction Inventory

| Existing location | Classification | Destination or reason |
| --- | --- | --- |
| `WorkflowTreeApp._build_resource_sections` | Extract | `ManageStateService.build_resource_sections` |
| `WorkflowTreeApp._workflow_has_active_rollout` | Extract | presentation-neutral active-rollout helper |
| `WorkflowTreeApp._assign_workflow_progress` and recursive helpers | Extract | presentation-neutral workflow/resource association |
| `WorkflowTreeApp._workflow_output_ref_map` | Extracted | `OutputService` in Phase 5 |
| `WorkflowTreeApp` config draft fields and apply/save workers | Extracted | `ConfigDraftService` in Phase 4 |
| `WorkflowTreeApp` submit worker | Extracted | `ConfigDraftService` review plus `OperationManager` in Phase 6 |
| `WorkflowTreeApp` approval callback | Extracted | exact-target `ApprovalService` in Phase 7 |
| `WorkflowTreeApp` reset command construction | Replaced | version-bound `ResetService` using direct reset helpers in Phase 7 |
| `WorkflowTreeApp` log actions and `LogManager` pager behavior | Replace later | cancellable `LogStreamService` in Phase 8 |
| Textual widgets, modals, bindings, and selection modes | Discard | React browser interactions |
| Rich labels, badges, colors, and terminal symbols | Discard at API boundary | semantic status, diagnostics, and plain labels |
| `textual-serve`, pagers, shell pipelines, and terminal mouse control | Discard | native HTTP and browser behavior |

The TUI may delegate extracted semantic work to the application layer during migration,
but its presentation code does not become an application-layer dependency.

## Representative Scenarios

Application and browser fixtures must cover:

- no Argo workflow with deployed resources;
- an active workflow with secondary step progress;
- a failed resource and failed workflow step;
- a pending-only projected resource;
- a resource waiting for an exact approval gate;
- managed output availability;
- deployed, submitted, and pending value differences;
- partial Argo, Kubernetes, config, or artifact failure;
- an unchanged refresh and a single-resource status change.

## Desktop And Narrow Workflows

Desktop validation:

1. Filter and expand the resource tree without changing the selected resource.
2. Inspect overview, diagnostics, comparison values, activity, logs, and output.
3. Enter editing without replacing the status tree or moving focus unexpectedly.
4. Review and start an exact-target action while operation status remains visible.
5. Follow changing state without remounting unchanged rows.

Narrow validation:

1. Open and close tree navigation without losing selection or expansion.
2. Move between the tree, selected-resource workspace, and operation activity.
3. Complete configuration controls and confirmation dialogs without clipped text.
4. Pause or stop logs with controls that remain visible and reachable.
5. Reconnect or refresh without losing durable cluster state.

## Regression Baseline

Measured on 2026-08-12 with Python 3.11 and the Gradle-managed Node 22/config-processor:

```text
165 passed in 31.80s
```

Command:

```sh
python -m pytest -q \
  tests/workflow-tests/test_resource_tree.py \
  tests/workflow-tests/test_manage.py
```

Required checks while implementing:

- focused application, API, and frontend tests are written before implementation;
- `test_resource_tree.py` and `test_manage.py` remain green after middle-layer extraction;
- the broader non-integration console-link suite runs before each phase commit;
- config-processor tests run when projection or edit contracts change;
- frontend type checking, unit tests, build, and Playwright join the baseline in Phases 2
  and 3.

Cluster integration suites run only against their dedicated configured test context.

## Explicit Exclusions

- reproducing TUI key modes, implicit Enter actions, terminal formatting, or pager behavior;
- treating input loss or recording artifacts as desired behavior;
- adding auth, ingress, multi-user coordination, or a durable application database;
- implementing editing before Phase 4, output before Phase 5, actions before Phases 6 and
  7, or logs before Phase 8;
- exposing raw Kubernetes, Argo, Secret values, Rich markup, or arbitrary command arguments
  to the browser.
