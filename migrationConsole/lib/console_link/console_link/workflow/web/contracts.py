"""Versioned HTTP contracts for the native workflow manage application."""

from __future__ import annotations

from datetime import datetime
from typing import Annotated, Any, Dict, List, Literal, Mapping, Optional, Union

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

from ..application.models import (
    ManageCapability,
    ManageNode,
    ManageSnapshot,
)
from ..application.external_resources import (
    ExternalResourceDetails,
    ExternalResourceInventory,
    ExternalResourceMutation,
)
from ..application.config_documents import ConfigurationDocument
from ..application.config_review import ConfigReviewChange
from ..application.config_submission import SavedConfigReview
from ..application.operations import Operation
from ..application.actions import (
    ApprovalGateInventory,
    ApprovalGateSummary,
    ApprovalReview,
)
from ..application.resets import ResetPlan, ResetTarget
from ..services.admission_preflight import (
    AdmissionDeploymentAction,
    AdmissionPreflightIssue,
    AdmissionPreflightReport,
)
from ..application.outputs import (
    OutputContent,
    OutputDescriptor,
    OutputInventory,
)
from ..application.logs import (
    LogEvent,
    LogPage,
    LogStream,
    LogStreamStatus,
    LogTarget,
    LogTargetInventory,
)
from ..application.runtime_status import (
    RuntimeStatus,
    RuntimeStatusConsumerOffset,
    RuntimeStatusConsumerOffsets,
    RuntimeStatusContent,
    RuntimeStatusMetric,
    RuntimeStatusMetrics,
    RuntimeStatusNameList,
    RuntimeStatusSection,
    RuntimeStatusText,
    RuntimeStatusTopicPartition,
    RuntimeStatusTopicPartitions,
)


class WebModel(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
    )


class HealthV1(WebModel):
    status: Literal["ok"] = "ok"
    api_version: Literal["v1"] = "v1"


class DiagnosticV1(WebModel):
    severity: str
    message: str
    path: List[str] = Field(default_factory=list)
    source: Optional[str] = None
    code: Optional[str] = None
    title: Optional[str] = None
    remedy: Optional[str] = None
    technical_detail: Optional[str] = None


class ProblemV1(WebModel):
    source: str
    message: str
    retryable: bool


class EditCapabilityV1(WebModel):
    kind: Literal["edit"]
    edit_target_id: str
    label: Optional[str] = None
    disabled_reason: Optional[str] = None


class ApproveCapabilityV1(WebModel):
    kind: Literal["approve"]
    approval_target_id: str
    label: str
    disabled_reason: Optional[str] = None
    output_target_id: Optional[str] = None


class ResetCapabilityV1(WebModel):
    kind: Literal["reset"]
    reset_target_id: str
    label: str
    disabled_reason: Optional[str] = None


class LogsCapabilityV1(WebModel):
    kind: Literal["logs"]
    log_target_id: str
    label: Optional[str] = None
    disabled_reason: Optional[str] = None


class OutputCapabilityV1(WebModel):
    kind: Literal["output"]
    output_target_id: str
    label: Optional[str] = None
    disabled_reason: Optional[str] = None


NodeCapabilityV1 = Union[
    EditCapabilityV1,
    ApproveCapabilityV1,
    ResetCapabilityV1,
    LogsCapabilityV1,
    OutputCapabilityV1,
]


class ValueStateV1(WebModel):
    present: bool
    value: Any = None
    provenance: Optional[Dict[str, Any]] = None


class ComparisonV1(WebModel):
    path: str
    label: str
    deployed: ValueStateV1
    submitted: ValueStateV1
    pending: ValueStateV1
    submitted_changed: bool
    pending_changed: bool


class DetailV1(WebModel):
    label: str
    value: Any
    kind: str


class RelationshipV1(WebModel):
    kind: Literal["runtime-dependency"]
    direction: Literal["requires", "required-by"]
    target_id: Optional[str] = None
    target_name: str
    target_plural: Optional[str] = None
    target_phase: Optional[str] = None
    target_status: str


