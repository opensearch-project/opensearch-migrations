# Workflow Manage Native Web Conversion Plan

Status: approved implementation plan; phases 0-7 implemented

This document is the implementation runbook for converting `workflow manage` from a
Textual presentation into a native React application backed by FastAPI. It is intended to
be detailed enough for an agent with no prior conversation context to execute one phase at
a time.

Read these related documents before changing code:

- [Workflow Manage Native Web Application](manageWebApplicationDesign.md) defines the
  product and architecture direction.
- [Manage External Configuration References](manageExternalConfigurationReferencesDesign.md)
  defines Secret, ConfigMap, image, and issuer selection behavior.
- [Workflow Manage Parity Inventory](manageWebParityInventory.md) records capability
  ownership, extraction targets, baseline tests, and explicit exclusions.

The framework prototypes, detailed evaluations, and `manageConfig.cast.gz` are archived on
the `workflow-manage-web-design-spikes` branch. They are design evidence, not production
code or behavioral specifications.

## Objective

Build an on-demand management application that:

- runs inside the migration-console pod;
- is reached through `kubectl port-forward`;
- serves a React and TypeScript application and a FastAPI API from one process and origin;
- preserves the resource-centered workflow model and all meaningful management
  capabilities;
- reuses the existing config-processor and Python cluster orchestration layers;
- keeps the browser responsive while status and long-running actions change;
- uses normal browser controls for navigation, editing, confirmation, progress, output,
  and logs.

The result must support resource status, schema-guided editing, managed output, review and
submit, approval, reset, active operations, and logs before the native path replaces the
current browser serve mode.

## Settled Decisions

| Area | Decision |
| --- | --- |
| Frontend | React with strict TypeScript |
| Build tool | Vite |
| Server | FastAPI in the existing `console_link` Python package |
| Runtime | One migration-console process; no production Node.js web server |
| Deployment | Compiled frontend served by FastAPI from the migration-console image |
| Access | Local browser through `kubectl port-forward`; no initial ingress or login flow |
| API client | Generate TypeScript transport types and client functions from FastAPI OpenAPI |
| Frontend remote-data cache | TanStack Query caches API responses in browser memory and reconciles normalized snapshots by stable ID |
| Live updates | HTTP snapshots plus server-sent events; no WebSocket without a demonstrated need |
| UI system | Do not use Cloudscape; own the application layout and styling, using focused accessible React libraries where they fit |
| Config semantics | Continue to reside in the TypeScript config-processor |
| Cluster semantics | Continue to reside in Python services using Kubernetes and Argo |
| Save model | Explicit draft save with revision checks |
| Consequential work | Return tracked operations instead of blocking HTTP requests |
| Logs | Implement late, after the state, edit, output, and action contracts are stable |

React is the selected frontend framework. This decision does not imply Next.js or an AWS
visual component library. The application needs a static bundle, not server-side rendering.

The Angular, React, and Vue prototypes all demonstrated partial tree updates while
preserving stable row identity, selection, expansion, and keyboard focus. The rendering
framework was therefore not the limiting factor; stable semantic IDs and patch-oriented
application state were the important requirements. React was selected for its specialized
widget ecosystem, direct TypeScript narrowing in TSX, predictable keyed reconciliation,
and contributor familiarity. Angular offered stronger integrated conventions at the cost
of more framework surface, while Vue offered concise reactivity and transitions with a
smaller ecosystem for unusual operational widgets.

Cloudscape is not a production dependency. Its application-shell and visual-system
constraints, AWS-console identity, package weight, and layout coupling do not fit this
specialized workflow closely enough to justify adopting it as the frontend foundation.
Use application-owned layout and styling, and select focused libraries by capability.
React Aria Components is a candidate for accessible control behavior where it fits, not a
requirement to use one library for every control. Domain DTOs, feature state, and
specialized widgets such as the resource tree and log viewer remain application-owned
rather than adopting library-specific data models.

## Scope And Parity

Functional parity means preserving the user's ability to accomplish a task, not copying
the TUI interaction used to reach it.

| Capability | Current implementation source | Native target |
| --- | --- | --- |
| Resource-centered status | `workflow/resource_tree.py` and config overlays | Resource tree and selected-resource workspace |
| Workflow-step status | `workflow/tree_utils.py` and Argo data | Activity/progress details and, if still useful, a secondary step view |
| Deployed/submitted/pending values | `apply_config_overlays` and `ConfigEditService` | Explicit comparison in overview, configuration, and review |
| Schema-guided editing | config-processor `EditStateV1` and `EditOperation` | Generic React form controls driven by the same DTO |
| External resource selection | `ConfigEditService` external-resource methods | Searchable picker showing resource identity and available keys |
| Draft save/discard | `WorkflowConfigStore` and TUI draft state | Revisioned server-side edit session with explicit save/discard |
| Review and submit | `ConfigEditService.submit_saved_config` | Review surface and tracked submit operation |
| Approval | approval-gate helpers and `approve_gate` | Exact-target confirmation and tracked operation |
| Reset | reset dry-run plan and execution helpers | Plan, review, stale-plan protection, and tracked execution |
| Managed output | `workflow/commands/show.py` and artifact store | Contextual output list, viewer, and download |
| Logs | Kubernetes pod metadata and logs | Bounded history, pagination, follow, reconnect, and explicit stop |
| Active work | TUI notifications and worker flags | Persistent operation drawer and activity history |
| Filtering/navigation | Textual tree state managers | Mouse and keyboard accessible browser tree |

