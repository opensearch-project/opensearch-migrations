"""Revisioned, process-local workflow configuration drafts."""

import hashlib
import json
from dataclasses import dataclass
from threading import RLock
from typing import Any, Dict, Iterable, Mapping, Optional, Sequence

import yaml

from ..external_resource_validation import (
    is_config_map_key,
    is_k8s_name,
    looks_like_log4j_properties,
    looks_like_pem_certificate_chain,
    looks_like_pem_private_key,
)


def _revision(raw_yaml: str) -> str:
    return f"sha256:{hashlib.sha256(raw_yaml.encode('utf-8')).hexdigest()}"


@dataclass(frozen=True)
class ConfigDraft:
    """Browser-safe draft state. Raw YAML intentionally remains server-side."""

    base_revision: str
    draft_revision: str
    dirty: bool
    edit_state: Dict[str, Any]


@dataclass(frozen=True)
class ConfigSubmission:
    draft: ConfigDraft
    workflow_name: str
    message: str


@dataclass(frozen=True)
class ConfigReviewChange:
    resource_id: Optional[str]
    resource_label: Optional[str]
    path: str
    label: str
    kind: str


@dataclass(frozen=True)
class ConfigReview:
    draft_revision: str
    base_revision: str
    dirty: bool
    valid: bool
    validation_messages: tuple[str, ...]
    changes: tuple[ConfigReviewChange, ...]


@dataclass(frozen=True)
class ConfigRemovalImpactEntry:
    path: tuple[str, ...]
    field_path: tuple[str, ...]
    reason: str


@dataclass(frozen=True)
class ConfigRemovalImpact:
    target_path: tuple[str, ...]
    target_label: str
    affected: tuple[ConfigRemovalImpactEntry, ...]


@dataclass(frozen=True)
class ExternalResourceInventory:
    node_id: str
    draft_revision: str
    display_name: str
    rows: list[Dict[str, Any]]


@dataclass(frozen=True)
class ExternalResourceDetails:
    node_id: str
    draft_revision: str
    display_name: str
    name: str
    kind: str
    resource_type: Optional[str]
    keys: list[str]
    field_values: Dict[str, str]
    hidden_fields: list[str]
    missing: bool
    message: Optional[str]


@dataclass(frozen=True)
class ExternalResourceMutation:
    draft: ConfigDraft
    name: str
    kind: str
    message: str


class ConfigDraftConflict(RuntimeError):
    def __init__(self, current: ConfigDraft):
        super().__init__("The configuration draft changed; reload the current draft.")
        self.current = current


class SavedConfigConflict(RuntimeError):
    def __init__(self, current: ConfigDraft, persisted_revision: str):
        super().__init__(
            "The saved configuration changed after this draft was opened; "
            "discard or reopen before saving."
        )
        self.current = current
        self.persisted_revision = persisted_revision


class ExternalResourceSelectionWarning(RuntimeError):
    def __init__(self, message: str):
        super().__init__(message or "The selected resource does not match all requirements.")
        self.message = message or "The selected resource does not match all requirements."


class ExternalResourceFormInvalid(ValueError):
    pass