class ConfigNodeStateV1(WebModel):
    validation_errors: int = 0
    validation_warnings: int = 0
    draft_change_count: int = 0


class ManageNodeV1(WebModel):
    id: str
    revision: str
    parent_id: Optional[str] = None
    child_ids: List[str] = Field(default_factory=list)
    kind: str
    label: str
    description: Optional[str] = None
    status: str
    phase: Optional[str] = None
    value_summary: Optional[str] = None
    activity_at: Optional[datetime] = None
    diagnostics: List[DiagnosticV1] = Field(default_factory=list)
    capabilities: List[NodeCapabilityV1] = Field(default_factory=list)
    details: List[DetailV1] = Field(default_factory=list)
    relationships: List[RelationshipV1] = Field(default_factory=list)
    comparisons: List[ComparisonV1] = Field(default_factory=list)
    resource_plural: Optional[str] = None
    resource_name: Optional[str] = None
    resource_type: Optional[str] = None
    config_presence: Dict[str, bool] = Field(default_factory=dict)
    config_state: Optional[ConfigNodeStateV1] = None
    navigation_key: List[str] = Field(default_factory=list)


class WorkflowV1(WebModel):
    name: str
    phase: str
    started_at: Optional[datetime] = None
    finished_at: Optional[datetime] = None


class ManageSnapshotV1(WebModel):
    format_version: Literal[1]
    revision: str
    observed_at: datetime
    namespace: str
    workflow_name: str
    workflow: Optional[WorkflowV1]
    root_ids: List[str]
    configuration_pending: bool = False
    nodes: Dict[str, ManageNodeV1]
    problems: List[ProblemV1] = Field(default_factory=list)
    stale: bool = False
    refresh_error: Optional[ProblemV1] = None

    @classmethod
    def from_domain(
        cls,
        snapshot: ManageSnapshot,
        *,
        stale: bool = False,
        refresh_error: Optional[Mapping[str, Any]] = None,
    ) -> "ManageSnapshotV1":
        payload = snapshot.to_dict()
        payload["nodes"] = {
            node_id: _node_payload(node)
            for node_id, node in snapshot.nodes.items()
        }
        payload["stale"] = stale
        payload["refreshError"] = refresh_error
        return cls.model_validate(payload)


class EditDiagnosticV1(WebModel):
    severity: Literal["required", "error", "warning", "gated", "blocked"]
    message: str
    path: List[str] = Field(default_factory=list)


class ConfigurationDocumentV1(WebModel):
    raw_yaml: str
    persisted_revision: str
    model_version: Literal["1"]
    deployment_defaults: Optional[Dict[str, Any]] = None

    @classmethod
    def from_domain(
        cls,
        document: ConfigurationDocument,
    ) -> "ConfigurationDocumentV1":
        return cls.model_validate(document.__dict__)


class ConfigurationSchemaV1(WebModel):
    unified_schema: Dict[str, Any]


class SaveConfigurationDocumentRequestV1(WebModel):
    expected_persisted_revision: str
    raw_yaml: str


class ConfigEnvironmentDiagnosticsRequestV1(WebModel):
    raw_yaml: str
    draft_nonce: str


class ConfigEnvironmentDiagnosticsV1(WebModel):
    draft_nonce: str
    status: Literal["valid", "warning", "error"]
    diagnostics: List[EditDiagnosticV1] = Field(default_factory=list)


class ConnectivityInventoryRequestV1(WebModel):
    raw_yaml: str
    config_nonce: str


class ConnectivityTargetV1(WebModel):
    id: str
    kind: Literal["source", "target", "repository"]
    ref_name: str
    label: str
    edit_path: List[str] = Field(default_factory=list)
    provider: Optional[Literal["s3", "gcs"]] = None


class ConnectivityInventoryV1(WebModel):
    config_nonce: str
    targets: List[ConnectivityTargetV1] = Field(default_factory=list)


class StartConnectivityChecksRequestV1(ConnectivityInventoryRequestV1):
    target_ids: List[str] = Field(default_factory=list)


class PersistedRevisionRequestV1(WebModel):
    expected_persisted_revision: str