Create and maintain a parity checklist during implementation. A capability is complete only
when it has a server contract, a frontend interaction, error behavior, and tests.

## Reuse, Extract, And Discard

### Reuse Directly

The following layers are product logic and should remain authoritative:

- `orchestrationSpecs/packages/config-processor/src/editConfig.ts`
- `orchestrationSpecs/packages/config-processor/src/schemaEditModel.ts`
- config-processor strict and loose resource projection
- `workflow/services/config_edit_service.py`
- `workflow/resource_tree.py`
- `workflow/manage_tree_schema.py`
- `workflow/manage_tree_status.py` where it contains semantic status rules
- `workflow/services/workflow_service.py`
- Kubernetes and Argo access helpers
- reset target resolution, planning, and execution logic
- approval-gate mutation logic
- `workflow/commands/show.py` managed-output resolution
- `workflow/commands/artifact_store.py`
- `WorkflowConfigStore`
- the migration-console Gradle Node setup and config-processor staging

Some of these modules currently mix semantic data with terminal formatting. Reuse the
semantic computation and move it behind typed application services. Do not serialize Rich
labels or Textual node data.

### Extract Into A Middle Layer

Logic currently embedded in `workflow/tui/workflow_manage_app.py` must move into
presentation-neutral services when it is needed by the web application:

- assembling resource and workflow state;
- computing exact-node capabilities;
- resolving managed-output descriptors;
- identifying log targets;
- preparing submit review;
- starting and tracking long-running actions;
- approval target descriptions;
- reset plan and execution orchestration;
- configuration draft lifecycle;
- state revision and invalidation.

The middle layer must be callable from unit tests without FastAPI, Textual, Click, or a live
cluster. FastAPI routes should be thin adapters over this layer.

### Do Not Carry Forward

The native implementation must not reuse or recreate:

- Textual widgets, screens, modal classes, tree nodes, or state managers;
- Rich markup, terminal colors, status symbols, or label parsing as API data;
- `textual-serve` or Textual's browser driver;
- TUI key-binding modes;
- terminal mouse reporting controls;
- `less`, pagers, `os.system`, or shell pipelines;
- subprocess calls back into `workflow` commands from API handlers;
- action discovery by walking to a selected node's ancestor;
- Enter on a diagnostic or status row as an implicit approval action;
- transient toast timing as the only indication that work is active;
- refresh behavior that rebuilds the visible tree or steals focus.

The current TUI remains available during the transition, but this plan contains no work to
extend or improve its presentation.

## Target Architecture

```text
Browser
  React application
    normalized manage state
    interaction state
    generated API client
    HTTP queries and mutations
    SSE subscribers
          |
          | same-origin /api/v1/*
          v
FastAPI
  transport validation
  static bundle and SPA fallback
  request cancellation
          |
          v
Python application layer
  ManageStateService
  ObservationCoordinator
  ConfigDraftService
  OutputService
  OperationManager
  ApprovalService
  ResetService
  LogStreamService
          |
          +---- ConfigEditService -> config-processor one-shot Node process
          +---- WorkflowService -> Argo
          +---- Kubernetes clients -> CRs, pods, logs, external resources
          `---- Artifact store -> managed output
```

### Proposed Source Layout

```text
migrationConsole/
  web/
    package.json
    package-lock.json
    vite.config.ts
    src/
      api/
      app/
      features/
        activity/
        configuration/
        logs/
        operations/
        output/
        review/
        tree/
      test/
  lib/console_link/console_link/workflow/
    application/
      actions.py
      config_drafts.py
      manage_state.py
      models.py
      observations.py
      operations.py
      outputs.py
      resets.py
      log_streams.py
    web/
      app.py
      dependencies.py
      errors.py
      routes/
      static.py
