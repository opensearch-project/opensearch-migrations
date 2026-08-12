# Manage Web Framework Spikes

These spikes compare Angular, React, and Vue for the native `workflow manage` frontend.
They are prototypes, not production application scaffolds.

Both applications consume `@manage-spike/shared` and must demonstrate the same behavior:

- a filterable, keyboard-accessible resource tree;
- stable selection, expansion, focus, and scroll across updates;
- patch-based insertion, update, and removal of individual tree nodes;
- animated entry into edit mode without replacing the resource tree;
- a selected-resource detail workspace;
- a representative schema-driven configuration form;
- active operation state;
- a cancellable simulated log stream;
- desktop and narrow layouts.

The component libraries are deliberately headless or lightly styled. OUI and Angular
Material are not part of this comparison.

## Partial Update Requirement

The shared data source emits `TreePatch` values. Applications must reconcile those patches
by stable node ID. They must not replace the tree with a separately keyed edit tree.

Entering edit mode inserts configuration nodes under the selected resource. Adding a
transform inserts another node into that branch. Existing DOM rows should retain identity,
and the selected resource should retain focus unless the user explicitly moves it.

## Evaluation

Compare:

- type narrowing from shared DTO to rendered control;
- amount of framework-specific state and adapter code;
- tree behavior and animation;
- accessibility and keyboard behavior;
- dynamic-form clarity;
- streaming update ergonomics;
- visual flexibility;
- testing experience;
- dependency and build complexity.