class ConfigDraftService:
    """Own one ephemeral draft and delegates all config semantics to TypeScript."""

    def __init__(self, edit_service: Any):
        self._edit_service = edit_service
        self._lock = RLock()
        self._raw_yaml: Optional[str] = None
        self._base_revision: Optional[str] = None
        self._edit_state: Optional[Dict[str, Any]] = None

    def open(self) -> ConfigDraft:
        with self._lock:
            if self._raw_yaml is None:
                self._reload()
            return self._snapshot()

    def apply(
        self,
        expected_revision: str,
        operation: Dict[str, Any],
    ) -> ConfigDraft:
        with self._lock:
            self._require_revision(expected_revision)
            self._apply_operations((operation,))
            return self._snapshot()

    def save(self, expected_revision: str) -> ConfigDraft:
        with self._lock:
            self._require_revision(expected_revision)
            current_saved = self._edit_service.load_edit_session()
            persisted_revision = _revision(current_saved.raw_yaml)
            if persisted_revision != self._base_revision:
                raise SavedConfigConflict(self._snapshot(), persisted_revision)
            self._edit_service.save_raw_yaml(self._raw_yaml or "")
            self._base_revision = _revision(self._raw_yaml or "")
            return self._snapshot()

    def discard(self, expected_revision: str) -> ConfigDraft:
        with self._lock:
            self._require_revision(expected_revision)
            self._reload()
            return self._snapshot()

    def submit(
        self,
        expected_revision: str,
        workflow_name: str,
    ) -> ConfigSubmission:
        with self._lock:
            saved = self.prepare_submit(expected_revision)
            result = self.submit_saved(workflow_name)
            submitted_name = str(
                (result or {}).get("workflow_name") or workflow_name
            )
            return ConfigSubmission(
                draft=saved,
                workflow_name=submitted_name,
                message=f"Workflow submitted: {submitted_name}",
            )

    def review(
        self,
        expected_revision: str,
        snapshot: Optional[Any] = None,
    ) -> ConfigReview:
        with self._lock:
            self._require_revision(expected_revision)
            current = self._snapshot()
            validation = (self._edit_state or {}).get("validation") or {}
            return ConfigReview(
                draft_revision=current.draft_revision,
                base_revision=current.base_revision,
                dirty=current.dirty,
                valid=validation.get("valid") is not False,
                validation_messages=_validation_messages(validation),
                changes=_review_changes(self._edit_state or {}, snapshot),
            )

    def prepare_submit(self, expected_revision: str) -> ConfigDraft:
        """Validate and persist the exact draft before background submission."""
        with self._lock:
            self._require_revision(expected_revision)
            validation = (self._edit_state or {}).get("validation") or {}
            if validation.get("valid") is False:
                detail = (
                    "; ".join(_validation_messages(validation))
                    or "Configuration validation failed"
                )
                raise ValueError(
                    f"Configuration cannot be submitted: {detail}"
                )
            return self.save(expected_revision)

    def submit_saved(self, workflow_name: str) -> Dict[str, Any]:
        """Submit the already-validated saved config from an operation worker."""
        return self._edit_service.submit_saved_config(workflow_name)

    def removal_impact(
        self,
        expected_revision: str,
        path: Sequence[str],
    ) -> ConfigRemovalImpact:
        with self._lock:
            self._require_revision(expected_revision)
            normalized_path = tuple(str(part) for part in path)
            if len(normalized_path) < 2:
                raise ValueError("Only named configuration entries can be removed")
            config = yaml.safe_load(self._raw_yaml or "") or {}
            if not isinstance(config, dict):
                config = {}
            affected = _removal_impact(config, normalized_path)
            return ConfigRemovalImpact(
                target_path=normalized_path,
                target_label=normalized_path[-1],
                affected=affected,
            )

    def list_external_resources(
        self,
        expected_revision: str,
        node_id: str,
    ) -> ExternalResourceInventory:
        with self._lock:
            self._require_revision(expected_revision)
            node = self._external_node(node_id)
            external_ref = node["externalRef"]
            rows = self._edit_service.list_external_resources(
                external_ref,
                node.get("value"),
            )
            return ExternalResourceInventory(
                node_id=node_id,
                draft_revision=self._draft_revision(),
                display_name=str(
                    external_ref.get("displayName")
                    or node.get("label")
                    or node_id
                ),
                rows=[_safe_inventory_row(row) for row in rows],
            )

    def select_external_resource(
        self,
        *,
        expected_revision: str,
        node_id: str,
        name: str,
        kind: str,
        group: str,
        key: Optional[str],
        accept_warning: bool,
        manual: bool = False,
    ) -> ConfigDraft:
        with self._lock:
            self._require_revision(expected_revision)
            node = self._external_node(node_id)
            external_ref = node["externalRef"]
            if manual:
                if not accept_warning:
                    raise ExternalResourceSelectionWarning(
                        "This reference was entered manually and could not be "
                        "verified from Kubernetes inventory."
                    )
                row = _manual_inventory_row(
                    external_ref,
                    name=name,
                    kind=kind,
                    group=group,
                    key=key,
                )
            else:
                rows = self._edit_service.list_external_resources(
                    external_ref,
                    node.get("value"),
                )
                row = _find_inventory_row(rows, name, kind, group)
                if row is None:
                    raise ValueError(
                        f"{kind or 'Resource'} '{name}' is no longer available."
                    )
                if row.get("status") != "matching" and not accept_warning:
                    raise ExternalResourceSelectionWarning(
                        str(row.get("message") or "")
                    )

            selection = external_ref.get("selection") or {
                "target": "scalarName",
            }
            operations = _external_selection_operations(
                node,
                selection,
                row,
                key,
            )
            self._apply_operations(operations)
            return self._snapshot()

    def read_external_resource(
        self,
        expected_revision: str,
        node_id: str,
        name: str,
    ) -> ExternalResourceDetails:
        with self._lock:
            self._require_revision(expected_revision)
            node = self._external_node(node_id)
            external_ref = node["externalRef"]
            _require_create_descriptor(external_ref)
            resource = self._edit_service.read_external_resource(
                external_ref,
                name,
            )
            return _safe_external_resource_details(
                node_id=node_id,
                draft_revision=self._draft_revision(),
                external_ref=external_ref,
                resource=resource,
            )

    def save_external_resource(
        self,
        *,
        expected_revision: str,
        node_id: str,
        values: Dict[str, str],
        confirmations: Dict[str, str],
        existing_name: Optional[str],
    ) -> ExternalResourceMutation:
        with self._lock:
            self._require_revision(expected_revision)
            node = self._external_node(node_id)
            external_ref = node["externalRef"]
            create = _require_create_descriptor(external_ref)
            submitted_values = {
                str(key): str(value)
                for key, value in values.items()
            }

            existing_keys: list[str] = []
            saved_existing_name = existing_name
            if existing_name:
                existing = self._edit_service.read_external_resource(
                    external_ref,
                    existing_name,
                )
                existing_keys = [
                    str(key)
                    for key in existing.get("keys") or []
                ]
                if existing.get("missing"):
                    saved_existing_name = None
                else:
                    name_field = str((create.get("apply") or {}).get("nameField") or "")
                    if name_field:
                        submitted_values[name_field] = existing_name

            _validate_external_resource_form(
                create,
                submitted_values,
                confirmations,
                existing_keys=existing_keys,
                updating=saved_existing_name is not None,
            )
            result = self._edit_service.save_external_resource(
                external_ref,
                submitted_values,
                existing_name=saved_existing_name,
            )
            name = str(result.get("name") or "").strip()
            if not name:
                raise RuntimeError("The external resource write did not return a name.")
            self._apply_operations(
                _created_external_resource_operations(
                    node,
                    create,
                    submitted_values,
                    name,
                )
            )
            return ExternalResourceMutation(
                draft=self._snapshot(),
                name=name,
                kind=str((create.get("output") or {}).get("kind") or "Resource"),
                message=str(result.get("message") or f"External resource saved: {name}"),
            )

    def _apply_operations(self, operations: Iterable[Dict[str, Any]]) -> None:
        raw_yaml = self._raw_yaml or ""
        edit_state = self._edit_state or {}
        for operation in operations:
            result = self._edit_service.apply_operation(raw_yaml, operation)
            raw_yaml = result.raw_yaml
            edit_state = result.edit_state
        self._raw_yaml = raw_yaml
        self._edit_state = edit_state

    def _reload(self) -> None:
        session = self._edit_service.load_edit_session()
        self._raw_yaml = session.raw_yaml
        self._base_revision = _revision(session.raw_yaml)
        self._edit_state = session.edit_state

    def _require_revision(self, expected_revision: str) -> None:
        if self._raw_yaml is None:
            self._reload()
        if expected_revision != self._draft_revision():
            raise ConfigDraftConflict(self._snapshot())

    def _draft_revision(self) -> str:
        return _revision(self._raw_yaml or "")

    def _snapshot(self) -> ConfigDraft:
        if self._raw_yaml is None or self._base_revision is None:
            raise RuntimeError("Configuration draft is not open")
        return ConfigDraft(
            base_revision=self._base_revision,
            draft_revision=self._draft_revision(),
            dirty=self._base_revision != self._draft_revision(),
            edit_state=self._edit_state or {},
        )

    def _external_node(self, node_id: str) -> Dict[str, Any]:
        node = _find_node((self._edit_state or {}).get("nodes") or [], node_id)
        if node is None:
            raise ValueError(f"Edit node '{node_id}' is not in the current draft.")
        if not node.get("externalRef"):
            raise ValueError(f"Edit node '{node_id}' is not an external reference.")
        return node


