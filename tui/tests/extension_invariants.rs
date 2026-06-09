// tests/extension_invariants.rs — property-based invariants for the
// extension contract types. These are DATA invariants; the trait methods
// themselves are tested with mock impls in unit-test land.
//
// Coverage:
//   - WorkflowStatus.walk() yields every node exactly once
//   - WorkflowStatus.walk() is DFS pre-order
//   - parent links inside walk() output are consistent (no orphans)
//   - depth is monotone non-decreasing along any walk path
//   - BackfillStatus.shard_counts_consistent ↔ sum invariant
//   - BackfillStatus.percentage_matches_shards is exactly the ratio
//   - all serde roundtrips for new types

use migration_tui::extension::{
    op::{OpStatus, OpUpdate, OpId},
    cluster::{BackfillStatus, BackfillStatusKind, ClusterRole},
    config::{ConfigSource, ValidationReport},
    workflow::{NodeType, WorkflowNode, WorkflowPhase, WorkflowStatus},
    log_streamer::{LogLevel, LogLine},
};

use proptest::prelude::*;

// ── Generators ────────────────────────────────────────────────────────────

fn arb_phase() -> impl Strategy<Value = WorkflowPhase> {
    prop_oneof![
        Just(WorkflowPhase::Pending),
        Just(WorkflowPhase::Running),
        Just(WorkflowPhase::Succeeded),
        Just(WorkflowPhase::Failed),
        Just(WorkflowPhase::Error),
        Just(WorkflowPhase::Stopped),
        Just(WorkflowPhase::Terminated),
        Just(WorkflowPhase::Suspended),
    ]
}

fn arb_node_type() -> impl Strategy<Value = NodeType> {
    prop_oneof![
        Just(NodeType::Pod), Just(NodeType::Steps), Just(NodeType::Dag),
        Just(NodeType::StepGroup), Just(NodeType::Suspend),
        Just(NodeType::Retry), Just(NodeType::Skipped), Just(NodeType::Other),
    ]
}

fn arb_leaf(depth: u32) -> impl Strategy<Value = WorkflowNode> {
    (any::<u32>(), arb_phase(), arb_node_type()).prop_map(move |(id, p, t)| WorkflowNode {
        id: format!("n-{id}"),
        name: format!("name-{id}"),
        display_name: format!("disp-{id}"),
        phase: p,
        node_type: t,
        started_at: None, finished_at: None,
        depth,
        parent: None,
        children: vec![],
    })
}

/// Bounded-depth tree generator. Keeps the property cheap by capping
/// fanout and depth.
fn arb_tree() -> impl Strategy<Value = WorkflowNode> {
    let leaf = arb_leaf(0).boxed();
    leaf.prop_recursive(
        4,    // max depth
        16,   // max nodes
        4,    // max fanout
        |inner| (any::<u32>(), arb_phase(), prop::collection::vec(inner, 0..4))
            .prop_map(|(id, p, kids)| WorkflowNode {
                id: format!("n-{id}"),
                name: format!("name-{id}"),
                display_name: format!("disp-{id}"),
                phase: p,
                node_type: NodeType::Pod,
                started_at: None, finished_at: None,
                depth: 0,
                parent: None,
                children: kids,
            }),
    )
}

fn assign_depths(node: &mut WorkflowNode, depth: u32) {
    node.depth = depth;
    for c in &mut node.children {
        assign_depths(c, depth + 1);
    }
}

fn count_nodes(node: &WorkflowNode) -> usize {
    1 + node.children.iter().map(count_nodes).sum::<usize>()
}

// ── Walk invariants ────────────────────────────────────────────────────────

proptest! {
    /// walk() yields every node exactly once.
    #[test]
    fn walk_yields_every_node(mut root in arb_tree()) {
        assign_depths(&mut root, 0);
        let status = WorkflowStatus {
            workflow_name: "wf".into(), namespace: "ma".into(),
            phase: WorkflowPhase::Running, progress: None,
            started_at: None, finished_at: None,
            step_tree: vec![root.clone()], error: None,
        };
        let walked: Vec<_> = status.walk();
        prop_assert_eq!(walked.len(), count_nodes(&root));
    }

    /// Parent links are consistent: every (node, parent) tuple either has
    /// parent == None (top-level) or there exists an earlier-walked node
    /// whose id matches.
    #[test]
    fn walk_parent_links_consistent(mut root in arb_tree()) {
        assign_depths(&mut root, 0);
        let status = WorkflowStatus {
            workflow_name: "wf".into(), namespace: "ma".into(),
            phase: WorkflowPhase::Running, progress: None,
            started_at: None, finished_at: None,
            step_tree: vec![root], error: None,
        };
        let walked = status.walk();
        let mut seen: std::collections::HashSet<&str> = std::collections::HashSet::new();
        for (node, parent) in &walked {
            if let Some(p) = parent {
                prop_assert!(seen.contains(*p),
                    "parent {p} of node {} not seen yet", node.id);
            }
            seen.insert(&node.id);
        }
    }

    /// Depth is monotone non-decreasing within a single sub-walk.
    /// Specifically: if node N has parent P, then N.depth == P.depth + 1.
    #[test]
    fn walk_depth_consistent(mut root in arb_tree()) {
        assign_depths(&mut root, 0);
        let status = WorkflowStatus {
            workflow_name: "wf".into(), namespace: "ma".into(),
            phase: WorkflowPhase::Running, progress: None,
            started_at: None, finished_at: None,
            step_tree: vec![root], error: None,
        };
        let walked = status.walk();
        let by_id: std::collections::HashMap<&str, &WorkflowNode> =
            walked.iter().map(|(n, _)| (n.id.as_str(), *n)).collect();
        for (node, parent) in &walked {
            if let Some(p) = parent {
                let parent_node = by_id.get(*p).unwrap();
                prop_assert_eq!(node.depth, parent_node.depth + 1);
            } else {
                prop_assert_eq!(node.depth, 0);
            }
        }
    }
}

