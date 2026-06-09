// extension/workflow.rs — Argo workflow tree + approval gates.
//
// Mirrors:
//   workflow_service.WorkflowStatusResult
//   workflow_service.WorkflowNode
//   workflow.commands.approve.{step,change,retry}
//
// Key design choice: WorkflowNode is a tree (children: Vec<WorkflowNode>),
// NOT a flat list. The bash-CLI POC's linear Phase enum cannot represent
// Argo workflows with retry sub-DAGs, fan-out, or suspended approval gates.
// Every property test that assumed `phases.len() == 10` is gone — replaced
// by tree-shape invariants (parent links consistent, depth correct, etc.).

use async_trait::async_trait;
use serde::{Deserialize, Serialize};

use super::{ServiceResult, op::OpHandle};

/// Argo phase. Strings match what the Python TypedDict carries verbatim.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum WorkflowPhase {
    Pending,
    Running,
    Succeeded,
    Failed,
    Error,
    Stopped,
    Terminated,
    /// Suspended waiting for an approval gate. Not in Argo's enum, but a
    /// useful UI distinction we derive from Argo node type=Suspend.
    Suspended,
}

impl WorkflowPhase {
    pub fn is_terminal(self) -> bool {
        matches!(self,
            Self::Succeeded | Self::Failed | Self::Error |
            Self::Stopped   | Self::Terminated)
    }
    pub fn is_running(self) -> bool {
        matches!(self, Self::Running | Self::Pending)
    }
}

/// One node in the workflow tree. The TUI renders this as one row in the
/// expandable tree widget; the depth field drives indentation.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct WorkflowNode {
    pub id: String,                // Argo's internal node id
    pub name: String,              // template name
    pub display_name: String,      // human-readable label
    pub phase: WorkflowPhase,
    pub node_type: NodeType,
    pub started_at: Option<String>,
    pub finished_at: Option<String>,
    pub depth: u32,
    pub parent: Option<String>,
    pub children: Vec<WorkflowNode>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum NodeType {
    Pod,
    Steps,
    Dag,
    StepGroup,
    Suspend,
    Retry,
    Skipped,
    Other,
}

/// Top-level workflow result. Returned by `workflow status`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct WorkflowStatus {
    pub workflow_name: String,
    pub namespace: String,
    pub phase: WorkflowPhase,
    pub progress: Option<String>,        // e.g. "5/12"
    pub started_at: Option<String>,
    pub finished_at: Option<String>,
    pub step_tree: Vec<WorkflowNode>,
    pub error: Option<String>,
}

impl WorkflowStatus {
    /// Walk the tree depth-first. Used by both rendering and invariant
    /// tests. Yields (node, parent_id_or_none).
    pub fn walk(&self) -> Vec<(&WorkflowNode, Option<&str>)> {
        let mut out = Vec::new();
        for n in &self.step_tree {
            walk_into(n, None, &mut out);
        }
        out
    }
}

fn walk_into<'a>(
    node: &'a WorkflowNode,
    parent: Option<&'a str>,
    out: &mut Vec<(&'a WorkflowNode, Option<&'a str>)>,
) {
    out.push((node, parent));
    for c in &node.children {
        walk_into(c, Some(&node.id), out);
    }
}

