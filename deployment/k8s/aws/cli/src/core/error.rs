//! Error types for the migration-assistant CLI.
//!
//! The bash CLI used `die <msg>` (exit 1) and a handful of specific exit codes
//! (64 bad-usage, 127 missing-command, 130 SIGINT). We model the same surface:
//! most failures are an [`Error`] carrying a message + an exit code, and `main`
//! maps them onto `std::process::exit`.

use std::fmt;

/// The canonical CLI error. Mirrors bash `die` (rc=1) plus the few special
/// exit codes the dispatcher used.
#[derive(Debug)]
pub struct Error {
    pub message: String,
    pub code: i32,
}

impl Error {
    /// Equivalent of bash `die "<msg>"` — a generic failure with exit code 1.
    pub fn die(message: impl Into<String>) -> Self {
        Self {
            message: message.into(),
            code: 1,
        }
    }

    /// A failure with an explicit exit code (e.g. 64 bad-usage, 127 missing cmd).
    pub fn with_code(message: impl Into<String>, code: i32) -> Self {
        Self {
            message: message.into(),
            code,
        }
    }
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.message)
    }
}

impl std::error::Error for Error {}

impl From<std::io::Error> for Error {
    fn from(e: std::io::Error) -> Self {
        Error::die(e.to_string())
    }
}

/// Crate-wide result type.
pub type Result<T> = std::result::Result<T, Error>;
