// extension/cluster.rs — ClusterService + Backfill/Snapshot/Replay status.
//
// Mirrors:
//   console_link.middleware.clusters.{cat_indices,connection_check,...}
//   console_link.middleware.snapshot.{create,status,delete,...}
//   console_link.middleware.backfill.{describe,start,stop,scale,status}
//
// Three top-level service traits — one per console_link.middleware module.
// Tests substitute Mock impls. The default real impl shells out to the
// `console` binary inside the migration-console pod via `kubectl exec`.

use async_trait::async_trait;
use serde::{Deserialize, Serialize};

use super::{ServiceResult, op::OpHandle};

/// Either side of the migration. `Proxy` covers the capture-proxy pseudo-cluster.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum ClusterRole { Source, Target, Proxy }

impl ClusterRole {
    pub fn as_str(self) -> &'static str {
        match self { Self::Source => "source", Self::Target => "target", Self::Proxy => "proxy" }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ConnectionResult {
    pub role: ClusterRole,
    pub reachable: bool,
    pub auth_ok: bool,
    pub cluster_name: Option<String>,
    pub version: Option<String>,
    pub error: Option<String>,
}

/// `BackfillOverallStatus` from Python middleware. Note: ALL fields can
/// be missing while the work coordinator is still bootstrapping — the
/// trait method returns ServiceError::DeepStatusNotYetAvailable in that case.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct BackfillStatus {
    pub status: BackfillStatusKind,
    pub percentage_completed: f64,
    pub eta_ms: Option<u64>,
    pub started: bool,
    pub finished: bool,
    pub shard_total: u64,
    pub shard_complete: u64,
    pub shard_in_progress: u64,
    pub shard_waiting: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum BackfillStatusKind {
    NotStarted, Running, Paused, Stopped, Completed, Failed,
}

impl BackfillStatus {
    /// Invariant the property tests check: shard counts must sum to total.
    pub fn shard_counts_consistent(&self) -> bool {
        self.shard_complete + self.shard_in_progress + self.shard_waiting
            == self.shard_total
    }

    /// Invariant: percentage matches shard ratio (within 0.01 tolerance).
    pub fn percentage_matches_shards(&self) -> bool {
        if self.shard_total == 0 { return self.percentage_completed == 0.0; }
        let computed = (self.shard_complete as f64) / (self.shard_total as f64) * 100.0;
        (computed - self.percentage_completed).abs() < 0.01
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct SnapshotStatus {
    pub repo: String,
    pub name: String,
    pub state: String,                  // raw from _snapshot API
    pub percentage_completed: Option<f64>,
    pub shard_total: Option<u64>,
    pub shard_complete: Option<u64>,
    pub started_at: Option<String>,
    pub finished_at: Option<String>,
}

#[async_trait]
pub trait ClusterService: Send + Sync {
    async fn connection_check(&self) -> ServiceResult<Vec<ConnectionResult>>;
    async fn cat_indices(&self, role: ClusterRole, refresh: bool)
        -> ServiceResult<String>; // raw _cat output
    async fn clear_indices(&self, role: ClusterRole, acknowledge: bool)
        -> ServiceResult<OpHandle>;
}

#[async_trait]
pub trait SnapshotService: Send + Sync {
    async fn create(&self, wait: bool, max_rate_mb_per_node: Option<u64>)
        -> ServiceResult<OpHandle>;
    async fn status(&self, deep_check: bool) -> ServiceResult<SnapshotStatus>;
    async fn delete(&self, acknowledge: bool) -> ServiceResult<()>;
}

#[async_trait]
pub trait BackfillService: Send + Sync {
    async fn start(&self, pipeline: Option<&str>) -> ServiceResult<OpHandle>;
    async fn stop(&self, pipeline: Option<&str>) -> ServiceResult<OpHandle>;
    async fn pause(&self, pipeline: Option<&str>) -> ServiceResult<()>;
    async fn scale(&self, units: u32) -> ServiceResult<()>;
    async fn status(&self, deep_check: bool) -> ServiceResult<BackfillStatus>;
}

#[cfg(test)]
mod tests {
    use super::*;

    fn s(total: u64, complete: u64, in_prog: u64, waiting: u64, pct: f64) -> BackfillStatus {
        BackfillStatus {
            status: BackfillStatusKind::Running,
            percentage_completed: pct,
            eta_ms: None,
            started: true, finished: false,
            shard_total: total,
            shard_complete: complete,
            shard_in_progress: in_prog,
            shard_waiting: waiting,
        }
    }

    #[test]
    fn shard_counts_sum_invariant() {
        assert!(s(10, 3, 4, 3, 30.0).shard_counts_consistent());
        assert!(!s(10, 3, 4, 4, 30.0).shard_counts_consistent());
        assert!(s(0, 0, 0, 0, 0.0).shard_counts_consistent());
    }

    #[test]
    fn percentage_matches_shard_ratio() {
        assert!(s(10, 3, 4, 3, 30.0).percentage_matches_shards());
        assert!(s(0, 0, 0, 0, 0.0).percentage_matches_shards());
        assert!(!s(10, 3, 4, 3, 50.0).percentage_matches_shards());
    }

    #[test]
    fn cluster_role_strings_match_python() {
        assert_eq!(ClusterRole::Source.as_str(), "source");
        assert_eq!(ClusterRole::Target.as_str(), "target");
        assert_eq!(ClusterRole::Proxy.as_str(), "proxy");
    }
}
