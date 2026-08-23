"""Project configuration-edit navigation from runtime state and schema hints."""

from dataclasses import dataclass, replace
from typing import Any, Dict, Iterable, Mapping, Optional, Tuple

from .config_drafts import ConfigDraft
from .models import (
    ManageCapability,
    ManageConfigState,
    ManageDetail,
    ManageDiagnostic,
    ManageNode,
    ManageSnapshot,
)


@dataclass(frozen=True)
class _Identity:
    kind: str
    prefix: str = ""
    suffix: str = ""
    first_index: int = 0


@dataclass(frozen=True)
class _Placement:
    collection_path: Tuple[str, ...]
    section_id: str
    section_label: str
    section_order: int
    group_id: str
    group_label: str
    group_order: int
    resource_plural: str
    resource_type: str
    identity: _Identity


@dataclass(frozen=True)
class _DefinitionPlacement:
    collection_path: Tuple[str, ...]
    owner_target_id: str
    group_label: str
    group_order: int
    definition_type: str


def project_config_navigation(
    snapshot: ManageSnapshot,
    draft: ConfigDraft,
) -> ManageSnapshot:
    """Return stable config navigation; clients may add transient UI overlays."""
    configuration = _without_workflow_steps(snapshot)
    provenance = _mapping(draft.edit_state.get("provenance"))
    if provenance.get("mode") == "raw" or draft.repair_yaml is not None:
        return configuration

    edit_nodes = _edit_nodes(draft.edit_state.get("nodes") or ())
    placements = _resource_placements(draft.edit_state.get("nodes") or ())
    definition_placements = _definition_placements(
        draft.edit_state.get("nodes") or ()
    )
    nodes = {
        node_id: _project_existing_node(
            node,
            draft,
            edit_nodes,
            placements,
        )
        for node_id, node in configuration.nodes.items()
    }
    root_ids = list(configuration.root_ids)

    _ensure_navigation(nodes, root_ids, placements, draft.draft_revision)
    _add_draft_resources(
        nodes,
        edit_nodes,
        placements,
        draft.draft_revision,
        draft.dirty,
    )
    _add_draft_definitions(
        nodes,
        edit_nodes,
        definition_placements,
        draft.draft_revision,
        draft.dirty,
    )
    return replace(
        configuration,
        revision=(
            f"{snapshot.revision}:{draft.draft_revision}:configuration"
        ),
        root_ids=tuple(root_ids),
        nodes=nodes,
    )


def _without_workflow_steps(snapshot: ManageSnapshot) -> ManageSnapshot:
    step_ids = {
        node_id
        for node_id, node in snapshot.nodes.items()
        if node.kind == "workflow-step"
    }
    if not step_ids:
        return snapshot
    nodes = {
        node_id: replace(
            node,
            child_ids=tuple(
                child_id
                for child_id in node.child_ids
                if child_id not in step_ids
            ),
        )
        for node_id, node in snapshot.nodes.items()
        if node_id not in step_ids
    }
    return replace(
        snapshot,
        root_ids=tuple(
            node_id for node_id in snapshot.root_ids
            if node_id not in step_ids
        ),
        nodes=nodes,
    )


def _project_existing_node(
    node: ManageNode,
    draft: ConfigDraft,
    edit_nodes: Mapping[str, Mapping[str, Any]],
    placements: Tuple[_Placement, ...],
) -> ManageNode:
    if node.kind != "resource":
        return node
    target_id = _edit_target(node)
    edit_node = edit_nodes.get(target_id) if target_id is not None else None
    config_state = (
        _config_state(edit_node, draft.dirty)
        if edit_node is not None
        else None
    )
    explicit_removal = (
        node.config_presence.get("pending") is False
        and (
            node.config_presence.get("deployed") is True
            or node.config_presence.get("submitted") is True
        )
    )
    removed_from_draft = (
        target_id is not None
        and _is_resource_target(target_id, placements)
        and target_id not in edit_nodes
    )
    if not explicit_removal and not removed_from_draft:
        return replace(node, config_state=config_state)
    label = (
        "Removal pending submission"
        if node.config_presence.get("pending") is False
        else (
            "Marked for removal"
            if draft.dirty
            else "Removal pending submission"
        )
    )
    return replace(
        node,
        revision=f"{node.revision}:{draft.draft_revision}:removed",
        status="removed",
        value_summary=label,
        config_state=config_state,
    )