def _validation_messages(
    validation: Mapping[str, Any],
) -> tuple[str, ...]:
    messages = [
        str(item.get("message"))
        for item in validation.get("diagnostics") or []
        if isinstance(item, Mapping) and item.get("message")
    ]
    messages.extend(
        str(message)
        for message in validation.get("errors") or []
        if message
    )
    return tuple(dict.fromkeys(messages))


def _review_changes(
    edit_state: Mapping[str, Any],
    snapshot: Optional[Any],
) -> tuple[ConfigReviewChange, ...]:
    changes: list[ConfigReviewChange] = []
    seen: set[tuple[Optional[str], str]] = set()

    for node in getattr(snapshot, "nodes", {}).values() if snapshot else ():
        if getattr(node, "kind", None) != "resource":
            continue
        resource_id = str(getattr(node, "id", ""))
        resource_label = str(getattr(node, "label", ""))
        for comparison in getattr(node, "comparisons", ()):
            if not getattr(comparison, "pending_changed", False):
                continue
            path = str(getattr(comparison, "path", ""))
            key = (resource_id, path)
            if key in seen:
                continue
            seen.add(key)
            changes.append(ConfigReviewChange(
                resource_id=resource_id,
                resource_label=resource_label,
                path=path,
                label=str(getattr(comparison, "label", path)),
                kind="field",
            ))
        summary = str(getattr(node, "value_summary", "") or "")
        if "pending submission" in summary.lower():
            key = (resource_id, "$presence")
            if key not in seen:
                seen.add(key)
                changes.append(ConfigReviewChange(
                    resource_id=resource_id,
                    resource_label=resource_label,
                    path="$presence",
                    label=summary,
                    kind="resource",
                ))

    def visit(nodes: Iterable[Mapping[str, Any]]) -> None:
        for node in nodes:
            children = node.get("children") or []
            if (
                node.get("status") == "changed"
                and (
                    not children
                    or node.get("valueKind")
                    in {"scalar", "boolean", "union"}
                )
            ):
                path = ".".join(str(part) for part in node.get("path") or [])
                key = (None, path)
                if path and key not in seen:
                    seen.add(key)
                    changes.append(ConfigReviewChange(
                        resource_id=None,
                        resource_label=None,
                        path=path,
                        label=str(node.get("label") or path),
                        kind="field",
                    ))
            visit(children)

    visit(edit_state.get("nodes") or [])
    return tuple(changes)


