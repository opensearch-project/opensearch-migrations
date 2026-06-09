//! migration-assistant — OpenSearch Migration Assistant CLI.
//!
//! Idiomatic Rust + Ratatui implementation. The design separates pure decision
//! logic (testable in isolation) from external-process I/O (behind the
//! [`runner::CommandRunner`] trait), so the orchestration that drives `aws` /
//! `kubectl` / `helm` can be exercised against a mock. ECR auth and image
//! mirroring use native SDK/OCI clients (`ecr`, `oci` modules) rather than
//! shelling out to `aws ecr` or `crane`.
//!
//! ## Layers
//!
//! The source is grouped into four layered folders; each leaf module is
//! re-exported at the crate root below, so the public path is a flat
//! `migration_assistant::<module>` regardless of which folder it lives in.
//!
//! * [`core`] — error/result, pure utils, config, state, run log, the
//!   `CommandRunner` seam.
//! * [`domain`] — deploy decision logic: cfn, crane, helm, discover, manifest,
//!   version, pack, agent, timeline.
//! * [`view`] — presentation: ui (output discipline) + the Ratatui tui.
//! * [`command`] — the deploy orchestrator (`app`), the dispatcher (`cli`), and
//!   the `pack` subcommand glue (`pack_cmd`).

mod command;
mod core;
mod domain;
mod view;

// Re-export every leaf module at the crate root. This keeps the internal API a
// flat `crate::<module>` (and the public API `migration_assistant::<module>`)
// while the source lives in layered folders — the standard Rust facade pattern.
pub use command::{app, cli, pack_cmd};
pub use core::{config, error, log, runner, state, util};
pub use domain::{
    agent, artifact, cfn, crane, discover, ecr, helm, manifest, mirror, oci, pack, timeline,
    version,
};
pub use view::{dashboard, tui, ui};

pub use error::{Error, Result};
pub use runner::{CommandRunner, MockRunner, Output, RealRunner};
