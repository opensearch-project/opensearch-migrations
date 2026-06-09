// extension/log_streamer.rs — text-stream surface for `workflow log -f`,
// pod-log follow, and metadata migration JAR stdout.
//
// Kept SEPARATE from OpHandle so the type story is clean: OpHandle carries
// structured progress; LogStreamer carries opaque text. The Python TUI
// already reuses this split (PodScraperInterface vs WaiterInterface vs
// LogManager).

use async_trait::async_trait;
use serde::{Deserialize, Serialize};

use super::{ServiceResult, op::OpId};

/// One line emitted by a streaming source. ts and source let the TUI
/// render multi-pod streams interleaved (the way `stern` does).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct LogLine {
    pub op_id: OpId,
    pub ts: Option<String>,        // ISO 8601 if available
    pub source: String,            // pod name / container / service
    pub level: LogLevel,
    pub message: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum LogLevel { Trace, Debug, Info, Warn, Error, Unknown }

/// The TUI calls `subscribe()` to obtain a tokio mpsc receiver; lines flow
/// through it until the underlying op terminates. The runtime's default
/// impl wraps `kubectl logs` / `stern`.
#[async_trait]
pub trait LogStreamer: Send + Sync {
    /// Subscribe to lines for an op. The receiver is closed when the op
    /// terminates (success or failure).
    async fn subscribe(&self, op: OpId)
        -> ServiceResult<tokio::sync::mpsc::Receiver<LogLine>>;
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn log_line_serde_roundtrip() {
        let l = LogLine {
            op_id: OpId(42),
            ts: Some("2026-06-09T12:34:56Z".into()),
            source: "migration-console-0".into(),
            level: LogLevel::Info,
            message: "snapshot uploaded".into(),
        };
        let s = serde_json::to_string(&l).unwrap();
        let d: LogLine = serde_json::from_str(&s).unwrap();
        assert_eq!(l, d);
    }
}
