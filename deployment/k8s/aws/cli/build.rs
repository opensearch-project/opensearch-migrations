//! Build script.
//!
//! The only job here is to make the compile-time `CLI_VERSION` capture
//! (`option_env!("CLI_VERSION")` in `src/version.rs`) react to a changed
//! version without a source edit. Cargo normally won't rebuild when only an
//! environment variable changes; this tells it to rerun (and thus recompile the
//! crate) whenever `CLI_VERSION` differs. The release packaging sets that env
//! var to the release tag; a plain `cargo build` leaves it unset and the binary
//! reports `0.0.0-dev`.

fn main() {
    println!("cargo:rerun-if-env-changed=CLI_VERSION");
}
