"""Stateless Kubernetes resource interactions for browser configuration edits."""

import json
from dataclasses import dataclass
from typing import Any, Dict, Iterable, Optional

from ..external_resource_validation import (
    is_config_map_key,
    is_k8s_name,
    looks_like_log4j_properties,
    looks_like_pem_certificate_chain,
    looks_like_pem_private_key,
)


@dataclass(frozen=True)
class ExternalResourceInventory:
    node_id: str
    display_name: str
    rows: list[Dict[str, Any]]


@dataclass(frozen=True)
class ExternalResourceDetails:
    node_id: str
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
    name: str
    kind: str
    message: str


class ExternalResourceSelectionWarning(RuntimeError):
    def __init__(self, message: str):
        resolved = (
            message
            or "The selected resource does not match all requirements."
        )
        super().__init__(resolved)
        self.message = resolved


class ExternalResourceFormInvalid(ValueError):
    pass


class ExternalResourceService:
    """Resolve schema hints from one raw document and operate on Kubernetes."""

    def __init__(self, edit_service: Any):
        self._edit_service = edit_service

    def list(
        self,
        raw_yaml: str,
        node_id: str,
    ) -> ExternalResourceInventory:
        node = self._external_node(raw_yaml, node_id)
        external_ref = node["externalRef"]
        rows = self._edit_service.list_external_resources(
            external_ref,
            node.get("value"),
        )
        return ExternalResourceInventory(
            node_id=node_id,
            display_name=str(
                external_ref.get("displayName")
                or node.get("label")
                or node_id
            ),
            rows=[_safe_inventory_row(row) for row in rows],
        )

    def validate_selection(
        self,
        *,
        raw_yaml: str,
        node_id: str,
        name: str,
        kind: str,
        group: str,
        key: Optional[str],
        accept_warning: bool,
        manual: bool = False,
    ) -> None:
        node = self._external_node(raw_yaml, node_id)
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
        _validate_selection_key(node, row, key)

    def read(
        self,
        raw_yaml: str,
        node_id: str,
        name: str,
    ) -> ExternalResourceDetails:
        node = self._external_node(raw_yaml, node_id)
        external_ref = node["externalRef"]
        _require_create_descriptor(external_ref)
        resource = self._edit_service.read_external_resource(
            external_ref,
            name,
        )
        return _safe_external_resource_details(
            node_id=node_id,
            external_ref=external_ref,
            resource=resource,
        )

    def save(
        self,
        *,
        raw_yaml: str,
        node_id: str,
        values: Dict[str, str],
        confirmations: Dict[str, str],
        existing_name: Optional[str],
    ) -> ExternalResourceMutation:
        node = self._external_node(raw_yaml, node_id)
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
                name_field = str(
                    (create.get("apply") or {}).get("nameField") or ""
                )
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
            raise RuntimeError(
                "The external resource write did not return a name."
            )
        return ExternalResourceMutation(
            name=name,
            kind=str(
                (create.get("output") or {}).get("kind") or "Resource"
            ),
            message=str(
                result.get("message")
                or f"External resource saved: {name}"
            ),
        )

    def _external_node(
        self,
        raw_yaml: str,
        node_id: str,
    ) -> Dict[str, Any]:
        edit_state = self._edit_service.project_raw_yaml(raw_yaml)
        node = _find_node(edit_state.get("nodes") or [], node_id)
        if node is None:
            raise ValueError(
                f"Edit node '{node_id}' is not in the current document."
            )
        if not node.get("externalRef"):
            raise ValueError(
                f"Edit node '{node_id}' is not an external reference."
            )
        return node