```

The archived `workflow-manage-web-design-spikes` branch preserves the prototype workspace
and its build evidence. The production React application uses the interaction conclusions
but does not import prototype fixtures or code.

### Dependency Rules

- `workflow/application` may depend on existing workflow services and middleware.
- `workflow/application` must not depend on `workflow/tui`, Textual, Rich rendering, or
  FastAPI.
- `workflow/web` may depend on FastAPI and `workflow/application`.
- React may depend only on browser-safe packages and generated/shared contracts.
- React must not import config-processor runtime code into the browser bundle.
- API routes must not call Click commands or construct shell commands.
- Kubernetes, Argo, config-processor, and artifact dependencies must be injectable.

### State Durability Model

Kubernetes and the configured artifact store remain the durable sources of truth. This
application introduces no database, local durable store, or authoritative FastAPI state.

| Durability | State | Purpose |
| --- | --- | --- |
| Durable and authoritative | Kubernetes CRs, pods, and Argo workflow state | Current deployed resources and workflow progress |
| Durable and authoritative | Saved pending YAML in the ConfigMap-backed `WorkflowConfigStore` | Configuration that survives a manage-server restart |
| Durable and authoritative | Managed artifacts in the configured artifact store | Workflow output |
| Ephemeral FastAPI process state | Latest derived observation and stale/error metadata | Avoid repeating Kubernetes, Argo, and config-processor work for every request and SSE subscriber |
| Ephemeral FastAPI process state | Unsaved config draft, draft revision, reset plans, and bounded operation registry | Coordinate one on-demand editing and action session |
| Ephemeral FastAPI process state | Background task handles, SSE queues/event IDs, and bounded log buffers/cursors | Deliver responsive progress and streaming behavior |
| Ephemeral browser state | TanStack Query cache and normalized snapshot | Render and reconcile remote observations efficiently |
| Ephemeral browser state | Selection, focus, expansion, tabs, scroll anchors, open controls, and uncommitted field input | Preserve the user's interaction context |

The derived observation cache is useful even though Kubernetes is authoritative because a
manage snapshot joins multiple Kubernetes and Argo reads with configuration projection and
augmentation. One process-level observation avoids recomputing that join independently for
every HTTP request and connected browser event stream.

After a FastAPI restart, the application reconstructs current state from Kubernetes, Argo,
`WorkflowConfigStore`, and the artifact store. Unsaved drafts, recent operation history,
and stream positions may be lost. Work already accepted by Kubernetes may continue and is
reported from subsequent cluster observations. An in-memory operation status must never
override contradictory observed cluster state. If operation history ever needs to survive
server restarts, it should be represented through an appropriate Kubernetes resource or
status contract rather than an application-local database.

## Contract Design

Contracts are versioned before implementing screens. Breaking changes create a new format
version or API version; they do not silently reinterpret fields.

### Ownership

FastAPI and Pydantic own transport envelopes and Python-owned domain DTOs. FastAPI OpenAPI
generates the normal TypeScript API client.

The config-processor remains the source of truth for `EditStateV1`, `EditNode`, and
`EditOperation`. Do not create an independently maintained Pydantic copy of every edit
field. The frontend should consume type-only edit contracts from a browser-safe contract
entry point, while FastAPI treats the versioned edit payload as structured JSON inside a
typed envelope. Add cross-language fixture tests so Python transport changes cannot corrupt
the config-processor payload.

### Manage Snapshot

The initial state contract should have this shape:

```ts
interface ManageSnapshotV1 {
  formatVersion: 1;
  revision: string;
  observedAt: string;
  namespace: string;
  workflow: {
    name: string;
    phase: string;
    startedAt?: string;
    finishedAt?: string;
  };
  // Ordered top-level nodes allow a forest without a synthetic presentation root.
  rootIds: string[];
  // Stable-ID normalization supports direct lookup and localized reconciliation.
  nodes: Record<string, ManageNodeV1>;
  // Activity comes from /api/v1/operations; it is not tree state.
}

interface ManageNodeV1 {
  id: string;
  // Supports upward navigation without searching down from every root.
  parentId?: string;
  // Ordered references retain tree structure without recursively nesting nodes.
  childIds: string[];
  kind: string;
  label: string;
  description?: string;
  status: string;
  phase?: string;
  valueSummary?: string;
  diagnostics: DiagnosticV1[];
  capabilities: NodeCapabilityV1[];
}
```

This is a normalized wire representation of a tree rather than a recursively nested
object. `rootIds` is the ordered list of top-level nodes. Each node's ordered `childIds`
continues the traversal through `nodes[id]`, while `parentId` supports upward navigation.
Multiple root IDs allow top-level workflow sections or resources without adding a
presentation-only synthetic root. Storing each node once also provides direct lookup by
stable ID and lets a status or content update replace only that node. A recursive payload
would recreate every containing ancestor when a descendant changes, making localized
rendering and animation harder and increasing the risk of disturbing selection,
expansion, focus, or scroll state.

Active and recent operations are not tree nodes and are not part of `ManageSnapshotV1`.
The browser queries `/api/v1/operations` separately and relates an operation to tree nodes
through its affected-node IDs. Keeping the contracts separate prevents an operation
update from changing the manage-state revision or duplicating operation state in two API
responses.

The final schema may add typed status and comparison objects, but it must retain:

- stable semantic IDs;
- normalized parent/child relationships;
- no Rich or HTML markup in labels;
- exact-node capabilities;
- no raw Kubernetes or Argo objects;
- no Secret values.

Stable IDs must derive from stable domain identity, such as resource type and name, edit
path, workflow node ID, or output reference. Array position and display label are not IDs.

### Capabilities

Capabilities are discriminated objects, not strings inferred by React:

```ts
type NodeCapabilityV1 =
  | { kind: "edit"; editTargetId: string }
  | { kind: "approve"; approvalTargetId: string; label: string }
  | { kind: "reset"; resetTargetId: string; label: string }
  | { kind: "logs"; logTargetId: string }
  | { kind: "output"; outputTargetId: string };