class ConfigReviewChangeV1(WebModel):
    resource_id: Optional[str] = None
    resource_label: Optional[str] = None
    path: str
    label: str
    kind: Literal["field", "resource"]

    @classmethod
    def from_domain(
        cls,
        change: Union[ConfigReviewChange, Mapping[str, Any]],
    ) -> "ConfigReviewChangeV1":
        if isinstance(change, Mapping):
            return cls.model_validate(change)
        return cls.model_validate(change.__dict__)


class ConfigReviewV1(WebModel):
    persisted_revision: str
    valid: bool
    validation_messages: List[str]
    changes: List[ConfigReviewChangeV1]

    @classmethod
    def from_domain(
        cls,
        review: Union[SavedConfigReview, Mapping[str, Any]],
    ) -> "ConfigReviewV1":
        source = review if isinstance(review, Mapping) else review.__dict__
        return cls(
            persisted_revision=str(source["persisted_revision"]),
            valid=bool(source["valid"]),
            validation_messages=list(source["validation_messages"]),
            changes=[
                ConfigReviewChangeV1.from_domain(change)
                for change in source["changes"]
            ],
        )


class AdmissionDeploymentActionV1(WebModel):
    kind: str
    name: str
    plural: Optional[str] = None
    action: Literal["create", "reconcile"]
    reason: Literal[
        "resource-missing",
        "resource-not-ready",
        "configuration-changed",
        "checksum-only",
    ]
    message: str
    resource_id: Optional[str] = None
    current_config_checksum: Optional[str] = None
    desired_config_checksum: Optional[str] = None

    @classmethod
    def from_domain(
        cls,
        action: AdmissionDeploymentAction,
    ) -> "AdmissionDeploymentActionV1":
        return cls(
            kind=action.kind,
            name=action.name,
            plural=action.plural,
            action=action.action,
            reason=action.reason,
            message=action.message,
            resource_id=action.resource_id,
            current_config_checksum=action.current_config_checksum,
            desired_config_checksum=action.desired_config_checksum,
        )


class AdmissionPreflightIssueV1(WebModel):
    kind: str
    name: str
    plural: Optional[str] = None
    classification: Literal[
        "recreate-required",
        "invalid",
        "approval-required",
        "warning",
    ]
    message: str
    source: str
    blocking: bool
    resource_id: Optional[str] = None
    reset_target_id: Optional[str] = None

    @classmethod
    def from_domain(
        cls,
        issue: AdmissionPreflightIssue,
    ) -> "AdmissionPreflightIssueV1":
        return cls(
            kind=issue.kind,
            name=issue.name,
            plural=issue.plural,
            classification=issue.classification,
            message=issue.message,
            source=issue.source,
            blocking=issue.blocking,
            resource_id=issue.resource_id,
            reset_target_id=issue.reset_target_id,
        )


class AdmissionPreflightV1(WebModel):
    checked_resources: int
    allowed: bool
    issues: List[AdmissionPreflightIssueV1]
    deployment_actions: Optional[List[AdmissionDeploymentActionV1]] = None

    @classmethod
    def from_domain(
        cls,
        report: AdmissionPreflightReport,
    ) -> "AdmissionPreflightV1":
        return cls(
            checked_resources=report.checked_resources,
            allowed=report.allowed,
            issues=[
                AdmissionPreflightIssueV1.from_domain(issue)
                for issue in report.issues
            ],
            deployment_actions=(
                [
                    AdmissionDeploymentActionV1.from_domain(action)
                    for action in report.deployment_actions
                ]
                if report.deployment_actions
                else None
            ),
        )


class RuntimeStatusMetricV1(WebModel):
    key: str
    label: str
    value: str | int | float | bool
    unit: Optional[str] = None

    @classmethod
    def from_domain(cls, metric: RuntimeStatusMetric) -> "RuntimeStatusMetricV1":
        return cls.model_validate(metric.__dict__)


class RuntimeStatusMetricsV1(WebModel):
    kind: Literal["metrics"] = "metrics"
    metrics: List[RuntimeStatusMetricV1]


