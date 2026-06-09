// domain/runtime.rs — pure runtime state for post-deploy TUI mode.
//
// After the bash-CLI-equivalent deploy completes, the TUI switches into
// runtime mode where the user drives the migration assistant: views
// workflow progress, edits config, approves gates, follows logs, runs
// destructive ops with explicit confirmation.
//
// This module is the PURE state model for that mode. No I/O, no async,
// no ratatui. Tests hammer it; the App glues it to async services.
//
// State is a value type. Every transition is `(Self, Event) -> Self`.
//
// Layered on top of (not replacing) DeployState — once the deploy is done
// we keep DeployState read-only for "where did this come from?" telemetry,
// and Runtime owns everything the user does after that.

use serde::{Deserialize, Serialize};

use crate::domain::confirmation::Confirmation;
use crate::extension::{
    cluster::BackfillStatus,
    config::{ConfigDoc, ValidationReport},
    op::{OpId, OpStatus, OpUpdate},
    workflow::{ApprovalGate, WorkflowStatus},
};

/// Which runtime panel is showing. Each variant maps 1:1 to a render path.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RuntimePanel {
    /// Workflow tree (Argo step_tree) — the home screen.
    WorkflowView,
    /// Editing the YAML config doc.
    ConfigEdit,
    /// Tail of one op's log stream.
    LogView { op_id: OpId },
    /// Approval gates list (the three categories).
    Approvals,
    /// Backfill / Snapshot / Replay deep-status drilldown.
    Status,
    /// Modal: typed-token confirmation in front of any other panel.
    /// `behind` carries the panel to return to after resolve/cancel.
    Confirm { behind: Box<RuntimePanel>, confirmation: Confirmation },
}

/// One in-flight or recently-finished op the user can see in the status bar.
/// We DON'T store the OpHandle here (it's not Clone-safe); the App side
/// keeps handles in a separate map keyed by OpId. Runtime just tracks
/// what we KNOW about each op for rendering.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct OpRecord {
    pub id: OpId,
    pub label: String,
    pub status: OpStatus,
    /// Most recent progress reading (numerator/denominator/ETA).
    pub progress: Option<Progress>,
    /// Most recent named milestone.
    pub milestone: Option<String>,
    /// Last error message, if any (only populated when status = Failed).
    pub error: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct Progress {
    pub complete: u64,
    pub total: u64,
    pub eta_ms: Option<u64>,
}

impl Progress {
    /// Percentage complete in [0.0, 100.0]. 0.0 when total == 0.
    pub fn pct(self) -> f64 {
        if self.total == 0 { 0.0 } else { (self.complete as f64) / (self.total as f64) * 100.0 }
    }
}

/// The full runtime application state. Held by App as `Option<Runtime>`
/// (None until deploy completes).
#[derive(Debug, Clone, PartialEq)]
pub struct Runtime {
    /// Current panel. Confirm wraps another panel as `behind`.
    pub panel: RuntimePanel,

    /// Most recent workflow status snapshot, if any.
    pub workflow: Option<WorkflowStatus>,

    /// Most recent backfill status snapshot, if any.
    pub backfill: Option<BackfillStatus>,

    /// Pending approval gates last fetched from ApprovalService.
    pub gates: Vec<ApprovalGate>,

    /// Tracked ops, ordered by start. Indexed by OpId for fast lookup.
    pub ops: Vec<OpRecord>,

    /// The user's config-doc draft. Two slots: `committed` is the last
    /// successful save; `draft` is what's in the editor right now.
    pub committed_config: Option<ConfigDoc>,
    pub draft_config: Option<ConfigDoc>,
    /// Last validation result for `draft`. Drives the inline error gutter.
    pub draft_validation: Option<ValidationReport>,

    /// Last surfaced error string (top-of-screen banner). Cleared on next
    /// successful event for the same panel.
    pub error_banner: Option<String>,
}

impl Default for Runtime {
    fn default() -> Self {
        Runtime {
            panel: RuntimePanel::WorkflowView,
            workflow: None,
            backfill: None,
            gates: vec![],
            ops: vec![],
            committed_config: None,
            draft_config: None,
            draft_validation: None,
            error_banner: None,
        }
    }
}

