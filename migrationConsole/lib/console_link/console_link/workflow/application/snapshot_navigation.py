"""Group runtime SnapshotMigration resources for compact navigation."""

from dataclasses import replace
import re
from typing import Any, Dict, Iterable, Mapping, Tuple, cast

from .models import ManageNode, ManageSnapshot


_SNAPSHOT_SECTION_ID = "section:Snapshot Migration"
_SNAPSHOT_BASE_GROUP_ID = "group:Snapshot Migration:Backfill"
_SNAPSHOT_DYNAMIC_GROUP_PREFIX = "snapshot-navigation:"
_NAVIGATION_STATUS_RANK = {
    "ok": 0,
    "unknown": 1,
    "changed": 2,
    "removed": 3,
    "warning": 4,
    "required": 5,
    "blocked": 6,
    "error": 7,
}


def _natural_component(value: str) -> Tuple[Tuple[int, Any], ...]:
    return tuple(
        (1, int(part)) if part.isdigit() else (0, part.casefold())
        for part in re.split(r"(\d+)", value)
        if part
    )


def _snapshot_sort_key(node: ManageNode) -> Tuple[Any, ...]:
    return tuple(
        _natural_component(value)
        for value in node.navigation_key
    )


def _snapshot_group_id(prefix: Tuple[str, ...]) -> str:
    return (
        f"{_SNAPSHOT_DYNAMIC_GROUP_PREFIX}{len(prefix)}:"
        + ":".join(prefix)
    )


def _snapshot_migration_name(*parts: str) -> str:
    return re.sub(
        r"[^a-z0-9.]+",
        "-",
        "-".join(parts).lower(),
    ).strip("-.")


def _snapshot_group_label(
    prefix: Tuple[str, ...],
    parent_id: str,
) -> str:
    parent_depth = (
        int(parent_id.split(":", 2)[1])
        if parent_id.startswith(_SNAPSHOT_DYNAMIC_GROUP_PREFIX)
        else 0
    )
    return _snapshot_migration_name(*prefix[parent_depth:])


def _snapshot_group_status(
    nodes: Mapping[str, ManageNode],
    child_ids: Iterable[str],
) -> str:
    statuses = [
        nodes[child_id].status
        for child_id in child_ids
        if child_id in nodes
    ]
    if not statuses:
        return "ok"
    if all(status == "removed" for status in statuses):
        return "removed"
    return max(
        ("changed" if status == "removed" else status for status in statuses),
        key=lambda status: _NAVIGATION_STATUS_RANK.get(status, 0),
    )


def _snapshot_navigation_resources(
    nodes: Mapping[str, ManageNode],
) -> list[ManageNode]:
    return sorted(
        (
            node for node in nodes.values()
            if (
                node.kind == "resource"
                and node.resource_plural == "snapshotmigrations"
                and len(node.navigation_key) == 4
                and all(node.navigation_key)
            )
        ),
        key=_snapshot_sort_key,
    )


def _prepare_snapshot_navigation_resources(
    nodes: Dict[str, ManageNode],
    resources: Iterable[ManageNode],
    base_group: ManageNode,
) -> None:
    resources = tuple(resources)
    resource_ids = {node.id for node in resources}
    nodes[_SNAPSHOT_BASE_GROUP_ID] = replace(
        base_group,
        label="Snapshot migrations",
        child_ids=tuple(
            child_id for child_id in base_group.child_ids
            if child_id not in resource_ids
            and not child_id.startswith(_SNAPSHOT_DYNAMIC_GROUP_PREFIX)
        ),
    )
    for resource in resources:
        nodes[resource.id] = replace(
            resource,
            parent_id=_SNAPSHOT_BASE_GROUP_ID,
            label=_snapshot_migration_name(*resource.navigation_key),
        )