class RuntimeStatusNameListV1(WebModel):
    kind: Literal["name-list"] = "name-list"
    items: List[str]


class RuntimeStatusTopicPartitionV1(WebModel):
    topic: str
    partition: int
    records: int

    @classmethod
    def from_domain(
        cls,
        partition: RuntimeStatusTopicPartition,
    ) -> "RuntimeStatusTopicPartitionV1":
        return cls.model_validate(partition.__dict__)


class RuntimeStatusTopicPartitionsV1(WebModel):
    kind: Literal["topic-partitions"] = "topic-partitions"
    partitions: List[RuntimeStatusTopicPartitionV1]


class RuntimeStatusConsumerOffsetV1(WebModel):
    group: str
    topic: str
    partition: int
    current_offset: Optional[int] = None
    log_end_offset: Optional[int] = None
    lag: Optional[int] = None

    @classmethod
    def from_domain(
        cls,
        offset: RuntimeStatusConsumerOffset,
    ) -> "RuntimeStatusConsumerOffsetV1":
        return cls.model_validate(offset.__dict__)


class RuntimeStatusConsumerOffsetsV1(WebModel):
    kind: Literal["consumer-offsets"] = "consumer-offsets"
    offsets: List[RuntimeStatusConsumerOffsetV1]


class RuntimeStatusTextV1(WebModel):
    kind: Literal["text"] = "text"
    lines: List[str]


RuntimeStatusContentV1 = Annotated[
    Union[
        RuntimeStatusMetricsV1,
        RuntimeStatusNameListV1,
        RuntimeStatusConsumerOffsetsV1,
        RuntimeStatusTopicPartitionsV1,
        RuntimeStatusTextV1,
    ],
    Field(discriminator="kind"),
]


def _runtime_status_content(
    content: Optional[RuntimeStatusContent],
) -> Optional[RuntimeStatusContentV1]:
    if isinstance(content, RuntimeStatusMetrics):
        return RuntimeStatusMetricsV1(
            metrics=[
                RuntimeStatusMetricV1.from_domain(metric)
                for metric in content.metrics
            ],
        )
    if isinstance(content, RuntimeStatusNameList):
        return RuntimeStatusNameListV1(items=list(content.items))
    if isinstance(content, RuntimeStatusConsumerOffsets):
        return RuntimeStatusConsumerOffsetsV1(
            offsets=[
                RuntimeStatusConsumerOffsetV1.from_domain(offset)
                for offset in content.offsets
            ],
        )
    if isinstance(content, RuntimeStatusTopicPartitions):
        return RuntimeStatusTopicPartitionsV1(
            partitions=[
                RuntimeStatusTopicPartitionV1.from_domain(partition)
                for partition in content.partitions
            ],
        )
    if isinstance(content, RuntimeStatusText):
        return RuntimeStatusTextV1(lines=list(content.lines))
    return None


class RuntimeStatusSectionV1(WebModel):
    key: str
    title: str
    state: Literal["ok", "running", "pending", "error", "unsupported"]
    summary: str
    source: str
    content: Optional[RuntimeStatusContentV1] = None

    @classmethod
    def from_domain(
        cls,
        section: RuntimeStatusSection,
    ) -> "RuntimeStatusSectionV1":
        return cls(
            key=section.key,
            title=section.title,
            state=section.state,
            summary=section.summary,
            source=section.source,
            content=_runtime_status_content(section.content),
        )


class RuntimeStatusV1(WebModel):
    node_id: str
    observed_at: datetime
    poll_after_ms: Optional[int] = None
    sections: List[RuntimeStatusSectionV1]

    @classmethod
    def from_domain(cls, status: RuntimeStatus) -> "RuntimeStatusV1":
        return cls(
            node_id=status.node_id,
            observed_at=status.observed_at,
            poll_after_ms=status.poll_after_ms,
            sections=[
                RuntimeStatusSectionV1.from_domain(section)
                for section in status.sections
            ],
        )


