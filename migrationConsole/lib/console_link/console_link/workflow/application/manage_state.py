"""Build normalized workflow manage state without depending on a presentation layer."""

import hashlib
import json
import logging
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Callable, Dict, Iterable, List, Mapping, Optional, Sequence, Tuple

from kubernetes.client.rest import ApiException

from .. import resource_tree as resource_tree_module
from ..commands.crd_utils import RESETTABLE_PLURALS
from ..manage_tree_schema import group_plurals_for
from ..manage_tree_status import same_value_state
from ..resource_tree import (
    PENDING_CONFIG_PHASE,
    SPEC_DISPLAY_FIELDS,
    ResourceGroup,
    ResourceNode,
    ResourceSection,
    active_approval_node,
    apply_config_overlays,
    approval_target_ref,
    collect_notable_steps,
    extract_workflow_steps_by_resource,
    find_last_succeeded,
    mark_not_configured_groups,
    maybe_rewrite_wait_step,
    step_timestamp,
)
from ..tree_utils import (
    build_nested_workflow_tree,
    filter_tree_nodes,
    get_node_input_parameter,
    get_node_phase,
    is_approval_node,
)
from .models import (
    ManageCapability,
    ManageComparison,
    ManageDetail,
    ManageDiagnostic,
    ManageNode,
    ManageProblem,
    ManageRelationship,
    ManageSnapshot,
    ManageValueState,
    ManageWorkflow,
)


logger = logging.getLogger(__name__)

ACTIVE_WORKFLOW_PHASES = {"Pending", "Running"}
TERMINAL_WORKFLOW_PHASES = {"Succeeded", "Failed", "Error"}
OUTPUT_STEP_MAPPINGS = {
    "patchMetadataEvaluateOutput": ("snapshotmigrations", "metadataEvaluate"),
    "patchMetadataMigrateOutput": ("snapshotmigrations", "metadataMigrate"),
}

_STATUS_RANK = {
    "ok": 0,
    "unknown": 1,
    "changed": 2,
    "pending": 3,
    "running": 4,
    "warning": 5,
    "gated": 6,
    "required": 7,
    "error": 8,
    "blocked": 9,
}


def _problem_message(error: Exception) -> str:
    if isinstance(error, ApiException):
        if error.status == 401:
            return (
                "Kubernetes authentication failed for the cluster selected "
                "when this server started."
            )
        if error.status == 403:
            return (
                "Kubernetes denied access to the requested resources for the "
                "cluster selected when this server started."
            )
    return str(error)


@dataclass
class _NodeDraft:
    id: str
    kind: str
    label: str
    status: str
    parent_id: Optional[str] = None
    description: Optional[str] = None
    phase: Optional[str] = None
    value_summary: Optional[str] = None
    child_ids: List[str] = field(default_factory=list)
    diagnostics: Tuple[ManageDiagnostic, ...] = ()
    capabilities: Tuple[ManageCapability, ...] = ()
    details: Tuple[ManageDetail, ...] = ()
    relationships: List[ManageRelationship] = field(default_factory=list)
    comparisons: Tuple[ManageComparison, ...] = ()
    resource_plural: Optional[str] = None
    resource_name: Optional[str] = None
    config_presence: Mapping[str, bool] = field(default_factory=dict)


def workflow_has_active_rollout(workflow_data: Mapping[str, Any]) -> bool:
    """Return whether submitted configuration still represents an active rollout."""
    status = (workflow_data or {}).get("status") or {}
    phase = status.get("phase")
    if phase in TERMINAL_WORKFLOW_PHASES:
        return False
    if phase in ACTIVE_WORKFLOW_PHASES:
        return True
    return any(
        (node or {}).get("phase") in ACTIVE_WORKFLOW_PHASES
        for node in (status.get("nodes") or {}).values()
    )


def iter_resource_nodes(resources: Iterable[ResourceNode]):
    for resource in resources:
        yield resource
        yield from iter_resource_nodes(resource.children)


def iter_running_approval_nodes(steps: Iterable[Mapping[str, Any]]):
    for step in steps:
        if is_approval_node(step) and get_node_phase(step) == "Running":
            yield step
        yield from iter_running_approval_nodes(step.get("children", []))


