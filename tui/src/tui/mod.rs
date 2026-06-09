// tui/mod.rs — pure ratatui render functions.
//
// Design constraint: NOTHING in this module causes side-effects.
// Every function takes `&App` (or a slice of data) + `&mut Frame` and
// returns (). The only output is pixels on the TestBackend.
//
// Tests use ratatui::backend::TestBackend to render to a buffer and
// assert the exact text output — the same technique the ratatui project
// uses for its own widget tests.

pub mod render;
pub mod widgets;
