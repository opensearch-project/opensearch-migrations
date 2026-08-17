"""Exact-target approval review and execution."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Callable, Dict, Mapping, Optional

from kubernetes import client

from ..commands.approve import (
    LABEL_RESOURCE_KIND,
    LABEL_RESOURCE_NAME,
    approve_gate,
)
from ..commands.crd_utils import CRD_GROUP, CRD_VERSION
from ..resource_tree import RESOURCE_KIND_TO_PLURAL
from ..tree_utils import (
    extract_denial_reason,
    get_node_input_parameter,
    is_approval_node,
)


APPROVAL_STAGES = {
    "evaluatemetadata": (
        "Metadata evaluation",
        (
            "Approving allows metadata evaluation to complete and advances "
            "to metadata migration."
        ),
    ),
    "migratemetadata": (
        "Metadata migration",
        (
            "Approving allows evaluated metadata changes to be applied "
            "before document backfill."
        ),
    ),
    "documentbackfill": (
        "Document backfill",
        "Approving starts the document backfill stage.",
    ),
    "vapretry": (
        "Resource reconciliation",
        "Approving retries applying the resource configuration.",
    ),
}


class ApprovalUnavailable(RuntimeError):
    pass


class ApprovalStale(RuntimeError):
    pass


@dataclass(frozen=True)
class ApprovalReview:
    target_id: str
    node_id: str
    gate_name: str
    gate_revision: str
    workflow_name: str
    resource_id: Optional[str]
    resource_kind: Optional[str]
    resource_name: Optional[str]
    stage: str
    effect: str
    reason: Optional[str]
    snapshot_revision: Optional[str]


class ApprovalService:
    def __init__(
        self,
        namespace: str,
        workflow_name: str,
        *,
        workflow_loader: Callable[[], Mapping[str, Any]],
        gate_loader: Optional[
            Callable[[str], Mapping[str, Any]]
        ] = None,
        approver: Optional[Callable[[str], bool]] = None,
        custom_api: Optional[Any] = None,
    ):
        self.namespace = namespace
        self.workflow_name = workflow_name
        self._workflow_loader = workflow_loader
        self._custom_api = custom_api
        self._gate_loader = gate_loader or self._load_gate
        self._approver = approver or (
            self._approve_gate if custom_api is not None
            else lambda name: approve_gate(self.namespace, name)
        )

    def review(
        self,
        target_id: str,
        snapshot_revision: Optional[str] = None,
    ) -> ApprovalReview:
        node_id = _approval_node_id(target_id)
        workflow = self._workflow_loader()
        node = _workflow_nodes(workflow).get(node_id)
        if (
            not node
            or not is_approval_node(dict(node))
            or str(node.get("phase")) != "Running"
        ):
            raise ApprovalUnavailable(
                "The selected approval target is no longer waiting."
            )
        gate_name = (
            get_node_input_parameter(dict(node), "resourceName")
            or get_node_input_parameter(dict(node), "name")
        )
        if not gate_name:
            raise ApprovalUnavailable(
                "The approval target does not identify an ApprovalGate."
            )
        try:
            gate = self._gate_loader(str(gate_name))
        except ApprovalUnavailable:
            raise
        except Exception as error:
            raise ApprovalUnavailable(
                f"ApprovalGate {gate_name} is no longer available."
            ) from error
        metadata = gate.get("metadata") or {}
        gate_revision = str(metadata.get("resourceVersion") or "")
        if not gate_revision:
            raise ApprovalUnavailable(
                "The ApprovalGate does not have a resource version."
            )
        if str((gate.get("status") or {}).get("phase") or "") == "Approved":
            raise ApprovalUnavailable(
                "The selected ApprovalGate is already approved."
            )
        labels = metadata.get("labels") or {}
        resource_kind = _optional(labels.get(LABEL_RESOURCE_KIND))
        resource_name = _optional(labels.get(LABEL_RESOURCE_NAME))
        plural = RESOURCE_KIND_TO_PLURAL.get(resource_kind or "")
        resource_id = (
            f"resource:{plural}:{resource_name}"
            if plural and resource_name else None
        )
        stage, effect = _approval_stage(str(gate_name))
        return ApprovalReview(
            target_id=target_id,
            node_id=node_id,
            gate_name=str(gate_name),
            gate_revision=gate_revision,
            workflow_name=self.workflow_name,
            resource_id=resource_id,
            resource_kind=resource_kind,
            resource_name=resource_name,
            stage=stage,
            effect=effect,
            reason=_approval_reason(node, workflow),
            snapshot_revision=snapshot_revision,
        )

    def approve(
        self,
        target_id: str,
        expected_gate_revision: str,
    ) -> ApprovalReview:
        review = self.validate(target_id, expected_gate_revision)
        if not self._approver(review.gate_name):
            raise RuntimeError(
                f"ApprovalGate {review.gate_name} could not be approved."
            )
        return review

    def validate(
        self,
        target_id: str,
        expected_gate_revision: str,
    ) -> ApprovalReview:
        review = self.review(target_id)
        if review.gate_revision != expected_gate_revision:
            raise ApprovalStale(
                "The ApprovalGate changed after it was reviewed."
            )
        return review

    def _load_gate(self, name: str) -> Mapping[str, Any]:
        custom_api = self._custom_api or client.CustomObjectsApi()
        return custom_api.get_namespaced_custom_object(
            group=CRD_GROUP,
            version=CRD_VERSION,
            namespace=self.namespace,
            plural="approvalgates",
            name=name,
        )

    def _approve_gate(self, name: str) -> bool:
        try:
            self._custom_api.patch_namespaced_custom_object_status(
                group=CRD_GROUP,
                version=CRD_VERSION,
                namespace=self.namespace,
                plural="approvalgates",
                name=name,
                body={"status": {"phase": "Approved"}},
            )
            return True
        except Exception:
            return False


def _workflow_nodes(
    workflow: Mapping[str, Any],
) -> Dict[str, Mapping[str, Any]]:
    nodes = (workflow.get("status") or {}).get("nodes") or {}
    return {
        str(node_id): node
        for node_id, node in nodes.items()
        if isinstance(node, Mapping)
    }


def _approval_node_id(target_id: str) -> str:
    prefix, separator, node_id = str(target_id).partition(":")
    if prefix != "approval" or not separator or not node_id:
        raise ApprovalUnavailable("The approval target identifier is invalid.")
    return node_id


def _approval_stage(gate_name: str) -> tuple[str, str]:
    prefix = gate_name.split(".", 1)[0].lower()
    if gate_name.lower().endswith(".vapretry"):
        prefix = "vapretry"
    return APPROVAL_STAGES.get(
        prefix,
        (
            "Workflow approval",
            "Approving advances the workflow past this exact gate.",
        ),
    )


def _approval_reason(
    node: Mapping[str, Any],
    workflow: Mapping[str, Any],
) -> Optional[str]:
    direct = _optional(node.get("denial_reason"))
    if direct:
        return direct
    boundary = node.get("boundaryID")
    if not boundary:
        return None
    for sibling in _workflow_nodes(workflow).values():
        if (
            sibling.get("boundaryID") == boundary
            and sibling.get("phase") == "Failed"
            and sibling.get("message")
        ):
            message = str(sibling["message"])
            return extract_denial_reason(message) or message
    return None


def _optional(value: Any) -> Optional[str]:
    return str(value) if value not in (None, "") else None
