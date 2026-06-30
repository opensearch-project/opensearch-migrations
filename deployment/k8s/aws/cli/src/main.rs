//! Binary entry point.
//!
//! Thin wrapper, mirroring `bin/migration-assistant`: collect argv, dispatch
//! against the real command runner, and exit with the resulting code. All the
//! logic lives in the library so it can be unit-tested with a mock runner.

use migration_assistant::cli;
use migration_assistant::runner::RealRunner;

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let code = cli::dispatch(&args, &RealRunner);
    std::process::exit(code);
}
