//! Presentation layer: terminal output discipline and the Ratatui front-end.
//!
//! [`ui`] holds the stderr-only message helpers plus the pure decision bits
//! (the confirm truth table, mode-pick matching). [`tui`] is the immediate-mode
//! Ratatui wizard + resume timeline. [`dashboard`] is the responsive,
//! scrollback-friendly deploy dashboard (timeline + activity log), all built
//! The-Elm-Architecture way.

pub mod dashboard;
pub mod tui;
pub mod ui;