/// One pending approval. Each `approve` subcommand category maps to a
/// distinct gate kind; the Python CLI calls these "step", "change", "retry".
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ApprovalGate {
    pub name: String,
    pub kind: GateKind,
    pub blockers: Vec<String>,    // human-readable list of follow-ups
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum GateKind {
    Step,    // ordinary suspended step
    Change,  // VAP-denied UPDATE on gated field
    Retry,   // recovery-required prereqs
}

/// The TUI calls these methods. The default impl wraps `workflow status`
/// (subprocess + JSON); tests use MockWorkflowService.
#[async_trait]
pub trait WorkflowService: Send + Sync {
    /// Snapshot of current workflow tree. Matches `workflow status --json`.
    async fn status(&self, namespace: &str) -> ServiceResult<WorkflowStatus>;

    /// Submit / re-submit. Returns OpHandle so the TUI can show progress.
    async fn submit(&self, namespace: &str) -> ServiceResult<OpHandle>;

    /// Stream pod logs. Returns OpHandle for cancellation; lines flow
    /// through a separate LogStreamer trait obtained from the runtime.
    async fn pod_log_stream(&self, namespace: &str, pod: &str, follow: bool)
        -> ServiceResult<OpHandle>;

    /// Reset (delete) named migration resources.
    async fn reset(&self, namespace: &str, names: &[String], cascade: bool)
        -> ServiceResult<OpHandle>;
}

#[async_trait]
pub trait ApprovalService: Send + Sync {
    async fn list_gates(&self, namespace: &str) -> ServiceResult<Vec<ApprovalGate>>;
    async fn approve(&self, namespace: &str, gate: &ApprovalGate) -> ServiceResult<()>;
    async fn pre_approve(&self, namespace: &str, gate: &ApprovalGate) -> ServiceResult<()>;
}

#[cfg(test)]
mod tests {
    use super::*;

    fn n(id: &str, depth: u32, phase: WorkflowPhase, kids: Vec<WorkflowNode>) -> WorkflowNode {
        WorkflowNode {
            id: id.into(),
            name: id.into(),
            display_name: id.into(),
            phase,
            node_type: NodeType::Pod,
            started_at: None,
            finished_at: None,
            depth,
            parent: None,
            children: kids,
        }
    }

    #[test]
    fn workflow_phase_terminal_classification() {
        for p in [WorkflowPhase::Succeeded, WorkflowPhase::Failed,
                  WorkflowPhase::Error, WorkflowPhase::Stopped,
                  WorkflowPhase::Terminated] {
            assert!(p.is_terminal(), "{p:?}");
            assert!(!p.is_running(), "{p:?}");
        }
        for p in [WorkflowPhase::Pending, WorkflowPhase::Running] {
            assert!(p.is_running(), "{p:?}");
            assert!(!p.is_terminal(), "{p:?}");
        }
        // Suspended is neither — it's a wait state for human action.
        assert!(!WorkflowPhase::Suspended.is_terminal());
        assert!(!WorkflowPhase::Suspended.is_running());
    }

    #[test]
    fn walk_yields_dfs_order_with_parent_links() {
        let tree = vec![
            n("a", 0, WorkflowPhase::Succeeded, vec![
                n("a.1", 1, WorkflowPhase::Succeeded, vec![]),
                n("a.2", 1, WorkflowPhase::Failed, vec![
                    n("a.2.1", 2, WorkflowPhase::Failed, vec![]),
                ]),
            ]),
            n("b", 0, WorkflowPhase::Running, vec![]),
        ];
        let status = WorkflowStatus {
            workflow_name: "wf".into(),
            namespace: "ma".into(),
            phase: WorkflowPhase::Running,
            progress: Some("3/4".into()),
            started_at: None, finished_at: None,
            step_tree: tree, error: None,
        };
        let walk: Vec<_> = status.walk().into_iter()
            .map(|(n, p)| (n.id.as_str(), p))
            .collect();
        assert_eq!(walk, vec![
            ("a",      None),
            ("a.1",    Some("a")),
            ("a.2",    Some("a")),
            ("a.2.1",  Some("a.2")),
            ("b",      None),
        ]);
    }

    #[test]
    fn workflow_status_serde_roundtrip() {
        let s = WorkflowStatus {
            workflow_name: "wf-1".into(),
            namespace: "ma".into(),
            phase: WorkflowPhase::Running,
            progress: Some("1/3".into()),
            started_at: None, finished_at: None,
            step_tree: vec![n("a", 0, WorkflowPhase::Running, vec![])],
            error: None,
        };
        let j = serde_json::to_string(&s).unwrap();
        let d: WorkflowStatus = serde_json::from_str(&j).unwrap();
        assert_eq!(s, d);
    }
}