def _edit_target(node: ManageNode) -> Optional[str]:
    return next(
        (
            capability.target_id
            for capability in node.capabilities
            if capability.kind == "edit"
        ),
        None,
    )


def _is_resource_target(
    target_id: str,
    placements: Tuple[_Placement, ...],
) -> bool:
    path = (
        target_id[len("edit:"):]
        if target_id.startswith("edit:")
        else target_id
    )
    return any(
        path == ".".join(placement.collection_path)
        or path.startswith(f"{'.'.join(placement.collection_path)}.")
        for placement in placements
    )


def _edit_nodes(
    roots: Iterable[Mapping[str, Any]],
) -> Dict[str, Mapping[str, Any]]:
    result: Dict[str, Mapping[str, Any]] = {}

    def visit(nodes: Iterable[Mapping[str, Any]]) -> None:
        for node in nodes:
            node_id = node.get("id")
            if isinstance(node_id, str):
                result[node_id] = node
            visit(_mapping_children(node))

    visit(roots)
    return result


def _resource_placements(
    roots: Iterable[Mapping[str, Any]],
) -> Tuple[_Placement, ...]:
    result: Dict[Tuple[str, ...], _Placement] = {}

    def visit(nodes: Iterable[Mapping[str, Any]]) -> None:
        for node in nodes:
            placement = _placement(node)
            if placement is not None:
                result[placement.collection_path] = placement
            visit(_mapping_children(node))

    visit(roots)
    return tuple(result.values())


def _definition_placements(
    roots: Iterable[Mapping[str, Any]],
) -> Tuple[_DefinitionPlacement, ...]:
    result: Dict[Tuple[str, ...], _DefinitionPlacement] = {}

    def visit(nodes: Iterable[Mapping[str, Any]]) -> None:
        for node in nodes:
            placement = _definition_placement(node)
            if placement is not None:
                result[placement.collection_path] = placement
            visit(_mapping_children(node))

    visit(roots)
    return tuple(result.values())


def _definition_placement(
    node: Mapping[str, Any],
) -> Optional[_DefinitionPlacement]:
    collection = _mapping(
        _mapping(node.get("inputHint")).get("definitionCollection")
    )
    navigation = _mapping(collection.get("navigation"))
    definition = _mapping(collection.get("definition"))
    path = node.get("path")
    owner_ancestor_levels = collection.get("ownerAncestorLevels")
    if (
        not isinstance(path, list)
        or not path
        or not all(isinstance(part, str) for part in path)
        or not isinstance(owner_ancestor_levels, int)
        or owner_ancestor_levels <= 0
        or owner_ancestor_levels >= len(path)
        or not isinstance(navigation.get("groupLabel"), str)
        or not isinstance(navigation.get("groupOrder"), int)
        or not isinstance(definition.get("typeLabel"), str)
    ):
        return None
    owner_path = path[:-owner_ancestor_levels]
    return _DefinitionPlacement(
        collection_path=tuple(path),
        owner_target_id=f"edit:{'.'.join(owner_path)}",
        group_label=navigation["groupLabel"],
        group_order=navigation["groupOrder"],
        definition_type=definition["typeLabel"],
    )


