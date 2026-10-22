"""Tests for stateless Kubernetes resource interactions."""

from copy import deepcopy
from dataclasses import dataclass

import pytest

from console_link.workflow.application.external_resources import (
    ExternalResourceFormInvalid,
    ExternalResourceSelectionWarning,
    ExternalResourceService,
)


NODE_ID = "edit:traffic.transform.configMap"


def _edit_state(value="saved", *, selection=None):
    return {
        "formatVersion": 1,
        "provenance": {
            "source": "pending-yaml",
            "lossy": False,
            "warnings": [],
        },
        "nodes": [{
            "id": "edit:traffic",
            "path": ["traffic"],
            "label": "Traffic",
            "valueKind": "object",
            "children": [{
                "id": NODE_ID,
                "path": ["traffic", "transform", "configMap"],
                "label": f"configMap: {value}",
                "value": value,
                "valueKind": "scalar",
                "externalRef": {
                    "displayName": "Transform ConfigMap",
                    "selection": selection or {
                        "target": "fileRefConfigMap",
                        "nameField": "configMap",
                        "pathField": "path",
                    },
                    "k8s": {
                        "resourceTypes": [{
                            "group": "",
                            "version": "v1",
                            "kind": "ConfigMap",
                            "namespaced": True,
                        }],
                    },
                },
                "children": [],
            }],
        }],
        "validation": {"valid": True, "errors": []},
    }


@dataclass
class _FakeEditService:
    def __post_init__(self):
        self.edit_state = _edit_state()
        self.saved_external = []
        self.external_payload = {
            "kind": "ConfigMap",
            "name": "matching",
            "keys": ["main.js", "settings.json"],
            "values": {
                "main.js": "export default () => true;",
                "settings.json": '{"enabled": true}',
            },
        }
        self.rows = [
            {
                "name": "matching",
                "kind": "ConfigMap",
                "group": "",
                "keys": ["main.js", "settings.json"],
                "status": "matching",
                "message": "",
            },
            {
                "name": "near-match",
                "kind": "ConfigMap",
                "group": "",
                "keys": ["README"],
                "status": "warn",
                "message": "no JavaScript-looking key",
            },
        ]

    def project_raw_yaml(self, raw_yaml):
        return deepcopy(self.edit_state)

    def list_external_resources(self, external_ref, current_value=None):
        return deepcopy(self.rows)

    def read_external_resource(self, external_ref, name):
        return deepcopy({**self.external_payload, "name": name})

    def save_external_resource(self, external_ref, values, existing_name=None):
        self.saved_external.append((
            deepcopy(external_ref),
            deepcopy(values),
            existing_name,
        ))
        create = external_ref["create"]
        name = values.get(create["apply"]["nameField"]) or existing_name
        return {
            "name": name,
            "message": f"{create['output']['kind']} saved: {name}",
        }


def _service(edit_service=None):
    return ExternalResourceService(edit_service or _FakeEditService())


def _add_config_map_create_descriptor(edit_service):
    external_ref = (
        edit_service.edit_state["nodes"][0]["children"][0]["externalRef"]
    )
    external_ref["create"] = {
        "label": "Transform ConfigMap",
        "fields": [
            {
                "name": "name",
                "label": "ConfigMap name",
                "input": "name",
                "required": True,
                "validationIds": ["k8s-name"],
            },
            {
                "name": "key",
                "label": "Key",
                "input": "text",
                "required": True,
                "validationIds": ["configmap-key"],
            },
            {
                "name": "contents",
                "label": "JavaScript",
                "input": "multilineText",
                "required": True,
                "validationIds": ["non-empty"],
            },
        ],
        "output": {
            "kind": "ConfigMap",
            "data": {"main.js": {"fromField": "contents"}},
        },
        "apply": {
            "target": "fileRefConfigMap",
            "nameField": "name",
            "pathField": "key",
        },
    }


def test_inventory_is_resolved_from_the_submitted_document_and_redacted():
    edit_service = _FakeEditService()
    inventory = _service(edit_service).list("value: saved\n", NODE_ID)

    assert inventory.node_id == NODE_ID
    assert inventory.display_name == "Transform ConfigMap"
    assert inventory.rows[0]["keys"] == ["main.js", "settings.json"]
    assert "values" not in inventory.rows[0]


def test_selection_rechecks_inventory_without_mutating_the_document():
    edit_service = _FakeEditService()
    service = _service(edit_service)

    assert service.validate_selection(
        raw_yaml="value: saved\n",
        node_id=NODE_ID,
        name="matching",
        kind="ConfigMap",
        group="",
        key="main.js",
        accept_warning=False,
    ) is None


