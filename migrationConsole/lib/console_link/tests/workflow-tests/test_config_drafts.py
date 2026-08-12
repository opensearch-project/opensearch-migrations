"""Tests for the process-local configuration draft service."""

from copy import deepcopy
from dataclasses import dataclass

import pytest

from console_link.workflow.application.config_drafts import (
    ConfigDraftConflict,
    ConfigDraftService,
    ExternalResourceFormInvalid,
    ExternalResourceSelectionWarning,
    SavedConfigConflict,
)
from console_link.workflow.services.config_edit_service import (
    ConfigEditApplyResult,
    ConfigEditSession,
)


def _edit_state(value="saved", *, selection=None):
    external_ref = {
        "kind": "kubernetesResource",
        "purpose": "file-ref-config-map",
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
    }
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
            "status": "ok",
            "statusCounts": {},
            "children": [{
                "id": "edit:traffic.transform.configMap",
                "path": ["traffic", "transform", "configMap"],
                "label": f"configMap: {value}",
                "value": value,
                "valueKind": "scalar",
                "valueType": "string",
                "presence": "required",
                "status": "ok",
                "statusCounts": {},
                "externalRef": external_ref,
                "children": [],
            }],
        }],
        "validation": {
            "valid": True,
            "errors": [],
            "diagnostics": [],
        },
    }


@dataclass
class _FakeEditService:
    saved_yaml: str = "value: saved\n"

    def __post_init__(self):
        self.operations = []
        self.saved = []
        self.saved_external = []
        self.edit_state_override = None
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
                "version": "v1",
                "keys": ["main.js", "settings.json"],
                "status": "matching",
                "message": "",
                "current": False,
            },
            {
                "name": "near-match",
                "kind": "ConfigMap",
                "group": "",
                "version": "v1",
                "keys": ["README"],
                "status": "warn",
                "message": "no JavaScript-looking key",
                "current": False,
            },
        ]

    def load_edit_session(self):
        value = self.saved_yaml.strip().split(": ", 1)[-1]
        return ConfigEditSession(
            raw_yaml=self.saved_yaml,
            edit_state=deepcopy(self.edit_state_override or _edit_state(value)),
        )

    def apply_operation(self, raw_yaml, operation):
        self.operations.append(deepcopy(operation))
        next_yaml = f"value: operation-{len(self.operations)}\n"
        return ConfigEditApplyResult(
            raw_yaml=next_yaml,
            edit_state=_edit_state(f"operation-{len(self.operations)}"),
        )

    def save_raw_yaml(self, raw_yaml):
        self.saved_yaml = raw_yaml
        self.saved.append(raw_yaml)
        return "saved"

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
        name_field = create["apply"]["nameField"]
        name = values.get(name_field) or existing_name
        return {"name": name, "message": f"{create['output']['kind']} saved: {name}"}


def test_open_reuses_one_process_local_draft_and_does_not_return_raw_yaml():
    edit_service = _FakeEditService()
    drafts = ConfigDraftService(edit_service)

    first = drafts.open()
    second = drafts.open()

    assert first == second
    assert first.dirty is False
    assert first.base_revision == first.draft_revision
    assert first.edit_state["nodes"][0]["label"] == "Traffic"
    assert not hasattr(first, "raw_yaml")


@pytest.mark.parametrize(
    "operation",
    [
        {"op": "set", "path": ["value"], "value": "next"},
        {"op": "unset", "path": ["value"]},
        {"op": "add", "path": ["items"], "value": {"name": "next"}},
        {"op": "removeConfig", "path": ["items", "old"]},
        {
            "op": "renameConfig",
            "path": ["items", "old"],
            "newName": "next",
        },
    ],
)
def test_apply_routes_every_operation_through_config_processor(operation):
    edit_service = _FakeEditService()
    drafts = ConfigDraftService(edit_service)
    opened = drafts.open()

    updated = drafts.apply(opened.draft_revision, operation)

    assert edit_service.operations == [operation]
    assert updated.dirty is True
    assert updated.base_revision == opened.base_revision
    assert updated.draft_revision != opened.draft_revision


