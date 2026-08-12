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
    ExternalResourceDetails,
    ExternalResourceInventory,
    ExternalResourceMutation,
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


class ProblemV1(WebModel):
    source: str
    message: str
    retryable: bool


class EditCapabilityV1(WebModel):
    kind: Literal["edit"]
    edit_target_id: str
    label: Optional[str] = None


class ApproveCapabilityV1(WebModel):
    kind: Literal["approve"]
    approval_target_id: str
    label: str


class ResetCapabilityV1(WebModel):
    kind: Literal["reset"]
    reset_target_id: str
    label: str


class LogsCapabilityV1(WebModel):
    kind: Literal["logs"]
    log_target_id: str
    label: Optional[str] = None


class OutputCapabilityV1(WebModel):
    kind: Literal["output"]
    output_target_id: str
    label: Optional[str] = None


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
    diagnostics: List[DiagnosticV1] = Field(default_factory=list)
    capabilities: List[NodeCapabilityV1] = Field(default_factory=list)
    details: List[DetailV1] = Field(default_factory=list)
    comparisons: List[ComparisonV1] = Field(default_factory=list)
    resource_plural: Optional[str] = None
    resource_name: Optional[str] = None


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
    description: Optional[str] = None
    required: Optional[bool] = None
    removable: Optional[bool] = None
    status: Optional[
        Literal["ok", "required", "error", "warning", "changed", "gated", "blocked"]
    ] = None
    status_counts: Optional[EditStatusCountsV1] = None
    input_hint: Optional[Dict[str, Any]] = None
    external_ref: Optional[Dict[str, Any]] = None
    effective_default: Optional[Dict[str, Any]] = None
    validation: Optional[Dict[str, str]] = None
    diagnostics: List[EditDiagnosticV1] = Field(default_factory=list)
    collapsed: Optional[bool] = None
    variants: List[EditVariantV1] = Field(default_factory=list)
    command: Optional[EditCommandV1] = None
    children: List["EditNodeV1"] = Field(default_factory=list)


class EditProvenanceV1(WebModel):
    source: Literal["pending-yaml"]
    lossy: bool
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

    @classmethod
    def from_domain(cls, draft: ConfigDraft) -> "ConfigDraftV1":
        return cls.model_validate({
            "baseRevision": draft.base_revision,
            "draftRevision": draft.draft_revision,
            "dirty": draft.dirty,
            "editState": draft.edit_state,
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
    ) -> "ExternalResourceMutationV1":
        return cls.model_validate({
            "draft": ConfigDraftV1.from_domain(mutation.draft),
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
    return payload
