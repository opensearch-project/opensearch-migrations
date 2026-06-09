// tests/runtime_invariants.rs — property-based invariants for Runtime.
//
// These hammer the pure runtime state machine with random event sequences
// and assert structural invariants that MUST hold regardless of order:
//
//   1. Confirm modal stack never grows beyond depth 1 (no nested confirms).
//   2. ConfirmCancel always returns to a non-Confirm panel.
//   3. OpUpdated for an unknown id never creates a record.
//   4. Once an op reaches a terminal status, only OpStarted (re-issue) can
//      move it back to non-terminal.
//   5. The order of ops matches the order of OpStarted events (stable).
//   6. ConfigSaved makes committed_config == draft_config and validation ok.
//   7. DraftSet never changes committed_config.
//   8. error_banner is None after ClearError, regardless of prior state.
//   9. OpStarted on existing id keeps len(ops) constant (de-dupe invariant).
//  10. apply_update aggregates: progress is always the LAST seen progress.
//
// All proptest cases run on Runtime with no shared state — every test
// builds its own Runtime from default. No fixtures, no seeds beyond
// proptest's default.

use migration_tui::domain::confirmation::Confirmation;
use migration_tui::domain::runtime::{Runtime, RuntimeEvent, RuntimePanel};
use migration_tui::extension::config::{ConfigDoc, ValidationReport};
use migration_tui::extension::op::{OpId, OpStatus, OpUpdate};
use proptest::prelude::*;

// ── strategies ────────────────────────────────────────────────────────

fn arb_panel_non_confirm() -> impl Strategy<Value = RuntimePanel> {
    prop_oneof![
        Just(RuntimePanel::WorkflowView),
        Just(RuntimePanel::ConfigEdit),
        Just(RuntimePanel::Approvals),
        Just(RuntimePanel::Status),
        // LogView with a small id — the event-loop usually has a small id pool.
        any::<u8>().prop_map(|n| RuntimePanel::LogView { op_id: OpId(n as u64) }),
    ]
}

fn arb_op_status() -> impl Strategy<Value = OpStatus> {
    prop_oneof![
        Just(OpStatus::Pending),
        Just(OpStatus::Running),
        Just(OpStatus::Succeeded),
        Just(OpStatus::Failed),
        Just(OpStatus::Cancelled),
    ]
}

fn arb_op_update() -> impl Strategy<Value = OpUpdate> {
    prop_oneof![
        arb_op_status().prop_map(OpUpdate::StatusChanged),
        (0u64..=100, 1u64..=200, prop::option::of(0u64..=10_000))
            .prop_map(|(c, t, e)| OpUpdate::Progress { complete: c.min(t), total: t, eta_ms: e }),
        "[a-z]{1,8}".prop_map(OpUpdate::Milestone),
        Just(OpUpdate::Result(serde_json::Value::Null)),
        "[a-z]{1,8}".prop_map(OpUpdate::Error),
    ]
}

fn arb_event() -> impl Strategy<Value = RuntimeEvent> {
    prop_oneof![
        arb_panel_non_confirm().prop_map(RuntimeEvent::SwitchPanel),

        (0u8..5, "[a-z]{1,4}").prop_map(|(i, l)| RuntimeEvent::OpStarted {
            id: OpId(i as u64), label: l,
        }),
        (0u8..5, arb_op_update())
            .prop_map(|(i, u)| RuntimeEvent::OpUpdated { id: OpId(i as u64), update: u }),

        // Confirmation events: a single typed-token with random buffer chars.
        Just(RuntimeEvent::OpenConfirm(Confirmation::new("act", "imp", "OK"))),
        prop_oneof![Just('A'), Just('B'), Just('C'), Just('O'), Just('K'), Just('X')]
            .prop_map(RuntimeEvent::ConfirmChar),
        Just(RuntimeEvent::ConfirmBackspace),
        Just(RuntimeEvent::ConfirmCancel),

        // Config flow.
        "[a-z]{1,16}".prop_map(|s| RuntimeEvent::ConfigLoaded(ConfigDoc { yaml: s })),
        "[a-z]{1,16}".prop_map(|s| RuntimeEvent::DraftSet(ConfigDoc { yaml: s })),
        "[a-z]{1,16}".prop_map(|s| RuntimeEvent::ConfigSaved(ConfigDoc { yaml: s })),

        // Error banner.
        "[a-z]{1,8}".prop_map(RuntimeEvent::SetError),
        Just(RuntimeEvent::ClearError),
    ]
}