class OperationV1(WebModel):
    id: str
    kind: str
    label: str
    status: Literal[
        "queued",
        "running",
        "waiting",
        "succeeded",
        "failed",
    ]
    target_ids: List[str]
    created_at: datetime
    updated_at: datetime
    message: str
    detail: Optional[str] = None
    result: Dict[str, Any] = Field(default_factory=dict)

    @classmethod
    def from_domain(cls, operation: Operation) -> "OperationV1":
        return cls.model_validate(operation.__dict__)


class OperationListV1(WebModel):
    operations: List[OperationV1]


class ApprovalReviewV1(WebModel):
    target_id: str
    node_id: str
    gate_name: str
    gate_revision: str
    workflow_name: str
    resource_id: Optional[str] = None
    resource_kind: Optional[str] = None
    resource_name: Optional[str] = None
    stage: str
    effect: str
    reason: Optional[str] = None
    snapshot_revision: Optional[str] = None

    @classmethod
    def from_domain(cls, review: ApprovalReview) -> "ApprovalReviewV1":
        return cls.model_validate(review.__dict__)


class ApproveRequestV1(WebModel):
    target_id: str
    expected_gate_revision: str


class ApprovalGateSummaryV1(WebModel):
    name: str
    gate_revision: str
    category: Literal["checkpoint", "recovery"]
    state: Literal[
        "upcoming",
        "preapproved",
        "blocking",
        "accepted",
        "passed",
        "not-required",
        "not-reached",
        "recovery-standby",
        "error",
    ]
    phase: str
    resource_id: Optional[str] = None
    resource_kind: Optional[str] = None
    resource_name: Optional[str] = None
    stage: str
    effect: str
    reason: Optional[str] = None
    enabled: bool
    approved: bool
    toggleable: bool
    disabled_reason: Optional[str] = None
    approval_target_id: Optional[str] = None
    output_target_id: Optional[str] = None

    @classmethod
    def from_domain(
        cls,
        gate: ApprovalGateSummary,
    ) -> "ApprovalGateSummaryV1":
        return cls.model_validate(gate.__dict__)


class ApprovalGateInventoryV1(WebModel):
    workflow_name: str
    gates: List[ApprovalGateSummaryV1]

    @classmethod
    def from_domain(
        cls,
        inventory: ApprovalGateInventory,
    ) -> "ApprovalGateInventoryV1":
        return cls(
            workflow_name=inventory.workflow_name,
            gates=[
                ApprovalGateSummaryV1.from_domain(gate)
                for gate in inventory.gates
            ],
        )


class SetPreapprovalRequestV1(WebModel):
    expected_gate_revision: str
    preapproved: bool


class SetPreapprovalResponseV1(WebModel):
    gate_name: str
    preapproved: bool


class ResetPlanRequestV1(WebModel):
    target_id: Optional[str] = None
    target_ids: List[str] = Field(default_factory=list)


class ResetTargetV1(WebModel):
    plural: str
    type: str
    name: str
    path: str
    phase: str
    depends_on: List[str]

    @classmethod
    def from_domain(cls, target: ResetTarget) -> "ResetTargetV1":
        return cls(
            plural=target.plural,
            type=target.type,
            name=target.name,
            path=target.path,
            phase=target.phase,
            depends_on=list(target.depends_on),
        )


class ResetPlanV1(WebModel):
    token: str
    request_target_id: str
    targets: List[ResetTargetV1]
    messages: List[str]
    warnings: List[str]

    @classmethod
    def from_domain(cls, plan: ResetPlan) -> "ResetPlanV1":
        return cls(
            token=plan.token,
            request_target_id=plan.request_target_id,
            targets=[
                ResetTargetV1.from_domain(target)
                for target in plan.targets
            ],
            messages=list(plan.messages),
            warnings=list(plan.warnings),
        )


class ResetApprovalRequestV1(WebModel):
    target_id: str
    expected_gate_revision: str


class ExecuteResetRequestV1(WebModel):
    plan_token: str
    resubmit: bool = False
    expected_persisted_revision: Optional[str] = None
    # Kept for one contract transition; legacy reset-and-retry clients now
    # trigger resubmission and these old gate revisions are never approved.
    approvals: List[ResetApprovalRequestV1] = Field(default_factory=list)


