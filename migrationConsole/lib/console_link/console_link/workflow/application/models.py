"""Immutable presentation-neutral models for the workflow manage application."""

from dataclasses import dataclass, field
from typing import Any, Dict, Mapping, Optional, Tuple


@dataclass(frozen=True)
class ManageDiagnostic:
    severity: str
    message: str
    path: Tuple[str, ...] = ()
    source: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        result: Dict[str, Any] = {
            "severity": self.severity,
            "message": self.message,
            "path": list(self.path),
        }
        if self.source:
            result["source"] = self.source
        return result


@dataclass(frozen=True)
class ManageProblem:
    source: str
    message: str
    retryable: bool = True

    def to_dict(self) -> Dict[str, Any]:
        return {
            "source": self.source,
            "message": self.message,
            "retryable": self.retryable,
        }


@dataclass(frozen=True)
class ManageCapability:
    kind: str
    target_id: str
    label: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        result = {
            "kind": self.kind,
            "targetId": self.target_id,
        }
        if self.label:
            result["label"] = self.label
        return result


@dataclass(frozen=True)
class ManageValueState:
    present: bool
    value: Any = None
    provenance: Optional[Mapping[str, Any]] = None

    def to_dict(self) -> Dict[str, Any]:
        result: Dict[str, Any] = {"present": self.present}
        if self.present:
            result["value"] = self.value
        if self.provenance:
            result["provenance"] = dict(self.provenance)
        return result


@dataclass(frozen=True)
class ManageComparison:
    path: str
    label: str
    deployed: ManageValueState
    submitted: ManageValueState
    pending: ManageValueState
    submitted_changed: bool
    pending_changed: bool

    def to_dict(self) -> Dict[str, Any]:
        return {
            "path": self.path,
            "label": self.label,
            "deployed": self.deployed.to_dict(),
            "submitted": self.submitted.to_dict(),
            "pending": self.pending.to_dict(),
            "submittedChanged": self.submitted_changed,
            "pendingChanged": self.pending_changed,
        }


@dataclass(frozen=True)
class ManageDetail:
    label: str
    value: Any
    kind: str = "value"

    def to_dict(self) -> Dict[str, Any]:
        return {
            "label": self.label,
            "value": self.value,
            "kind": self.kind,
        }


@dataclass(frozen=True)
class ManageNode:
    id: str
    revision: str
    kind: str
    label: str
    status: str
    child_ids: Tuple[str, ...] = ()
    parent_id: Optional[str] = None
    description: Optional[str] = None
    phase: Optional[str] = None
    value_summary: Optional[str] = None
    diagnostics: Tuple[ManageDiagnostic, ...] = ()
    capabilities: Tuple[ManageCapability, ...] = ()
    details: Tuple[ManageDetail, ...] = ()
    comparisons: Tuple[ManageComparison, ...] = ()
    resource_plural: Optional[str] = None
    resource_name: Optional[str] = None
    config_presence: Mapping[str, bool] = field(default_factory=dict)

    def to_dict(self) -> Dict[str, Any]:
        result: Dict[str, Any] = {
            "id": self.id,
            "revision": self.revision,
            "kind": self.kind,
            "label": self.label,
            "status": self.status,
            "childIds": list(self.child_ids),
            "diagnostics": [item.to_dict() for item in self.diagnostics],
            "capabilities": [item.to_dict() for item in self.capabilities],
            "details": [item.to_dict() for item in self.details],
            "comparisons": [item.to_dict() for item in self.comparisons],
        }
        if self.parent_id:
            result["parentId"] = self.parent_id
        if self.description:
            result["description"] = self.description
        if self.phase:
            result["phase"] = self.phase
        if self.value_summary:
            result["valueSummary"] = self.value_summary
        if self.resource_plural:
            result["resourcePlural"] = self.resource_plural
        if self.resource_name:
            result["resourceName"] = self.resource_name
        if self.config_presence:
            result["configPresence"] = dict(self.config_presence)
        return result


@dataclass(frozen=True)
class ManageWorkflow:
    name: str
    phase: str
    started_at: Optional[str] = None
    finished_at: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        result: Dict[str, Any] = {
            "name": self.name,
            "phase": self.phase,
        }
        if self.started_at:
            result["startedAt"] = self.started_at
        if self.finished_at:
            result["finishedAt"] = self.finished_at
        return result


@dataclass(frozen=True)
class ManageSnapshot:
    format_version: int
    revision: str
    observed_at: str
    namespace: str
    workflow_name: str
    workflow: Optional[ManageWorkflow]
    root_ids: Tuple[str, ...]
    nodes: Mapping[str, ManageNode] = field(compare=True)
    problems: Tuple[ManageProblem, ...] = ()

    def to_dict(self) -> Dict[str, Any]:
        return {
            "formatVersion": self.format_version,
            "revision": self.revision,
            "observedAt": self.observed_at,
            "namespace": self.namespace,
            "workflowName": self.workflow_name,
            "workflow": self.workflow.to_dict() if self.workflow else None,
            "rootIds": list(self.root_ids),
            "nodes": {
                node_id: node.to_dict()
                for node_id, node in self.nodes.items()
            },
            "problems": [problem.to_dict() for problem in self.problems],
        }