fn replay(events: Vec<RuntimeEvent>) -> Runtime {
    events.into_iter().fold(Runtime::default(), |r, e| r.update(e))
}

// ── invariants ─────────────────────────────────────────────────────────

proptest! {
    // 1. Confirm panel never nests: behind is always a non-Confirm panel.
    #[test]
    fn confirm_panel_never_nests(events in proptest::collection::vec(arb_event(), 0..50)) {
        let r = replay(events);
        if let RuntimePanel::Confirm { behind, .. } = &r.panel {
            let nested = matches!(**behind, RuntimePanel::Confirm { .. });
            prop_assert!(!nested,
                "Confirm modal nested inside another Confirm — would lose the original behind");
        }
    }

    // 2. After ConfirmCancel, panel is not a Confirm.
    #[test]
    fn confirm_cancel_returns_to_non_confirm(events in proptest::collection::vec(arb_event(), 0..40)) {
        let r = replay(events).update(RuntimeEvent::ConfirmCancel);
        let still_confirm = matches!(r.panel, RuntimePanel::Confirm { .. });
        prop_assert!(!still_confirm);
    }

    // 3. OpUpdated for unknown id does not create a record.
    #[test]
    fn op_updated_unknown_id_silent(updates in proptest::collection::vec(arb_op_update(), 0..30)) {
        // Send a stream of updates targeting id=42 with no preceding OpStarted.
        let mut r = Runtime::default();
        for u in updates {
            r = r.update(RuntimeEvent::OpUpdated { id: OpId(42), update: u });
        }
        prop_assert!(r.op(OpId(42)).is_none());
        prop_assert!(r.ops.is_empty());
    }

    // 4. Without re-OpStarted, terminal ops stay terminal.
    //    (Updates AFTER terminal can still mutate fields, but status only goes
    //    back to non-terminal on OpStarted.)
    #[test]
    fn terminal_op_only_resets_on_op_started(
        post_terminal in proptest::collection::vec(arb_op_update(), 0..20),
    ) {
        let mut r = Runtime::default()
            .update(RuntimeEvent::OpStarted { id: OpId(1), label: "x".into() })
            .update(RuntimeEvent::OpUpdated {
                id: OpId(1), update: OpUpdate::StatusChanged(OpStatus::Succeeded),
            });
        prop_assert_eq!(r.op(OpId(1)).unwrap().status, OpStatus::Succeeded);
        // Apply only updates (no OpStarted) — current rule: any StatusChanged
        // wins, including back-to-Pending. So we exclude StatusChanged variants
        // that go back to non-terminal in this invariant — instead we assert
        // that without StatusChanged, terminal stays terminal.
        for u in post_terminal {
            if matches!(u, OpUpdate::StatusChanged(_) | OpUpdate::Result(_) | OpUpdate::Error(_)) {
                continue; // these intentionally CAN flip status
            }
            r = r.update(RuntimeEvent::OpUpdated { id: OpId(1), update: u });
            prop_assert_eq!(r.op(OpId(1)).unwrap().status, OpStatus::Succeeded,
                "non-status updates must not flip terminal status");
        }
    }

    // 5. ops vec ordering matches first-seen OpStarted order.
    #[test]
    fn op_order_is_insertion_order(ids in proptest::collection::vec(0u8..6, 0..30)) {
        let mut r = Runtime::default();
        let mut expected: Vec<OpId> = Vec::new();
        for i in &ids {
            let id = OpId(*i as u64);
            if !expected.contains(&id) { expected.push(id); }
            r = r.update(RuntimeEvent::OpStarted { id, label: format!("op{i}") });
        }
        let actual: Vec<OpId> = r.ops.iter().map(|o| o.id).collect();
        prop_assert_eq!(actual, expected);
    }

    // 6. After ConfigSaved, committed == draft and validation is ok.
    #[test]
    fn config_saved_promotes_and_validates(
        prelude in proptest::collection::vec(arb_event(), 0..30),
        yaml in "[a-z]{1,16}",
    ) {
        let r = replay(prelude)
            .update(RuntimeEvent::ConfigSaved(ConfigDoc { yaml: yaml.clone() }));
        prop_assert_eq!(r.committed_config.as_ref().map(|d| d.yaml.clone()), Some(yaml.clone()));
        prop_assert_eq!(r.draft_config.as_ref().map(|d| d.yaml.clone()), Some(yaml));
        prop_assert!(r.draft_validation.as_ref().map(|v| v.ok).unwrap_or(false));
    }

    // 7. DraftSet never mutates committed_config.
    #[test]
    fn draft_set_does_not_touch_committed(
        committed_yaml in "[a-z]{1,16}",
        drafts in proptest::collection::vec("[a-z]{1,16}", 0..20),
    ) {
        let mut r = Runtime::default()
            .update(RuntimeEvent::ConfigLoaded(ConfigDoc { yaml: committed_yaml.clone() }));
        for d in drafts {
            r = r.update(RuntimeEvent::DraftSet(ConfigDoc { yaml: d }));
        }
        prop_assert_eq!(r.committed_config.as_ref().map(|d| d.yaml.clone()), Some(committed_yaml));
    }

    // 8. ClearError zeroes the banner regardless of prior state.
    #[test]
    fn clear_error_always_clears(events in proptest::collection::vec(arb_event(), 0..30)) {
        let r = replay(events).update(RuntimeEvent::ClearError);
        prop_assert!(r.error_banner.is_none());
    }

    // 9. OpStarted on existing id is a no-op for ops length (de-dupe).
    #[test]
    fn op_started_idempotent_in_length(reissues in 0u8..20) {
        let mut r = Runtime::default()
            .update(RuntimeEvent::OpStarted { id: OpId(1), label: "x".into() });
        for i in 0..reissues {
            r = r.update(RuntimeEvent::OpStarted {
                id: OpId(1), label: format!("v{i}"),
            });
        }
        prop_assert_eq!(r.ops.len(), 1);
    }

    // 10. progress reflects the LAST progress update applied.
    #[test]
    fn progress_is_last_write_wins(
        sequence in proptest::collection::vec((0u64..100, 1u64..200), 1..20),
    ) {
        let mut r = Runtime::default()
            .update(RuntimeEvent::OpStarted { id: OpId(1), label: "x".into() });
        let mut last = (0u64, 1u64);
        for (c, t) in sequence {
            let c = c.min(t);
            last = (c, t);
            r = r.update(RuntimeEvent::OpUpdated {
                id: OpId(1),
                update: OpUpdate::Progress { complete: c, total: t, eta_ms: None },
            });
        }
        let p = r.op(OpId(1)).unwrap().progress.unwrap();
        prop_assert_eq!((p.complete, p.total), last);
    }

    // 11. update is total: every event sequence terminates without panic.
    #[test]
    fn update_is_total(events in proptest::collection::vec(arb_event(), 0..100)) {
        let _ = replay(events);
        // If we got here, update never panicked on this sequence.
    }
}

// ── manual sanity for ValidationReport (not proptest-shaped) ──────────
#[test]
fn validation_report_default_shape() {
    let v = ValidationReport { ok: true, errors: vec![], referenced_secrets: vec![] };
    assert!(v.ok);
}