class OutputDescriptorV1(WebModel):
    id: str
    target_id: str
    resource_id: str
    resource_plural: str
    resource_name: str
    output_name: str
    stage: str
    stage_order: int
    attempt: Optional[str] = None
    timestamp: Optional[datetime] = None
    source: str
    content_type: str

    @classmethod
    def from_domain(
        cls,
        descriptor: OutputDescriptor,
    ) -> "OutputDescriptorV1":
        return cls.model_validate(descriptor.to_dict())


class OutputInventoryV1(WebModel):
    target_id: str
    resource_id: str
    outputs: List[OutputDescriptorV1]

    @classmethod
    def from_domain(
        cls,
        inventory: OutputInventory,
    ) -> "OutputInventoryV1":
        return cls(
            target_id=inventory.target_id,
            resource_id=inventory.resource_id,
            outputs=[
                OutputDescriptorV1.from_domain(item)
                for item in inventory.outputs
            ],
        )


class OutputContentV1(WebModel):
    descriptor: OutputDescriptorV1
    content: Optional[str] = None
    inline: bool
    size: int
    message: Optional[str] = None

    @classmethod
    def from_domain(cls, content: OutputContent) -> "OutputContentV1":
        return cls(
            descriptor=OutputDescriptorV1.from_domain(content.descriptor),
            content=content.content,
            inline=content.inline,
            size=content.size,
            message=content.message,
        )


class LogTargetV1(WebModel):
    id: str
    label: str
    kind: Literal["aggregate", "container"]
    pod_name: Optional[str] = None
    pod_uid: Optional[str] = None
    container: Optional[str] = None
    restart_count: Optional[int] = None
    previous: bool
    supports_follow: bool

    @classmethod
    def from_domain(cls, target: LogTarget) -> "LogTargetV1":
        return cls.model_validate(target.__dict__)


class LogTargetInventoryV1(WebModel):
    node_id: str
    subject_label: str
    subject_kind: str
    capability_target_id: str
    targets: List[LogTargetV1]
    message: Optional[str] = None
    external_logs_url: Optional[str] = None

    @classmethod
    def from_domain(
        cls,
        inventory: LogTargetInventory,
        *,
        subject_label: str,
        subject_kind: str,
        external_logs_url: Optional[str] = None,
    ) -> "LogTargetInventoryV1":
        return cls(
            node_id=inventory.node_id,
            subject_label=subject_label,
            subject_kind=subject_kind,
            capability_target_id=inventory.capability_target_id,
            targets=[
                LogTargetV1.from_domain(target)
                for target in inventory.targets
            ],
            message=inventory.message,
            external_logs_url=external_logs_url,
        )


class StartLogStreamRequestV1(WebModel):
    target_id: str
    tail_lines: int = Field(default=1000, ge=1, le=5000)
    follow: bool = True
    page_size: int = Field(default=200, ge=1, le=1000)


class LogEventV1(WebModel):
    sequence: int
    received_at: datetime
    timestamp: Optional[datetime] = None
    pod_name: str
    pod_uid: str
    container: str
    restart_count: int
    previous: bool
    message: str
    kind: Literal["log", "error"] = "log"

    @classmethod
    def from_domain(cls, event: LogEvent) -> "LogEventV1":
        return cls.model_validate(event.__dict__)


class LogPageV1(WebModel):
    events: List[LogEventV1]
    before_cursor: Optional[str] = None
    after_cursor: Optional[str] = None
    at_available_start: bool
    at_buffer_end: bool
    history_truncated: bool
    state: Literal["starting", "following", "ended", "stopped", "error"]

    @classmethod
    def from_domain(cls, page: LogPage) -> "LogPageV1":
        return cls(
            events=[
                LogEventV1.from_domain(event)
                for event in page.events
            ],
            before_cursor=page.before_cursor,
            after_cursor=page.after_cursor,
            at_available_start=page.at_available_start,
            at_buffer_end=page.at_buffer_end,
            history_truncated=page.history_truncated,
            state=page.state,
        )


