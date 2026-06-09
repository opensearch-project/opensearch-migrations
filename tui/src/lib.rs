// lib.rs — public API surface for integration tests and the binary.
//
// Layer map (arrows point down — upper layers NEVER import lower):
//
//   ┌──────────────────────────────────────────────────────────────┐
//   │  main.rs   binary: event loop, terminal setup/teardown,      │
//   │            wires extension impls into App                    │
//   └─────────────────────────┬────────────────────────────────────┘
//                             │ imports
//   ┌─────────────────────────▼────────────────────────────────────┐
//   │  tui/      ratatui rendering. Reads &App, emits frames.      │
//   │            Pure (no I/O of its own).                         │
//   └─────────────────────────┬────────────────────────────────────┘
//                             │ imports
//   ┌─────────────────────────▼────────────────────────────────────┐
//   │  domain/   App, Runtime, Message, Confirmation. Zero I/O,    │
//   │            zero async, zero ratatui. Hammered by proptest.   │
//   └─────────────────────────┬────────────────────────────────────┘
//                             │ imports types from
//   ┌─────────────────────────▼────────────────────────────────────┐
//   │  extension/  Trait contracts to the outside world.           │
//   │              ConfigStore, WorkflowService, OpHandle, etc.    │
//   │              Impls live in main.rs (or a future bin crate).  │
//   └──────────────────────────────────────────────────────────────┘

pub mod domain;
pub mod tui;
pub mod extension;
pub mod version;
