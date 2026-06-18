//! Foundation layer: error/result types, pure utilities, run configuration,
//! per-stage state, the run log, and the external-command seam.
//!
//! These modules have no dependencies on the domain or presentation layers —
//! everything else builds on them.

pub mod config;
pub mod error;
pub mod log;
pub mod runner;
pub mod state;
pub mod util;