def assign_workflow_progress(
    sections: Sequence[ResourceSection],
    steps: Mapping[str, List[Dict[str, Any]]],
) -> None:
    """Attach workflow steps and cross-resource approvals to resource nodes."""
    resources = [
        resource
        for section in sections
        for group in section.groups
        for resource in iter_resource_nodes(group.resources)
    ]
    by_ref = {(resource.plural, resource.name): resource for resource in resources}
    for resource in resources:
        if resource.name in steps:
            resource.workflow_progress = steps[resource.name]
    for resource in resources:
        for approval in iter_running_approval_nodes(resource.workflow_progress or []):
            target = approval_target_ref(approval)
            if not target or target == (resource.plural, resource.name):
                continue
            target_resource = by_ref.get(target)
            if not target_resource:
                continue
            existing = {step.get("id") for step in target_resource.workflow_progress or []}
            if approval.get("id") not in existing:
                target_resource.workflow_progress = [
                    *(target_resource.workflow_progress or []),
                    approval,
                ]


class ManageStateService:
    """Combines Argo, Kubernetes, and config observations into a stable DTO."""

    def __init__(
        self,
        namespace: str,
        workflow_name: str,
        argo_service: Any = None,
        resource_loader: Optional[Callable[[str], List[ResourceSection]]] = None,
        config_service_provider: Optional[Callable[[], Any]] = None,
        clock: Optional[Callable[[], datetime]] = None,
    ):
        self.namespace = namespace
        self.workflow_name = workflow_name
        self._argo_service = argo_service
        self._resource_loader = resource_loader or (
            lambda target_namespace: resource_tree_module.build_resource_tree(
                target_namespace
            )
        )
        self._config_service_provider = config_service_provider
        self._clock = clock or (lambda: datetime.now(timezone.utc))
        self.last_config_snapshots: Optional[Dict[str, Any]] = None

    def observe(self) -> ManageSnapshot:
        problems: List[ManageProblem] = []
        workflow_data = self.fetch_workflow(problems)
        try:
            sections = self.build_resource_sections(workflow_data, problems)
        except Exception as error:
            logger.exception("Failed to build workflow manage resource state")
            problems.append(ManageProblem(
                source="kubernetes",
                message=_problem_message(error),
            ))
            sections = []
        return self.build_snapshot(sections, workflow_data, problems)

    def fetch_workflow(
        self,
        problems: Optional[List[ManageProblem]] = None,
    ) -> Dict[str, Any]:
        if self._argo_service is None:
            return {}
        try:
            result, data = self._argo_service.get_workflow(
                self.workflow_name,
                self.namespace,
            )
        except Exception as error:
            if problems is not None:
                problems.append(ManageProblem(source="argo", message=str(error)))
            return {}
        if result.get("success"):
            return data or {}
        if problems is not None:
            problems.append(ManageProblem(
                source="argo",
                message=str(result.get("error") or "Workflow is unavailable"),
            ))
        return {}

    def build_resource_sections(
        self,
        workflow_data: Mapping[str, Any],
        problems: Optional[List[ManageProblem]] = None,
    ) -> List[ResourceSection]:
        sections = self._resource_loader(self.namespace)
        steps: Mapping[str, List[Dict[str, Any]]] = {}
        if workflow_data and (workflow_data.get("status") or {}).get("nodes"):
            tree_nodes = build_nested_workflow_tree(dict(workflow_data))
            filtered_tree = filter_tree_nodes(tree_nodes)
            steps = extract_workflow_steps_by_resource(filtered_tree)
            mark_not_configured_groups(sections, filtered_tree)

        if self._config_service_provider is not None:
            try:
                service = self._config_service_provider()
                if (
                    service is not None
                    and hasattr(service, "load_resource_config_snapshots")
                ):
                    snapshots = service.load_resource_config_snapshots(
                        self.workflow_name
                    )
                    self.last_config_snapshots = snapshots
                    submitted_active = workflow_has_active_rollout(workflow_data)
                    apply_config_overlays(
                        sections,
                        submitted_resolved_config=(
                            snapshots.get("submitted")
                            if submitted_active else None
                        ),
                        pending_resolved_config=snapshots.get("pending"),
                        deployed_console_config=(
                            snapshots.get("submitted_console")
                            if not submitted_active else None
                        ),
                        submitted_console_config=(
                            snapshots.get("submitted_console")
                            if submitted_active else None
                        ),
                        pending_console_config=snapshots.get("pending_console"),
                    )
            except Exception as error:
                logger.exception("Failed to load resource config change overlays")
                if problems is not None:
                    problems.append(ManageProblem(
                        source="configuration",
                        message=_problem_message(error),
                    ))

        # Config overlays can add pending resources that own active workflow steps.
        assign_workflow_progress(sections, steps)
        return sections

    def build_snapshot(
        self,
        sections: Sequence[ResourceSection],
        workflow_data: Optional[Mapping[str, Any]] = None,
        problems: Sequence[ManageProblem] = (),
    ) -> ManageSnapshot:
        workflow_data = workflow_data or {}
        drafts: Dict[str, _NodeDraft] = {}
        root_ids: List[str] = []
        output_refs = _workflow_output_refs(workflow_data)

        for section in sections:
            visible_groups = [
                group for group in section.groups
                if group.resources or group.not_configured
            ]
            if not visible_groups:
                continue
            section_id = f"section:{section.name}"
            root_ids.append(section_id)
            section_draft = _NodeDraft(
                id=section_id,
                kind="section",
                label=section.name,
                status="ok",
            )
            drafts[section_id] = section_draft

            for group in visible_groups:
                group_id = f"group:{section.name}:{group.display_name}"
                section_draft.child_ids.append(group_id)
                group_draft = _NodeDraft(
                    id=group_id,
                    parent_id=section_id,
                    kind="group",
                    label=group.display_name,
                    status="unknown" if group.not_configured else "ok",
                    description="Not configured" if group.not_configured else None,
                )
                drafts[group_id] = group_draft
                plural_order = {
                    plural: index
                    for index, plural in enumerate(group_plurals_for(group.plural))
                }
                resources = sorted(
                    group.resources,
                    key=lambda resource: (
                        resource.tree_sort_index
                        if resource.tree_sort_index is not None
                        else 10_000,
                        plural_order.get(resource.plural, 99),
                        resource.name,
                    ),
                )
                for resource in resources:
                    resource_id = self._add_resource(
                        drafts,
                        resource,
                        group_id,
                        output_refs,
                    )
                    group_draft.child_ids.append(resource_id)

        _attach_reverse_relationships(drafts)
        nodes = _finalize_nodes(drafts)
        workflow = _workflow_summary(workflow_data)
        semantic = {
            "formatVersion": 1,
            "namespace": self.namespace,
            "workflowName": self.workflow_name,
            "workflow": workflow.to_dict() if workflow else None,
            "rootIds": root_ids,
            "rootRevisions": [nodes[node_id].revision for node_id in root_ids],
            "problems": [problem.to_dict() for problem in problems],
        }
        observed_at = _format_datetime(self._clock())
        return ManageSnapshot(
            format_version=1,
            revision=_revision(semantic),
            observed_at=observed_at,
            namespace=self.namespace,
            workflow_name=self.workflow_name,
            workflow=workflow,
            root_ids=tuple(root_ids),
            nodes=nodes,
            problems=tuple(problems),
        )

    def _add_resource(
        self,
        drafts: Dict[str, _NodeDraft],
        resource: ResourceNode,
        parent_id: str,
        output_refs: Mapping[str, Sequence[Tuple[str, str]]],
    ) -> str:
        resource_id = f"resource:{resource.plural}:{resource.name}"
        comparisons = _comparisons(resource)
        diagnostics = (
            *tuple(_diagnostic(item) for item in resource.diagnostics or []),
            *_resource_workflow_diagnostics(resource),
        )
        capabilities = _resource_capabilities(resource, output_refs.get(resource.name, ()))
        draft = _NodeDraft(
            id=resource_id,
            parent_id=parent_id,
            kind="resource",
            label=resource.name,
            description=f"{resource.plural}/{resource.name}",
            phase=resource.phase,
            status=_resource_status(resource, diagnostics),
            value_summary=_resource_value_summary(resource, comparisons),
            diagnostics=diagnostics,
            capabilities=capabilities,
            details=_resource_details(resource),
            relationships=_resource_relationships(resource),
            comparisons=comparisons,
            resource_plural=resource.plural,
            resource_name=resource.name,
            config_presence=dict(resource.config_presence or {}),
        )
        drafts[resource_id] = draft

        for child in resource.children:
            child_id = self._add_resource(drafts, child, resource_id, output_refs)
            draft.child_ids.append(child_id)
        for step in _notable_steps(resource.workflow_progress or []):
            step_id = self._add_workflow_step(
                drafts,
                step,
                resource_id,
                resource_id,
                resource,
            )
            draft.child_ids.append(step_id)
        return resource_id

    def _add_workflow_step(
        self,
        drafts: Dict[str, _NodeDraft],
        step: Mapping[str, Any],
        parent_id: str,
        resource_id: str,
        owner: ResourceNode,
    ) -> str:
        step_key = str(step.get("id") or _revision(step))
        step_id = f"workflow-step:{resource_id}:{step_key}"
        display_step = maybe_rewrite_wait_step(dict(step))
        label = str(display_step.get("display_name") or step_key)
        phase = get_node_phase(step)
        external_target = _external_approval_target(owner, step)
        approval_failure = _approval_failure_diagnostic(owner, step)
        if approval_failure:
            label = (
                f"{external_target[1]} apply failed"
                if external_target
                else "Apply failed"
            )
            phase = "Blocked"
        details = []
        if step.get("started_at"):
            details.append(ManageDetail("Started", step.get("started_at"), "timestamp"))
        if step.get("finished_at"):
            details.append(ManageDetail("Finished", step.get("finished_at"), "timestamp"))
        if approval_failure:
            details.extend([
                ManageDetail("Reason", approval_failure.message, "message"),
                ManageDetail("Remedy", approval_failure.remedy, "remedy"),
            ])
            if approval_failure.technical_detail:
                details.append(ManageDetail(
                    "Technical details",
                    approval_failure.technical_detail,
                    "technical",
                ))
        elif step.get("message"):
            details.append(ManageDetail("Message", str(step.get("message")), "message"))
        draft = _NodeDraft(
            id=step_id,
            parent_id=parent_id,
            kind="workflow-step",
            label=label,
            phase=phase,
            status=_phase_status(phase),
            capabilities=_step_capabilities(
                step,
                _approval_disabled_reason(owner, step),
                allow_approval=external_target is None,
            ),
            details=tuple(details),
        )
        drafts[step_id] = draft
        for child in sorted(
            collect_notable_steps(step.get("children", [])),
            key=step_timestamp,
        ):
            child_id = self._add_workflow_step(
                drafts,
                child,
                step_id,
                resource_id,
                owner,
            )
            draft.child_ids.append(child_id)
        return step_id


