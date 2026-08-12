# Framework Spike Evaluation

Use the same browser width, selected node, and interaction sequence for both applications.
Record findings after the implementations are complete; do not score scaffolding or
framework familiarity.

## Required Interaction Sequence

1. Expand Live Traffic Migration, Capture, and Replay.
2. Select and keyboard-focus `capture-proxy`.
3. Trigger a status refresh.
4. Verify the focused `capture-proxy` row is the same DOM element and remains focused.
5. Enter edit mode.
6. Verify configuration rows animate into the existing branches.
7. Verify `capture-proxy` remains selected and focused.
8. Select Trusted client CA and use the ConfigMap-plus-key control.
9. Select `traffic-replayer`, add a transform, and verify only that branch changes.
10. Start logs, navigate elsewhere, return, and stop logs.
11. Repeat at a narrow viewport.
12. Repeat with reduced-motion enabled.

## Functional Evidence

| Area | Angular | React | Vue |
| --- | --- | --- | --- |
| Existing row DOM identity survives update | Pass | Pass | Pass |
| Focus survives status update | Pass | Pass | Pass |
| Focus survives edit-node insertion | Pass | Pass | Pass |
| Expansion survives all patches | Pass | Pass | Pass |
| Insertions are localized and animated | Pass | Pass | Pass |
| Reduced motion is respected | Pass | Pass | Pass |
| Tree keyboard navigation is coherent | Pass | Pass | Pass |
| Config control is fully keyboard usable | Pass | Pass | Pass |
| Log stream is visibly cancellable | Pass | Pass | Pass |
| Narrow layout has no overlap or clipping | Pass | Pass | Pass |

## Engineering Comparison

For each implementation, record:

- compile-time exhaustiveness of `ConfigControl`;
- framework-specific adapter and state code;
- dependencies added;
- production build output size;
- unit/component test setup and runtime;
- clarity of recursive tree rendering;
- clarity of focus and expansion ownership;
- ease of animating insert/remove without delaying state;
- ease of testing asynchronous patches;
- any framework escape hatch required by a specialized widget.

## Decision Notes

Do not select a framework solely from default appearance. The relevant question is which
implementation gives the best control over a specialized, continuously updating operations
UI while retaining strong types and predictable interaction state.