def _find_node(nodes: Iterable[Dict[str, Any]], node_id: str) -> Optional[Dict[str, Any]]:
    stack = list(nodes)
    while stack:
        node = stack.pop()
        if node.get("id") == node_id:
            return node
        stack.extend(node.get("children") or [])
    return None


@dataclass(frozen=True)
class _ConfigReference:
    from_path: tuple[str, ...]
    from_field_path: tuple[str, ...]
    to_path: tuple[str, ...]
    reason: str


def _mapping(value: Any) -> Mapping[str, Any]:
    return value if isinstance(value, Mapping) else {}


def _config_dependency_graph(config: Mapping[str, Any]) -> list[_ConfigReference]:
    edges: list[_ConfigReference] = []

    def add(
        from_path: Sequence[str],
        from_field_path: Sequence[str],
        to_path: Sequence[str],
        reason: str,
    ) -> None:
        edges.append(_ConfigReference(
            from_path=tuple(str(part) for part in from_path),
            from_field_path=tuple(str(part) for part in from_field_path),
            to_path=tuple(str(part) for part in to_path),
            reason=reason,
        ))

    traffic = _mapping(config.get("traffic"))
    proxies = _mapping(traffic.get("proxies"))
    s3_sources = _mapping(traffic.get("s3Sources"))
    replayers = _mapping(traffic.get("replayers"))

    migrations = config.get("snapshotMigrationConfigs")
    if isinstance(migrations, list):
        for index, migration_value in enumerate(migrations):
            migration = _mapping(migration_value)
            if not migration:
                continue
            migration_path = ("snapshotMigrationConfigs", str(index))
            source = str(migration.get("fromSource") or "")
            if source:
                add(
                    migration_path,
                    (*migration_path, "fromSource"),
                    ("sourceClusters", source),
                    f"fromSource={source}",
                )
            target = str(migration.get("toTarget") or "")
            if target:
                add(
                    migration_path,
                    (*migration_path, "toTarget"),
                    ("targetClusters", target),
                    f"toTarget={target}",
                )
            snapshots = _mapping(migration.get("perSnapshotConfig"))
            for snapshot_name in snapshots:
                snapshot_path = (
                    *migration_path,
                    "perSnapshotConfig",
                    str(snapshot_name),
                )
                if source:
                    add(
                        snapshot_path,
                        snapshot_path,
                        (
                            "sourceClusters",
                            source,
                            "snapshotInfo",
                            "snapshots",
                            str(snapshot_name),
                        ),
                        f"snapshot={snapshot_name}",
                    )

    for proxy_name, proxy_value in proxies.items():
        proxy = _mapping(proxy_value)
        if not proxy:
            continue
        proxy_path = ("traffic", "proxies", str(proxy_name))
        source = str(proxy.get("source") or "")
        if source:
            add(
                proxy_path,
                (*proxy_path, "source"),
                ("sourceClusters", source),
                f"source={source}",
            )
        kafka = str(proxy.get("kafka") or "default")
        add(
            proxy_path,
            (*proxy_path, "kafka"),
            ("traffic", "kafkaClusters", kafka),
            f"kafka={kafka}",
        )

    for source_name, source_value in s3_sources.items():
        source = _mapping(source_value)
        if not source:
            continue
        source_path = ("traffic", "s3Sources", str(source_name))
        kafka = str(source.get("kafka") or "default")
        add(
            source_path,
            (*source_path, "kafka"),
            ("traffic", "kafkaClusters", kafka),
            f"kafka={kafka}",
        )

    for replay_name, replay_value in replayers.items():
        replay = _mapping(replay_value)
        if not replay:
            continue
        replay_path = ("traffic", "replayers", str(replay_name))
        captured = str(replay.get("fromCapturedTraffic") or "")
        if captured:
            captured_path: Optional[tuple[str, ...]] = None
            if captured in proxies:
                captured_path = ("traffic", "proxies", captured)
            elif captured in s3_sources:
                captured_path = ("traffic", "s3Sources", captured)
            if captured_path:
                add(
                    replay_path,
                    (*replay_path, "fromCapturedTraffic"),
                    captured_path,
                    f"fromCapturedTraffic={captured}",
                )
        target = str(replay.get("toTarget") or "")
        if target:
            add(
                replay_path,
                (*replay_path, "toTarget"),
                ("targetClusters", target),
                f"toTarget={target}",
            )
        dependencies = replay.get("dependsOnSnapshotMigrations")
        if isinstance(dependencies, list):
            for index, dependency_value in enumerate(dependencies):
                dependency = _mapping(dependency_value)
                if not dependency:
                    continue
                dependency_path = (
                    *replay_path,
                    "dependsOnSnapshotMigrations",
                    str(index),
                )
                source = str(dependency.get("source") or "")
                if source:
                    add(
                        dependency_path,
                        (*dependency_path, "source"),
                        ("sourceClusters", source),
                        f"source={source}",
                    )
                snapshot = str(dependency.get("snapshot") or "")
                if source and snapshot:
                    add(
                        dependency_path,
                        (*dependency_path, "snapshot"),
                        (
                            "sourceClusters",
                            source,
                            "snapshotInfo",
                            "snapshots",
                            snapshot,
                        ),
                        f"snapshot={snapshot}",
                    )

    for source_name, source_value in _mapping(
        config.get("sourceClusters")
    ).items():
        source = _mapping(source_value)
        snapshot_info = _mapping(source.get("snapshotInfo"))
        repositories = _mapping(snapshot_info.get("repos"))
        snapshots = _mapping(snapshot_info.get("snapshots"))
        if not repositories or not snapshots:
            continue
        for snapshot_name, snapshot_value in snapshots.items():
            snapshot = _mapping(snapshot_value)
            repository = str(snapshot.get("repoName") or "")
            if repository:
                snapshot_path = (
                    "sourceClusters",
                    str(source_name),
                    "snapshotInfo",
                    "snapshots",
                    str(snapshot_name),
                )
                add(
                    snapshot_path,
                    (*snapshot_path, "repoName"),
                    (
                        "sourceClusters",
                        str(source_name),
                        "snapshotInfo",
                        "repos",
                        repository,
                    ),
                    f"repoName={repository}",
                )
    return edges