def _workflow_summary(workflow_data: Mapping[str, Any]) -> Optional[ManageWorkflow]:
    if not workflow_data:
        return None
    metadata = workflow_data.get("metadata") or {}
    status = workflow_data.get("status") or {}
    return ManageWorkflow(
        name=str(metadata.get("name") or "unknown"),
        phase=str(status.get("phase") or "Unknown"),
        started_at=status.get("startedAt"),
        finished_at=status.get("finishedAt"),
    )


def _workflow_output_refs(
    workflow_data: Mapping[str, Any],
) -> Dict[str, List[Tuple[str, str]]]:
    result: Dict[str, List[Tuple[str, str]]] = {}
    for node in ((workflow_data.get("status") or {}).get("nodes") or {}).values():
        step_name = str(node.get("displayName") or "").split("(", 1)[0].strip()
        output = OUTPUT_STEP_MAPPINGS.get(step_name)
        resource_name = get_node_input_parameter(node, "resourceName")
        if output and resource_name:
            result.setdefault(str(resource_name), []).append(output)
    return result


def _resource_capabilities(
    resource: ResourceNode,
    output_refs: Sequence[Tuple[str, str]],
) -> Tuple[ManageCapability, ...]:
    capabilities = [
        ManageCapability(
            kind="edit",
            target_id=(
                resource.config_edit_target_id
                or f"edit:{resource.plural}:{resource.name}"
            ),
            label=f"Edit {resource.name}",
        ),
    ]
    deployed = (resource.config_presence or {}).get(
        "deployed",
        resource.phase != PENDING_CONFIG_PHASE,
    )
    if deployed and resource.plural in RESETTABLE_PLURALS:
        capabilities.extend([
            ManageCapability(
                kind="logs",
                target_id=f"logs:{resource.plural}:{resource.name}",
                label=f"Logs for {resource.name}",
            ),
            ManageCapability(
                kind="reset",
                target_id=f"reset:{resource.plural}:{resource.name}",
                label=f"Reset {resource.name}",
            ),
        ])
    approval = active_approval_node(resource)
    if approval:
        approval_failure = _approval_failure_diagnostic(resource, approval)
        approval_node_id = approval.get("approval_node_id") or approval.get("id")
        capabilities.append(ManageCapability(
            kind="approve",
            target_id=f"approval:{approval_node_id}",
            label=(
                "Retry apply"
                if approval_failure
                else str(
                    approval.get("display_name")
                    or f"Approve {resource.name}"
                )
            ),
            disabled_reason=_approval_disabled_reason(resource, approval),
        ))
    for plural, output_name in output_refs:
        capabilities.append(ManageCapability(
            kind="output",
            target_id=f"output:{plural}:{resource.name}:{output_name}",
            label=f"View {output_name}",
        ))
    return tuple(sorted(capabilities, key=lambda item: (item.kind, item.target_id)))


