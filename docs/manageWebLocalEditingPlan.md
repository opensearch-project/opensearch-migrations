# Workflow Manage Browser-Local Editing Plan

Status: implemented through PR 7 on 2026-09-12

This document describes how to move routine workflow configuration editing out
of the FastAPI request path and into the browser without moving Kubernetes,
workflow, or persistence authority into the frontend. It is a follow-up to:

- [Workflow Manage Native Web Application](manageWebApplicationDesign.md)
- [Workflow Manage Native Web Conversion Plan](manageWebConversionPlan.md)
- [Manage External Configuration References](manageExternalConfigurationReferencesDesign.md)

The implementation should be delivered as stacked pull requests. Workflow
submission must remain usable throughout the transition.

## Decisions Superseded From The Initial Conversion

The initial web conversion kept unsaved drafts on the server and directed React
not to parse workflow YAML. This follow-up supersedes those two decisions:

- the unsaved draft moves into browser memory; and
- a shared `orchestrationSpecs` package, not React presentation code, parses
  and projects the workflow YAML in the browser.

React still does not implement configuration semantics. It invokes the same
TypeScript implementation used by the CLI and server. Saved configuration and
all cluster-dependent behavior remain authoritative on the server.

## Problem

The current configuration editor sends every committed edit operation to
FastAPI. Even a boolean change follows this path:

1. React posts the operation and globally blocks interaction.
2. FastAPI checks the process-local draft revision.
3. Python creates temporary configuration and operation files.
4. Python starts a one-shot Node.js config-processor process.
5. TypeScript reparses the whole configuration, applies one operation, runs
   validation and transformation, and rebuilds the full edit model.
6. Python queries Kubernetes to annotate configured Secret and ConfigMap
   references.
7. Python compares the draft to its saved base, projects configuration
   navigation over the resource graph, and serializes the complete result.
8. React replaces its cached draft and unblocks the application.

The one-shot config-processor invocation takes approximately 300 milliseconds
for a trivial edit on a development machine before HTTP, Kubernetes, and EKS
latency are included. Running the same TypeScript edit logic in an already
loaded process takes approximately 6.5 milliseconds for the current sample
configuration. Process startup, remote validation, and whole-application
locking dominate the user experience.

Routine editing does not require server authority. The workflow schema, edit
operations, defaults, references, and most validation are already implemented
in TypeScript. They can be shared directly by the CLI, server, and browser.

## Objectives

- Apply ordinary configuration changes immediately in the browser.
- Keep workflow configuration semantics in `orchestrationSpecs`.
- Use the same TypeScript implementation in the browser, CLI, and server.
- Keep saved configuration, Kubernetes state, and workflow operations
  authoritative on the server.
- Run environment-dependent diagnostics asynchronously without blocking edits.
- Continue refreshing runtime resources, dependencies, operations, and status
  while the user is editing.
- Preserve revision conflict detection when configuration is modified outside
  the current browser.
- Repeat authoritative validation during save and submit.
- Remove the process-local mutable server draft after the browser path is
  proven equivalent.

## Non-Goals

- Moving Kubernetes credentials or clients into the browser.
- Treating browser validation as sufficient for persistence or submission.
- Storing workflow drafts in local storage.
- Building a general collaborative editor in the first implementation.
- Sending Kubernetes Secret values to the browser.
- Replacing the existing workflow submission, approval, reset, log, output, or
  status implementations.
- Implementing incremental resource-graph patches before full snapshot refresh
  is shown to be insufficient.

## Target Architecture

```text
Browser
  React application
    in-memory configuration draft
    browser-safe TypeScript edit engine
    locally derived edit tree and navigation
    local draft overlay on the server resource graph
    asynchronous diagnostic results
          |
          | configuration load/save and remote commands
          v
FastAPI
  saved configuration revision checks
  Kubernetes and environment diagnostics
  resource graph snapshots and invalidation events
  workflow submit, approve, reset, logs, output, and status
          |
          +---- Kubernetes and Argo
          +---- WorkflowConfigStore
          `---- config-processor Node adapter for authoritative checks