def _removed_snapshot_paths(
    config: Mapping[str, Any],
    path: tuple[str, ...],
) -> set[tuple[str, ...]]:
    if len(path) < 3 or path[0] != "sourceClusters":
        return set()
    snapshots = _mapping(
        _mapping(
            _mapping(
                _mapping(config.get("sourceClusters")).get(path[1])
            ).get("snapshotInfo")
        ).get("snapshots")
    )
    if len(path) == 5 and path[2:4] == ("snapshotInfo", "snapshots"):
        names = [path[4]]
    elif len(path) == 4 and path[2:4] == ("snapshotInfo", "snapshots"):
        names = [str(name) for name in snapshots]
    elif len(path) == 3 and path[2] == "snapshotInfo":
        names = [str(name) for name in snapshots]
    else:
        return set()
    return {
        (
            "sourceClusters",
            path[1],
            "snapshotInfo",
            "snapshots",
            name,
        )
        for name in names
    }


def _removal_impact(
    config: Mapping[str, Any],
    path: tuple[str, ...],
) -> tuple[ConfigRemovalImpactEntry, ...]:
    graph = _config_dependency_graph(config)
    targets = {path, *_removed_snapshot_paths(config, path)}
    selected = [edge for edge in graph if edge.to_path in targets]

    if len(path) == 2 and path[0] == "sourceClusters":
        removed_traffic = {
            edge.from_path
            for edge in selected
            if (
                len(edge.from_path) == 3
                and edge.from_path[:2] in {
                    ("traffic", "proxies"),
                    ("traffic", "s3Sources"),
                }
            )
        }
        selected.extend(
            edge for edge in graph if edge.to_path in removed_traffic
        )

    result = []
    seen = set()
    for edge in selected:
        if edge.from_path in seen:
            continue
        seen.add(edge.from_path)
        result.append(ConfigRemovalImpactEntry(
            path=edge.from_path,
            field_path=edge.from_field_path,
            reason=edge.reason,
        ))
    return tuple(result)


