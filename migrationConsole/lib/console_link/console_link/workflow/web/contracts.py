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
from ..application.config_drafts import (
    ConfigDraft,
    ConfigReview,
    ConfigReviewChange,
    ConfigRemovalImpact,
    ConfigSubmission,
    ExternalResourceDetails,
    ExternalResourceInventory,
    ExternalResourceMutation,
)
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
from ..application.runtime_status import RuntimeStatus, RuntimeStatusSection


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


class EditStatusCountsV1(WebModel):
    required: int = 0
    errors: int = 0
    warnings: int = 0
    changed: int = 0
    gated: int = 0
    blocked: int = 0


class EditCommandV1(WebModel):
    requires_name: bool = True
    edit_added: bool = False
    auto_edit_added: bool = True
    blocked_message: Optional[str] = None


class EditVariantV1(WebModel):
    label: str
    value: Any
    description: Optional[str] = None
    child_schema: List["EditNodeV1"] = Field(default_factory=list)


class EditDraftChangeV1(WebModel):
    kind: Literal["added", "modified"]
    previous_value: Any = None
    previous_value_present: bool = False


class ResourceNavigationHintV1(WebModel):
    section_id: str
    section_label: str
    section_order: int
    group_id: str
    group_label: str
    group_order: int
    parent_group_id: Optional[str] = None
    parent_group_label: Optional[str] = None
    parent_group_order: Optional[int] = None
    add_control_id: Optional[str] = None


class NamedResourceIdentityHintV1(WebModel):
    kind: Literal["named"]
    prefix: Optional[str] = None
    suffix: Optional[str] = None


class IndexedResourceIdentityHintV1(WebModel):
    kind: Literal["indexed-config"]
    prefix: str
    first_index: int


ResourceIdentityHintV1 = Annotated[
    Union[
        NamedResourceIdentityHintV1,
        IndexedResourceIdentityHintV1,
    ],
    Field(discriminator="kind"),
]


class ResourceDescriptorHintV1(WebModel):
    kind: str
    plural: str
    type_label: str
    identity: ResourceIdentityHintV1


class ResourceCollectionHintV1(WebModel):
    navigation: ResourceNavigationHintV1
    resource: ResourceDescriptorHintV1


class DefinitionNavigationHintV1(WebModel):
    group_label: str
    group_order: int
    group_id: Optional[str] = None


class DefinitionDescriptorHintV1(WebModel):
    type_label: str


class DefinitionCollectionHintV1(WebModel):
    owner_ancestor_levels: int
    navigation: DefinitionNavigationHintV1
    definition: DefinitionDescriptorHintV1


class EditInputHintV1(WebModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="allow",
    )

    resource_collection: Optional[ResourceCollectionHintV1] = None
    definition_collection: Optional[DefinitionCollectionHintV1] = None


class EditNodeV1(WebModel):
    id: str
    path: List[str]
    label: str
    value: Any = None
    value_defaulted: Optional[bool] = None
    value_authored: Optional[bool] = None
    value_type: Optional[Literal["string", "number", "boolean"]] = None
    value_kind: Literal[
        "object",
        "record",
        "array",
        "union",
        "boolean",
        "scalar",
        "command",
    ]
    presence: Optional[Literal["required", "optional"]] = None
    expert: Optional[bool] = None
    essential: Optional[bool] = None
    implicit: Optional[bool] = None
    description: Optional[str] = None
    required: Optional[bool] = None
    removable: Optional[bool] = None
    status: Optional[
        Literal["ok", "required", "error", "warning", "changed", "gated", "blocked"]
    ] = None
    status_counts: Optional[EditStatusCountsV1] = None
    draft_change: Optional[EditDraftChangeV1] = None
    draft_change_count: Optional[int] = None
    input_hint: Optional[EditInputHintV1] = None
    external_ref: Optional[Dict[str, Any]] = None
    effective_default: Optional[Dict[str, Any]] = None
    validation: Optional[Dict[str, str]] = None
    diagnostics: List[EditDiagnosticV1] = Field(default_factory=list)
    collapsed: Optional[bool] = None
    reference_target_id: Optional[str] = None
    reference_label: Optional[str] = None
    variants: List[EditVariantV1] = Field(default_factory=list)
    command: Optional[EditCommandV1] = None
    children: List["EditNodeV1"] = Field(default_factory=list)


class EditProvenanceV1(WebModel):
    source: Literal["pending-yaml"]
    lossy: bool
    mode: Literal["structured", "raw"] = "structured"
    warnings: List[str] = Field(default_factory=list)


class EditValidationV1(WebModel):
    valid: bool
    errors: List[str] = Field(default_factory=list)
    diagnostics: List[EditDiagnosticV1] = Field(default_factory=list)


class EditStateV1(WebModel):
    format_version: Literal[1]
    provenance: EditProvenanceV1
    nodes: List[EditNodeV1]
    validation: EditValidationV1