def _placement(node: Mapping[str, Any]) -> Optional[_Placement]:
    collection = _mapping(
        _mapping(node.get("inputHint")).get("resourceCollection")
    )
    navigation = _mapping(collection.get("navigation"))
    resource = _mapping(collection.get("resource"))
    identity = _mapping(resource.get("identity"))
    path = node.get("path")
    required_strings = (
        navigation.get("sectionId"),
        navigation.get("sectionLabel"),
        navigation.get("groupId"),
        navigation.get("groupLabel"),
        resource.get("plural"),
        resource.get("typeLabel"),
        identity.get("kind"),
    )
    if (
        not isinstance(path, list)
        or not all(isinstance(part, str) for part in path)
        or not all(isinstance(value, str) for value in required_strings)
        or not isinstance(navigation.get("sectionOrder"), int)
        or not isinstance(navigation.get("groupOrder"), int)
        or identity.get("kind") not in {"named", "indexed-config"}
    ):
        return None
    return _Placement(
        collection_path=tuple(path),
        section_id=navigation["sectionId"],
        section_label=navigation["sectionLabel"],
        section_order=navigation["sectionOrder"],
        group_id=navigation["groupId"],
        group_label=navigation["groupLabel"],
        group_order=navigation["groupOrder"],
        resource_plural=resource["plural"],
        resource_type=resource["typeLabel"],
        identity=_Identity(
            kind=identity["kind"],
            prefix=(
                identity["prefix"]
                if isinstance(identity.get("prefix"), str)
                else ""
            ),
            suffix=(
                identity["suffix"]
                if isinstance(identity.get("suffix"), str)
                else ""
            ),
            first_index=(
                identity["firstIndex"]
                if isinstance(identity.get("firstIndex"), int)
                else 0
            ),
        ),
    )


def _ensure_navigation(
    nodes: Dict[str, ManageNode],
    root_ids: list[str],
    placements: Tuple[_Placement, ...],
    revision: str,
) -> None:
    section_order = {
        placement.section_id: placement.section_order
        for placement in placements
    }
    group_order = {
        placement.group_id: placement.group_order
        for placement in placements
    }
    for placement in placements:
        section = nodes.get(placement.section_id)
        if section is None:
            section = ManageNode(
                id=placement.section_id,
                revision=f"{revision}:{placement.section_id}",
                kind="section",
                label=placement.section_label,
                status="ok",
            )
            nodes[section.id] = section
        if section.id not in root_ids:
            root_ids.append(section.id)

        group = nodes.get(placement.group_id)
        if group is None:
            group = ManageNode(
                id=placement.group_id,
                revision=f"{revision}:{placement.group_id}",
                parent_id=section.id,
                kind="group",
                label=placement.group_label,
                status="ok",
            )
            nodes[group.id] = group
        if group.id not in section.child_ids:
            nodes[section.id] = replace(
                section,
                revision=f"{section.revision}:{revision}",
                child_ids=tuple(_ordered_ids(
                    (*section.child_ids, group.id),
                    group_order,
                )),
            )

    root_ids[:] = _ordered_ids(root_ids, section_order)


def _ordered_ids(
    node_ids: Iterable[str],
    order: Mapping[str, int],
) -> list[str]:
    indexed = list(enumerate(node_ids))
    indexed.sort(
        key=lambda item: (
            order.get(item[1], 2**31 - 1),
            item[0],
        )
    )
    return [node_id for _, node_id in indexed]