def _safe_inventory_row(row: Dict[str, Any]) -> Dict[str, Any]:
    safe_keys = {
        "name",
        "kind",
        "group",
        "version",
        "apiVersion",
        "namespaced",
        "type",
        "keys",
        "status",
        "message",
        "current",
    }
    return {
        key: value
        for key, value in row.items()
        if key in safe_keys
    }


def _find_inventory_row(
    rows: Iterable[Dict[str, Any]],
    name: str,
    kind: str,
    group: str,
) -> Optional[Dict[str, Any]]:
    return next(
        (
            row for row in rows
            if row.get("name") == name
            and str(row.get("kind") or "") == kind
            and str(row.get("group") or "") == group
        ),
        None,
    )


def _manual_inventory_row(
    external_ref: Dict[str, Any],
    *,
    name: str,
    kind: str,
    group: str,
    key: Optional[str],
) -> Dict[str, Any]:
    if not is_k8s_name(name):
        raise ValueError("The resource name must be a valid Kubernetes DNS name.")

    resource_types = list((external_ref.get("k8s") or {}).get("resourceTypes") or [])
    if not resource_types:
        legacy_kind = {
            "secret": "Secret",
            "configMap": "ConfigMap",
        }.get(str(external_ref.get("kind") or ""))
        if legacy_kind:
            resource_types = [{"kind": legacy_kind, "group": ""}]
    allowed = next(
        (
            resource_type
            for resource_type in resource_types
            if str(resource_type.get("kind") or "") == kind
            and str(resource_type.get("group") or "") == group
        ),
        None,
    )
    if allowed is None:
        identity = f"{group}/{kind}" if group else kind or "Resource"
        raise ValueError(
            f"Resource type '{identity}' is not allowed for this reference."
        )

    selection = external_ref.get("selection") or {"target": "scalarName"}
    if selection.get("target") == "fileRefConfigMap":
        if not key or not is_config_map_key(key):
            raise ValueError("The selected key must be a valid ConfigMap key.")

    return {
        "name": name,
        "kind": kind,
        "group": group,
        "version": str(allowed.get("version") or ""),
        "keys": [key] if key else [],
        "status": "warn",
        "message": "Reference entered manually; inventory validation is pending.",
        "current": False,
    }


