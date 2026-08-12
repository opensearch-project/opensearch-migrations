"""Versioned HTTP contracts for the native workflow manage application."""

from datetime import datetime
from typing import Any, Dict, List, Literal, Mapping, Optional, Union

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

from ..application.models import (
    ManageCapability,
    ManageNode,
    ManageSnapshot,
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