def _add_draft_resources(
    nodes: Dict[str, ManageNode],
    edit_nodes: Mapping[str, Mapping[str, Any]],
    placements: Tuple[_Placement, ...],
    revision: str,
    dirty: bool,
) -> None:
    existing_targets = {
        target
        for node in nodes.values()
        if (target := _edit_target(node)) is not None
    }
    for placement in placements:
        collection_id = f"edit:{'.'.join(placement.collection_path)}"
        collection = edit_nodes.get(collection_id)
        if collection is None:
            continue
        for index, child in enumerate(_mapping_children(collection)):
            if child.get("valueKind") == "command":
                continue
            target_id = child.get("id")
            if not isinstance(target_id, str) or target_id in existing_targets:
                continue
            identity = _resource_identity(placement, child, index)
            if identity is None or identity[0] in nodes:
                continue
            node_id, resource_name = identity
            diagnostics = tuple(
                diagnostic
                for value in child.get("diagnostics") or ()
                if (diagnostic := _diagnostic(value)) is not None
            )
            status_value = child.get("status")
            status = (
                status_value
                if isinstance(status_value, str) and status_value != "ok"
                else "changed"
            )
            node = ManageNode(
                id=node_id,
                revision=f"{revision}:{target_id}:added",
                parent_id=placement.group_id,
                kind="resource",
                label=resource_name,
                description=(
                    f"{placement.resource_plural}/{resource_name}"
                ),
                status=status,
                phase="Pending Config",
                value_summary="Addition pending submission",
                diagnostics=diagnostics,
                capabilities=(
                    ManageCapability(
                        kind="edit",
                        target_id=target_id,
                        label=f"Edit {resource_name}",
                    ),
                ),
                details=(
                    ManageDetail(
                        label="Phase",
                        value="Pending Config",
                        kind="phase",
                    ),
                ),
                resource_plural=placement.resource_plural,
                resource_name=resource_name,
                resource_type=placement.resource_type,
                config_presence={
                    "deployed": False,
                    "pending": True,
                },
                config_state=_config_state(child, dirty),
            )
            nodes[node.id] = node
            existing_targets.add(target_id)
            group = nodes.get(placement.group_id)
            if group is not None and node.id not in group.child_ids:
                nodes[group.id] = replace(
                    group,
                    revision=f"{group.revision}:{revision}",
                    child_ids=(*group.child_ids, node.id),
                )


def _add_draft_definitions(
    nodes: Dict[str, ManageNode],
    edit_nodes: Mapping[str, Mapping[str, Any]],
    placements: Tuple[_DefinitionPlacement, ...],
    revision: str,
    dirty: bool,
) -> None:
    owners_by_target = {
        target: node
        for node in nodes.values()
        if (target := _edit_target(node)) is not None
    }
    placements_by_owner: Dict[str, list[_DefinitionPlacement]] = {}
    for placement in placements:
        placements_by_owner.setdefault(
            placement.owner_target_id,
            [],
        ).append(placement)

    for owner_target_id, owner_placements in placements_by_owner.items():
        owner = owners_by_target.get(owner_target_id)
        if owner is None:
            continue
        group_order: Dict[str, int] = {}
        child_ids = list(owner.child_ids)
        for placement in owner_placements:
            collection_id = f"edit:{'.'.join(placement.collection_path)}"
            collection = edit_nodes.get(collection_id)
            if collection is None:
                continue
            group_id = f"definition-group:{collection_id}"
            group_order[group_id] = placement.group_order
            definition_ids: list[str] = []
            for child in _mapping_children(collection):
                if child.get("valueKind") == "command":
                    continue
                target_id = child.get("id")
                path = child.get("path")
                if (
                    not isinstance(target_id, str)
                    or not isinstance(path, list)
                    or len(path) <= len(placement.collection_path)
                ):
                    continue
                label = str(path[len(placement.collection_path)])
                definition_id = f"definition:{target_id}"
                diagnostics = tuple(
                    diagnostic
                    for value in child.get("diagnostics") or ()
                    if (diagnostic := _diagnostic(value)) is not None
                )
                status_value = child.get("status")
                status = (
                    status_value
                    if isinstance(status_value, str)
                    else "ok"
                )
                nodes[definition_id] = ManageNode(
                    id=definition_id,
                    revision=f"{revision}:{target_id}",
                    parent_id=group_id,
                    kind="config-definition",
                    label=label,
                    description=placement.definition_type,
                    status=status,
                    diagnostics=diagnostics,
                    capabilities=(
                        ManageCapability(
                            kind="edit",
                            target_id=target_id,
                            label=f"Edit {label}",
                        ),
                    ),
                    resource_type=placement.definition_type,
                    config_state=_config_state(child, dirty),
                )
                definition_ids.append(definition_id)
            nodes[group_id] = ManageNode(
                id=group_id,
                revision=f"{revision}:{collection_id}",
                parent_id=owner.id,
                child_ids=tuple(definition_ids),
                kind="group",
                label=placement.group_label,
                status="ok",
            )
            child_ids.append(group_id)
        nodes[owner.id] = replace(
            owner,
            revision=f"{owner.revision}:{revision}:definitions",
            child_ids=tuple(_ordered_ids(child_ids, group_order)),
        )