```

The capability belongs only to the node on which it is displayed. React must not search
ancestors for a command. A separate explicit command may operate on a group if the server
provides a group capability.

### Revisions And Concurrency

Use separate revision domains:

| Revision | Protects |
| --- | --- |
| Manage state revision | Live workflow/resource observation |
| Config base revision | Saved pending config in `WorkflowConfigStore` |
| Config draft revision | In-memory edit operations within the current server process |
| Reset plan token | Exact resources and resource versions reviewed by the user |
| Operation revision | Latest known operation state |
| Log cursor | Position in one identified pod/container/restart stream |

Revisions are cache and concurrency tokens, not durable records. They identify what a
request observed or reviewed so the server can reject stale mutations.

Mutation requests include the revision they were based on. A stale request returns HTTP
`409` with enough information to refetch or reopen review. Do not silently apply an edit or
reset against a different base.

### Error Envelope

Use one problem-details shape for expected failures:

```json
{
  "type": "config-revision-conflict",
  "title": "Configuration changed",
  "status": 409,
  "detail": "Reload the saved configuration before saving this draft.",
  "retryable": true,
  "context": {}
}
```

Expected validation and conflict errors are not HTTP 500 responses. Unexpected exceptions
receive a correlation ID in the response and full details only in server logs.

### State Updates

The first state implementation may return complete normalized snapshots. The frontend must
reconcile snapshots by node ID and preserve object identity for unchanged nodes. React keys
must use `node.id`, while selection, expansion, focus, and scroll anchors remain separate
interaction state.

Use an SSE event stream for invalidation and operation changes:

- `state-invalidated`
- `operation-upserted`
- `operation-removed`
- `heartbeat`

On `state-invalidated`, React refetches the snapshot and reconciles it. This keeps server
state assembly simple while retaining localized DOM updates. Add fine-grained state patches
only if profiling shows snapshot refetch or reconciliation is a problem.

SSE event IDs are monotonic within the process. Reconnect uses `Last-Event-ID`; an event
history gap causes a snapshot refetch.

## Initial API Surface

Exact Pydantic names may change, but the resource boundaries should remain:

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/api/v1/system/health` | Process and dependency readiness |
| `GET` | `/api/v1/manage/state` | Normalized manage snapshot |
| `GET` | `/api/v1/manage/events` | State invalidation and operation SSE |
| `GET` | `/api/v1/config` | Open or return the current edit session |
| `POST` | `/api/v1/config/operations` | Apply one typed edit operation to the draft |
| `POST` | `/api/v1/config/save` | Persist the current draft with revision checks |
| `POST` | `/api/v1/config/discard` | Reset the draft to saved pending config |
| `GET` | `/api/v1/external-resources` | List candidates for one schema external reference |
| `GET` | `/api/v1/external-resources/{id}` | Read safe metadata and ConfigMap content where allowed |
| `PUT` | `/api/v1/external-resources/{id}` | Create or update an allowed external resource |
| `GET` | `/api/v1/nodes/{node_id}/outputs` | List managed-output descriptors |
| `GET` | `/api/v1/outputs/{output_id}` | Read or download one managed output |
| `GET` | `/api/v1/submission-review` | Validate and summarize pending changes |
| `POST` | `/api/v1/submissions` | Start a tracked submit operation |
| `POST` | `/api/v1/approvals/{target_id}` | Start an exact-target approval operation |
| `POST` | `/api/v1/reset-plans` | Create a reset plan from an exact target |
| `POST` | `/api/v1/resets` | Execute a reviewed, current reset plan |
| `GET` | `/api/v1/operations` | List active and recent operations |
| `GET` | `/api/v1/operations/{operation_id}` | Read one operation |

Log endpoints are intentionally added in the dedicated late log phase after their
pagination and cancellation behavior has been proven.

## Middle-Layer Services

### ManageStateService

Responsibilities:

- query current workflow and migration resources;
- call `build_resource_tree`;
- load submitted and pending projections through `ConfigEditService`;
- apply config overlays;
- attach workflow progress, diagnostics, dependencies, and output availability;
- map semantic resource data to normalized DTOs;
- assign exact-node capabilities;
- return a deterministic snapshot and revision.

Do not mutate frontend interaction state. Expansion, selection, active tab, and focus belong
to React.

### ObservationCoordinator

Run one managed observation loop per server process, not one poller per HTTP request or SSE
subscriber. The coordinator:

- asks `ManageStateService` for the initial snapshot;
- performs blocking Kubernetes, Argo, and config-processor work in a controlled executor;
- refreshes at a configurable interval;
- compares semantic revisions;
- stores the latest snapshot;
- publishes `state-invalidated` only when semantic state changes;
- wakes or stops cleanly during server startup and shutdown;
- applies bounded retry and backoff after dependency failures;
- keeps the last successful snapshot available with stale/error metadata.

`GET /api/v1/manage/state` returns the latest observation and waits for the initial load
when necessary. SSE subscribers consume the coordinator's bounded event stream; they do
not start their own cluster polling.

### ConfigDraftService

One on-demand manage server initially has one edit session:

```text
saved WorkflowConfigStore value
  -> base revision
  -> in-memory raw YAML draft
  -> config-processor EditStateV1
  -> draft revision
```

The browser receives edit state and revisions, not raw YAML. Each committed edit sends an
`EditOperation` and expected draft revision. The service calls
`ConfigEditService.apply_operation`, retains the returned YAML and edit state, and returns
the next revision.

Save verifies the config base revision before calling `save_raw_yaml`. Discard reloads the
saved value. A server restart loses unsaved draft operations but never corrupts saved
configuration.

Continue using one-shot config-processor invocations at committed interaction boundaries.
Do not run config-processor on every keystroke. A long-lived stdio helper remains an
optional measured optimization.

### OutputService

Responsibilities:

- map a selected node to explicit managed-output descriptors;
- resolve output references from CR status and workflow metadata;
- read content through the existing managed-output and artifact helpers;
- attach resource, stage, attempt, content type, timestamp, and source context;
- enforce response-size and download limits;
- return structured JSON/YAML as text plus an optional parsed representation.

Do not scrape TUI labels or replay pager output.

### OperationManager

Operations use an in-process bounded registry because the service is on-demand and
single-process. Every operation contains:

- stable operation ID;
- kind and target;
- user-facing label;
- queued, running, waiting, succeeded, failed, or cancelled state;
- phase and progress when meaningful;
- creation and update timestamps;
- terminal result or problem details;
- links to affected nodes.

Action routes return HTTP `202` with the operation. Background work runs outside the async
event loop through a controlled executor. Operation changes publish SSE events.

Use a bounded history and time-to-live. Do not create an unbounded thread or task per
refresh. On process restart, active in-memory operations are lost; live resource state
remains authoritative and should let the UI explain the resulting cluster state.

### ApprovalService

The service accepts only server-issued approval target IDs. It resolves the exact gate and
returns the target description before execution. Approval confirmation must identify:

- approval gate;
- owning resource or stage;
- current reason for waiting;
- expected effect.

The service calls reusable approval logic directly. It does not invoke the Click command.

### ResetService

Split existing reset behavior into callable planning and execution functions:

```text
exact target
  -> resolve dependency-safe targets
  -> reset plan with warnings and resource versions
  -> opaque plan token
  -> confirmation
  -> verify token/current resources
  -> execute as tracked operation
```

Execution rejects stale or modified plans. Preserve artifact cleanup, owned-resource
cleanup, dependency ordering, timeout diagnostics, and target-index warnings.

### LogStreamService

This service is implemented in the late log phase. Use the Kubernetes client for target
discovery, bounded history, and explicit `previous` container logs. Preserve Stern's
valuable dynamic multi-pod tailing through a managed `SternFollowSource` if the technical
spike verifies its structured-output and cancellation contracts. Never connect a terminal
pipeline such as `kubectl | less` to the web request.

## Frontend Architecture

### State Ownership

Keep three kinds of state separate:

| State | Owner |
| --- | --- |
| Remote-data cache | TanStack Query in browser memory plus normalized snapshot reconciliation |
| Live events | SSE adapter that invalidates queries or applies operation updates |
| Interaction state | React feature state keyed by stable IDs |

Interaction state includes selected node, focused node, expanded IDs, active detail tab,
tree filter, form focus, scroll anchor, open confirmation, and log viewer preferences.

Do not put server DTOs and mutable interaction state into one global object.

### Resource Tree

Start from the React spike's behavior:

- normalized nodes and stable IDs;
- keyed rows;
- coherent tree keyboard navigation;
- explicit expand/collapse controls;
- selection distinct from focus;
- filter without destructive state reset;
- localized insertion animation;
- reduced-motion support;
- no action on diagnostic text;
- no unexpected ancestor action.

Add virtualization only after measuring real tree sizes. If needed, use a proven React
virtualization library while retaining stable item keys and an accessible tree strategy.

### Configuration Renderer

Render `EditNode` generically by `valueKind`, `valueType`, `inputHint`, `externalRef`,
`variants`, `presence`, `expert`, and command metadata.

Use:

- text and number inputs for scalar values;
- checkboxes or switches for booleans;
- selects, radios, or searchable menus for finite options;
- explicit segmented or select controls for union variants;
- add/remove/rename controls attached to the exact config node;
- a ConfigMap/Secret picker that shows resource plus available keys;
- inline diagnostics at the owning field and aggregate diagnostics in review.

React must not contain workflow-specific checks such as "if this is the proxy TLS path."
Missing schema metadata is fixed in config-processor or schema ownership, not patched by
frontend label matching.

### Activity And Operations

The operation drawer remains visible while navigating. A consequential action:

1. opens an exact-target review or confirmation;
2. starts an operation;
3. closes the dialog after the server accepts it;
4. appears immediately in activity;
5. updates through SSE;
6. remains visible in waiting or failed states;
7. links back to affected nodes.

Do not use a toast as the sole durable record of an operation.

## Implementation Phases

Each phase should be independently reviewable and leave all existing CLI and TUI tests
working. Do not begin a later phase while the prior phase's acceptance criteria are
unmet.

### Phase 0: Baseline And Parity Inventory

Work:

- create a checked parity matrix from the table in this document;
- capture representative DTO fixtures from config-processor and resource projection;
- identify all TUI methods that contain product logic needed by the web path;
- classify each method as reuse, extract, replace, or discard;
- record expected desktop and narrow viewport workflows;
- establish baseline Python and TypeScript test commands and timing.

Acceptance:

- every required capability has an owning implementation path;
- no capability depends solely on a key binding or Rich label;
- the extraction list has corresponding unit-test locations;
- scope exclusions are explicit.

### Phase 1: Presentation-Neutral Middle Layer

Work:

- create `workflow/application/models.py`;
- implement `ManageStateService` with injected Argo, Kubernetes, config, and artifact
  dependencies;
- move resource-state orchestration out of `WorkflowTreeApp` without changing TUI behavior;
- map resource data into stable normalized DTOs;
- compute exact-node capabilities;
- create deterministic revision calculation;
- add unit fixtures for no workflow, active workflow, failed resource, pending-only resource,
  approval gate, output availability, and config differences.

Acceptance:

- application-layer tests run without Textual or FastAPI;
- DTO serialization contains no Rich markup or terminal symbols;
- repeated identical observations produce the same revision and node data aside from
  `observedAt`;
- a changed resource updates only that node and affected ancestors;
- existing TUI behavior and tests remain unchanged.

### Phase 2: FastAPI And React Production Scaffold

Work:

- create the production Vite React application under `migrationConsole/web`;
- add strict TypeScript, lint, Vitest, Testing Library, MSW, and Playwright configuration;
- add FastAPI and its production ASGI runner to console-link dependencies;
- create the FastAPI app, health endpoint, static bundle mount, and SPA fallback;
- expose the initial Pydantic state contract;
- generate the TypeScript API client from OpenAPI;
- integrate frontend install, generation, test, and build tasks with
  `migrationConsole/build.gradle`;
- copy the compiled assets into the migration-console Docker context;
- add a development command that runs Vite with an API proxy;
- add a production-style command that serves the compiled bundle and API from one port.

Acceptance:

- one Gradle task builds config-processor, generates the client, tests React, and stages the
  static bundle;
- FastAPI serves `/api/v1/system/health` and the React shell;
- refreshing a client route returns the SPA rather than 404;
- production requires no Node.js web server;
- API and static content use one origin without permissive CORS.

### Phase 3: Read-Only Real-Data Vertical Slice

Work:

- implement `GET /api/v1/manage/state`;
- add state invalidation SSE and heartbeat handling;
- replace spike fixtures with generated API data;
- implement the resource tree, filtering, selection, overview, diagnostics, and activity;
- show deployed, submitted, and pending comparisons;
- preserve selection, expansion, focus, and scroll across refresh;
- expose secondary workflow-step detail where it adds information not present in resources;
- add reconnect, loading, empty, partial failure, and stale-data states.

Acceptance:

- the application works against fake services and a real development cluster;
- status refresh does not remount unchanged rows;
- no keypress or active control is lost during refresh;
- actions are not yet executable but availability can be shown from capabilities;
- Playwright passes at desktop and narrow widths;
- reduced-motion mode has no insertion animation.

This phase intentionally excludes logs.

### Phase 4: Configuration Editing

Implementation status: complete as of 2026-08-12.

Work:

- implement `ConfigDraftService`;
- expose config open, operation, save, and discard endpoints;
- render every generic `EditNode` kind currently emitted by config-processor;
- support set, unset, add, remove, and rename operations;
- support optional/expert visibility without changing the DTO;
- implement union and boolean controls;
- implement external-resource listing and safe selection;
- show ConfigMap and Secret key names while never returning Secret values;
- run cluster-dependent external reference validation;
- show field, branch, and whole-config diagnostics;
- add dirty-state navigation protection and revision conflict recovery.

Acceptance:

- representative workflow configs can be created and edited without raw YAML editing;
- all edit mutations pass through config-processor;
- React contains no workflow-path-specific schema rules;
- the incorrect ancestor rename interaction is impossible;
- ConfigMap-plus-key choices show both map and key;
- stale save returns a recoverable conflict instead of overwriting;
- component and API tests cover all edit operation variants.

### Phase 5: Managed Output

Implementation status: complete as of 2026-08-13.

Managed output follows editing so the selected-resource workspace and typed node
capabilities are already stable.

Work:

- implement `OutputService`;
- expose output descriptor and content endpoints;
- map exact nodes to current output references;
- show resource, stage, attempt, timestamp, source, and content-type context;
- render text, JSON, and YAML safely;
- support copy and download;
- show unavailable, expired, missing, and read-failure states;
- preserve the semantic order of evaluate and migrate outputs;
- enforce bounded inline size and stream or download larger content.

Acceptance:

- output is never inferred from display labels;
- selecting a resource shows only output owned by that resource or explicitly documented
  descendants;
- stage ordering is understandable and deterministic;
- output read failures do not destabilize manage state;
- tests cover mounted, S3, GCS, missing-reference, and stale-reference behavior where those
  artifact stores are supported.

### Phase 6: Review, Submit, And Operation Tracking

Implementation status: complete as of 2026-08-13.

Work:

- implement `OperationManager` and operation SSE events;
- create review DTOs from saved pending config and live/submitted state;
- show validation results and a concise pending-change summary;
- implement tracked submit;
- retain detailed submission failures as operation results;
- poll or observe cluster state after submission so waiting and convergence are visible;
- link operations to the resources they affect.

Acceptance:

- submit returns quickly with HTTP `202`;
- the browser remains usable while submission or convergence runs;
- a page refresh restores cluster state and recent in-process operations;
- submit cannot start from an invalid or stale config revision;
- operation waiting is visually distinct from failure;
- operation history is bounded and tested.

### Phase 7: Approval And Reset

Implementation status: complete as of 2026-08-13.

Work:

- implement `ApprovalService` with exact server-issued target IDs;
- create approval review content that names the gate, resource, stage, and effect;
- implement approval as a tracked operation;
- extract reset planning and execution from Click-facing code;
- expose reset planning with warnings and dependency-safe targets;
- issue a reset plan token tied to resource identity and versions;
- reject stale reset execution;
- execute reset as a tracked operation;
- retain cleanup, timeout, finalizer, owner-reference, artifact, and target-index diagnostics.

Acceptance:

- Enter or selection alone never approves or resets anything;
- approval confirmation identifies exactly what is approved;
- a successful approval remains active until the observed cluster state confirms its effect;
- reset executes only the plan displayed to the user;
- stale plans return `409`;
- failures retain actionable diagnostics in activity.

### Phase 8: Logs, Pagination, And Follow

Logs are deliberately late because their transport and lifecycle differ from normal manage
state and operations.

#### Required Technical Spike

Before committing the API, test the Kubernetes log API against the supported cluster
versions and answer:

- behavior of `tail_lines`, `since_time`, `timestamps`, `limit_bytes`, and `follow`;
- timestamp precision and duplicate behavior during reconnect;
- current versus `previous` container logs after restart;
- response behavior when a pod terminates or is replaced;
- how quickly an open follow response notices client disconnect;
- whether the Python Kubernetes client exposes a reliable close/cancel path;
- maximum safe initial history size;
- what backward pagination can honestly support without an external log store.

The migration-console image already pins Stern, and `workflow log -f` already uses it for
multi-pod follow. Include that exact pinned version in the spike and answer:

- whether its machine-readable output preserves timestamp, namespace, pod, container, and
  message boundaries for plain, JSON, multiline, and invalid-byte log content;
- whether pod UID and container restart identity must be augmented from a Kubernetes pod
  watch;
- how it behaves as matching pods and containers start, terminate, restart, or disappear;
- whether its initial tail, ordering, overlap, and reconnect behavior can satisfy the
  session contract;
- whether termination reliably closes every Kubernetes request and child process;
- how `max-log-requests`, high-volume output, and a slow browser interact with server
  backpressure;
- whether the pod's normal in-cluster credentials and RBAC work without a generated
  kubeconfig.

Kubernetes pod logs do not provide arbitrary offset-based backward pagination. Do not
advertise unlimited history if the source cannot provide it.

#### Stern Follow Adapter

If the spike passes, use a hybrid provider design:

- `KubernetesHistorySource` lists exact pod/container targets and reads bounded current or
  `previous` logs;
- `SternFollowSource` watches a server-resolved label selector and follows all matching
  pods and containers, including pods that appear after the session starts;
- `LogStreamService` normalizes both providers into the same `LogEvent` contract and owns
  buffering, cursors, deduplication, and browser delivery.

Start one Stern process per active server log session with an argument vector and no
shell. Selectors and options come from a server-issued log target and a small typed
allowlist; never accept arbitrary command arguments from the browser. Configure Stern's
machine-readable output mode, parse stdout into events, and treat stderr as source-status
or error events rather than log content. Strip terminal color and prefixes because the
React viewer renders pod and container identity itself.

Each Stern process must have an explicit owner and lifecycle. On Stop, expiry of a short
last-subscriber reconnect grace period, idle timeout, or server shutdown, terminate its
process group, wait for a short bounded interval, kill it if necessary, drain its pipes,
and reap it. Cap concurrent sessions and output rate, and place parsed events in the same
bounded line/byte ring buffer used by Kubernetes history. A browser pause stops rendering
but does not imply source cancellation; Stop ends the server session and Stern process.

Do not call the Click-facing `workflow log` command from the API. Extract its resource-to-
label-selector rules into the presentation-neutral application layer so the CLI and web
adapter can share target resolution without sharing terminal behavior.

#### Proposed Session Contract