def _step_capabilities(
    step: Mapping[str, Any],
    approval_disabled_reason: Optional[str] = None,
    allow_approval: bool = True,
) -> Tuple[ManageCapability, ...]:
    capabilities = []
    step_id = str(step.get("id") or "")
    if step.get("type") == "Pod":
        capabilities.append(ManageCapability("logs", f"logs:workflow-step:{step_id}", "View logs"))
    if (
        allow_approval
        and is_approval_node(step)
        and get_node_phase(step) == "Running"
    ):
        retry = bool(step.get("denial_reason"))
        approval_node_id = step.get("approval_node_id") or step_id
        capabilities.append(ManageCapability(
            "approve",
            f"approval:{approval_node_id}",
            "Retry apply" if retry else str(step.get("display_name") or "Approve"),
            approval_disabled_reason,
        ))
    step_name = str(step.get("display_name") or "").split("(", 1)[0].strip()
    output = OUTPUT_STEP_MAPPINGS.get(step_name)
    resource_name = get_node_input_parameter(step, "resourceName")
    if output and resource_name:
        plural, output_name = output
        capabilities.append(ManageCapability(
            "output",
            f"output:{plural}:{resource_name}:{output_name}",
            f"View {output_name}",
        ))
    return tuple(sorted(capabilities, key=lambda item: (item.kind, item.target_id)))


