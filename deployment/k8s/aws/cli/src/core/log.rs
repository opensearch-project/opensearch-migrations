//! Append-only run log at `<stage>/log/migrate.log`.
//!
//! The Jenkins CI helper reads `$MIGRATE_HOME/<stage>/log/migrate.log` on
//! failure, so the path and behavior are a stable contract:
//!   * always append (created under `<stage>/log/`);
//!   * mirror to stderr only when verbose;
//!   * rotate once to `migrate.log.1` when the file exceeds 5 MiB;
//!   * write a session header on init so multiple runs are separable.
//!
//! Rendering of operator UI stays in [`crate::ui`] (stderr); this is the
//! durable record a CI operator greps after a failed run.

use std::fs::OpenOptions;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

const ROTATE_BYTES: u64 = 5 * 1024 * 1024;

/// A per-stage logger. Cheap to clone (just a path + a verbose flag).
#[derive(Debug, Clone)]
pub struct Log {
    file: Option<PathBuf>,
    verbose: bool,
}

impl Log {
    /// A no-op logger (used before a stage dir is known, and in tests).
    pub fn disabled() -> Self {
        Self {
            file: None,
            verbose: false,
        }
    }

    /// Initialize logging under `stage_dir/log/migrate.log`, rotating if the
    /// existing file is over 5 MiB, and writing a session header. A failure to
    /// create the dir/file degrades to disabled rather than aborting the run.
    pub fn init(stage_dir: &Path, verbose: bool) -> Self {
        let dir = stage_dir.join("log");
        if std::fs::create_dir_all(&dir).is_err() {
            return Self::disabled();
        }
        let file = dir.join("migrate.log");
        if let Ok(meta) = std::fs::metadata(&file) {
            if meta.len() > ROTATE_BYTES {
                let _ = std::fs::rename(&file, dir.join("migrate.log.1"));
            }
        }
        let log = Self {
            file: Some(file),
            verbose,
        };
        log.write_raw(&format!(
            "\n{bar}\n  migration-assistant session @ epoch {ts}\n{bar}\n",
            bar = "=".repeat(68),
            ts = epoch_secs(),
        ));
        log
    }

    /// The path to the active log file, if any.
    pub fn path(&self) -> Option<&Path> {
        self.file.as_deref()
    }

    fn write_raw(&self, line: &str) {
        if let Some(path) = &self.file {
            if let Ok(mut f) = OpenOptions::new().create(true).append(true).open(path) {
                let _ = f.write_all(line.as_bytes());
            }
        }
    }

    /// Append a level-prefixed line; mirror to stderr when verbose. The level
    /// is one of INFO/WARN/ERROR/DEBUG (DEBUG only emits when verbose).
    pub fn log(&self, level: &str, msg: &str) {
        if level == "DEBUG" && !self.verbose {
            return;
        }
        let line = format!("[{}] {} {}\n", epoch_secs(), level, msg);
        self.write_raw(&line);
        if self.verbose {
            let _ = write!(std::io::stderr(), "{line}");
        }
    }

    /// Append an INFO line.
    pub fn info(&self, msg: &str) {
        self.log("INFO", msg);
    }
    /// Append a WARN line.
    pub fn warn(&self, msg: &str) {
        self.log("WARN", msg);
    }
    /// Append an ERROR line.
    pub fn error(&self, msg: &str) {
        self.log("ERROR", msg);
    }
}

fn epoch_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn init_creates_log_under_stage_log_dir() {
        let dir = tempfile::tempdir().unwrap();
        let stage = dir.path().join("default");
        let log = Log::init(&stage, false);
        let path = log.path().unwrap();
        assert_eq!(path, stage.join("log/migrate.log"));
        assert!(path.is_file(), "log file created on init");
        // The Jenkins helper reads exactly this path.
        assert!(stage.join("log/migrate.log").is_file());
    }

    #[test]
    fn lines_are_appended_with_level() {
        let dir = tempfile::tempdir().unwrap();
        let log = Log::init(&dir.path().join("s"), false);
        log.info("hello");
        log.error("boom");
        let text = std::fs::read_to_string(log.path().unwrap()).unwrap();
        assert!(text.contains("INFO hello"));
        assert!(text.contains("ERROR boom"));
    }

    #[test]
    fn debug_suppressed_unless_verbose() {
        let dir = tempfile::tempdir().unwrap();
        let log = Log::init(&dir.path().join("s"), false);
        log.log("DEBUG", "noisy");
        let text = std::fs::read_to_string(log.path().unwrap()).unwrap();
        assert!(!text.contains("noisy"));
    }

    #[test]
    fn rotation_moves_oversized_log() {
        let dir = tempfile::tempdir().unwrap();
        let stage = dir.path().join("s");
        std::fs::create_dir_all(stage.join("log")).unwrap();
        // Pre-create an oversized log.
        let big = stage.join("log/migrate.log");
        std::fs::write(&big, vec![b'x'; (ROTATE_BYTES + 1) as usize]).unwrap();
        let _log = Log::init(&stage, false);
        assert!(stage.join("log/migrate.log.1").is_file(), "rotated to .1");
    }

    #[test]
    fn disabled_logger_is_noop() {
        let log = Log::disabled();
        assert!(log.path().is_none());
        log.info("ignored"); // must not panic
    }
}