After the spike, prefer a bounded server-side log session:

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/api/v1/nodes/{node_id}/log-targets` | List exact pod/container/restart choices |
| `POST` | `/api/v1/log-streams` | Start a bounded session and initial tail |
| `GET` | `/api/v1/log-streams/{stream_id}/pages` | Read buffered pages using opaque cursors |
| `GET` | `/api/v1/log-streams/{stream_id}/events` | Follow new lines through SSE |
| `DELETE` | `/api/v1/log-streams/{stream_id}` | Stop and release the source stream |

The service should:

- identify single-pod history by pod UID, container, and restart generation;
- identify a multi-pod follow session by its server-issued target and resolved selector,
  while retaining pod/container identity on every event;
- request timestamps from Kubernetes;
- assign a monotonic sequence within the session;
- deduplicate overlap after reconnect;
- keep a bounded ring buffer by lines and bytes;
- expose opaque before/after cursors;
- state clearly when the beginning of available history has been reached;
- support `previous` logs as a separate target;
- close the Kubernetes response or Stern process on explicit Stop, expiry of the
  last-subscriber reconnect grace period, timeout, or server shutdown;
- stop idle sessions and cap concurrent sessions;
- preserve the viewer and buffer while the user navigates within the application.

The managed Stern adapter is a narrow, explicit log-source integration. It is not
permission to expose general shell execution or arbitrary CLI pass-through.

#### Frontend Work

- add target selection before starting a stream;
- implement bounded initial history and Load older within the available buffer;
- follow new lines without moving the viewport when the user has scrolled upward;
- provide explicit Follow/Pause and Stop controls;
- show reconnecting, ended, replaced-pod, and failed states;
- retain copy and download for buffered content;
- use a virtualized line viewer if measured volume requires it;
- make ANSI handling explicit and safe.

Acceptance:

- Stop closes the backend source without another terminal;
- navigation never traps the user in a log process;
- disconnect and reconnect neither lose nor duplicate lines within documented guarantees;
- buffer and concurrency limits are enforced;
- pod restart and replacement are visible rather than silently merging streams;
- pagination never claims history that Kubernetes did not provide;
- stress tests cover slow consumers, bursty logs, disconnect, reconnect, termination, and
  cancellation.

### Phase 9: Hardening, Parity, And Cutover

Work:

- complete the parity checklist;
- run performance tests with realistic tree size and update frequency;
- test operation and log resource limits;
- test browser refresh, API restart, cluster failure, and Argo failure;
- verify Secret values never enter frontend payloads, logs, or generated fixtures;
- validate same-origin and Host/Origin handling for the port-forward model;
- add accessibility checks and manual keyboard review;
- run Playwright in desktop, narrow, and reduced-motion modes;
- run kind integration tests for state, editing, output, submit, approval, reset, and logs;
- verify Docker image assembly and the exact port-forward lifecycle;
- document support and troubleshooting commands;
- make `workflow manage --serve` launch the native FastAPI application;
- retain the current Textual browser path only under an explicitly named compatibility
  option for one transition period;
- remove `textual-serve` after the compatibility period.

Acceptance:

- every parity item has automated or documented manual evidence;
- the production image starts one server and serves both API and assets;
- all consequential actions are exact-target, revision-safe, and tracked;
- no frontend behavior depends on TUI formatting or key modes;
- logs terminate reliably;
- the native application is the documented browser path.

## Testing Strategy

### Contract Tests

- serialize every Pydantic response through FastAPI;
- generate the TypeScript client in CI and fail on uncommitted output;
- validate representative config-processor edit fixtures at the Python boundary;
- compile frontend fixtures against shared edit contracts;
- reject unknown format versions.

### Python Unit Tests

- use fake Kubernetes, Argo, config-processor, config store, and artifact dependencies;
- test state construction without FastAPI;
- test capability ownership;
- test revisions and conflicts;
- test operation state transitions and retention;
- test approval and reset target resolution;
- test output descriptors and limits;
- test log lifecycle and cancellation after Phase 8.

### API Tests

- use FastAPI's test client with injected fakes;
- cover normal, validation, conflict, not-found, dependency failure, and cancellation paths;
- assert state-changing routes cannot execute arbitrary commands;
- assert Secret values are redacted;
- assert streaming responses terminate.

### Frontend Tests

- use Testing Library for behavior rather than implementation details;
- use MSW generated-client handlers;
- preserve the React spike's DOM identity and focus tests;
- test every config control and operation state;
- test error, reconnect, stale, empty, and loading states;
- use fake timers only where deterministic event timing is necessary.

### Browser Tests

- use Playwright against a production build served by FastAPI;
- cover the primary workflow at desktop and narrow widths;
- verify no overlap or clipping;
- verify keyboard navigation and dialog focus restoration;
- verify reduced motion;
- verify active operation persistence across navigation;
- verify log stop and reconnect after Phase 8.

### Cluster Integration Tests

Keep these focused and expensive:

- resource state from representative CRs;
- config external-reference augmentation;
- managed output from each supported artifact store;
- submit and observed convergence;
- approval state change;
- reset plan and execution;
- Kubernetes log tail, follow, restart, and cancellation.

## Build And Packaging

Extend `migrationConsole/build.gradle` rather than introducing an unrelated build system.
Create tasks with clear inputs and outputs for:

- frontend dependency installation with `npm ci`;
- OpenAPI generation;
- generated TypeScript client generation;
- frontend type checking;
- frontend unit tests;
- frontend production build;
- Playwright where the build environment supports a browser;
- copying `dist` into `build/dockerContext`;
- Python API tests.

The Docker image contains:

- the compiled React assets;
- FastAPI and the ASGI runner;
- existing Python workflow services;
- the config-processor bundle and Node runtime already required by migration-console.

Development may run Vite and FastAPI separately with a Vite proxy. Production and
port-forward testing must use the single-origin FastAPI-served bundle.

## Security And Resource Limits

The initial port-forward-only model still requires defensive defaults:

- bind to loopback by default;
- do not enable permissive CORS;
- validate Host and Origin for state-changing browser requests;
- expose no arbitrary command, path, selector, or shell endpoint;
- accept only server-issued target IDs for actions;
- never return Secret values;
- limit ConfigMap content returned for editing;
- limit output inline size;
- bound operation history;
- bound SSE subscriber queues;
- bound log sessions, buffers, line size, and total bytes;
- cancel work on disconnect where the operation is request-scoped;
- redact tokens, credentials, and Secret data from errors and logs.

The service is not a permanent multi-user control plane. Do not add distributed queues,
databases, ingress, or a separate authorization system unless the deployment model changes.

## Agent Execution Rules

An agent implementing this plan must:

1. Read this document and the related ownership documents before editing.
2. Inspect the current implementation instead of relying on line numbers in this plan.
3. Keep each phase independently buildable and testable.
4. Add presentation-neutral tests before moving logic out of the TUI.
5. Reuse config-processor semantics instead of reproducing them in Python or React.
6. Reuse callable Python logic instead of invoking Click commands through subprocesses.
7. Keep Textual behavior working until cutover, but add no new native-web behavior to it.
8. Preserve stable IDs and exact-node capabilities.
9. Treat cluster/schema defects as fixes in their owning layer.
10. Stop and document a contract ambiguity instead of encoding label-based frontend rules.
11. Run focused tests during development and the phase gate suite before completion.
12. Update this plan's parity and decision records when implementation evidence changes a
    decision.

## Definition Of Done

The conversion is complete when:

- the React application is built and served by FastAPI in the migration-console image;
- the intended port-forward workflow is documented and verified;
- resource state, configuration, output, submit, approval, reset, operations, and logs meet
  the parity checklist;
- partial updates preserve interaction state;
- all consequential actions are explicit, tracked, and revision-safe;
- log pagination and follow behavior have documented guarantees and deterministic stop;
- no native web code imports or serializes Textual/Rich presentation objects;
- `workflow manage --serve` starts the native application;
- the temporary Textual browser compatibility path has an explicit removal plan.