def _diagnostic(value: Mapping[str, Any]) -> ManageDiagnostic:
    return ManageDiagnostic(
        severity=str(value.get("severity") or "error"),
        message=str(value.get("message") or "Invalid value"),
        path=tuple(str(part) for part in value.get("path") or []),
        source=str(value.get("source")) if value.get("source") else None,
        code=str(value.get("code")) if value.get("code") else None,
        title=str(value.get("title")) if value.get("title") else None,
        remedy=str(value.get("remedy")) if value.get("remedy") else None,
        technical_detail=(
            str(value.get("technicalDetail"))
            if value.get("technicalDetail")
            else None
        ),
    )


def _with_terminal_period(value: str) -> str:
    result = value.strip()
    if result and result[-1] not in ".!?":
        result += "."
    return result


def _approval_failure_diagnostic(
    resource: ResourceNode,
    approval: Mapping[str, Any],
) -> Optional[ManageDiagnostic]:
    reason = str(approval.get("denial_reason") or "").strip()
    if not reason:
        return None
    immutable_update = "delete and recreate" in reason.lower()
    external_target = _external_approval_target(resource, approval)
    target_name = external_target[1] if external_target else resource.name
    deployed = (resource.config_presence or {}).get(
        "deployed",
        resource.phase != PENDING_CONFIG_PHASE,
    )
    reset_required = immutable_update and deployed
    return ManageDiagnostic(
        severity="error",
        message=_with_terminal_period(reason),
        source="workflow-apply",
        code=(
            "immutable-resource-update"
            if immutable_update
            else "apply-approval-required"
        ),
        title=(
            f"Blocked by {target_name} apply failure"
            if external_target
            else "Replacement workflow required"
            if immutable_update and not deployed
            else "Apply failed; reset required"
            if reset_required
            else "Apply failed; approval required"
        ),
        remedy=(
            (
                f"Open {target_name}, reset it to delete and recreate it, "
                "then submit a replacement workflow."
                if external_target
                else f"Reset {target_name} to delete and recreate it, then "
                "submit a replacement workflow."
            )
            if reset_required
            else (
                f"{target_name} is already absent. Submit a replacement "
                "workflow to recreate it from the saved configuration."
            )
            if immutable_update
            else "Review the denied change, then approve the change when it "
            "is safe to continue."
        ),
        technical_detail=(
            str(approval.get("message")).strip()
            if approval.get("message")
            else None
        ),
    )