def test_manual_selection_validates_type_name_and_configmap_key():
    service = _service()
    service.validate_selection(
        raw_yaml="value: saved\n",
        node_id=NODE_ID,
        name="not-visible-to-list",
        kind="ConfigMap",
        group="",
        key="transform.js",
        accept_warning=True,
        manual=True,
    )

    with pytest.raises(ValueError, match="not allowed"):
        service.validate_selection(
            raw_yaml="value: saved\n",
            node_id=NODE_ID,
            name="credentials",
            kind="Secret",
            group="",
            key="transform.js",
            accept_warning=True,
            manual=True,
        )

    with pytest.raises(ValueError, match="valid ConfigMap key"):
        service.validate_selection(
            raw_yaml="value: saved\n",
            node_id=NODE_ID,
            name="not-visible-to-list",
            kind="ConfigMap",
            group="",
            key="../transform.js",
            accept_warning=True,
            manual=True,
        )


def test_warning_requires_explicit_acceptance():
    with pytest.raises(ExternalResourceSelectionWarning) as error:
        _service().validate_selection(
            raw_yaml="value: saved\n",
            node_id=NODE_ID,
            name="near-match",
            kind="ConfigMap",
            group="",
            key="README",
            accept_warning=False,
        )

    assert error.value.message == "no JavaScript-looking key"


def test_secret_inventory_never_exposes_dependency_values():
    edit_service = _FakeEditService()
    edit_service.rows = [{
        "name": "credentials",
        "kind": "Secret",
        "group": "",
        "keys": ["password", "username"],
        "values": {"password": "must-not-leak"},
        "status": "matching",
        "message": "",
    }]

    inventory = _service(edit_service).list("value: saved\n", NODE_ID)

    assert inventory.rows[0]["keys"] == ["password", "username"]
    assert "values" not in inventory.rows[0]


def test_configmap_details_map_fields_without_returning_raw_payload():
    edit_service = _FakeEditService()
    _add_config_map_create_descriptor(edit_service)

    details = _service(edit_service).read(
        "value: saved\n",
        NODE_ID,
        "matching",
    )

    assert details.field_values == {
        "name": "matching",
        "contents": "export default () => true;",
    }
    assert details.hidden_fields == []
    assert details.keys == ["main.js", "settings.json"]
    assert not hasattr(details, "values")


def test_secret_details_never_return_sensitive_values():
    edit_service = _FakeEditService()
    edit_service.edit_state = _edit_state(
        "credentials",
        selection={"target": "scalarName"},
    )
    external_ref = (
        edit_service.edit_state["nodes"][0]["children"][0]["externalRef"]
    )
    external_ref["create"] = {
        "label": "HTTP Basic Auth Secret",
        "fields": [
            {
                "name": "secretName",
                "label": "Secret name",
                "input": "name",
                "required": True,
            },
            {
                "name": "username",
                "label": "Username",
                "input": "text",
                "required": True,
                "sensitive": False,
            },
            {
                "name": "password",
                "label": "Password",
                "input": "password",
                "required": True,
                "sensitive": True,
            },
        ],
        "output": {
            "kind": "Secret",
            "type": "kubernetes.io/basic-auth",
            "stringData": {
                "username": {"fromField": "username"},
                "password": {"fromField": "password"},
            },
        },
        "apply": {"target": "scalarName", "nameField": "secretName"},
    }
    edit_service.external_payload = {
        "kind": "Secret",
        "name": "credentials",
        "type": "kubernetes.io/basic-auth",
        "keys": ["password", "username"],
        "values": {
            "username": "admin",
            "password": "must-not-cross-the-api",
        },
    }

    details = _service(edit_service).read(
        "value: saved\n",
        NODE_ID,
        "credentials",
    )

    assert details.field_values == {
        "secretName": "credentials",
        "username": "admin",
    }
    assert details.hidden_fields == ["password"]
    assert "must-not-cross-the-api" not in repr(details)


def test_save_validates_and_returns_only_the_external_mutation():
    edit_service = _FakeEditService()
    _add_config_map_create_descriptor(edit_service)
    service = _service(edit_service)

    with pytest.raises(ExternalResourceFormInvalid) as error:
        service.save(
            raw_yaml="value: saved\n",
            node_id=NODE_ID,
            values={"name": "Bad Name", "key": "..", "contents": ""},
            confirmations={},
            existing_name=None,
        )

    assert "valid Kubernetes DNS name" in str(error.value)
    assert edit_service.saved_external == []

    saved = service.save(
        raw_yaml="value: saved\n",
        node_id=NODE_ID,
        values={
            "name": "new-transform",
            "key": "main.js",
            "contents": "export default () => true;",
        },
        confirmations={},
        existing_name=None,
    )

    assert saved.name == "new-transform"
    assert saved.message == "ConfigMap saved: new-transform"
    assert edit_service.saved_external[0][1]["contents"].startswith("export")