def _snapshot_navigation_representations(
    nodes: Dict[str, ManageNode],
    revision: str,
    items: Tuple[ManageNode, ...],
    level: int,
    parent_id: str,
) -> list[str]:
    if level >= 3:
        for item in items:
            nodes[item.id] = replace(item, parent_id=parent_id)
        return [item.id for item in items]

    buckets: Dict[str, list[ManageNode]] = {}
    for item in items:
        buckets.setdefault(item.navigation_key[level], []).append(item)
    result: list[str] = []
    for value in sorted(buckets, key=_natural_component):
        bucket = tuple(sorted(buckets[value], key=_snapshot_sort_key))
        prefix = bucket[0].navigation_key[:level + 1]
        prospective_parent = _snapshot_group_id(prefix)
        children = _snapshot_navigation_representations(
            nodes,
            revision,
            bucket,
            level + 1,
            prospective_parent,
        )
        if len(bucket) >= 3 and len(children) >= 2:
            nodes[prospective_parent] = ManageNode(
                id=prospective_parent,
                revision=f"{revision}:{prospective_parent}",
                parent_id=parent_id,
                kind="group",
                label=_snapshot_group_label(prefix, parent_id),
                status=_snapshot_group_status(nodes, children),
                child_ids=tuple(children),
            )
            result.append(prospective_parent)
        else:
            for child_id in children:
                nodes[child_id] = replace(
                    nodes[child_id],
                    parent_id=parent_id,
                )
            result.extend(children)
    return result


def _relabel_snapshot_navigation(
    nodes: Dict[str, ManageNode],
    resources: Iterable[ManageNode],
) -> None:
    for node_id, node in nodes.items():
        if node.kind != "group" or not node_id.startswith(
            _SNAPSHOT_DYNAMIC_GROUP_PREFIX
        ):
            continue
        nodes[node_id] = replace(
            node,
            label=_snapshot_group_label(
                tuple(node_id.split(":")[2:]),
                node.parent_id or "",
            ),
        )
    for resource in resources:
        grouped_resource = nodes[resource.id]
        parent_id = grouped_resource.parent_id
        parent_depth = (
            int(parent_id.split(":", 2)[1])
            if (
                parent_id
                and parent_id.startswith(_SNAPSHOT_DYNAMIC_GROUP_PREFIX)
            )
            else 0
        )
        nodes[resource.id] = replace(
            grouped_resource,
            label=_snapshot_migration_name(
                *resource.navigation_key[parent_depth:]
            ),
        )


def _flatten_snapshot_navigation_base(
    nodes: Dict[str, ManageNode],
) -> None:
    section = nodes.get(_SNAPSHOT_SECTION_ID)
    base_group = nodes.get(_SNAPSHOT_BASE_GROUP_ID)
    if (
        section is None
        or base_group is None
        or base_group.parent_id != section.id
    ):
        return
    flattened_ids = []
    for child_id in section.child_ids:
        if child_id == base_group.id:
            flattened_ids.extend(base_group.child_ids)
        else:
            flattened_ids.append(child_id)
    for child_id in base_group.child_ids:
        child = nodes.get(child_id)
        if child is not None:
            nodes[child_id] = replace(child, parent_id=section.id)
    nodes[section.id] = replace(
        section,
        child_ids=tuple(
            child_id for child_id in dict.fromkeys(flattened_ids)
            if child_id not in nodes
            or nodes[child_id].parent_id == section.id
        ),
    )
    del nodes[base_group.id]


def group_snapshot_migration_navigation(
    snapshot: ManageSnapshot,
) -> ManageSnapshot:
    """Sort SnapshotMigration leaves and add only useful semantic groups."""
    nodes = {
        node_id: node
        for node_id, node in snapshot.nodes.items()
        if not node_id.startswith(_SNAPSHOT_DYNAMIC_GROUP_PREFIX)
    }
    resources = _snapshot_navigation_resources(nodes)
    base_group = nodes.get(_SNAPSHOT_BASE_GROUP_ID)
    if base_group is None:
        return snapshot

    _prepare_snapshot_navigation_resources(nodes, resources, base_group)
    grouped_ids = _snapshot_navigation_representations(
        nodes,
        snapshot.revision,
        tuple(resources),
        0,
        _SNAPSHOT_BASE_GROUP_ID,
    )
    base = nodes[_SNAPSHOT_BASE_GROUP_ID]
    nodes[_SNAPSHOT_BASE_GROUP_ID] = replace(
        base,
        child_ids=(*base.child_ids, *grouped_ids),
        status=(
            _snapshot_group_status(nodes, grouped_ids)
            if grouped_ids else base.status
        ),
    )
    _relabel_snapshot_navigation(nodes, resources)
    _flatten_snapshot_navigation_base(nodes)
    return cast(ManageSnapshot, replace(snapshot, nodes=nodes))