def _external_approval_target(
    resource: ResourceNode,
    approval: Mapping[str, Any],
) -> Optional[Tuple[str, str]]:
    target = approval_target_ref(dict(approval))
    if target and target != (resource.plural, resource.name):
        return target
    return None


def _approval_disabled_reason(
    resource: ResourceNode,
    approval: Mapping[str, Any],
) -> Optional[str]:
    diagnostic = _approval_failure_diagnostic(resource, approval)
    if (
        not diagnostic
        or diagnostic.code != "immutable-resource-update"
    ):
        return None
    deployed = (resource.config_presence or {}).get(
        "deployed",
        resource.phase != PENDING_CONFIG_PHASE,
    )
    if not deployed:
        return (
            f"Submit a replacement workflow to recreate {resource.name}; "
            "the current workflow cannot recreate it."
        )
    return (
        f"Reset {resource.name} and submit a replacement workflow; "
        "the current workflow cannot recreate it."
    )


def _iter_workflow_steps(
    steps: Iterable[Mapping[str, Any]],
):
    for step in steps:
        yield step
        yield from _iter_workflow_steps(step.get("children", []))


def _resource_workflow_diagnostics(
    resource: ResourceNode,
) -> Tuple[ManageDiagnostic, ...]:
    approval = active_approval_node(resource)
    if approval:
        diagnostic = _approval_failure_diagnostic(resource, approval)
        if diagnostic:
            return (diagnostic,)
    for step in _iter_workflow_steps(resource.workflow_progress or []):
        diagnostic = _approval_failure_diagnostic(resource, step)
        if diagnostic:
            return (diagnostic,)
        if get_node_phase(step) not in {"Failed", "Error"}:
            continue
        message = str(step.get("message") or "").strip()
        if not message:
            continue
        label = str(step.get("display_name") or "Workflow step")
        return (ManageDiagnostic(
            severity="error",
            message=message,
            source="workflow-step",
            code="workflow-step-failed",
            title=f"{label} failed",
            remedy=(
                "Review the failure details and logs before retrying or "
                "resetting."
            ),
        ),)
    return ()


def _comparisons(resource: ResourceNode) -> Tuple[ManageComparison, ...]:
    result = []
    for field_value in (resource.config_diff or {}).get("fields") or []:
        values = field_value.get("values") or {}
        deployed = _value_state(values.get("deployed") or {})
        submitted = _value_state(values.get("submitted") or {})
        pending = _value_state(values.get("pending") or {})
        result.append(ManageComparison(
            path=str(field_value.get("path") or ""),
            label=str(field_value.get("label") or field_value.get("path") or ""),
            deployed=deployed,
            submitted=submitted,
            pending=pending,
            submitted_changed=not same_value_state(
                values.get("deployed") or {},
                values.get("submitted") or {},
            ),
            pending_changed=not same_value_state(
                values.get("submitted") or {},
                values.get("pending") or {},
            ),
        ))
    return tuple(result)


