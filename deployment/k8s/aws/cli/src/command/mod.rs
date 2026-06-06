//! Command layer: the deploy orchestrator and the CLI dispatcher.
//!
//! [`app`] is the `manual_path` deploy pipeline as a state machine over the
//! runner. [`cli`] is the clap-style dispatcher mapping subcommands to handlers.
//! [`pack_cmd`] is the filesystem/tar side of the `pack` subcommand on top of
//! the pure merge logic in [`crate::pack`].

pub mod app;
pub mod cli;
pub mod pack_cmd;