class ConfigDraftV1(WebModel):
    base_revision: str
    draft_revision: str
    dirty: bool
    edit_state: EditStateV1
    navigation: Optional[ManageSnapshotV1] = None
    raw_yaml: Optional[str] = None

    @classmethod
    def from_domain(
        cls,
        draft: ConfigDraft,
        *,
        navigation: Optional[ManageSnapshot] = None,
    ) -> "ConfigDraftV1":
        return cls.model_validate({
            "baseRevision": draft.base_revision,
            "draftRevision": draft.draft_revision,
            "dirty": draft.dirty,
            "editState": draft.edit_state,
            "navigation": (
                ManageSnapshotV1.from_domain(navigation)
                if navigation is not None else None
            ),
            "rawYaml": draft.repair_yaml,
        })


class SetEditOperationV1(WebModel):
    op: Literal["set"]
    path: List[str]
    value: Any


class UnsetEditOperationV1(WebModel):
    op: Literal["unset"]
    path: List[str]


class RemoveConfigEditOperationV1(WebModel):
    op: Literal["removeConfig"]
    path: List[str]


class RenameConfigEditOperationV1(WebModel):
    op: Literal["renameConfig"]
    path: List[str]
    new_name: str


class AddEditOperationV1(WebModel):
    op: Literal["add"]
    path: List[str]
    value: Any


EditOperationV1 = Annotated[
    Union[
        SetEditOperationV1,
        UnsetEditOperationV1,
        RemoveConfigEditOperationV1,
        RenameConfigEditOperationV1,
        AddEditOperationV1,
    ],
    Field(discriminator="op"),
]


class ApplyEditOperationRequestV1(WebModel):
    expected_draft_revision: str
    operation: EditOperationV1


class DraftRevisionRequestV1(WebModel):
    expected_draft_revision: str


class ReplaceRawConfigRequestV1(DraftRevisionRequestV1):
    raw_yaml: str


class ConfigRemovalImpactRequestV1(DraftRevisionRequestV1):
    path: List[str]


class ConfigRemovalImpactEntryV1(WebModel):
    path: List[str]
    field_path: List[str]
    reason: str


class ConfigRemovalImpactV1(WebModel):
    target_path: List[str]
    target_label: str
    affected: List[ConfigRemovalImpactEntryV1]

    @classmethod
    def from_domain(
        cls,
        impact: ConfigRemovalImpact,
    ) -> "ConfigRemovalImpactV1":
        return cls.model_validate({
            "targetPath": list(impact.target_path),
            "targetLabel": impact.target_label,
            "affected": [
                {
                    "path": list(entry.path),
                    "fieldPath": list(entry.field_path),
                    "reason": entry.reason,
                }
                for entry in impact.affected
            ],
        })


class ConfigSubmissionV1(WebModel):
    draft: ConfigDraftV1
    workflow_name: str
    message: str

    @classmethod
    def from_domain(cls, submission: ConfigSubmission) -> "ConfigSubmissionV1":
        return cls(
            draft=ConfigDraftV1.from_domain(submission.draft),
            workflow_name=submission.workflow_name,
            message=submission.message,
        )


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
    draft_revision: str
    base_revision: str
    dirty: bool
    valid: bool
    validation_messages: List[str]
    changes: List[ConfigReviewChangeV1]

    @classmethod
    def from_domain(
        cls,
        review: Union[ConfigReview, Mapping[str, Any]],
    ) -> "ConfigReviewV1":
        source = review if isinstance(review, Mapping) else review.__dict__
        return cls(
            draft_revision=str(source["draft_revision"]),
            base_revision=str(source["base_revision"]),
            dirty=bool(source["dirty"]),
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


class RuntimeStatusSectionV1(WebModel):
    key: str
    title: str
    state: Literal["ok", "running", "pending", "error", "unsupported"]
    summary: str
    source: str
    details: List[str] = Field(default_factory=list)

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
            details=list(section.details),
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
    expected_draft_revision: Optional[str] = None
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
    draft_revision: str
    display_name: str
    rows: List[ExternalResourceRowV1]

    @classmethod
    def from_domain(
        cls,
        inventory: ExternalResourceInventory,
    ) -> "ExternalResourceInventoryV1":
        return cls.model_validate({
            "nodeId": inventory.node_id,
            "draftRevision": inventory.draft_revision,
            "displayName": inventory.display_name,
            "rows": inventory.rows,
        })


class SelectExternalResourceRequestV1(WebModel):
    expected_draft_revision: str
    node_id: str
    name: str
    kind: str
    group: str = ""
    key: Optional[str] = None
    accept_warning: bool = False
    manual: bool = False


class ExternalResourceDetailsV1(WebModel):
    node_id: str
    draft_revision: str
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
    expected_draft_revision: str
    node_id: str
    values: Dict[str, str]
    confirmations: Dict[str, str] = Field(default_factory=dict)
    existing_name: Optional[str] = None


class ExternalResourceMutationV1(WebModel):
    draft: ConfigDraftV1
    name: str
    kind: str
    message: str

    @classmethod
    def from_domain(
        cls,
        mutation: ExternalResourceMutation,
        *,
        navigation: Optional[ManageSnapshot] = None,
    ) -> "ExternalResourceMutationV1":
        return cls.model_validate({
            "draft": ConfigDraftV1.from_domain(
                mutation.draft,
                navigation=navigation,
            ),
            "name": mutation.name,
            "kind": mutation.kind,
            "message": mutation.message,
        })


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
