// domain/mod.rs — pure domain model.
//
// No I/O. No ratatui. No async. Every function is a pure transformation.
// This is the layer that proptest hammers and unit tests cover exhaustively.
//
// Architecture:
//   confirmation : typed-token modal (destructive-op gate)
//   runtime      : the post-deploy operational state machine
//   message      : keypress → RuntimeEvent translation
//   app          : top-level state owning Runtime + quit/size
//
// Extension contracts (services the App calls into) live under crate::extension.

pub mod confirmation;
pub mod runtime;
pub mod message;
pub mod app;
