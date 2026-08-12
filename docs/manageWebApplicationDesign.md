# Workflow Manage Native Web Application

Status: initial direction

This document describes the broad direction for replacing the Textual presentation of
`workflow manage` with a native web application. It is intentionally not a detailed API
specification or a complete UX design.

The existing TUI, its code, and `manageConfig.cast` are references for understanding the
workflow and the functionality users need. They are not a behavioral specification for the
new application. Surprising interactions in the recording should not be preserved merely
for parity, and apparent input loss may be a recording artifact rather than an application
defect.

## Product Direction

Keep the useful shape of the current experience:

- a resource tree for understanding the migration;
- schema-guided workflow configuration;
- visibility into deployed and pending state;
- workflow actions such as submit, approve, and reset;
- access to logs and output.

Move those workflows into an interface built for a browser. Selection, forms, dialogs,
tabs, progress, and streaming content should behave like normal web controls rather than
terminal key modes.

The application will run on demand in the migration-console pod. The user will reach it
through `kubectl port-forward` and open it in a local browser. Authentication, ingress, and
operation as a permanent multi-user service are outside the initial scope.

## Recommended Architecture

Use a React and TypeScript frontend built with Vite, with a FastAPI server in the existing
Python migration-console package.

```text
Browser
  React application
       |
       | HTTP and streaming events
       v
Python manage server
  - presentation-neutral state
  - workflow commands
  - Kubernetes and Argo access
  - long-running operation tracking
       |
       +---- config-processor
       +---- Kubernetes API
       `---- Argo/workflow services
