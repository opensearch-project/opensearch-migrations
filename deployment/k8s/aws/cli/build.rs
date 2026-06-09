//! Build script.
//!
//! Keeps `CLI_VERSION` as a compile-time fallback for backwards compat. The
//! primary version resolution now happens at *runtime* via `VERSION.txt` (see
//! `src/domain/version.rs :: resolve_cli_version()`). CI can build the binary
//! once and stamp VERSION.txt into the install layout later.
//!
//! This directive ensures Cargo rebuilds the crate when the env var changes,
//! so the compile-time fallback stays fresh for builds that set it.

fn main() {
    println!("cargo:rerun-if-env-changed=CLI_VERSION");
}