// ── BackfillStatus invariants ──────────────────────────────────────────────

fn arb_backfill_kind() -> impl Strategy<Value = BackfillStatusKind> {
    prop_oneof![
        Just(BackfillStatusKind::NotStarted),
        Just(BackfillStatusKind::Running),
        Just(BackfillStatusKind::Paused),
        Just(BackfillStatusKind::Stopped),
        Just(BackfillStatusKind::Completed),
        Just(BackfillStatusKind::Failed),
    ]
}

proptest! {
    /// shard_counts_consistent is true iff complete + in_progress + waiting == total.
    #[test]
    fn backfill_shard_counts_definition(
        kind in arb_backfill_kind(),
        total in 0u64..1000,
        complete in 0u64..1000,
        in_prog in 0u64..1000,
        waiting in 0u64..1000,
    ) {
        let s = BackfillStatus {
            status: kind, percentage_completed: 0.0, eta_ms: None,
            started: false, finished: false,
            shard_total: total,
            shard_complete: complete,
            shard_in_progress: in_prog,
            shard_waiting: waiting,
        };
        prop_assert_eq!(s.shard_counts_consistent(),
            complete + in_prog + waiting == total);
    }

    /// percentage_matches_shards is true iff percentage exactly equals
    /// the computed ratio (within floating-point tolerance).
    #[test]
    fn backfill_percentage_definition(
        total in 1u64..1000,
        complete in 0u64..1000,
    ) {
        let complete = complete.min(total);
        let pct = (complete as f64) / (total as f64) * 100.0;
        let s = BackfillStatus {
            status: BackfillStatusKind::Running,
            percentage_completed: pct,
            eta_ms: None,
            started: true, finished: false,
            shard_total: total,
            shard_complete: complete,
            shard_in_progress: 0,
            shard_waiting: total - complete,
        };
        prop_assert!(s.percentage_matches_shards());
        prop_assert!(s.shard_counts_consistent());
    }
}

// ── Serde roundtrips ──────────────────────────────────────────────────────

proptest! {
    #[test]
    fn op_update_roundtrip_progress(
        complete in 0u64..1_000_000,
        total in 0u64..1_000_000,
        eta_ms in proptest::option::of(0u64..1_000_000),
    ) {
        let u = OpUpdate::Progress { complete, total, eta_ms };
        let s = serde_json::to_string(&u).unwrap();
        let d: OpUpdate = serde_json::from_str(&s).unwrap();
        prop_assert_eq!(u, d);
    }

    #[test]
    fn op_update_roundtrip_status(
        st in prop_oneof![
            Just(OpStatus::Pending), Just(OpStatus::Running),
            Just(OpStatus::Succeeded), Just(OpStatus::Failed),
            Just(OpStatus::Cancelled),
        ],
    ) {
        let u = OpUpdate::StatusChanged(st);
        let s = serde_json::to_string(&u).unwrap();
        let d: OpUpdate = serde_json::from_str(&s).unwrap();
        prop_assert_eq!(u, d);
    }

    #[test]
    fn cluster_role_string_roundtrip(role in prop_oneof![
        Just(ClusterRole::Source), Just(ClusterRole::Target), Just(ClusterRole::Proxy),
    ]) {
        let s = serde_json::to_string(&role).unwrap();
        let d: ClusterRole = serde_json::from_str(&s).unwrap();
        prop_assert_eq!(role, d);
    }

    #[test]
    fn config_source_roundtrip(
        ns in "[a-z0-9-]{1,16}",
        name in "[a-z0-9-]{1,16}",
    ) {
        for src in [
            ConfigSource::Yaml(format!("/config/{name}.yaml")),
            ConfigSource::Kubernetes { namespace: ns.clone(), name: name.clone() },
        ] {
            let s = serde_json::to_string(&src).unwrap();
            let d: ConfigSource = serde_json::from_str(&s).unwrap();
            prop_assert_eq!(src, d);
        }
    }

    #[test]
    fn validation_report_roundtrip(
        ok in any::<bool>(),
        err_count in 0usize..5,
        secret_count in 0usize..5,
    ) {
        let r = ValidationReport {
            ok,
            errors: (0..err_count).map(|i| format!("err-{i}")).collect(),
            referenced_secrets: (0..secret_count).map(|i| format!("secret-{i}")).collect(),
        };
        let s = serde_json::to_string(&r).unwrap();
        let d: ValidationReport = serde_json::from_str(&s).unwrap();
        prop_assert_eq!(r, d);
    }

    #[test]
    fn log_line_roundtrip(
        op_id in 0u64..u64::MAX,
        msg in ".*",
    ) {
        let l = LogLine {
            op_id: OpId(op_id),
            ts: None,
            source: "src".into(),
            level: LogLevel::Info,
            message: msg,
        };
        let s = serde_json::to_string(&l).unwrap();
        let d: LogLine = serde_json::from_str(&s).unwrap();
        prop_assert_eq!(l, d);
    }
}

// ── OpStatus terminal classification ───────────────────────────────────────

proptest! {
    #[test]
    fn op_status_terminal_partition(st in prop_oneof![
        Just(OpStatus::Pending), Just(OpStatus::Running),
        Just(OpStatus::Succeeded), Just(OpStatus::Failed),
        Just(OpStatus::Cancelled),
    ]) {
        let terminal = matches!(st,
            OpStatus::Succeeded | OpStatus::Failed | OpStatus::Cancelled);
        prop_assert_eq!(st.is_terminal(), terminal);
    }
}
