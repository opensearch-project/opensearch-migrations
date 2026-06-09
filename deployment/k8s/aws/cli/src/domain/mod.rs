//! Domain layer: the deploy decision logic.
//!
//! Each module is the pure-logic half of one concern — CloudFormation output
//! parsing, image-mirror layout/retry, helm flag building, discovery, the
//! bundle manifest, version resolution, manifest repacking, agent handoff, and
//! the resume timeline. Process I/O is reached through
//! [`crate::runner::CommandRunner`], so the logic is exercised against a mock.

pub mod agent;
pub mod artifact;
pub mod cfn;
pub mod crane;
pub mod discover;
pub mod ecr;
pub mod helm;
pub mod manifest;
pub mod mirror;
pub mod oci;
pub mod pack;
pub mod timeline;
pub mod version;