class LogStreamV1(WebModel):
    id: str
    target: LogTargetV1
    state: Literal["starting", "following", "ended", "stopped", "error"]
    page: LogPageV1

    @classmethod
    def from_domain(cls, stream: LogStream) -> "LogStreamV1":
        return cls(
            id=stream.id,
            target=LogTargetV1.from_domain(stream.target),
            state=stream.state,
            page=LogPageV1.from_domain(stream.page),
        )


class LogStreamStatusV1(WebModel):
    id: str
    state: Literal["starting", "following", "ended", "stopped", "error"]
    message: Optional[str] = None

    @classmethod
    def from_domain(
        cls,
        status: LogStreamStatus,
    ) -> "LogStreamStatusV1":
        return cls.model_validate(status.__dict__)


class ExternalResourceRowV1(WebModel):
    name: str
    kind: str
    group: str = ""
    version: str = ""
    api_version: Optional[str] = None
    namespaced: Optional[bool] = None
    type: Optional[str] = None
    keys: List[str] = Field(default_factory=list)
    status: Literal["matching", "warn", "error"]
    message: str = ""
    current: bool = False


class ExternalResourceInventoryV1(WebModel):
    node_id: str
    display_name: str
    rows: List[ExternalResourceRowV1]

    @classmethod
    def from_domain(
        cls,
        inventory: ExternalResourceInventory,
    ) -> "ExternalResourceInventoryV1":
        return cls.model_validate({
            "nodeId": inventory.node_id,
            "displayName": inventory.display_name,
            "rows": inventory.rows,
        })


class ExternalResourceContextRequestV1(WebModel):
    raw_yaml: str
    node_id: str


class SelectExternalResourceRequestV1(ExternalResourceContextRequestV1):
    name: str
    kind: str
    group: str = ""
    key: Optional[str] = None
    accept_warning: bool = False
    manual: bool = False


class ExternalResourceSelectionV1(WebModel):
    accepted: Literal[True] = True


class ExternalResourceDetailsRequestV1(ExternalResourceContextRequestV1):
    name: str


class ExternalResourceDetailsV1(WebModel):
    node_id: str
    display_name: str
    name: str
    kind: str
    resource_type: Optional[str] = None
    keys: List[str] = Field(default_factory=list)
    field_values: Dict[str, str] = Field(default_factory=dict)
    hidden_fields: List[str] = Field(default_factory=list)
    missing: bool = False
    message: Optional[str] = None

    @classmethod
    def from_domain(
        cls,
        details: ExternalResourceDetails,
    ) -> "ExternalResourceDetailsV1":
        return cls.model_validate(details.__dict__)


class SaveExternalResourceRequestV1(WebModel):
    raw_yaml: str
    node_id: str
    values: Dict[str, str]
    confirmations: Dict[str, str] = Field(default_factory=dict)
    existing_name: Optional[str] = None


class ExternalResourceMutationV1(WebModel):
    name: str
    kind: str
    message: str

    @classmethod
    def from_domain(
        cls,
        mutation: ExternalResourceMutation,
    ) -> "ExternalResourceMutationV1":
        return cls.model_validate(mutation.__dict__)


def _node_payload(node: ManageNode) -> Dict[str, Any]:
    payload = node.to_dict()
    payload["capabilities"] = [
        _capability_payload(capability)
        for capability in node.capabilities
    ]
    return payload


def _capability_payload(capability: ManageCapability) -> Dict[str, Any]:
    target_fields = {
        "edit": "editTargetId",
        "approve": "approvalTargetId",
        "reset": "resetTargetId",
        "logs": "logTargetId",
        "output": "outputTargetId",
    }
    target_field = target_fields.get(capability.kind)
    if target_field is None:
        raise ValueError(f"Unsupported capability kind: {capability.kind}")
    payload: Dict[str, Any] = {
        "kind": capability.kind,
        target_field: capability.target_id,
    }
    if capability.label:
        payload["label"] = capability.label
    if capability.disabled_reason:
        payload["disabledReason"] = capability.disabled_reason
    if capability.related_output_target_id:
        payload["outputTargetId"] = capability.related_output_target_id
    return payload
