//! Error types for the migration-assistant CLI.
//!
//! Most failures are an [`Error`] carrying a message + an exit code (1 generic,
//! 64 bad-usage, 127 missing-command, 130 SIGINT); `main` maps them onto
//! `std::process::exit`.

use std::fmt;

/// The canonical CLI error: a message + exit code (1 generic, 64 bad-usage,
/// 127 missing-command).
#[derive(Debug)]
pub struct Error {
    pub message: String,
    pub code: i32,
}

impl Error {
    /// A generic failure with exit code 1.
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