```

The browser provides immediate feedback, but it is not a trust boundary.
FastAPI and the config-processor must repeat validation before saving or
submitting because:

- browser code or state can be stale;
- configuration may have been edited outside the web application;
- Kubernetes resources and admission policies can change while editing;
- environment-dependent checks cannot be performed authoritatively in the
  browser; and
- the exact saved or submitted document must be checked, not an earlier local
  projection.

## Ownership Boundaries

### Shared TypeScript Editing Core

The browser-safe editing package owns:

- parsing and serializing structured workflow configuration;
- `set`, `unset`, `add`, `renameConfig`, and `removeConfig`;
- schema and Zod validation that does not require external state;
- generic and workflow-specific edit-node projection;
- field labels, descriptions, choices, defaults, and expert/optional metadata;
- automatic selection when exactly one referenced definition exists;
- rename propagation to dependent configuration references;
- draft-versus-saved comparison;
- configuration dependency and removal-impact analysis; and
- configuration-derived resource identities and graph overlay hints.

This logic remains under `orchestrationSpecs/packages`. A likely layout is:

```text
orchestrationSpecs/packages/
  config-edit-core/
  config-processor/
  schemas/
```

`config-edit-core` is consumed by both `config-processor` and
`migrationConsole/web`. The package must not depend on Node.js filesystem,
stream, DNS, process-environment, or Kubernetes APIs.

The implementation may instead expose a browser-safe subpath from
`config-processor` if that produces a clearer dependency boundary. The
ownership and browser-compatibility requirements are more important than the
exact package name.

### FastAPI And Python Application Services

The server remains responsible for:

- reading and writing the saved configuration ConfigMap;
- comparing the expected persisted revision with current cluster state;
- Kubernetes Secret, ConfigMap, CR, image, endpoint, and bucket inspection;
- resource graph construction from configured, submitted, deployed, and
  workflow state;
- admission dry-runs and VAP classification;
- full submission transformation and workflow creation;
- approvals, resets, logs, managed output, and runtime status; and
- tracking consequential operations.

### React

React owns:

- the in-memory unsaved draft;
- applying shared edit-core results to visible controls;
- presentation grouping, filtering, sorting, linking, and layout;
- combining the local draft overlay with the latest server resource graph;
- deciding which diagnostics are visible for the current resource;
- busy-state presentation; and
- navigation and animation state.

React must not duplicate schema, reference, rename, dependency, or removal
semantics. Those remain in the shared TypeScript package.

## Configuration Document Contract

The initial configuration response should provide the saved document and the
revision required for conflict detection:

```ts
interface ConfigurationDocument {
  rawYaml: string;
  persistedRevision: string;
  modelVersion: string;
  deploymentDefaults?: DeploymentDefaults;
}
```

`EditEnvironment` is intentionally not part of this design. That name is too
broad and would allow implicit server process state to leak into editing.

The unified schema should be bundled with the shared editing package.
Submit-only environment resolution remains in `config-processor`. If editing
genuinely requires deployment-specific defaults, define and version only those
specific values in `DeploymentDefaults`. Prefer deterministic editing based
only on the configuration and bundled schema.

Saving should send the exact browser document and its saved base revision:

```ts
interface SaveConfigurationRequest {
  expectedPersistedRevision: string;
  rawYaml: string;
}
```

The server must:

1. confirm that `expectedPersistedRevision` is still current;
2. parse and validate the exact submitted YAML;
3. persist it through `WorkflowConfigStore`;
4. return the new persisted revision; and
5. publish configuration invalidation.

A Kubernetes `resourceVersion` precondition should be used when practical so
the read-check-write sequence is not vulnerable to a concurrent update between
the application revision check and ConfigMap patch.

## Resource Graph And Refresh Model

The server owns the domain resource graph. It should send stable resource and
activity identities plus semantic relationships and state:

- configured, submitted, and deployed resource state;
- resource dependencies;
- workflow steps and activities;
- approvals and blockers;
- runtime status;
- operations; and
- relevant timestamps.

The browser derives presentation from that graph:

- left-navigation grouping;
- configured/deployed/pending filters;
- right-pane dependency layout;
- deep links and reference links;
- sorting;
- collapse and expansion state; and
- transitions and animations.

The local configuration draft produces an immediate overlay on the latest
server graph. A newly added, renamed, or removed resource is visible before any
server round trip.

Runtime graph updates continue through the existing SSE invalidation mechanism
with polling as a fallback. Initially, an invalidation event should advertise a
new graph revision and the browser should fetch a complete graph snapshot.
React Query structural sharing and stable IDs can preserve unaffected browser
objects. Incremental graph patches should be introduced only if full snapshot
refresh is measured as a problem.

Saved configuration refresh follows these rules:

| Browser state | Saved configuration changes remotely |
| --- | --- |
| Outside edit mode | Reload automatically |
| Editing without local changes | Reload automatically |
| Editing with local changes | Keep the draft, mark its base stale, and require conflict resolution |

Runtime graph updates do not replace the local configuration draft. They are
reconciled underneath its overlay.

The first conflict experience may offer reload/discard and retain-local-copy
choices. Automatic three-way rebasing can be added later, but silent overwrite
is not acceptable.

## Interaction And Locking Model

| Interaction | Waiting behavior |
| --- | --- |
| Boolean, text, select, add, rename, remove | No server wait and no global lock |
| Local validation and projection | No global lock |
| Remote diagnostics | Continue in the background |
| Secret or ConfigMap inventory | Lock only the picker contents |
| Secret or ConfigMap creation/update | Lock only the creation dialog and its triggering field |
| Save | Disable conflicting save/revert/submit controls |
| Submit, reset, approve | Disable the relevant command and resource action |
| Logs and status refresh | No editing lock |

A save should capture local draft sequence `N`. The user may continue editing
sequence `N+1` while the save is in progress. When the server confirms `N`, it
becomes the new saved baseline and later edits remain dirty. If this snapshot
model proves too complex for the first implementation, temporarily lock only
the configuration workspace during save, never the whole application.

## Asynchronous Diagnostics

Environment-dependent checks are separate from editing and local schema
validation. Candidate checks include:

- Secret or ConfigMap existence, type, and required keys;
- endpoint reachability;
- bucket existence and access;
- image or file-source availability;
- credential usability; and
- Kubernetes capability or permission checks.

Each diagnostic request carries a draft fingerprint and the resource or field
identity being checked. Results use explicit lifecycle state:

```text
Not checked | Checking | Valid | Warning | Error | Stale
```

Changing a relevant field cancels the request when possible and marks previous
results stale. Unrelated edits do not invalidate unrelated checks. Results may
be cached by cluster, namespace, resource identity, Kubernetes
`resourceVersion`, and draft fingerprint.

Diagnostics should appear in a resource-oriented pane and may stream partial
results. They do not prevent continued editing. Submit repeats applicable
checks because the configuration and environment may have changed.

## Implementation Plan And Stacked Pull Requests

### PR 1: Extract The Browser-Safe Editing Core

Branch objective: separate pure edit semantics without changing runtime
behavior.

- Create `config-edit-core` or an equivalent browser-safe package boundary
  under `orchestrationSpecs/packages`.
- Move or expose edit operations, edit projection, local validation, defaults,
  reference handling, rename propagation, and dependency analysis.
- Inject the unified schema rather than reading it through process environment.
- Separate Node.js DNS, crypto, streams, filesystem, and CLI entry points.
- Keep `config-processor` importing the extracted implementation.
- Add parity fixtures proving the old Node adapter and shared core return
  identical configuration and edit-state results.
- Add scale benchmarks for representative configurations.

No API or visible web behavior changes in this PR.

### PR 2: Add The Revisioned Configuration Document API

Branch objective: introduce stateless load and authoritative save while
temporarily preserving the existing server draft API.

- Add the configuration document response.
- Add save-by-persisted-revision.
- Revalidate the exact save payload server-side.
- Publish saved-configuration invalidation.
- Add ConfigMap concurrency tests.
- Keep the server draft behavior available temporarily as a compatibility
  path. PR 7 removes it after the browser path is proven.

Workflow submit continues using the existing path.

### PR 3: Run Configuration Draft Editing Locally

Branch objective: remove HTTP from ordinary edit operations.

- Load the saved YAML and initialize an in-memory browser draft.
- Apply all five edit operations through `config-edit-core`.
- Rebuild validation and edit projection locally.
- Preserve raw repair mode for malformed or unrepresentable YAML.
- Keep drafts in memory; local storage remains limited to display preferences.
- Preserve save, discard, exit, and browser navigation prompts.
- Remove the global lock from local edits.
- Add a Web Worker if scale measurements exceed the main-thread frame budget.
- Retain a temporary fallback to the server draft path until PR 7.

Workflow submit remains usable. The browser saves through the new API before
invoking the existing submission path.

### PR 4: Overlay Drafts On The Server Resource Graph

Branch objective: make navigation and dependency presentation immediate while
runtime state continues to refresh.

- Treat the server response as the semantic resource graph.
- Move draft navigation, grouping, reference links, and removal impact to the
  shared TypeScript/browser projection.
- Overlay local configured resources and relationships on the latest graph.
- Reconcile full graph snapshots by stable ID and revision.
- Handle external saved-configuration updates according to the refresh table
  above.
- Preserve selection, expansion, scroll position, and graph animation across
  refreshes.
- Remove the Python draft-navigation and dependency-analysis implementations
  only after fixture parity is established.

### PR 5: Add Asynchronous Diagnostics And Scoped Busy States

Branch objective: separate infrastructure validation from editing.

- Add revisioned, cancellable remote diagnostics.
- Move configured Secret and ConfigMap validation out of every edit operation.
- Render diagnostic lifecycle and stale results in resource context.
- Scope external-resource inventory and mutation waits to their dialogs.
- Scope command waits to affected actions.
- Remove the whole-application interaction shield.

### PR 6: Cut Submission Over To Saved Revisions

Branch objective: remove submission's dependency on a process-local server
draft without replacing the submit implementation.

- Ensure the exact locally reviewed document is saved by revision.
- Invoke the existing authoritative validation, transformation, admission
  preflight, workflow creation, and operation tracking.
- Keep CLI and web submission checks equivalent.
- Reject stale saved revisions rather than submitting a different document.
- Add end-to-end coverage for edit, save, review, preflight, submit, and
  concurrent external configuration changes.

This PR changes the source of the submit input, not the workflow submission
semantics.

### PR 7: Remove The Legacy Draft Path

Branch objective: delete migration scaffolding after the new path has proven
equivalent.

- Remove per-field `/config/operations`.
- Remove the process-local mutable configuration draft.
- Remove per-edit Node.js subprocess execution.
- Remove superseded Python draft comparison, dependency, and navigation code.
- Remove compatibility feature flags and adapters.
- Retain the Node adapter used by CLI and authoritative server checks.
- Update architecture and operating documentation.

This PR intentionally adds no user-facing functionality.

Implementation result:

- ordinary edits, projection, comparison, dependency analysis, removal impact,
  and navigation run through `config-edit-core` in browser memory;
- FastAPI persists revisioned documents and owns environment-dependent work;
- submit operates on an exact saved revision;
- external-resource endpoints are stateless with respect to editing; and
- the legacy server draft, per-edit subprocess, compatibility flag, and
  duplicate Python projection code have been removed.

## Testing Strategy

### Shared Package

- Golden fixtures for valid, invalid, partial, and raw-repair configurations.
- Operation parity for set, unset, add, rename, and remove.
- Reference-default and rename propagation coverage.
- Dependency and removal-impact coverage.
- Browser and Node runtime parity.
- Large synthetic configurations for scaling.

### FastAPI

- Persisted revision conflicts.
- ConfigMap `resourceVersion` conflicts.
- Server revalidation rejecting tampered or stale browser results.
- Configuration invalidation.
- Diagnostic cancellation and stale-result handling.
- Existing submit, approval, reset, output, log, and status coverage.

### React

- Boolean, choice, and structural changes issue no HTTP requests.
- Immediate field, validation, navigation, and dependency updates.
- Runtime graph refresh does not erase local edits.
- Remote configuration changes produce the correct reload or conflict state.
- Busy indicators affect only the intended controls.
- Save sequence handling preserves edits made during save.
- Reload and exit discard unsaved in-memory drafts as specified.

### End To End

- Local kind cluster editing and submit.
- Remote EKS editing with artificial latency.
- Configuration modified through the CLI while the browser is editing.
- Kubernetes resource changes while diagnostics are running.
- Browser refresh, disconnect, and SSE reconnect.
- Large workflows with multiple sources, targets, snapshots, proxies,
  migrations, and replayers.

## Acceptance Criteria

- Ordinary field and structural edits do not call FastAPI.
- Ordinary edits never display a whole-application interaction shield.
- Local validation and navigation update within the agreed performance budget.
- Runtime status continues updating while the user edits.
- Remote diagnostics never overwrite or block a newer local draft.
- Saved configuration conflicts are detected and never silently overwritten.
- Save and submit repeat authoritative validation.
- CLI configuration and submission behavior continue to use the same shared
  TypeScript semantics.
- Workflow submit remains functional in every stacked PR.
- The final implementation has one edit-semantics implementation, not parallel
  browser and server copies.
