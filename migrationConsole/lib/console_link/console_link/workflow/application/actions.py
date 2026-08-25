"""Exact-target approval review and execution."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Callable, Dict, Mapping, Optional, Sequence, Tuple

from kubernetes import client

from ..commands.approve import (
    LABEL_RESOURCE_KIND,
    LABEL_RESOURCE_NAME,
    LABEL_WORKFLOW,
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
LABEL_APPROVAL_CLASS = "migrations.opensearch.org/approval-class"
LABEL_PREAPPROVAL_ENABLED = (
    "migrations.opensearch.org/preapproval-enabled"
)
CHECKPOINT_APPROVAL_CLASS = "checkpoint"
RECOVERY_APPROVAL_CLASS = "recovery"
TERMINAL_WORKFLOW_PHASES = {
    "Error",
    "Failed",
    "Succeeded",
}


class ApprovalUnavailable(RuntimeError):
    pass


class ApprovalStale(RuntimeError):
    pass


@dataclass(frozen=True)
class ApprovalGateSummary:
    name: str
    gate_revision: str
    category: str
    state: str
    phase: str
    resource_id: Optional[str]
    resource_kind: Optional[str]
    resource_name: Optional[str]
    stage: str
    effect: str
    reason: Optional[str]
    enabled: bool
    approved: bool
    toggleable: bool
    disabled_reason: Optional[str]
    approval_target_id: Optional[str]
    output_target_id: Optional[str]


@dataclass(frozen=True)
class ApprovalGateInventory:
    workflow_name: str
    gates: Tuple[ApprovalGateSummary, ...]


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
        gate_inventory_loader: Optional[
            Callable[[], Sequence[Mapping[str, Any]]]
        ] = None,
        resolved_config_loader: Optional[
            Callable[[], Mapping[str, Any]]
        ] = None,
        phase_setter: Optional[Callable[[str, str], bool]] = None,
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
        self._gate_inventory_loader = (
            gate_inventory_loader or self._list_gates
        )
        self._resolved_config_loader = (
            resolved_config_loader or self._load_resolved_config
        )
        self._phase_setter = phase_setter or self._set_gate_phase

    def inventory(self) -> ApprovalGateInventory:
        workflow = self._workflow_loader()
        workflow_phase = str((workflow.get("status") or {}).get("phase") or "")
        gate_nodes = _approval_nodes_by_gate(workflow)
        try:
            resolved_config = self._resolved_config_loader()
        except Exception:
            resolved_config = {}
        summaries = [
            _gate_summary(
                gate,
                gate_nodes.get(_gate_name(gate), ()),
                resolved_config,
                workflow_phase,
                workflow,
            )
            for gate in self._gate_inventory_loader()
            if _gate_name(gate)
        ]
        summaries.sort(key=lambda gate: (
            _state_order(gate.state),
            gate.resource_kind or "",
            gate.resource_name or "",
            _stage_order(gate.name),
            gate.name,
        ))
        return ApprovalGateInventory(
            workflow_name=self.workflow_name,
            gates=tuple(summaries),
        )

    def set_preapproval(
        self,
        gate_name: str,
        expected_gate_revision: str,
        preapproved: bool,
    ) -> ApprovalGateSummary:
        inventory = self.inventory()
        gate = next(
            (candidate for candidate in inventory.gates
             if candidate.name == gate_name),
            None,
        )
        if gate is None:
            raise ApprovalUnavailable(
                f"ApprovalGate {gate_name} is no longer available."
            )
        if gate.gate_revision != expected_gate_revision:
            raise ApprovalStale(
                "The ApprovalGate changed after it was reviewed."
            )
        if not gate.toggleable:
            raise ApprovalUnavailable(
                gate.disabled_reason
                or "This approval checkpoint cannot be changed."
            )
        phase = "Approved" if preapproved else "Created"
        if not self._phase_setter(gate.name, phase):
            raise RuntimeError(
                f"ApprovalGate {gate.name} could not be updated."
            )
        return gate

    def review(
        self,
        target_id: str,
        snapshot_revision: Optional[str] = None,
    ) -> ApprovalReview:
        node_id = _approval_node_id(target_id)
        workflow = self._workflow_loader()
        node = _workflow_nodes(workflow).get(node_id)
        if not _is_waiting_approval(node):
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

    def _list_gates(self) -> Sequence[Mapping[str, Any]]:
        response = self._custom_api.list_namespaced_custom_object(
            group=CRD_GROUP,
            version=CRD_VERSION,
            namespace=self.namespace,
            plural="approvalgates",
            label_selector=f"{LABEL_WORKFLOW}={self.workflow_name}",
        )
        return tuple(response.get("items") or ())

    def _load_resolved_config(self) -> Mapping[str, Any]:
        response = self._custom_api.list_namespaced_custom_object(
            group=CRD_GROUP,
            version=CRD_VERSION,
            namespace=self.namespace,
            plural="migrationruns",
            label_selector=(
                "migrations.opensearch.org/workflow-name="
                f"{self.workflow_name}"
            ),
        )
        items = response.get("items") or ()
        current = max(
            items,
            key=lambda item: int(
                ((item.get("metadata") or {}).get("labels") or {}).get(
                    "migrations.opensearch.org/run-number",
                    0,
                )
            ),
        )
        resolved = (current.get("spec") or {}).get("resolvedConfig") or {}
        return resolved.get("workflowConfig") or {}

    def _set_gate_phase(self, name: str, phase: str) -> bool:
        try:
            self._custom_api.patch_namespaced_custom_object_status(
                group=CRD_GROUP,
                version=CRD_VERSION,
                namespace=self.namespace,
                plural="approvalgates",
                name=name,
                body={"status": {"phase": phase}},
            )
            return True
        except Exception:
            return False


def _gate_name(gate: Mapping[str, Any]) -> str:
    return str((gate.get("metadata") or {}).get("name") or "")


def _approval_nodes_by_gate(
    workflow: Mapping[str, Any],
) -> Dict[str, Tuple[Mapping[str, Any], ...]]:
    grouped: Dict[str, list[Mapping[str, Any]]] = {}
    for node in _workflow_nodes(workflow).values():
        if not is_approval_node(dict(node)):
            continue
        gate_name = (
            get_node_input_parameter(dict(node), "resourceName")
            or get_node_input_parameter(dict(node), "name")
        )
        if gate_name:
            grouped.setdefault(str(gate_name), []).append(node)
    return {
        gate_name: tuple(nodes)
        for gate_name, nodes in grouped.items()
    }


def _gate_summary(
    gate: Mapping[str, Any],
    nodes: Sequence[Mapping[str, Any]],
    resolved_config: Mapping[str, Any],
    workflow_phase: str,
    workflow: Mapping[str, Any],
) -> ApprovalGateSummary:
    metadata = gate.get("metadata") or {}
    labels = metadata.get("labels") or {}
    name = _gate_name(gate)
    phase = str((gate.get("status") or {}).get("phase") or "Unknown")
    resource_kind = _optional(labels.get(LABEL_RESOURCE_KIND))
    resource_name = _optional(labels.get(LABEL_RESOURCE_NAME))
    plural = RESOURCE_KIND_TO_PLURAL.get(resource_kind or "")
    resource_id = (
        f"resource:{plural}:{resource_name}"
        if plural and resource_name else None
    )
    category = _approval_category(name, labels)
    enabled = (
        category == CHECKPOINT_APPROVAL_CLASS
        and _checkpoint_enabled(name, labels, resolved_config, resource_name)
    )
    succeeded = any(
        str(node.get("phase") or "") == "Succeeded"
        for node in nodes
    )
    running = next(
        (
            node for node in nodes
            if str(node.get("phase") or "") == "Running"
        ),
        None,
    )
    approved = phase.lower() == "approved"
    if succeeded:
        state = "passed"
    elif running is not None:
        state = "accepted" if approved else "blocking"
    elif workflow_phase in TERMINAL_WORKFLOW_PHASES:
        state = "not-reached"
    elif category == RECOVERY_APPROVAL_CLASS:
        state = "recovery-standby"
    elif not enabled:
        state = "not-required"
    elif phase.lower() == "error":
        state = "error"
    elif approved:
        state = "preapproved"
    else:
        state = "upcoming"
    toggleable = enabled and state in {"upcoming", "preapproved"}
    stage, effect = _approval_stage(name)
    return ApprovalGateSummary(
        name=name,
        gate_revision=str(metadata.get("resourceVersion") or ""),
        category=category,
        state=state,
        phase=phase,
        resource_id=resource_id,
        resource_kind=resource_kind,
        resource_name=resource_name,
        stage=stage,
        effect=effect,
        reason=(
            _approval_reason(running, workflow)
            if running is not None else None
        ),
        enabled=enabled,
        approved=approved,
        toggleable=toggleable,
        disabled_reason=_preapproval_disabled_reason(
            category,
            enabled,
            state,
        ),
        approval_target_id=(
            f"approval:{running.get('id')}"
            if running is not None and running.get("id") else None
        ),
        output_target_id=_gate_output_target(
            name,
            plural,
            resource_name,
        ),
    )


def _approval_category(
    name: str,
    labels: Mapping[str, Any],
) -> str:
    explicit = str(labels.get(LABEL_APPROVAL_CLASS) or "")
    if explicit in {CHECKPOINT_APPROVAL_CLASS, RECOVERY_APPROVAL_CLASS}:
        return explicit
    return (
        RECOVERY_APPROVAL_CLASS
        if name.endswith(".vapretry")
        else CHECKPOINT_APPROVAL_CLASS
    )


def _checkpoint_enabled(
    name: str,
    labels: Mapping[str, Any],
    config: Mapping[str, Any],
    resource_name: Optional[str],
) -> bool:
    explicit = labels.get(LABEL_PREAPPROVAL_ENABLED)
    if explicit is not None:
        return str(explicit).lower() == "true"
    prefix = name.split(".", 1)[0].lower()
    if prefix == "begin":
        return bool(config.get("requireBeginApproval"))
    if prefix == "captureproxysetup":
        proxy = _configured_resource(
            config.get("proxies"),
            "name",
            resource_name,
        )
        return bool(proxy) and not bool(proxy.get("skipApproval"))
    migration = _configured_resource(
        config.get("snapshotMigrations"),
        "resourceName",
        resource_name,
    )
    if not migration:
        return False
    if prefix == "evaluatemetadata":
        metadata = migration.get("metadataMigrationConfig")
        return bool(metadata) and not bool(
            metadata.get("skipEvaluateApproval")
        )
    if prefix == "migratemetadata":
        metadata = migration.get("metadataMigrationConfig")
        return bool(metadata) and not bool(
            metadata.get("skipMigrateApproval")
        )
    if prefix == "documentbackfill":
        backfill = migration.get("documentBackfillConfig")
        return bool(backfill) and not bool(backfill.get("skipApproval"))
    return False


def _configured_resource(
    values: Any,
    key: str,
    expected: Optional[str],
) -> Optional[Mapping[str, Any]]:
    if not expected or not isinstance(values, Sequence):
        return None
    return next(
        (
            value for value in values
            if isinstance(value, Mapping)
            and str(value.get(key) or "") == expected
        ),
        None,
    )


def _preapproval_disabled_reason(
    category: str,
    enabled: bool,
    state: str,
) -> Optional[str]:
    if category == RECOVERY_APPROVAL_CLASS:
        return (
            "Recovery gates are activated by policy failures and cannot be "
            "preapproved."
        )
    if not enabled:
        return (
            "The submitted configuration does not use this approval "
            "checkpoint."
        )
    return {
        "blocking": (
            "This checkpoint is blocking now. Review and approve it directly."
        ),
        "accepted": (
            "Approval was accepted and workflow reconciliation is in progress."
        ),
        "passed": "The workflow already passed this approval checkpoint.",
        "not-reached": (
            "The workflow finished without reaching this approval checkpoint."
        ),
        "error": "This approval checkpoint is in an error state.",
    }.get(state)


def _gate_output_target(
    gate_name: str,
    plural: Optional[str],
    resource_name: Optional[str],
) -> Optional[str]:
    if plural != "snapshotmigrations" or not resource_name:
        return None
    output_name = {
        "evaluatemetadata": "metadataEvaluate",
        "migratemetadata": "metadataMigrate",
    }.get(gate_name.split(".", 1)[0].lower())
    return (
        f"output:{plural}:{resource_name}:{output_name}"
        if output_name else None
    )


def _state_order(state: str) -> int:
    return {
        "blocking": 0,
        "accepted": 1,
        "upcoming": 2,
        "preapproved": 2,
        "error": 3,
        "passed": 4,
        "not-required": 5,
        "not-reached": 5,
        "recovery-standby": 6,
    }.get(state, 7)


def _stage_order(gate_name: str) -> int:
    return {
        "begin": 0,
        "captureproxysetup": 10,
        "evaluatemetadata": 20,
        "migratemetadata": 30,
        "documentbackfill": 40,
    }.get(gate_name.split(".", 1)[0].lower(), 50)


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


def _is_waiting_approval(node: Optional[Mapping[str, Any]]) -> bool:
    return bool(
        node
        and is_approval_node(dict(node))
        and str(node.get("phase")) == "Running"
    )