/// Events that mutate Runtime. Pure data; delivered by the App's outer
/// update loop. Distinct from domain::Message because runtime events come
/// from async services and shouldn't share the keypress namespace.
#[derive(Debug, Clone, PartialEq)]
pub enum RuntimeEvent {
    // ── panel navigation ────────────────────────────────────────────────
    SwitchPanel(RuntimePanel),

    // ── workflow + status feeds ─────────────────────────────────────────
    WorkflowStatusReceived(WorkflowStatus),
    BackfillStatusReceived(BackfillStatus),
    GatesReceived(Vec<ApprovalGate>),

    // ── op lifecycle ────────────────────────────────────────────────────
    OpStarted { id: OpId, label: String },
    OpUpdated { id: OpId, update: OpUpdate },

    // ── config editor ───────────────────────────────────────────────────
    ConfigLoaded(ConfigDoc),
    DraftSet(ConfigDoc),
    DraftValidated(ValidationReport),
    ConfigSaved(ConfigDoc),

    // ── confirmation modal ──────────────────────────────────────────────
    /// Open a typed-token confirmation in front of the current panel.
    OpenConfirm(Confirmation),
    /// User typed a char into the confirmation buffer.
    ConfirmChar(char),
    /// User pressed backspace in the confirmation buffer.
    ConfirmBackspace,
    /// User dismissed (Esc or no-button).
    ConfirmCancel,

    // ── error reporting ─────────────────────────────────────────────────
    SetError(String),
    ClearError,
}

impl Runtime {
    /// Pure update. Same contract as App::update: never panics, always
    /// returns a new Runtime, errors set `error_banner` rather than escape.
    pub fn update(self, ev: RuntimeEvent) -> Runtime {
        use RuntimeEvent::*;
        match ev {
            SwitchPanel(p) => Runtime { panel: p, ..self },

            WorkflowStatusReceived(ws) => Runtime {
                workflow: Some(ws), error_banner: None, ..self
            },
            BackfillStatusReceived(bs) => Runtime {
                backfill: Some(bs), error_banner: None, ..self
            },
            GatesReceived(g) => Runtime {
                gates: g, error_banner: None, ..self
            },

            OpStarted { id, label } => {
                let mut ops = self.ops;
                // De-dupe: if op id is already tracked (re-replay), upsert.
                if let Some(existing) = ops.iter_mut().find(|o| o.id == id) {
                    existing.label = label;
                    existing.status = OpStatus::Pending;
                    existing.progress = None;
                    existing.milestone = None;
                    existing.error = None;
                } else {
                    ops.push(OpRecord {
                        id, label,
                        status: OpStatus::Pending,
                        progress: None, milestone: None, error: None,
                    });
                }
                Runtime { ops, ..self }
            }

            OpUpdated { id, update } => {
                let mut ops = self.ops;
                if let Some(rec) = ops.iter_mut().find(|o| o.id == id) {
                    apply_update(rec, update);
                }
                // Unknown ids are silently ignored — happens when a stale
                // tokio task fires after Runtime forgot the op.
                Runtime { ops, ..self }
            }

            ConfigLoaded(doc) => Runtime {
                committed_config: Some(doc.clone()),
                draft_config: Some(doc),
                draft_validation: None,
                error_banner: None,
                ..self
            },
            DraftSet(doc) => Runtime {
                draft_config: Some(doc),
                // Editing invalidates the previous validation.
                draft_validation: None,
                ..self
            },
            DraftValidated(rep) => Runtime {
                draft_validation: Some(rep), ..self
            },
            ConfigSaved(doc) => Runtime {
                committed_config: Some(doc.clone()),
                draft_config: Some(doc),
                draft_validation: Some(ValidationReport {
                    ok: true, errors: vec![], referenced_secrets: vec![],
                }),
                error_banner: None,
                ..self
            },

            OpenConfirm(c) => {
                // INVARIANT: never nest Confirm inside Confirm. If a modal is
                // already up we drop the new one — destructive-op modals are
                // exclusive. Caller must ConfirmCancel before opening another.
                if matches!(self.panel, RuntimePanel::Confirm { .. }) {
                    Runtime { ..self }
                } else {
                    Runtime {
                        panel: RuntimePanel::Confirm {
                            behind: Box::new(self.panel),
                            confirmation: c,
                        },
                        ..self
                    }
                }
            }
            ConfirmChar(c) => match self.panel {
                RuntimePanel::Confirm { behind, confirmation } => Runtime {
                    panel: RuntimePanel::Confirm {
                        behind,
                        confirmation: confirmation.push_char(c),
                    },
                    ..self
                },
                other => Runtime { panel: other, ..self },
            },
            ConfirmBackspace => match self.panel {
                RuntimePanel::Confirm { behind, confirmation } => Runtime {
                    panel: RuntimePanel::Confirm {
                        behind,
                        confirmation: confirmation.backspace(),
                    },
                    ..self
                },
                other => Runtime { panel: other, ..self },
            },
            ConfirmCancel => match self.panel {
                RuntimePanel::Confirm { behind, .. } => Runtime {
                    panel: *behind, ..self
                },
                other => Runtime { panel: other, ..self },
            },

            SetError(s) => Runtime { error_banner: Some(s), ..self },
            ClearError => Runtime { error_banner: None, ..self },
        }
    }