def _find_node(
    nodes: Iterable[Dict[str, Any]],
    node_id: str,
) -> Optional[Dict[str, Any]]:
    stack = list(nodes)
    while stack:
        node = stack.pop()
        if node.get("id") == node_id:
            return node
        stack.extend(node.get("children") or [])
    return None


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
        raise ValueError(
            "The resource name must be a valid Kubernetes DNS name."
        )

    allowed = _find_allowed_resource_type(external_ref, kind, group)
    if allowed is None:
        identity = f"{group}/{kind}" if group else kind or "Resource"
        raise ValueError(
            f"Resource type '{identity}' is not allowed for this reference."
        )

    _validate_manual_selection_key(external_ref, key)
    return {
        "name": name,
        "kind": kind,
        "group": group,
        "version": str(allowed.get("version") or ""),
        "keys": [key] if key else [],
        "status": "warn",
        "message": (
            "Reference entered manually; inventory validation is pending."
        ),
        "current": False,
    }


def _find_allowed_resource_type(
    external_ref: Dict[str, Any],
    kind: str,
    group: str,
) -> Optional[Dict[str, Any]]:
    resource_types = list(
        (external_ref.get("k8s") or {}).get("resourceTypes") or []
    )
    if not resource_types:
        resource_types = _legacy_resource_types(external_ref)
    return next(
        (
            resource_type
            for resource_type in resource_types
            if str(resource_type.get("kind") or "") == kind
            and str(resource_type.get("group") or "") == group
        ),
        None,
    )


def _legacy_resource_types(
    external_ref: Dict[str, Any],
) -> list[Dict[str, str]]:
    legacy_kind = {
        "secret": "Secret",
        "configMap": "ConfigMap",
    }.get(str(external_ref.get("kind") or ""))
    return [{"kind": legacy_kind, "group": ""}] if legacy_kind else []


def _validate_manual_selection_key(
    external_ref: Dict[str, Any],
    key: Optional[str],
) -> None:
    selection = external_ref.get("selection") or {"target": "scalarName"}
    if (
        selection.get("target") == "fileRefConfigMap"
        and (not key or not is_config_map_key(key))
    ):
        raise ValueError(
            "The selected key must be a valid ConfigMap key."
        )


def _validate_selection_key(
    node: Dict[str, Any],
    row: Dict[str, Any],
    key: Optional[str],
) -> None:
    selection = (node.get("externalRef") or {}).get("selection") or {
        "target": "scalarName",
    }
    if selection.get("target") != "fileRefConfigMap":
        return
    if not key:
        raise ValueError("A ConfigMap key must be selected.")
    keys = [str(candidate) for candidate in row.get("keys") or []]
    if key not in keys:
        raise ValueError(
            f"ConfigMap key '{key}' is no longer available in "
            f"'{row.get('name')}'."
        )


def _require_create_descriptor(
    external_ref: Dict[str, Any],
) -> Dict[str, Any]:
    create = external_ref.get("create")
    if not isinstance(create, dict):
        raise ValueError(
            "Create or update is not available for this reference."
        )
    return create


def _safe_external_resource_details(
    *,
    node_id: str,
    external_ref: Dict[str, Any],
    resource: Dict[str, Any],
) -> ExternalResourceDetails:
    create = _require_create_descriptor(external_ref)
    name = str(resource.get("name") or "")
    name_field = str((create.get("apply") or {}).get("nameField") or "")
    resource_keys = [str(key) for key in resource.get("keys") or []]
    field_values, hidden_fields = _external_resource_field_values(
        create,
        resource,
        resource_keys,
    )
    if name_field:
        field_values[name_field] = name

    display_name = (
        external_ref.get("displayName")
        or create.get("label")
        or "External resource"
    )
    resource_kind = (
        resource.get("kind")
        or (create.get("output") or {}).get("kind")
        or "Resource"
    )

    return ExternalResourceDetails(
        node_id=node_id,
        display_name=str(display_name),
        name=name,
        kind=str(resource_kind),
        resource_type=_optional_string(resource.get("type")),
        keys=sorted(resource_keys),
        field_values=field_values,
        hidden_fields=sorted(hidden_fields),
        missing=bool(resource.get("missing")),
        message=_optional_string(resource.get("message")),
    )