def _value_state(value: Mapping[str, Any]) -> ManageValueState:
    return ManageValueState(
        present=bool(value.get("present")),
        value=value.get("value"),
        provenance=value.get("provenance"),
    )


def _resource_details(resource: ResourceNode) -> Tuple[ManageDetail, ...]:
    details = [
        ManageDetail("Phase", resource.phase, "phase"),
    ]
    fields = resource.display_fields or SPEC_DISPLAY_FIELDS.get(resource.plural, [])
    for path in fields:
        found, value = _nested_value(resource.spec, path)
        if found and value not in (None, "", []):
            details.append(ManageDetail(path.split(".")[-1], value, "spec"))
    for dependency in resource.dependency_states:
        details.append(ManageDetail(
            "Depends on",
            {
                "name": dependency.get("name"),
                "phase": dependency.get("phase"),
                "plural": dependency.get("plural"),
            },
            "dependency",
        ))
    return tuple(details)


def _resource_relationships(resource: ResourceNode) -> List[ManageRelationship]:
    states_by_name = {
        str(dependency.get("name")): dependency
        for dependency in resource.dependency_states
        if dependency.get("name")
    }
    relationships = []
    for dependency_name in resource.depends_on:
        name = str(dependency_name)
        dependency = states_by_name.get(name)
        if dependency is None:
            relationships.append(ManageRelationship(
                kind="runtime-dependency",
                direction="requires",
                target_name=name,
                target_status="unknown",
            ))
            continue
        plural = str(dependency.get("plural") or "")
        phase = str(dependency.get("phase") or "")
        relationships.append(ManageRelationship(
            kind="runtime-dependency",
            direction="requires",
            target_id=f"resource:{plural}:{name}" if plural else None,
            target_name=name,
            target_plural=plural or None,
            target_phase=phase or None,
            target_status=_phase_status(phase),
        ))
    return relationships


def _attach_reverse_relationships(drafts: Mapping[str, _NodeDraft]) -> None:
    for source in tuple(drafts.values()):
        for relationship in tuple(source.relationships):
            if (
                relationship.direction != "requires"
                or not relationship.target_id
            ):
                continue
            target = drafts.get(relationship.target_id)
            if target is None:
                continue
            target.relationships.append(ManageRelationship(
                kind=relationship.kind,
                direction="required-by",
                target_id=source.id,
                target_name=source.label,
                target_plural=source.resource_plural,
                target_phase=source.phase,
                target_status=_phase_status(source.phase),
            ))


def _nested_value(source: Mapping[str, Any], path: str) -> Tuple[bool, Any]:
    current: Any = source
    for part in path.split("."):
        if not isinstance(current, Mapping) or part not in current:
            return False, None
        current = current[part]
    return True, current


def _resource_value_summary(
    resource: ResourceNode,
    comparisons: Sequence[ManageComparison],
) -> str:
    presence = resource.config_presence or {}
    deployed = presence.get("deployed", True)
    submitted = presence.get("submitted", deployed)
    pending_presence = presence.get("pending", submitted)
    if "pending" in presence and pending_presence != submitted:
        if deployed and submitted and not pending_presence:
            return "Will be orphaned by new configuration"
        return (
            "Addition pending submission"
            if pending_presence else "Removal pending submission"
        )
    if "submitted" in presence and submitted != deployed:
        return "Addition in progress" if submitted else "Orphaned; cleanup required"
    pending = sum(1 for item in comparisons if item.pending_changed)
    submitted = sum(1 for item in comparisons if item.submitted_changed)
    if pending:
        return f"{pending} change{'s' if pending != 1 else ''} to submit"
    if submitted:
        return f"{submitted} pending change{'s' if submitted != 1 else ''}"
    return resource.phase


def _resource_status(
    resource: ResourceNode,
    diagnostics: Sequence[ManageDiagnostic],
) -> str:
    approval = active_approval_node(resource)
    if approval:
        return "blocked"
    if diagnostics:
        return max(
            (item.severity for item in diagnostics),
            key=lambda status: _STATUS_RANK.get(status, _STATUS_RANK["warning"]),
        )
    phase_status = _phase_status(resource.phase)
    if phase_status not in {"ok", "unknown"}:
        return phase_status
    diff = resource.config_diff or {}
    if diff.get("has_pending_submit_changes") or diff.get("has_submitted_changes"):
        return "changed"
    return phase_status