```

The Python server should serve both the compiled application and its API from one origin.
This keeps packaging and port-forwarding simple. A separate Node.js server is unnecessary.

Use ordinary request/response APIs for state, configuration changes, and commands. Use a
streaming response such as server-sent events for changing workflow status, active
operations, and logs. WebSockets can be added later if a genuinely bidirectional use case
appears.

Run a single server process. This matches the on-demand deployment model and avoids
introducing distributed coordination for in-progress actions.

## Ownership Boundaries

The TypeScript config-processor remains the owner of:

- workflow schema and validation;
- transformations and resource projection;
- edit operations and field metadata;
- config paths, descriptions, choices, and reference hints.

Python remains the owner of:

- Kubernetes and Argo access;
- cluster-dependent checks and augmentation;
- workflow submission, approval, reset, logs, and output;
- combining live, submitted, and pending state;
- tracking work started by the web application.

React owns presentation and browser interaction. It should render semantic data from the
server, not parse workflow YAML, infer domain rules from labels, or depend on Rich/Textual
formatting.

This preserves the lower-level work already done on the branch. The web application is a
new presentation and application-service layer over that work, not a rewrite of it.

## Server Shape

The server needs a presentation-neutral manage model rather than a serialized Textual tree.
At a high level, that model should contain:

- stable node IDs and tree relationships;
- resource and workflow status;
- diagnostics;
- deployed, submitted, and pending configuration state;
- workflow progress and known dependencies;
- the actions available for the exact selected node;
- active and recent operations.

Actions should be explicit capabilities. For example, a node either has a rename, reset,
approve, logs, or output action or it does not. The browser should not search ancestors or
nearby nodes for an action.

Long-running commands should return immediately as tracked operations. An operation can
move through queued, running, waiting, succeeded, failed, or cancelled states. The server
continues checking cluster state where a command has an asynchronous effect, while the user
is free to navigate elsewhere.

The exact endpoint and DTO definitions should be designed alongside the first vertical
slice rather than fixed in this initial document.

## Main User Experience

The application opens directly into the working interface:

```text
+------------------------------------------------------------------+
| Workflow, namespace, current state                    Review Submit|
+----------------------+-------------------------------------------+
| Resource tree        | Selected item                              |
|                      | Overview | Configuration | Activity        |
|                      | Logs | Output                              |
|                      |                                           |
+----------------------+-------------------------------------------+
| Active and recent operations                                     |
+------------------------------------------------------------------+
```

### Resource Tree

The tree remains the primary navigation. It should support normal mouse and keyboard
selection, explicit expand/collapse controls, filtering, and stable selection across
refreshes.

Selecting a node changes the detail pane. Destructive or consequential actions live in the
detail pane or an action menu for that exact node. Status and error text are not themselves
hidden action entry points.

### Details and Configuration

The detail pane gives room to explain the selected resource without making every fact a
tree row. It should separate:

- overall status and diagnostics;
- deployed, submitted, and pending values;
- workflow activity;
- logs;
- generated or retained output.

Configuration editing should retain tree navigation where it helps users understand
structure, while using ordinary form controls for the selected field. Schema metadata
drives labels, descriptions, controls, optional/expert fields, and external-resource
pickers.

External references should show the information needed to make a choice. For example, a
ConfigMap-backed file selection should make both the ConfigMap and its available keys
visible.

### Review and Submit

Before submit, show a review of relevant pending changes and validation results. Submission
then becomes a visible operation with understandable phases rather than a blocking command
or a transient notification.

The first design does not need to solve every possible dependency visualization. A clear
list of active work and its current phase is sufficient; richer dependency connections can
follow.

### Approvals and Reset

Approval dialogs should identify what is being approved and the resource or stage it
affects. Reset should continue to use the existing dry-run plan followed by confirmation of
the exact targets.

Both actions should appear in the operation area while the backend and cluster converge.

### Logs and Output

Logs should stream in the application with explicit container selection and a Stop control.
Leaving a terminal pager or killing a second process should never be part of the web flow.

Output should appear in a dedicated view with enough resource, stage, and attempt context
to understand where it came from.

## Deployment Shape

The frontend is compiled during the migration-console build and copied into the same image.
FastAPI serves the static files and API.

The intended lifecycle is:

1. Start the manage web server in the migration-console pod.
2. Start or reuse a managed `kubectl port-forward`.
3. Open the local browser.
4. Stop the server when the management session is over.

The server should bind to loopback by default and make wider binding explicit. It does not
need authentication in the port-forward-only model, but it should still avoid CORS,
arbitrary shell-command APIs, and returning Secret values.

The existing `textual-serve` mode can remain temporarily while the native application is
built. New browser-specific behavior should target the native application.

## Implementation Sequence

### 1. Read-Only Vertical Slice

Build the server process, static application packaging, resource tree, detail pane, status
refresh, and one log stream. This validates the architecture and port-forward lifecycle
without moving destructive actions.

### 2. Configuration

Expose the existing schema-guided edit model through the server. Add natural form controls,
external-reference selection, pending changes, save behavior, and submit review.

The save and concurrency model should be decided here. Reasonable options include explicit
save with revision checks or carefully signaled autosave to the pending config.

### 3. Workflow Actions

Add tracked submit, approval, and reset operations. Reuse the current command and service
logic, extracting it from TUI callbacks where necessary.

### 4. Refinement

Use real workflows and additional recordings to refine hierarchy, status language,
validation placement, operation progress, responsive behavior, and dependency
visualization. Backend/schema defects found during this work remain fixes in their owning
layers rather than frontend special cases.

## Testing Direction

The initial test strategy should include:

- TypeScript contract tests for schema-driven edit metadata;
- Python tests for state construction and workflow operations;
- API tests with fake Kubernetes and Argo services;
- frontend component tests for tree, forms, and operation state;
- Playwright flows at desktop and narrow viewport sizes;
- focused Kubernetes integration tests for logs and consequential actions.

The recording is useful design research and can inspire small scenarios. It should not be
replayed as a golden test or treated as the source of truth for every observed behavior.

## Questions for Detailed Design

The next design pass should settle:

- the precise state and edit DTOs;
- explicit save versus autosave;
- the first component library and tree implementation;
- how status refresh and operation events share one stream;
- operation cancellation and history limits;
- the CLI flag and transition from `textual-serve`;
- the minimum useful Review/Submit diff;
- which dependency relationships are reliable enough to expose initially.