def test_mutation_rejects_a_stale_draft_revision_without_applying():
    edit_service = _FakeEditService()
    drafts = ConfigDraftService(edit_service)
    opened = drafts.open()
    current = drafts.apply(
        opened.draft_revision,
        {"op": "set", "path": ["value"], "value": "next"},
    )

    with pytest.raises(ConfigDraftConflict) as error:
        drafts.apply(
            opened.draft_revision,
            {"op": "set", "path": ["value"], "value": "stale"},
        )

    assert error.value.current == current
    assert len(edit_service.operations) == 1


def test_save_detects_a_changed_persisted_base_and_preserves_the_draft():
    edit_service = _FakeEditService()
    drafts = ConfigDraftService(edit_service)
    opened = drafts.open()
    current = drafts.apply(
        opened.draft_revision,
        {"op": "set", "path": ["value"], "value": "draft"},
    )
    edit_service.saved_yaml = "value: changed-elsewhere\n"

    with pytest.raises(SavedConfigConflict) as error:
        drafts.save(current.draft_revision)

    assert error.value.current == current
    assert edit_service.saved == []


def test_save_and_discard_advance_the_base_from_persisted_configuration():
    edit_service = _FakeEditService()
    drafts = ConfigDraftService(edit_service)
    opened = drafts.open()
    edited = drafts.apply(
        opened.draft_revision,
        {"op": "set", "path": ["value"], "value": "draft"},
    )

    saved = drafts.save(edited.draft_revision)

    assert saved.dirty is False
    assert saved.base_revision == saved.draft_revision
    assert edit_service.saved == ["value: operation-1\n"]

    edited_again = drafts.apply(
        saved.draft_revision,
        {"op": "set", "path": ["value"], "value": "discard-me"},
    )
    discarded = drafts.discard(edited_again.draft_revision)
    assert discarded.dirty is False
    assert discarded.base_revision == saved.base_revision
    assert discarded.edit_state["nodes"][0]["children"][0]["value"] == "operation-1"


def test_external_inventory_is_resolved_from_the_exact_server_side_node():
    edit_service = _FakeEditService()
    drafts = ConfigDraftService(edit_service)
    opened = drafts.open()

    inventory = drafts.list_external_resources(
        opened.draft_revision,
        "edit:traffic.transform.configMap",
    )

    assert inventory.node_id == "edit:traffic.transform.configMap"
    assert inventory.display_name == "Transform ConfigMap"
    assert inventory.rows[0]["keys"] == ["main.js", "settings.json"]
    assert "values" not in inventory.rows[0]


def test_external_selection_re_lists_and_sets_both_configmap_and_key():
    edit_service = _FakeEditService()
    drafts = ConfigDraftService(edit_service)
    opened = drafts.open()

    selected = drafts.select_external_resource(
        expected_revision=opened.draft_revision,
        node_id="edit:traffic.transform.configMap",
        name="matching",
        kind="ConfigMap",
        group="",
        key="main.js",
        accept_warning=False,
    )

    assert selected.dirty is True
    assert edit_service.operations == [
        {
            "op": "set",
            "path": ["traffic", "transform", "configMap"],
            "value": "matching",
        },
        {
            "op": "set",
            "path": ["traffic", "transform", "path"],
            "value": "main.js",
        },
    ]


def test_manual_external_selection_validates_type_name_and_configmap_key():
    edit_service = _FakeEditService()
    drafts = ConfigDraftService(edit_service)
    opened = drafts.open()

    selected = drafts.select_external_resource(
        expected_revision=opened.draft_revision,
        node_id="edit:traffic.transform.configMap",
        name="not-visible-to-list",
        kind="ConfigMap",
        group="",
        key="transform.js",
        accept_warning=True,
        manual=True,
    )

    assert selected.dirty is True
    assert edit_service.operations == [
        {
            "op": "set",
            "path": ["traffic", "transform", "configMap"],
            "value": "not-visible-to-list",
        },
        {
            "op": "set",
            "path": ["traffic", "transform", "path"],
            "value": "transform.js",
        },
    ]

    with pytest.raises(ValueError, match="not allowed"):
        drafts.select_external_resource(
            expected_revision=selected.draft_revision,
            node_id="edit:traffic.transform.configMap",
            name="credentials",
            kind="Secret",
            group="",
            key="transform.js",
            accept_warning=True,
            manual=True,
        )

    with pytest.raises(ValueError, match="valid ConfigMap key"):
        drafts.select_external_resource(
            expected_revision=selected.draft_revision,
            node_id="edit:traffic.transform.configMap",
            name="not-visible-to-list",
            kind="ConfigMap",
            group="",
            key="../transform.js",
            accept_warning=True,
            manual=True,
        )