    // ── queries used by the renderer ────────────────────────────────────

    /// Index of an op record by id, for renderer lookups.
    pub fn op(&self, id: OpId) -> Option<&OpRecord> {
        self.ops.iter().find(|o| o.id == id)
    }

    /// Currently-running ops (Pending or Running).
    pub fn running_ops(&self) -> impl Iterator<Item = &OpRecord> {
        self.ops.iter().filter(|o| !o.status.is_terminal())
    }

    /// Whether a confirmation modal is up.
    pub fn confirm_active(&self) -> bool {
        matches!(self.panel, RuntimePanel::Confirm { .. })
    }

    /// Confirmation reference if active.
    pub fn confirmation(&self) -> Option<&Confirmation> {
        match &self.panel {
            RuntimePanel::Confirm { confirmation, .. } => Some(confirmation),
            _ => None,
        }
    }
}

fn apply_update(rec: &mut OpRecord, update: OpUpdate) {
    match update {
        OpUpdate::StatusChanged(s) => rec.status = s,
        OpUpdate::Progress { complete, total, eta_ms } => {
            rec.progress = Some(Progress { complete, total, eta_ms });
        }
        OpUpdate::Milestone(m) => rec.milestone = Some(m),
        OpUpdate::Result(_) => rec.status = OpStatus::Succeeded,
        OpUpdate::Error(e) => {
            rec.status = OpStatus::Failed;
            rec.error = Some(e);
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────
// Unit tests — pure transitions only; integration tests live separately.
// ──────────────────────────────────────────────────────────────────────────
#[cfg(test)]
mod tests {
    use super::*;
    use crate::extension::workflow::{NodeType, WorkflowNode, WorkflowPhase};

    fn yaml(s: &str) -> ConfigDoc { ConfigDoc { yaml: s.into() } }
    fn doc1() -> ConfigDoc { yaml("source_cluster:\n  endpoint: https://es:9200\n") }
    fn doc2() -> ConfigDoc { yaml("target_cluster:\n  endpoint: https://os:443\n") }

    fn ws_running() -> WorkflowStatus {
        WorkflowStatus {
            workflow_name: "wf".into(),
            namespace: "ma".into(),
            phase: WorkflowPhase::Running,
            progress: Some("1/3".into()),
            started_at: None, finished_at: None,
            step_tree: vec![WorkflowNode {
                id: "n1".into(), name: "n1".into(), display_name: "n1".into(),
                phase: WorkflowPhase::Running, node_type: NodeType::Pod,
                started_at: None, finished_at: None,
                depth: 0, parent: None, children: vec![],
            }],
            error: None,
        }
    }

    // ── panel navigation ──────────────────────────────────────────────
    #[test]
    fn switch_panel_changes_panel() {
        let r = Runtime::default()
            .update(RuntimeEvent::SwitchPanel(RuntimePanel::ConfigEdit));
        assert_eq!(r.panel, RuntimePanel::ConfigEdit);
    }

    // ── workflow feed ─────────────────────────────────────────────────
    #[test]
    fn workflow_status_received_stores_and_clears_banner() {
        let r = Runtime { error_banner: Some("stale".into()), ..Runtime::default() }
            .update(RuntimeEvent::WorkflowStatusReceived(ws_running()));
        assert_eq!(r.workflow.as_ref().unwrap().workflow_name, "wf");
        assert!(r.error_banner.is_none(), "fresh data clears stale banner");
    }

    // ── op lifecycle ──────────────────────────────────────────────────
    #[test]
    fn op_started_then_progress_then_succeeded() {
        let r = Runtime::default()
            .update(RuntimeEvent::OpStarted { id: OpId(1), label: "snapshot create".into() })
            .update(RuntimeEvent::OpUpdated {
                id: OpId(1),
                update: OpUpdate::Progress { complete: 3, total: 10, eta_ms: Some(5_000) },
            })
            .update(RuntimeEvent::OpUpdated {
                id: OpId(1),
                update: OpUpdate::StatusChanged(OpStatus::Running),
            })
            .update(RuntimeEvent::OpUpdated {
                id: OpId(1),
                update: OpUpdate::Result(serde_json::json!({"ok": true})),
            });
        let rec = r.op(OpId(1)).unwrap();
        assert_eq!(rec.status, OpStatus::Succeeded);
        assert_eq!(rec.progress.unwrap().complete, 3);
        assert_eq!(rec.progress.unwrap().pct(), 30.0);
    }

    #[test]
    fn op_error_marks_failed_and_captures_message() {
        let r = Runtime::default()
            .update(RuntimeEvent::OpStarted { id: OpId(7), label: "backfill stop".into() })
            .update(RuntimeEvent::OpUpdated {
                id: OpId(7),
                update: OpUpdate::Error("workers in progress".into()),
            });
        let rec = r.op(OpId(7)).unwrap();
        assert_eq!(rec.status, OpStatus::Failed);
        assert_eq!(rec.error.as_deref(), Some("workers in progress"));
    }

    #[test]
    fn op_update_for_unknown_id_is_silent() {
        // A stale tokio task firing after the op was forgotten must not panic.
        let r = Runtime::default().update(RuntimeEvent::OpUpdated {
            id: OpId(999),
            update: OpUpdate::StatusChanged(OpStatus::Running),
        });
        assert!(r.ops.is_empty());
    }

    #[test]
    fn op_started_dedupes_and_resets() {
        let r = Runtime::default()
            .update(RuntimeEvent::OpStarted { id: OpId(1), label: "v1".into() })
            .update(RuntimeEvent::OpUpdated {
                id: OpId(1),
                update: OpUpdate::StatusChanged(OpStatus::Failed),
            })
            .update(RuntimeEvent::OpStarted { id: OpId(1), label: "v2".into() });
        let rec = r.op(OpId(1)).unwrap();
        assert_eq!(rec.label, "v2");
        assert_eq!(rec.status, OpStatus::Pending);
        assert!(rec.error.is_none(), "re-start clears error");
        assert_eq!(r.ops.len(), 1, "no duplicate record");
    }

    #[test]
    fn running_ops_excludes_terminal() {
        let r = Runtime::default()
            .update(RuntimeEvent::OpStarted { id: OpId(1), label: "a".into() })
            .update(RuntimeEvent::OpStarted { id: OpId(2), label: "b".into() })
            .update(RuntimeEvent::OpUpdated {
                id: OpId(2), update: OpUpdate::StatusChanged(OpStatus::Succeeded),
            });
        let running: Vec<_> = r.running_ops().map(|o| o.id).collect();
        assert_eq!(running, vec![OpId(1)]);
    }

    // ── config draft lifecycle ────────────────────────────────────────
    #[test]
    fn config_loaded_populates_both_slots() {
        let r = Runtime::default().update(RuntimeEvent::ConfigLoaded(doc1()));
        assert_eq!(r.committed_config, Some(doc1()));
        assert_eq!(r.draft_config, Some(doc1()));
        assert!(r.draft_validation.is_none());
    }

    #[test]
    fn draft_set_invalidates_validation() {
        let r = Runtime::default()
            .update(RuntimeEvent::ConfigLoaded(doc1()))
            .update(RuntimeEvent::DraftValidated(ValidationReport {
                ok: true, errors: vec![], referenced_secrets: vec![],
            }))
            .update(RuntimeEvent::DraftSet(doc2()));
        assert_eq!(r.draft_config, Some(doc2()));
        assert_eq!(r.committed_config, Some(doc1()), "committed unchanged until save");
        assert!(r.draft_validation.is_none(), "edit invalidates validation");
    }

    #[test]
    fn config_saved_promotes_draft_to_committed() {
        let r = Runtime::default()
            .update(RuntimeEvent::ConfigLoaded(doc1()))
            .update(RuntimeEvent::DraftSet(doc2()))
            .update(RuntimeEvent::ConfigSaved(doc2()));
        assert_eq!(r.committed_config, Some(doc2()));
        assert_eq!(r.draft_config, Some(doc2()));
        assert!(r.draft_validation.as_ref().unwrap().ok);
    }

    // ── confirmation modal ────────────────────────────────────────────
    #[test]
    fn open_confirm_wraps_current_panel() {
        let r = Runtime::default()
            .update(RuntimeEvent::SwitchPanel(RuntimePanel::ConfigEdit))
            .update(RuntimeEvent::OpenConfirm(Confirmation::new(
                "delete", "irreversible", "DELETE")));
        match &r.panel {
            RuntimePanel::Confirm { behind, .. } => {
                assert_eq!(**behind, RuntimePanel::ConfigEdit);
            }
            other => panic!("expected Confirm, got {other:?}"),
        }
        assert!(r.confirm_active());
    }

    #[test]
    fn open_confirm_while_confirm_open_is_dropped() {
        // Destructive-op modals must be exclusive. A second OpenConfirm while
        // one is already up would otherwise stack and leak the original
        // `behind` panel — instead we drop the new one. Caller must cancel
        // first if they really need to swap.
        let original = Confirmation::new("first", "x", "AAA");
        let intruder = Confirmation::new("second", "y", "BBB");
        let r = Runtime::default()
            .update(RuntimeEvent::SwitchPanel(RuntimePanel::Status))
            .update(RuntimeEvent::OpenConfirm(original.clone()))
            .update(RuntimeEvent::OpenConfirm(intruder));
        // Original confirmation still showing; behind still Status.
        match &r.panel {
            RuntimePanel::Confirm { behind, confirmation } => {
                assert_eq!(**behind, RuntimePanel::Status);
                assert_eq!(confirmation.action_label, "first");
            }
            other => panic!("expected first Confirm preserved, got {other:?}"),
        }
    }

    #[test]
    fn confirm_chars_satisfy_token_then_cancel_returns_to_behind() {
        let mut r = Runtime::default()
            .update(RuntimeEvent::SwitchPanel(RuntimePanel::WorkflowView))
            .update(RuntimeEvent::OpenConfirm(Confirmation::new(
                "delete", "x", "OK")));
        for ch in ['O', 'K'] {
            r = r.update(RuntimeEvent::ConfirmChar(ch));
        }
        assert!(r.confirmation().unwrap().satisfied);
        let r = r.update(RuntimeEvent::ConfirmCancel);
        assert_eq!(r.panel, RuntimePanel::WorkflowView);
        assert!(!r.confirm_active());
    }

    #[test]
    fn confirm_backspace_unsatisfies() {
        let mut r = Runtime::default()
            .update(RuntimeEvent::OpenConfirm(Confirmation::new("a", "b", "OK")));
        for ch in ['O', 'K'] { r = r.update(RuntimeEvent::ConfirmChar(ch)); }
        assert!(r.confirmation().unwrap().satisfied);
        r = r.update(RuntimeEvent::ConfirmBackspace);
        assert!(!r.confirmation().unwrap().satisfied);
    }

    #[test]
    fn confirm_chars_outside_modal_are_noops() {
        // Char arriving without an open modal is silently dropped.
        let r = Runtime::default().update(RuntimeEvent::ConfirmChar('X'));
        assert_eq!(r.panel, RuntimePanel::WorkflowView);
    }

    // ── error banner ──────────────────────────────────────────────────
    #[test]
    fn set_and_clear_error() {
        let r = Runtime::default()
            .update(RuntimeEvent::SetError("boom".into()));
        assert_eq!(r.error_banner.as_deref(), Some("boom"));
        let r = r.update(RuntimeEvent::ClearError);
        assert!(r.error_banner.is_none());
    }

    // ── update is pure ────────────────────────────────────────────────
    #[test]
    fn update_is_deterministic() {
        let a = Runtime::default()
            .update(RuntimeEvent::WorkflowStatusReceived(ws_running()));
        let b = Runtime::default()
            .update(RuntimeEvent::WorkflowStatusReceived(ws_running()));
        assert_eq!(a, b);
    }

    // ── Progress arithmetic ───────────────────────────────────────────
    #[test]
    fn progress_pct_handles_zero_total() {
        let p = Progress { complete: 0, total: 0, eta_ms: None };
        assert_eq!(p.pct(), 0.0);
    }
}