def _external_selection_operations(
    node: Dict[str, Any],
    selection: Dict[str, Any],
    row: Dict[str, Any],
    key: Optional[str],
) -> tuple[Dict[str, Any], ...]:
    path = [str(part) for part in node.get("path") or []]
    target = selection.get("target") or "scalarName"
    if target == "scalarName":
        return ({"op": "set", "path": path, "value": row.get("name")},)
    if target == "objectRef":
        value = {
            selection.get("nameField") or "name": row.get("name"),
            selection.get("kindField") or "kind": row.get("kind"),
        }
        group = row.get("group")
        if group:
            value[selection.get("groupField") or "group"] = group
        return ({"op": "set", "path": path, "value": value},)
    if target == "fileRefConfigMap":
        if not key:
            raise ValueError("A ConfigMap key must be selected.")
        keys = [str(candidate) for candidate in row.get("keys") or []]
        if key not in keys:
            raise ValueError(
                f"ConfigMap key '{key}' is no longer available in '{row.get('name')}'."
            )
        parent_path = path[:-1]
        name_field = str(selection.get("nameField") or path[-1])
        path_field = str(selection.get("pathField") or "path")
        return (
            {
                "op": "set",
                "path": [*parent_path, name_field],
                "value": row.get("name"),
            },
            {
                "op": "set",
                "path": [*parent_path, path_field],
                "value": key,
            },
        )
    raise ValueError(f"Unsupported external selection target: {target}")


def _require_create_descriptor(external_ref: Dict[str, Any]) -> Dict[str, Any]:
    create = external_ref.get("create")
    if not isinstance(create, dict):
        raise ValueError("Create or update is not available for this reference.")
    return create


def _safe_external_resource_details(
    *,
    node_id: str,
    draft_revision: str,
    external_ref: Dict[str, Any],
    resource: Dict[str, Any],
) -> ExternalResourceDetails:
    create = _require_create_descriptor(external_ref)
    fields = list(create.get("fields") or [])
    resource_values = resource.get("values") or {}
    field_values: Dict[str, str] = {}
    hidden_fields: list[str] = []
    name = str(resource.get("name") or "")
    name_field = str((create.get("apply") or {}).get("nameField") or "")
    if name_field:
        field_values[name_field] = name

    output = create.get("output") or {}
    mappings = output.get("stringData") or output.get("data") or {}
    field_by_name = {
        str(field.get("name")): field
        for field in fields
        if field.get("name")
    }
    resource_keys = [str(key) for key in resource.get("keys") or []]
    for output_key, source in mappings.items():
        field_name = str((source or {}).get("fromField") or "")
        field = field_by_name.get(field_name) or {}
        if not field_name or output_key not in resource_keys:
            continue
        if _field_is_sensitive(field):
            hidden_fields.append(field_name)
            continue
        if output_key in resource_values:
            field_values[field_name] = str(resource_values.get(output_key) or "")

    return ExternalResourceDetails(
        node_id=node_id,
        draft_revision=draft_revision,
        display_name=str(
            external_ref.get("displayName")
            or create.get("label")
            or "External resource"
        ),
        name=name,
        kind=str(resource.get("kind") or (create.get("output") or {}).get("kind") or "Resource"),
        resource_type=(
            str(resource.get("type"))
            if resource.get("type") is not None
            else None
        ),
        keys=sorted(resource_keys),
        field_values=field_values,
        hidden_fields=sorted(hidden_fields),
        missing=bool(resource.get("missing")),
        message=(
            str(resource.get("message"))
            if resource.get("message")
            else None
        ),
    )


