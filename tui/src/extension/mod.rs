// extension/mod.rs — The TUI's contract with the world.
//
// EVERY external surface the TUI touches goes through one of the traits in
// this module. The TUI ships with one default impl per trait (talking to the
// real `console` / `workflow` Python CLIs via subprocess + JSON), but every
// trait can be swapped:
//   - tests substitute `Mock*` impls
//   - alternate deployments (CDK, Helm-only, future REST) substitute their own
//   - a future in-process Rust port of the middleware can substitute a
//     direct-call impl
//
// Source-of-truth mapping (Python → Rust trait):
//
//   console_link.middleware.clusters         → ClusterService
//   console_link.middleware.snapshot         → SnapshotService
//   console_link.middleware.backfill         → BackfillService
//   console_link.middleware.replay           → ReplayService
//   console_link.middleware.metadata         → MetadataService
//   console_link.middleware.metrics          → MetricsService
//   console_link.middleware.kafka            → KafkaService
//   console_link.workflow.services.workflow_service.WorkflowService
//                                            → WorkflowService + LogStreamer
//   workflow.stores.WorkflowConfigStore      → ConfigStore
//   workflow.stores.SecretStore              → SecretStore
//   workflow.commands.approve.*              → ApprovalService
//
// Every trait method that talks to an external system is `async` and returns
// `Result<T, ServiceError>` so the TUI can render a panel-level error without
// crashing. Long-running ops return an OpHandle and stream Updates via a
// channel — the App never blocks in update().

pub mod cluster;
pub mod config;
pub mod log_streamer;
pub mod op;
pub mod workflow;

pub use cluster::ClusterService;
pub use config::{ConfigStore, SecretStore};
pub use log_streamer::{LogStreamer, LogLine};
pub use op::{OpHandle, OpUpdate, OpStatus};
pub use workflow::{WorkflowService, WorkflowNode, WorkflowStatus, ApprovalService, ApprovalGate};

/// Errors are surfaced by *every* trait method. The error variants map
/// directly to the failure modes a TUI must render specially.
#[derive(Debug, Clone, thiserror::Error)]
pub enum ServiceError {
    /// Underlying CLI / API not configured (services.yaml missing,
    /// no kubeconfig, etc.). Maps to Python's `UsageError`.
    #[error("service not available: {0}")]
    NotAvailable(String),

    /// Destructive op refused without --acknowledge-risk.
    #[error("destructive op requires confirmation: {0}")]
    NeedsConfirmation(String),

    /// Backfill / replay deep status not yet ready (work coordinator
    /// hasn't published). UI should render "Pending 0%" and retry.
    #[error("deep status not yet available")]
    DeepStatusNotYetAvailable,

    /// Generic upstream failure (subprocess non-zero, HTTP 5xx, etc.).
    /// String captures stderr or response body for the error pane.
    #[error("upstream error: {0}")]
    Upstream(String),

    /// Invalid input rejected before dispatch (schema violation,
    /// unknown component, etc.).
    #[error("invalid input: {0}")]
    Invalid(String),
}

pub type ServiceResult<T> = Result<T, ServiceError>;