def _resource_identity(
    placement: _Placement,
    child: Mapping[str, Any],
    index: int,
) -> Optional[Tuple[str, str]]:
    path = child.get("path")
    if (
        not isinstance(path, list)
        or len(path) <= len(placement.collection_path)
    ):
        return None
    authored_name = str(path[len(placement.collection_path)])
    if placement.identity.kind == "indexed-config":
        resource_name = (
            f"{placement.identity.prefix}"
            f"{index + placement.identity.first_index}"
        )
        return (
            f"config:{'.'.join(placement.collection_path)}:{index}",
            resource_name,
        )
    resource_name = (
        f"{placement.identity.prefix}{authored_name}"
        f"{placement.identity.suffix}"
    )
    return (
        f"resource:{placement.resource_plural}:{resource_name}",
        resource_name,
    )


def _diagnostic(value: Any) -> Optional[ManageDiagnostic]:
    if not isinstance(value, Mapping):
        return None
    severity = value.get("severity")
    message = value.get("message")
    if not isinstance(severity, str) or not isinstance(message, str):
        return None
    path = value.get("path")
    return ManageDiagnostic(
        severity=severity,
        message=message,
        path=tuple(
            str(part) for part in path
        ) if isinstance(path, list) else (),
        source=_optional_string(value.get("source")),
        code=_optional_string(value.get("code")),
        title=_optional_string(value.get("title")),
        remedy=_optional_string(value.get("remedy")),
        technical_detail=_optional_string(value.get("technicalDetail")),
    )


def _config_state(
    node: Mapping[str, Any],
    dirty: bool,
) -> ManageConfigState:
    errors, warnings = _validation_issue_counts(node)
    return ManageConfigState(
        validation_errors=errors,
        validation_warnings=warnings,
        draft_change_count=(
            _draft_change_count(node)
            if dirty
            else 0
        ),
    )


def _validation_issue_counts(
    node: Mapping[str, Any],
) -> Tuple[int, int]:
    counts = _mapping(node.get("statusCounts"))
    errors = sum(
        _nonnegative_int(counts.get(key))
        for key in ("errors", "required", "gated", "blocked")
    )
    warnings = _nonnegative_int(counts.get("warnings"))
    if errors == 0 and warnings == 0:
        status = node.get("status")
        if status in {"required", "error", "gated", "blocked"}:
            errors = 1
        elif status == "warning":
            warnings = 1

    child_counts = tuple(
        _validation_issue_counts(child)
        for child in _mapping_children(node)
        if child.get("valueKind") != "command"
    )
    errors = max(
        errors,
        sum(
            child_errors + child_warnings
            for child_errors, child_warnings in child_counts
            if child_errors > 0
        ),
    )
    warnings = max(
        warnings,
        sum(
            child_warnings
            for child_errors, child_warnings in child_counts
            if child_errors == 0
        ),
    )
    return errors, warnings


def _draft_change_count(node: Mapping[str, Any]) -> int:
    count = _nonnegative_int(node.get("draftChangeCount"))
    if count > 0:
        return count
    return 1 if node.get("draftChange") else 0


def _nonnegative_int(value: Any) -> int:
    return value if isinstance(value, int) and value > 0 else 0


def _mapping(value: Any) -> Mapping[str, Any]:
    return value if isinstance(value, Mapping) else {}


def _mapping_children(
    node: Mapping[str, Any],
) -> Tuple[Mapping[str, Any], ...]:
    return tuple(
        child
        for child in node.get("children") or ()
        if isinstance(child, Mapping)
    )


def _optional_string(value: Any) -> Optional[str]:
    return value if isinstance(value, str) else None