def _field_is_sensitive(field: Dict[str, Any]) -> bool:
    if "sensitive" in field:
        return bool(field.get("sensitive"))
    return field.get("input") in {"password", "secretMultilineText"}


def _output_key_for_field(
    create: Dict[str, Any],
    field_name: str,
) -> Optional[str]:
    output = create.get("output") or {}
    mappings = output.get("stringData") or output.get("data") or {}
    return next(
        (
            str(key)
            for key, source in mappings.items()
            if str((source or {}).get("fromField") or "") == field_name
        ),
        None,
    )


def _validate_external_resource_form(
    create: Dict[str, Any],
    values: Dict[str, str],
    confirmations: Dict[str, str],
    *,
    existing_keys: list[str],
    updating: bool,
) -> None:
    for field in create.get("fields") or []:
        name = str(field.get("name") or "")
        label = str(field.get("label") or name or "Value")
        value = values.get(name, "")
        output_key = _output_key_for_field(create, name)
        preserves_sensitive_value = (
            updating
            and _field_is_sensitive(field)
            and not value
            and output_key in existing_keys
        )
        if preserves_sensitive_value:
            continue
        if field.get("required") and not value.strip():
            raise ExternalResourceFormInvalid(f"{label} is required.")
        for validation_id in field.get("validationIds") or []:
            message = _external_field_validation_message(
                str(validation_id),
                label,
                value,
            )
            if message:
                raise ExternalResourceFormInvalid(message)
        if field.get("confirm") and value != confirmations.get(name, ""):
            raise ExternalResourceFormInvalid(
                f"{label} and confirmation do not match."
            )


def _external_field_validation_message(
    validation_id: str,
    label: str,
    value: str,
) -> Optional[str]:
    if validation_id == "non-empty" and not value.strip():
        return f"{label} is required."
    if validation_id == "k8s-name" and value and not is_k8s_name(value):
        return f"{label} must be a valid Kubernetes DNS name."
    if validation_id == "configmap-key" and value and not is_config_map_key(value):
        return f"{label} must be a valid ConfigMap key."
    if (
        validation_id == "pem-certificate-chain"
        and value
        and not looks_like_pem_certificate_chain(value)
    ):
        return f"{label} must include at least one PEM CERTIFICATE block."
    if (
        validation_id == "pem-private-key"
        and value
        and not looks_like_pem_private_key(value)
    ):
        return f"{label} must include a PEM PRIVATE KEY block."
    if (
        validation_id == "log4j-properties"
        and value
        and not looks_like_log4j_properties(value)
    ):
        return f"{label} must include at least one Log4j2 property assignment."
    if validation_id == "json" and value:
        try:
            json.loads(value)
        except json.JSONDecodeError as error:
            return f"{label} must be valid JSON: {error.msg}."
    return None


def _created_external_resource_operations(
    node: Dict[str, Any],
    create: Dict[str, Any],
    values: Dict[str, str],
    name: str,
) -> tuple[Dict[str, Any], ...]:
    apply = create.get("apply") or {}
    target = apply.get("target") or "scalarName"
    path = [str(part) for part in node.get("path") or []]
    if target == "scalarName":
        return ({"op": "set", "path": path, "value": name},)
    if target == "fileRefConfigMap":
        selection = (node.get("externalRef") or {}).get("selection") or {}
        if selection.get("target") != "fileRefConfigMap":
            raise ValueError(
                "The file reference is missing its ConfigMap selection descriptor."
            )
        key_field = str(apply.get("pathField") or "")
        key = str(values.get(key_field) or "")
        if not key:
            raise ExternalResourceFormInvalid("A ConfigMap key is required.")
        parent_path = path[:-1]
        return (
            {
                "op": "set",
                "path": [
                    *parent_path,
                    str(selection.get("nameField") or path[-1]),
                ],
                "value": name,
            },
            {
                "op": "set",
                "path": [
                    *parent_path,
                    str(selection.get("pathField") or "path"),
                ],
                "value": key,
            },
        )
    raise ValueError(f"Unsupported external create target: {target}")