def _phase_status(phase: Optional[str]) -> str:
    normalized = str(phase or "").lower()
    if normalized in {"failed", "error"}:
        return "error"
    if normalized == "blocked":
        return "blocked"
    if normalized in {"running"}:
        return "running"
    if normalized in {
        "created",
        "deleting",
        "pending",
        "initialized",
        "pending config",
    }:
        return "pending"
    if normalized in {
        "approved",
        "checked",
        "ready",
        "completed",
        "succeeded",
        "skipped",
    }:
        return "ok"
    return "unknown"


def _notable_steps(steps: Sequence[Dict[str, Any]]) -> List[Dict[str, Any]]:
    notable = collect_notable_steps(list(steps))
    last_succeeded = find_last_succeeded(list(steps))
    if last_succeeded and last_succeeded not in notable:
        notable.append(last_succeeded)
    return sorted(notable, key=step_timestamp)


def _finalize_nodes(drafts: Mapping[str, _NodeDraft]) -> Dict[str, ManageNode]:
    revisions: Dict[str, str] = {}
    statuses: Dict[str, str] = {}

    def node_status(node_id: str) -> str:
        if node_id in statuses:
            return statuses[node_id]
        draft = drafts[node_id]
        statuses[node_id] = _highest_status([
            draft.status,
            *(node_status(child_id) for child_id in draft.child_ids),
        ])
        return statuses[node_id]

    def node_revision(node_id: str) -> str:
        if node_id in revisions:
            return revisions[node_id]
        draft = drafts[node_id]
        semantic = _draft_dict(draft)
        semantic["status"] = node_status(node_id)
        semantic["childRevisions"] = [
            node_revision(child_id)
            for child_id in draft.child_ids
        ]
        revisions[node_id] = _revision(semantic)
        return revisions[node_id]

    for node_id in drafts:
        node_revision(node_id)

    nodes = {}
    for node_id, draft in drafts.items():
        nodes[node_id] = ManageNode(
            id=draft.id,
            revision=revisions[node_id],
            kind=draft.kind,
            label=draft.label,
            status=statuses[node_id],
            child_ids=tuple(draft.child_ids),
            parent_id=draft.parent_id,
            description=draft.description,
            phase=draft.phase,
            value_summary=draft.value_summary,
            diagnostics=draft.diagnostics,
            capabilities=draft.capabilities,
            details=draft.details,
            relationships=tuple(draft.relationships),
            comparisons=draft.comparisons,
            resource_plural=draft.resource_plural,
            resource_name=draft.resource_name,
            config_presence=draft.config_presence,
        )
    return nodes


def _highest_status(statuses: Iterable[str]) -> str:
    return max(statuses, key=lambda status: _STATUS_RANK.get(status, 0), default="ok")


def _draft_dict(draft: _NodeDraft) -> Dict[str, Any]:
    return {
        "id": draft.id,
        "kind": draft.kind,
        "label": draft.label,
        "status": draft.status,
        "parentId": draft.parent_id,
        "description": draft.description,
        "phase": draft.phase,
        "valueSummary": draft.value_summary,
        "childIds": list(draft.child_ids),
        "diagnostics": [item.to_dict() for item in draft.diagnostics],
        "capabilities": [item.to_dict() for item in draft.capabilities],
        "details": [item.to_dict() for item in draft.details],
        "relationships": [
            item.to_dict()
            for item in draft.relationships
        ],
        "comparisons": [item.to_dict() for item in draft.comparisons],
        "resourcePlural": draft.resource_plural,
        "resourceName": draft.resource_name,
        "configPresence": dict(draft.config_presence),
    }


def _revision(value: Any) -> str:
    payload = json.dumps(
        value,
        sort_keys=True,
        separators=(",", ":"),
        default=str,
    ).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()[:20]


def _format_datetime(value: datetime) -> str:
    if value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
