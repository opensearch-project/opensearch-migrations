// extension/op.rs — Long-running operation handles.
//
// Every method that takes more than ~100ms returns an OpHandle. The TUI
// stores it in the App and polls in the event loop — update() stays pure
// and synchronous. This is THE pattern that lets us property-test the
// state machine without async runtime.
//
// Concrete examples from the real Python CLI:
//   - `console snapshot create --wait`         → progress events
//   - `console snapshot status --deep-check`   → eventually-available deep status
//   - `console backfill start/stop/scale`      → polls RfsWorkersInProgress every 5s
//   - `workflow submit --wait`                 → Argo phase transitions
//   - `workflow log -f`                         → streams stern lines (separate trait)
//
// Splitting OpHandle (control) from LogStreamer (streaming text) keeps the
// type story clean: OpHandle carries STRUCTURED progress, LogStreamer
// carries OPAQUE text.

use serde::{Deserialize, Serialize};
use std::sync::Arc;
use tokio::sync::Mutex;

/// Unique id assigned by the runtime when the op starts. The TUI uses
/// this id to look up the handle in App state and to cancel the op.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub struct OpId(pub u64);

/// Lifecycle status. Maps directly to Argo's phase enum + "cancelled".
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum OpStatus {
    Pending,    // queued / not yet started
    Running,    // executing
    Succeeded,  // terminal: ok
    Failed,     // terminal: error
    Cancelled,  // terminal: user-cancelled (Ctrl-C semantics)
}

impl OpStatus {
    pub fn is_terminal(self) -> bool {
        matches!(self, Self::Succeeded | Self::Failed | Self::Cancelled)
    }
}

/// One progress update emitted by a running op. Variants cover every
/// progress shape the real Python CLI emits — no free-form text in
/// Progress (text is for LogStreamer).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum OpUpdate {
    /// Op moved to a new lifecycle status.
    StatusChanged(OpStatus),

    /// Quantitative progress: numerator/denominator and optional ETA ms.
    /// Maps to BackfillOverallStatus { shard_complete, shard_total, eta_ms }.
    Progress { complete: u64, total: u64, eta_ms: Option<u64> },

    /// Named milestone (e.g., "snapshot uploaded", "indices created").
    Milestone(String),

    /// Op finished with a structured result (JSON from the underlying CLI).
    /// The App pattern-matches on the consumer side to extract typed data.
    Result(serde_json::Value),

    /// Op finished with an error. Carries the displayable message.
    Error(String),
}

/// Handle returned to the App when it asks the runtime to start an op.
/// The handle is `Send + Sync` so it can be moved into a tokio task and
/// also held by the App (via Arc<Mutex<...>>).
pub struct OpHandle {
    pub id: OpId,
    pub label: String,                    // e.g., "backfill start"
    pub status: Arc<Mutex<OpStatus>>,
    pub last_update: Arc<Mutex<Option<OpUpdate>>>,
    pub cancel: Arc<dyn Fn() + Send + Sync>,
}

impl std::fmt::Debug for OpHandle {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("OpHandle")
            .field("id", &self.id)
            .field("label", &self.label)
            .finish_non_exhaustive()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn terminal_classification() {
        assert!(!OpStatus::Pending.is_terminal());
        assert!(!OpStatus::Running.is_terminal());
        assert!(OpStatus::Succeeded.is_terminal());
        assert!(OpStatus::Failed.is_terminal());
        assert!(OpStatus::Cancelled.is_terminal());
    }

    #[test]
    fn op_update_serde_roundtrip() {
        let cases = vec![
            OpUpdate::StatusChanged(OpStatus::Running),
            OpUpdate::Progress { complete: 7, total: 10, eta_ms: Some(5_000) },
            OpUpdate::Milestone("snapshot-uploaded".into()),
            OpUpdate::Error("timeout".into()),
        ];
        for u in cases {
            let s = serde_json::to_string(&u).unwrap();
            let d: OpUpdate = serde_json::from_str(&s).unwrap();
            assert_eq!(u, d);
        }
    }
}