def _external_resource_field_values(
    create: Dict[str, Any],
    resource: Dict[str, Any],
    resource_keys: list[str],
) -> tuple[Dict[str, str], list[str]]:
    fields = list(create.get("fields") or [])
    field_by_name = {
        str(field.get("name")): field
        for field in fields
        if field.get("name")
    }
    output = create.get("output") or {}
    mappings = output.get("stringData") or output.get("data") or {}
    resource_values = resource.get("values") or {}
    field_values: Dict[str, str] = {}
    hidden_fields: list[str] = []

    for output_key, source in mappings.items():
        field_name = str((source or {}).get("fromField") or "")
        if not field_name or output_key not in resource_keys:
            continue
        field = field_by_name.get(field_name) or {}
        if _field_is_sensitive(field):
            hidden_fields.append(field_name)
        elif output_key in resource_values:
            field_values[field_name] = str(
                resource_values.get(output_key) or ""
            )
    return field_values, hidden_fields


def _optional_string(value: Any) -> Optional[str]:
    return str(value) if value is not None and value != "" else None


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
        _validate_external_resource_field(
            create,
            field,
            values,
            confirmations,
            existing_keys=existing_keys,
            updating=updating,
        )


def _validate_external_resource_field(
    create: Dict[str, Any],
    field: Dict[str, Any],
    values: Dict[str, str],
    confirmations: Dict[str, str],
    *,
    existing_keys: list[str],
    updating: bool,
) -> None:
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
        return
    if field.get("required") and not value.strip():
        raise ExternalResourceFormInvalid(f"{label} is required.")
    _validate_external_field_values(field, label, value)
    if field.get("confirm") and value != confirmations.get(name, ""):
        raise ExternalResourceFormInvalid(
            f"{label} and confirmation do not match."
        )


def _validate_external_field_values(
    field: Dict[str, Any],
    label: str,
    value: str,
) -> None:
    for validation_id in field.get("validationIds") or []:
        message = _external_field_validation_message(
            str(validation_id),
            label,
            value,
        )
        if message:
            raise ExternalResourceFormInvalid(message)


def _external_field_validation_message(
    validation_id: str,
    label: str,
    value: str,
) -> Optional[str]:
    validator = _EXTERNAL_FIELD_VALIDATORS.get(validation_id)
    return validator(label, value) if validator else None


def _non_empty_message(label: str, value: str) -> Optional[str]:
    return f"{label} is required." if not value.strip() else None


def _k8s_name_message(label: str, value: str) -> Optional[str]:
    if value and not is_k8s_name(value):
        return f"{label} must be a valid Kubernetes DNS name."
    return None


def _config_map_key_message(label: str, value: str) -> Optional[str]:
    if value and not is_config_map_key(value):
        return f"{label} must be a valid ConfigMap key."
    return None


def _pem_certificate_chain_message(
    label: str,
    value: str,
) -> Optional[str]:
    if value and not looks_like_pem_certificate_chain(value):
        return f"{label} must include at least one PEM CERTIFICATE block."
    return None


def _pem_private_key_message(label: str, value: str) -> Optional[str]:
    if value and not looks_like_pem_private_key(value):
        return f"{label} must include a PEM PRIVATE KEY block."
    return None


def _log4j_properties_message(label: str, value: str) -> Optional[str]:
    if value and not looks_like_log4j_properties(value):
        return f"{label} must include at least one Log4j2 property assignment."
    return None


def _json_message(label: str, value: str) -> Optional[str]:
    if not value:
        return None
    try:
        json.loads(value)
    except json.JSONDecodeError as error:
        return f"{label} must be valid JSON: {error.msg}."
    return None


_EXTERNAL_FIELD_VALIDATORS = {
    "non-empty": _non_empty_message,
    "k8s-name": _k8s_name_message,
    "configmap-key": _config_map_key_message,
    "pem-certificate-chain": _pem_certificate_chain_message,
    "pem-private-key": _pem_private_key_message,
    "log4j-properties": _log4j_properties_message,
    "json": _json_message,
}