def test_external_warning_requires_explicit_acceptance():
    edit_service = _FakeEditService()
    drafts = ConfigDraftService(edit_service)
    opened = drafts.open()

    with pytest.raises(ExternalResourceSelectionWarning) as error:
        drafts.select_external_resource(
            expected_revision=opened.draft_revision,
            node_id="edit:traffic.transform.configMap",
            name="near-match",
            kind="ConfigMap",
            group="",
            key="README",
            accept_warning=False,
        )

    assert error.value.message == "no JavaScript-looking key"
    assert edit_service.operations == []


def test_secret_inventory_never_exposes_values_returned_by_a_dependency():
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
    state = _edit_state(
        "credentials",
        selection={"target": "scalarName"},
    )
    state["nodes"][0]["children"][0]["externalRef"]["displayName"] = "Credentials"
    edit_service.load_edit_session = lambda: ConfigEditSession(
        raw_yaml=edit_service.saved_yaml,
        edit_state=state,
    )
    drafts = ConfigDraftService(edit_service)
    opened = drafts.open()

    inventory = drafts.list_external_resources(
        opened.draft_revision,
        "edit:traffic.transform.configMap",
    )

    assert inventory.rows[0]["keys"] == ["password", "username"]
    assert "values" not in inventory.rows[0]


def test_external_configmap_details_map_descriptor_fields_without_raw_payload():
    edit_service = _FakeEditService()
    state = _edit_state("matching")
    external_ref = state["nodes"][0]["children"][0]["externalRef"]
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
    edit_service.edit_state_override = state
    drafts = ConfigDraftService(edit_service)
    opened = drafts.open()

    details = drafts.read_external_resource(
        opened.draft_revision,
        "edit:traffic.transform.configMap",
        "matching",
    )

    assert details.field_values == {
        "name": "matching",
        "contents": "export default () => true;",
    }
    assert details.hidden_fields == []
    assert details.keys == ["main.js", "settings.json"]
    assert not hasattr(details, "values")


def test_external_secret_details_never_return_sensitive_values():
    edit_service = _FakeEditService()
    state = _edit_state("credentials", selection={"target": "scalarName"})
    external_ref = state["nodes"][0]["children"][0]["externalRef"]
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
    edit_service.edit_state_override = state
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
    drafts = ConfigDraftService(edit_service)
    opened = drafts.open()

    details = drafts.read_external_resource(
        opened.draft_revision,
        "edit:traffic.transform.configMap",
        "credentials",
    )

    assert details.field_values == {
        "secretName": "credentials",
        "username": "admin",
    }
    assert details.hidden_fields == ["password"]
    assert "must-not-cross-the-api" not in repr(details)


def test_external_resource_save_validates_then_updates_resource_and_draft():
    edit_service = _FakeEditService()
    state = _edit_state("matching")
    external_ref = state["nodes"][0]["children"][0]["externalRef"]
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
    edit_service.edit_state_override = state
    drafts = ConfigDraftService(edit_service)
    opened = drafts.open()

    with pytest.raises(ExternalResourceFormInvalid) as error:
        drafts.save_external_resource(
            expected_revision=opened.draft_revision,
            node_id="edit:traffic.transform.configMap",
            values={"name": "Bad Name", "key": "..", "contents": ""},
            confirmations={},
            existing_name=None,
        )

    assert "valid Kubernetes DNS name" in str(error.value)
    assert edit_service.saved_external == []

    saved = drafts.save_external_resource(
        expected_revision=opened.draft_revision,
        node_id="edit:traffic.transform.configMap",
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
    assert saved.draft.dirty is True
    assert edit_service.saved_external[0][1] == {
        "name": "new-transform",
        "key": "main.js",
        "contents": "export default () => true;",
    }
    assert edit_service.operations == [
        {
            "op": "set",
            "path": ["traffic", "transform", "configMap"],
            "value": "new-transform",
        },
        {
            "op": "set",
            "path": ["traffic", "transform", "path"],
            "value": "main.js",
        },
    ]
