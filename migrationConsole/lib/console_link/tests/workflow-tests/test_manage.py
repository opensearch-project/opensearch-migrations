import base64
import copy
import time
from typing import Any
from urllib.parse import parse_qs, urlparse

import pytest
from unittest.mock import MagicMock, patch
from rich.markup import render
from textual.app import App, ComposeResult
from textual.widgets import Button, Input, Static, TextArea

from console_link.workflow.tree_utils import APPROVAL_TEMPLATE_NAME
from console_link.workflow.resource_tree import (
    ResourceGroup,
    ResourceNode,
    ResourceSection,
    _build_tree_from_raw,
    apply_config_overlays,
    format_config_diff_fields,
)
from console_link.workflow.manage_tree_schema import RESOURCE_SECTIONS
from console_link.workflow.tui.workflow_manage_app import (
    DISABLE_MOUSE_PIXELS_SEQUENCE,
    DISABLE_MOUSE_SEQUENCES,
    ENABLE_MOUSE_SEQUENCES,
    WorkflowTreeApp,
    copy_to_clipboard,
    PHASE_SUCCEEDED,
    PHASE_RUNNING,
    reset_terminal_mouse_reporting,
)
from console_link.workflow.tui.choice_select_modal import ChoiceSelectModal
from console_link.workflow.tui.config_edit_exit_modal import ConfigEditExitModal
from console_link.workflow.tui.confirm_modal import ConfirmModal
from console_link.workflow.tui.container_select_modal import ContainerSelectModal
from console_link.workflow.tui.external_resource_modal import (
    ExternalResourceFormModal,
    ExternalResourcePickerModal,
    PICKER_PAGE_SIZE,
)
from console_link.workflow.tui.structured_value_modal import StructuredValueModal
from console_link.workflow.tui.text_input_modal import TextInputModal
from console_link.workflow.tui.manage_injections import (
    WaiterInterface,
    PodScraperInterface,
    ArgoWorkflowInterface as ArgoService
)

import logging

logging.basicConfig(format='%(asctime)s [%(levelname)s] %(message)s', level=logging.INFO)
logger = logging.getLogger(__name__)


def get_clean_text_label(textual_node):
    """Extract plain text from a Rich-enabled Textual label."""
    label = textual_node.label
    return label.plain if hasattr(label, 'plain') else str(label)


def get_label_style(textual_node):
    label = textual_node.label
    return str(getattr(label, "style", ""))


def binding_descriptions(app, key):
    try:
        bindings = app._bindings.get_bindings_for_key(key)
    except Exception:
        return []
    return [binding.description for binding in bindings]


def find_tree_node_by_id(root, target_id):
    stack = list(root.children)
    while stack:
        node = stack.pop()
        if node.data and isinstance(node.data, dict) and node.data.get("id") == target_id:
            return node
        stack.extend(node.children)
    return None


@pytest.fixture
def mock_workflow_with_two_pods() -> dict[str, Any]:
    return {
        "metadata": {"name": "test-wf", "resourceVersion": "123"},
        "status": {
            "startedAt": "2023-01-01T00:00:00Z",
            "nodes": {
                "node-1": {"id": "node-1", "displayName": "step-1", "type": "Pod", "phase": "Failed",
                           "children": [], "startedAt": "2023-01-01T00:01:00Z",
                           "inputs": {"parameters": [{"name": "configContents", "value": "cfg"}]}},
                "node-2": {"id": "node-2", "displayName": "step-2", "type": "Pod", "phase": PHASE_RUNNING,
                           "children": [], "startedAt": "2023-01-01T00:02:00Z",
                           "inputs": {"parameters": [{"name": "configContents", "value": "cfg"}]}}
            }
        }
    }


@pytest.fixture
def mock_workflow_with_pod_and_suspend():
    return {
        "metadata": {"name": "test-wf", "resourceVersion": "123"},
        "status": {
            "startedAt": "2023-01-01T00:00:00Z",
            "nodes": {
                "node-1": {"id": "node-1", "displayName": "step-1", "type": "Pod",
                           "phase": PHASE_SUCCEEDED, "children": ["node-1-patch"],
                           "outputs": {"artifacts": [{"name": "metadataOutput"}]}},
                "node-1-patch": {"id": "node-1-patch", "displayName": "patchMetadataEvaluateOutput",
                                 "type": "Pod", "phase": PHASE_SUCCEEDED, "boundaryID": "node-1",
                                 "children": [],
                                 "inputs": {"parameters": [{"name": "resourceName", "value": "migration-0"}]}},
                "node-2": {"id": "node-2", "displayName": "suspend-1", "type": "Resource", "phase": PHASE_RUNNING,
                           "children": [],
                           "templateRef": {"name": "resource-management", "template": APPROVAL_TEMPLATE_NAME},
                           "inputs": {"parameters": [{"name": "resourceName", "value": "my-gate"}]}}
            }
        }
    }


FAILING_WAITER = WaiterInterface(
    trigger=lambda: pytest.fail("Waiter trigger called unexpectedly"),
    checker=lambda: pytest.fail("Waiter checker called unexpectedly"),
    reset=MagicMock()
)


def basic_auth_secret_external_ref():
    return {
        "kind": "secret",
        "purpose": "http-basic-auth",
        "displayName": "HTTP Basic Auth Secret",
        "description": "Kubernetes Secret containing 'username' and 'password' keys.",
        "k8s": {
            "resource": "Secret",
            "acceptedSecretTypes": ["kubernetes.io/basic-auth", "Opaque"],
            "requiredKeys": ["username", "password"],
        },
        "create": {
            "label": "HTTP Basic Auth Secret",
            "fields": [
                {
                    "name": "secretName",
                    "label": "Secret name",
                    "input": "name",
                    "required": True,
                    "validationIds": ["k8s-name"],
                },
                {
                    "name": "username",
                    "label": "Username",
                    "input": "text",
                    "required": True,
                    "validationIds": ["non-empty"],
                },
                {
                    "name": "password",
                    "label": "Password",
                    "input": "password",
                    "required": True,
                    "sensitive": True,
                    "validationIds": ["non-empty"],
                    "confirm": True,
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
        },
    }


def tls_secret_external_ref(purpose="proxy-server-tls", display_name="TLS Certificate Secret"):
    return {
        "kind": "secret",
        "purpose": purpose,
        "displayName": display_name,
        "description": "Kubernetes TLS Secret containing 'tls.crt' and 'tls.key' entries.",
        "k8s": {
            "resource": "Secret",
            "acceptedSecretTypes": ["kubernetes.io/tls", "Opaque"],
            "requiredKeys": ["tls.crt", "tls.key"],
            "contentValidationIds": ["tls-certificate-key-pair"],
        },
        "create": {
            "label": display_name,
            "fields": [
                {
                    "name": "secretName",
                    "label": "Secret name",
                    "input": "name",
                    "required": True,
                    "validationIds": ["k8s-name"],
                },
                {
                    "name": "certificate",
                    "label": "Certificate PEM",
                    "input": "multilineText",
                    "required": True,
                    "validationIds": ["non-empty", "pem-certificate-chain"],
                },
                {
                    "name": "privateKey",
                    "label": "Private key PEM",
                    "input": "secretMultilineText",
                    "required": True,
                    "sensitive": True,
                    "validationIds": ["non-empty", "pem-private-key"],
                    "confirm": True,
                },
            ],
            "output": {
                "kind": "Secret",
                "type": "kubernetes.io/tls",
                "stringData": {
                    "tls.crt": {"fromField": "certificate"},
                    "tls.key": {"fromField": "privateKey"},
                },
            },
            "apply": {"target": "scalarName", "nameField": "secretName"},
        },
    }


def log4j_config_map_external_ref():
    return {
        "kind": "configMap",
        "purpose": "log4j-config",
        "displayName": "Log4j2 ConfigMap",
        "description": "Kubernetes ConfigMap containing a Log4j2 properties file.",
        "k8s": {
            "resource": "ConfigMap",
            "requiredKeys": ["log4j2.properties"],
            "contentValidationIds": ["log4j-properties"],
        },
        "create": {
            "label": "Log4j2 ConfigMap",
            "fields": [
                {
                    "name": "configMapName",
                    "label": "ConfigMap name",
                    "input": "name",
                    "required": True,
                    "validationIds": ["k8s-name"],
                },
                {
                    "name": "properties",
                    "label": "log4j2.properties",
                    "input": "multilineText",
                    "required": True,
                    "validationIds": ["non-empty", "log4j-properties"],
                },
            ],
            "output": {
                "kind": "ConfigMap",
                "data": {
                    "log4j2.properties": {"fromField": "properties"},
                },
            },
            "apply": {"target": "scalarName", "nameField": "configMapName"},
        },
    }


def test_external_resource_picker_filters_and_paginates_rows():
    rows = [
        {
            "name": f"matching-{index}",
            "kind": "Secret",
            "type": "Opaque",
            "keys": ["username", "password"],
            "status": "matching",
            "message": "",
            "current": False,
        }
        for index in range(PICKER_PAGE_SIZE + 2)
    ]
    rows.extend([
        {
            "name": "current-but-missing",
            "kind": "Secret",
            "type": "Opaque",
            "keys": ["username"],
            "status": "warn",
            "message": "missing password",
            "current": True,
        },
        {
            "name": "other-missing",
            "kind": "Secret",
            "type": "Opaque",
            "keys": ["username"],
            "status": "warn",
            "message": "missing password",
            "current": False,
        },
    ])

    modal = ExternalResourcePickerModal(
        "Select Secret",
        rows,
        current_value="current-but-missing",
        external_ref=basic_auth_secret_external_ref(),
    )

    assert [row["name"] for row in modal._visible_rows()][-1] == "current-but-missing"
    assert "other-missing" not in [row["name"] for row in modal._visible_rows()]
    assert len(modal._displayed_entries()) == PICKER_PAGE_SIZE
    assert modal._page_count() == 2

    modal.page_index = 1
    assert [row["name"] for row in modal._displayed_rows()] == [
        "matching-9",
        "matching-10",
        "matching-11",
        "current-but-missing",
    ]

    modal.show_all = True
    modal.page_index = 1
    assert modal._displayed_rows()[-1]["name"] == "other-missing"


@pytest.mark.asyncio
async def test_external_resource_picker_auto_pages_at_row_boundaries():
    rows = [
        {
            "name": f"matching-{index}",
            "kind": "Secret",
            "type": "Opaque",
            "keys": ["username", "password"],
            "status": "matching",
            "message": "",
            "current": False,
        }
        for index in range(PICKER_PAGE_SIZE + 2)
    ]
    modal = ExternalResourcePickerModal(
        "Select Secret",
        rows,
        external_ref=basic_auth_secret_external_ref(),
        can_create=True,
    )

    class PickerHarness(App):
        def compose(self) -> ComposeResult:
            yield Static("")

        async def on_mount(self) -> None:
            self.push_screen(modal)

    app = PickerHarness()
    async with app.run_test() as pilot:
        assert await wait_until(pilot, lambda: isinstance(app.screen, ExternalResourcePickerModal))
        picker = app.screen
        picker.set_focus(picker.query_one(f"#row-{PICKER_PAGE_SIZE - 1}", Button))

        await pilot.press("down")
        assert picker.page_index == 1
        assert picker.focused.id == "row-0"
        assert picker._displayed_rows()[0]["name"] == f"matching-{PICKER_PAGE_SIZE - 2}"

        picker._focus_row(len(picker._displayed_entries()) - 1)
        await pilot.press("down")
        assert picker.page_index == 0
        assert picker.focused.id == "row-0"
        assert picker.query_one("#row-0", Button).label.plain == "+ Create New (c)"

        await pilot.press("up")
        assert picker.page_index == picker._page_count() - 1
        assert picker.focused.id == f"row-{len(picker._displayed_entries()) - 1}"


@pytest.mark.asyncio
async def test_external_resource_picker_action_buttons_click_focused_item_without_taking_focus():
    rows = [
        {
            "name": "first-creds",
            "kind": "Secret",
            "type": "kubernetes.io/basic-auth",
            "keys": ["username", "password"],
            "status": "matching",
            "message": "",
            "current": False,
        },
        {
            "name": "second-creds",
            "kind": "Secret",
            "type": "kubernetes.io/basic-auth",
            "keys": ["username", "password"],
            "status": "matching",
            "message": "",
            "current": False,
        },
    ]
    modal = ExternalResourcePickerModal(
        "Select Secret",
        rows,
        external_ref=basic_auth_secret_external_ref(),
    )
    result = {}

    class PickerHarness(App):
        def compose(self) -> ComposeResult:
            yield Static("")

        async def on_mount(self) -> None:
            self.push_screen(modal, lambda choice: result.update(choice or {}))

    app = PickerHarness()
    async with app.run_test() as pilot:
        assert await wait_until(pilot, lambda: isinstance(app.screen, ExternalResourcePickerModal))
        picker = app.screen
        assert picker.focused.id == "row-1"
        assert picker.query_one("#row-0", Button).label.plain == "Matching"
        assert picker.query_one("#select", Button).label.plain == "Select (<Enter>)"
        assert not picker.query_one("#select", Button).can_focus
        assert not picker.query_one("#update", Button).can_focus
        assert not picker.query_one("#cancel", Button).can_focus

        await pilot.press("down")
        assert picker.focused.id == "row-2"

        await pilot.click("#select")

    assert result["action"] == "select"
    assert result["row"]["name"] == "second-creds"


def test_external_resource_selection_builds_object_ref_values():
    row = {
        "name": "migrations-ca",
        "kind": "ClusterIssuer",
        "group": "cert-manager.io",
    }

    assert WorkflowTreeApp._external_resource_value_for_row(
        {"selection": {"target": "objectRef"}},
        row,
    ) == {
        "name": "migrations-ca",
        "kind": "ClusterIssuer",
        "group": "cert-manager.io",
    }
    assert WorkflowTreeApp._external_resource_value_for_row(
        {"selection": {"target": "scalarName"}},
        row,
    ) == "migrations-ca"


@pytest.mark.asyncio
async def test_choice_select_modal_renders_mouse_ok_enter_affordance():
    modal = ChoiceSelectModal(
        "Select mode",
        [
            {"label": "first", "value": "first"},
            {"label": "second", "value": "second"},
        ],
        current_value="first",
    )
    result = {}

    class ChoiceHarness(App):
        def compose(self) -> ComposeResult:
            yield Static("")

        async def on_mount(self) -> None:
            self.push_screen(modal, lambda value: result.update({"value": value}))

    app = ChoiceHarness()
    async with app.run_test() as pilot:
        assert await wait_until(pilot, lambda: isinstance(app.screen, ChoiceSelectModal))
        assert app.screen.query_one("#ok", Button).label.plain == "OK (<Enter>)"
        assert app.screen.query_one("#cancel", Button).label.plain == "Cancel (Esc)"
        assert not app.screen.query_one("#ok", Button).can_focus

        await pilot.press("down")
        await pilot.click("#ok")

    assert result == {"value": "second"}


@pytest.mark.asyncio
async def test_container_select_modal_renders_mouse_ok_enter_affordance():
    modal = ContainerSelectModal(["main", "sidecar"], "pod-a")
    result = {}

    class ContainerHarness(App):
        def compose(self) -> ComposeResult:
            yield Static("")

        async def on_mount(self) -> None:
            self.push_screen(modal, lambda value: result.update({"value": value}))

    app = ContainerHarness()
    async with app.run_test() as pilot:
        assert await wait_until(pilot, lambda: isinstance(app.screen, ContainerSelectModal))
        assert app.screen.query_one("#ok", Button).label.plain == "OK (<Enter>)"
        assert app.screen.query_one("#cancel", Button).label.plain == "Cancel (Esc)"
        assert not app.screen.query_one("#ok", Button).can_focus

        await pilot.press("down")
        await pilot.click("#ok")

    assert result == {"value": "sidecar"}


def edit_state_with_missing_basic_auth():
    return {
        "formatVersion": 1,
        "provenance": {"source": "pending-yaml", "lossy": False, "warnings": []},
        "nodes": [
            {
                "id": "edit:sourceClusters",
                "path": ["sourceClusters"],
                "label": "[REQ 1] Sources",
                "valueKind": "record",
                "description": "Source Elasticsearch or OpenSearch clusters to migrate from.",
                "status": "required",
                "statusCounts": {"required": 1},
                "children": [
                    {
                        "id": "edit:sourceClusters.legacy",
                        "path": ["sourceClusters", "legacy"],
                        "label": "[REQ 1] legacy",
                        "valueKind": "object",
                        "description": "Connection and snapshot configuration for a source cluster.",
                        "status": "required",
                        "statusCounts": {"required": 1},
                        "children": [
                            {
                                "id": "edit:sourceClusters.legacy.endpoint",
                                "path": ["sourceClusters", "legacy", "endpoint"],
                                "label": "[OK] endpoint: https://legacy.example.com:9200",
                                "valueKind": "scalar",
                                "presence": "optional",
                                "description": "HTTP(S) endpoint URL for the cluster.",
                                "status": "ok",
                                "statusCounts": {},
                            },
                            {
                                "id": "edit:sourceClusters.legacy.authConfig",
                                "path": ["sourceClusters", "legacy", "authConfig"],
                                "label": "[REQ 1] authConfig: < basic >",
                                "value": "basic",
                                "valueKind": "union",
                                "description": "Authentication configuration for connecting to the cluster.",
                                "status": "required",
                                "statusCounts": {"required": 1},
                                "variants": [
                                    {"label": "none", "value": "none"},
                                    {"label": "basic", "value": "basic"},
                                    {
                                        "label": "sigv4",
                                        "value": "sigv4",
                                        "description": "AWS SigV4 request signing authentication.",
                                    },
                                ],
                                "children": [
                                    {
                                        "id": "edit:sourceClusters.legacy.authConfig.basic.secretName",
                                        "path": [
                                            "sourceClusters", "legacy", "authConfig", "basic", "secretName"
                                        ],
                                        "label": "[REQ] secretName: <required>",
                                        "valueKind": "scalar",
                                        "description": "Name of a Kubernetes Secret containing credentials.",
                                        "required": True,
                                        "externalRef": basic_auth_secret_external_ref(),
                                        "status": "required",
                                        "statusCounts": {"required": 1},
                                        "diagnostics": [
                                            {
                                                "severity": "required",
                                                "message": "secretName is required.",
                                                "path": [
                                                    "sourceClusters", "legacy", "authConfig", "basic", "secretName"
                                                ],
                                            }
                                        ],
                                    }
                                ],
                            },
                        ],
                    }
                ],
            }
        ],
        "pendingSubmitChanges": [],
        "submittedRolloutChanges": [],
        "policyPreview": [],
        "validation": {"valid": False, "errors": ["secretName is required"]},
    }


def edit_state_with_no_auth():
    state = copy.deepcopy(edit_state_with_missing_basic_auth())
    source = state["nodes"][0]
    legacy = source["children"][0]
    auth = legacy["children"][1]
    source["label"] = "[OK] Sources"
    source["status"] = "ok"
    source["statusCounts"] = {}
    legacy["label"] = "[OK] legacy"
    legacy["status"] = "ok"
    legacy["statusCounts"] = {}
    auth["label"] = "[OK] authConfig: < none >"
    auth["value"] = "none"
    auth["status"] = "ok"
    auth["statusCounts"] = {}
    auth["children"] = []
    state["validation"] = {"valid": True, "errors": []}
    return state


def edit_state_with_sigv4_auth():
    state = copy.deepcopy(edit_state_with_missing_basic_auth())
    source = state["nodes"][0]
    legacy = source["children"][0]
    auth = legacy["children"][1]
    auth["label"] = "[REQ 1] authConfig: < sigv4 >"
    auth["value"] = "sigv4"
    auth["children"] = [
        {
            "id": "edit:sourceClusters.legacy.authConfig.sigv4.region",
            "path": ["sourceClusters", "legacy", "authConfig", "sigv4", "region"],
            "label": "[REQ] region: <required>",
            "valueKind": "scalar",
            "description": "AWS region for SigV4 request signing.",
            "required": True,
            "status": "required",
            "statusCounts": {"required": 1},
            "diagnostics": [
                {
                    "severity": "required",
                    "message": "region is required.",
                    "path": ["sourceClusters", "legacy", "authConfig", "sigv4", "region"],
                }
            ],
        }
    ]
    return state


def edit_state_with_basic_auth_secret(secret_name="source-creds"):
    state = copy.deepcopy(edit_state_with_missing_basic_auth())
    source = state["nodes"][0]
    legacy = source["children"][0]
    auth = legacy["children"][1]
    secret = auth["children"][0]
    source["label"] = "[OK] Sources"
    source["status"] = "ok"
    source["statusCounts"] = {}
    legacy["label"] = "[OK] legacy"
    legacy["status"] = "ok"
    legacy["statusCounts"] = {}
    auth["label"] = "[OK] authConfig: < basic >"
    auth["status"] = "ok"
    auth["statusCounts"] = {}
    secret["label"] = f"[OK] secretName: {secret_name}"
    secret["value"] = secret_name
    secret["status"] = "ok"
    secret["statusCounts"] = {}
    secret["diagnostics"] = []
    state["validation"] = {"valid": True, "errors": []}
    return state


def edit_state_with_proxy_logging_config(config_map_name=""):
    missing = not bool(config_map_name)
    return {
        "formatVersion": 1,
        "provenance": {"source": "pending-yaml", "lossy": False, "warnings": []},
        "nodes": [
            {
                "id": "edit:traffic",
                "path": ["traffic"],
                "label": "[REQ 1] Live Traffic Migration" if missing else "[OK] Live Traffic Migration",
                "valueKind": "object",
                "description": "Live traffic capture and replay resources.",
                "status": "required" if missing else "ok",
                "statusCounts": {"required": 1} if missing else {},
                "children": [
                    {
                        "id": "edit:traffic.proxies",
                        "path": ["traffic", "proxies"],
                        "label": "[REQ 1] Capture" if missing else "[OK] Capture",
                        "valueKind": "record",
                        "description": "Capture proxies.",
                        "status": "required" if missing else "ok",
                        "statusCounts": {"required": 1} if missing else {},
                        "children": [
                            {
                                "id": "edit:traffic.proxies.cap",
                                "path": ["traffic", "proxies", "cap"],
                                "label": "[REQ 1] cap" if missing else "[OK] cap",
                                "valueKind": "object",
                                "description": "Capture proxy.",
                                "status": "required" if missing else "ok",
                                "statusCounts": {"required": 1} if missing else {},
                                "children": [
                                    {
                                        "id": "edit:traffic.proxies.cap.proxyConfig",
                                        "path": ["traffic", "proxies", "cap", "proxyConfig"],
                                        "label": "[REQ 1] proxyConfig" if missing else "[OK] proxyConfig",
                                        "valueKind": "object",
                                        "description": "Capture proxy options.",
                                        "status": "required" if missing else "ok",
                                        "statusCounts": {"required": 1} if missing else {},
                                        "children": [
                                            {
                                                "id": "edit:traffic.proxies.cap.proxyConfig.loggingConfigurationOverrideConfigMap",
                                                "path": [
                                                    "traffic", "proxies", "cap", "proxyConfig",
                                                    "loggingConfigurationOverrideConfigMap"
                                                ],
                                                "label": (
                                                    f"[OK] loggingConfigurationOverrideConfigMap: {config_map_name}"
                                                    if config_map_name
                                                    else "[REQ] loggingConfigurationOverrideConfigMap: <required>"
                                                ),
                                                "value": config_map_name,
                                                "valueKind": "scalar",
                                                "description": "Name of a Kubernetes ConfigMap containing Log4j2 properties.",
                                                "required": True,
                                                "externalRef": log4j_config_map_external_ref(),
                                                "status": "ok" if config_map_name else "required",
                                                "statusCounts": {} if config_map_name else {"required": 1},
                                            },
                                        ],
                                    },
                                ],
                            },
                        ],
                    },
                ],
            }
        ],
        "pendingSubmitChanges": [],
        "submittedRolloutChanges": [],
        "policyPreview": [],
        "validation": {"valid": not missing, "errors": [] if not missing else ["logging config is required"]},
    }


def edit_state_with_proxy_tls_secret(secret_name=""):
    state = edit_state_with_proxy_logging_config("logging-config")
    traffic = state["nodes"][0]
    proxy_config = traffic["children"][0]["children"][0]["children"][0]
    tls_node = {
        "id": "edit:traffic.proxies.cap.proxyConfig.tls",
        "path": ["traffic", "proxies", "cap", "proxyConfig", "tls"],
        "label": "[REQ 1] tls: < existingSecret >" if not secret_name else "[OK] tls: < existingSecret >",
        "value": "existingSecret",
        "valueKind": "union",
        "description": "TLS certificate configuration for HTTPS termination at the proxy.",
        "presence": "optional",
        "status": "required" if not secret_name else "ok",
        "statusCounts": {"required": 1} if not secret_name else {},
        "variants": [
            {"label": "default", "value": "unset"},
            {"label": "existingSecret", "value": "existingSecret"},
            {"label": "certManager", "value": "certManager"},
            {"label": "plaintext", "value": "plaintext"},
        ],
        "children": [
            {
                "id": "edit:traffic.proxies.cap.proxyConfig.tls.secretName",
                "path": ["traffic", "proxies", "cap", "proxyConfig", "tls", "secretName"],
                "label": f"[OK] secretName: {secret_name}" if secret_name else "[REQ] secretName: <required>",
                "value": secret_name,
                "valueKind": "scalar",
                "description": "Name of an existing Kubernetes TLS secret containing 'tls.crt' and 'tls.key' entries.",
                "required": True,
                "externalRef": tls_secret_external_ref(),
                "status": "ok" if secret_name else "required",
                "statusCounts": {} if secret_name else {"required": 1},
            }
        ],
    }
    proxy_config["children"] = [tls_node]
    for node in [traffic, traffic["children"][0], traffic["children"][0]["children"][0], proxy_config]:
        node["label"] = node["label"].replace("[OK]", "[REQ 1]") if not secret_name else node["label"]
        node["status"] = "required" if not secret_name else "ok"
        node["statusCounts"] = {"required": 1} if not secret_name else {}
    state["validation"] = {"valid": bool(secret_name), "errors": [] if secret_name else ["secretName is required"]}
    return state


def edit_state_with_proxy_console_client_secret(secret_name=""):
    state = edit_state_with_proxy_tls_secret("proxy-tls")
    tls_node = state["nodes"][0]["children"][0]["children"][0]["children"][0]["children"][0]
    tls_node["children"].append({
        "id": "edit:traffic.proxies.cap.proxyConfig.tls.clientAuth",
        "path": ["traffic", "proxies", "cap", "proxyConfig", "tls", "clientAuth"],
        "label": "[OK] clientAuth: < enabled >",
        "value": "enabled",
        "valueKind": "union",
        "description": "Optional mutual TLS client-authentication configuration for the capture proxy listener.",
        "presence": "optional",
        "status": "ok",
        "statusCounts": {},
        "variants": [
            {"label": "disabled", "value": "disabled"},
            {"label": "enabled", "value": "enabled"},
        ],
        "children": [
            {
                "id": "edit:traffic.proxies.cap.proxyConfig.tls.clientAuth.trustedClientCaPem",
                "path": ["traffic", "proxies", "cap", "proxyConfig", "tls", "clientAuth", "trustedClientCaPem"],
                "label": "[OK] trustedClientCaPem: -----BEGIN CERTIFICATE-----...",
                "value": "-----BEGIN CERTIFICATE-----\nabc\n-----END CERTIFICATE-----\n",
                "valueKind": "scalar",
                "description": "Inline PEM trusted CA certificate used to verify client certificates accepted by the capture proxy.",
                "status": "ok",
                "statusCounts": {},
            },
            {
                "id": "edit:traffic.proxies.cap.proxyConfig.tls.clientAuth.consoleClientSecretName",
                "path": [
                    "traffic", "proxies", "cap", "proxyConfig", "tls", "clientAuth", "consoleClientSecretName"
                ],
                "label": f"[OK] consoleClientSecretName: {secret_name}" if secret_name
                else "[OK] consoleClientSecretName: <unset>",
                "value": secret_name,
                "valueKind": "scalar",
                "description": "Name of a Kubernetes TLS Secret containing the client certificate and private key "
                "that migration-console commands use when connecting to this mTLS-enabled proxy.",
                "presence": "optional",
                "externalRef": tls_secret_external_ref(
                    "proxy-console-client-tls",
                    "Proxy Client Certificate Secret"
                ),
                "status": "ok",
                "statusCounts": {},
            },
        ],
    })
    return state


def edit_state_with_editable_source_fields():
    return {
        "formatVersion": 1,
        "provenance": {"source": "pending-yaml", "lossy": False, "warnings": []},
        "nodes": [
            {
                "id": "edit:sourceClusters",
                "path": ["sourceClusters"],
                "label": "[CHG 1] Sources",
                "valueKind": "record",
                "description": "Source Elasticsearch or OpenSearch clusters to migrate from.",
                "status": "changed",
                "statusCounts": {"changed": 1},
                "children": [
                    {
                        "id": "edit:sourceClusters.legacy",
                        "path": ["sourceClusters", "legacy"],
                        "label": "[CHG 1] legacy",
                        "valueKind": "object",
                        "description": "Connection and snapshot configuration for a source cluster.",
                        "status": "changed",
                        "statusCounts": {"changed": 1},
                        "children": [
                            {
                                "id": "edit:sourceClusters.legacy.endpoint",
                                "path": ["sourceClusters", "legacy", "endpoint"],
                                "label": "[CHG 1] endpoint: https://new.example.com:9200",
                                "value": "https://new.example.com:9200",
                                "valueKind": "scalar",
                                "presence": "optional",
                                "description": "HTTP(S) endpoint URL for the cluster.",
                                "validation": {
                                    "pattern": (
                                        r"^https?:\/\/[^:\/\s]+(?::(?:[1-9]\d{0,3}|[1-5]\d{4}|"
                                        r"6[0-4]\d{3}|65[0-4]\d{2}|655[0-2]\d|6553[0-5]))?(?:\/)?$"
                                    ),
                                    "message": "Use an http:// or https:// endpoint with an optional port and trailing slash.",
                                },
                                "status": "changed",
                                "statusCounts": {"changed": 1},
                                "states": {
                                    "deployed": {
                                        "value": "https://old.example.com:9200",
                                        "status": "ok",
                                    },
                                    "currentWorkflow": {
                                        "value": "https://old.example.com:9200",
                                        "status": "ok",
                                    },
                                    "pendingSubmit": {
                                        "value": "https://new.example.com:9200",
                                        "status": "changed",
                                        "statusCounts": {"changed": 1},
                                    },
                                },
                            },
                            {
                                "id": "edit:sourceClusters.legacy.allowInsecure",
                                "path": ["sourceClusters", "legacy", "allowInsecure"],
                                "label": "[OK] allowInsecure: false",
                                "value": False,
                                "valueKind": "boolean",
                                "presence": "optional",
                                "description": "Disable TLS certificate verification.",
                                "status": "ok",
                                "statusCounts": {},
                            },
                        ],
                    },
                    {
                        "id": "edit:sourceClusters:add",
                        "path": ["sourceClusters"],
                        "label": "[OK] + Add source cluster",
                        "valueKind": "command",
                        "description": "Create a new source cluster entry in pending workflow YAML.",
                        "status": "ok",
                        "statusCounts": {},
                        "command": {"requiresName": True},
                    },
                ],
            }
        ],
        "pendingSubmitChanges": [],
        "submittedRolloutChanges": [],
        "policyPreview": [],
        "validation": {"valid": True, "errors": []},
    }


def edit_state_with_array_items(include_provisional_item=False):
    children = [
        {
            "id": "edit:roles.0",
            "path": ["roles", "0"],
            "label": "[OK] item 1: configured",
            "valueKind": "object",
            "description": "Kafka node role.",
            "status": "ok",
            "statusCounts": {},
            "collapsed": True,
            "children": [
                {
                    "id": "edit:roles.0.name",
                    "path": ["roles", "0", "name"],
                    "label": "[OK] name: broker",
                    "value": "broker",
                    "valueKind": "scalar",
                    "presence": "required",
                    "description": "Role name.",
                    "status": "ok",
                    "statusCounts": {},
                },
            ],
        },
    ]
    if include_provisional_item:
        children.append({
            "id": "edit:roles.1",
            "path": ["roles", "1"],
            "label": "[REQ 1] item 2: configured",
            "valueKind": "object",
            "description": "Kafka node role.",
            "status": "required",
            "statusCounts": {"required": 1},
            "collapsed": True,
            "children": [
                {
                    "id": "edit:roles.1.name",
                    "path": ["roles", "1", "name"],
                    "label": "[REQ] name: <required>",
                    "valueKind": "scalar",
                    "presence": "required",
                    "required": True,
                    "description": "Role name.",
                    "status": "required",
                    "statusCounts": {"required": 1},
                },
            ],
        })
    children.append({
        "id": "edit:roles:add",
        "path": ["roles"],
        "label": "[OK] + Add item",
        "valueKind": "command",
        "description": "Create a new array item in pending workflow YAML.",
        "status": "ok",
        "statusCounts": {},
        "command": {"requiresName": False},
    })
    return {
        "formatVersion": 1,
        "provenance": {"source": "pending-yaml", "lossy": False, "warnings": []},
        "nodes": [
            {
                "id": "edit:roles",
                "path": ["roles"],
                "label": f"[OK] roles: {'2 items' if include_provisional_item else '1 item'}",
                "valueKind": "array",
                "description": "Kafka node roles.",
                "status": "ok",
                "statusCounts": {},
                "children": children,
            },
        ],
        "pendingSubmitChanges": [],
        "submittedRolloutChanges": [],
        "policyPreview": [],
        "validation": {"valid": True, "errors": []},
    }


def edit_state_with_kafka_override_leaf():
    return {
        "formatVersion": 1,
        "provenance": {"source": "pending-yaml", "lossy": False, "warnings": []},
        "nodes": [
            {
                "id": "edit:kafkaClusterConfiguration",
                "path": ["kafkaClusterConfiguration"],
                "label": "[OK] Kafka Clusters",
                "valueKind": "record",
                "description": "Kafka cluster configurations.",
                "status": "ok",
                "statusCounts": {},
                "children": [
                    {
                        "id": "edit:kafkaClusterConfiguration.kafka",
                        "path": ["kafkaClusterConfiguration", "kafka"],
                        "label": "[OK] kafka",
                        "valueKind": "object",
                        "description": "Kafka cluster configuration.",
                        "status": "ok",
                        "statusCounts": {},
                        "children": [
                            {
                                "id": "edit:kafkaClusterConfiguration.kafka.autoCreate.clusterSpecOverrides",
                                "path": [
                                    "kafkaClusterConfiguration",
                                    "kafka",
                                    "autoCreate",
                                    "clusterSpecOverrides",
                                ],
                                "label": "[OK] clusterSpecOverrides: <unset>",
                                "valueKind": "object",
                                "presence": "optional",
                                "description": "Optional overrides merged into the generated Strimzi Kafka.spec.",
                                "status": "ok",
                                "statusCounts": {},
                            }
                        ],
                    }
                ],
            }
        ],
        "pendingSubmitChanges": [],
        "submittedRolloutChanges": [],
        "policyPreview": [],
        "validation": {"valid": True, "errors": []},
    }


def edit_state_with_unset_kafka_override_children():
    return {
        "formatVersion": 1,
        "provenance": {"source": "pending-yaml", "lossy": False, "warnings": []},
        "nodes": [
            {
                "id": "edit:kafkaClusterConfiguration",
                "path": ["kafkaClusterConfiguration"],
                "label": "Kafka Clusters",
                "valueKind": "record",
                "description": "Kafka cluster configurations.",
                "status": "ok",
                "statusCounts": {},
                "children": [
                    {
                        "id": "edit:kafkaClusterConfiguration.kafka",
                        "path": ["kafkaClusterConfiguration", "kafka"],
                        "label": "kafka",
                        "valueKind": "object",
                        "description": "Kafka cluster configuration.",
                        "status": "ok",
                        "statusCounts": {},
                        "children": [
                            {
                                "id": "edit:kafkaClusterConfiguration.kafka.autoCreate.clusterSpecOverrides",
                                "path": [
                                    "kafkaClusterConfiguration",
                                    "kafka",
                                    "autoCreate",
                                    "clusterSpecOverrides",
                                ],
                                "label": "clusterSpecOverrides: <unset>",
                                "valueKind": "object",
                                "presence": "optional",
                                "description": "Optional overrides merged into the generated Strimzi Kafka.spec.",
                                "status": "ok",
                                "statusCounts": {},
                                "children": [
                                    {
                                        "id": (
                                            "edit:kafkaClusterConfiguration.kafka.autoCreate"
                                            ".clusterSpecOverrides.kafka"
                                        ),
                                        "path": [
                                            "kafkaClusterConfiguration",
                                            "kafka",
                                            "autoCreate",
                                            "clusterSpecOverrides",
                                            "kafka",
                                        ],
                                        "label": "kafka: <unset>",
                                        "valueKind": "object",
                                        "presence": "optional",
                                        "description": "Kafka broker configuration.",
                                        "status": "ok",
                                        "statusCounts": {},
                                        "children": [
                                            {
                                                "id": (
                                                    "edit:kafkaClusterConfiguration.kafka.autoCreate"
                                                    ".clusterSpecOverrides.kafka.replicas"
                                                ),
                                                "path": [
                                                    "kafkaClusterConfiguration",
                                                    "kafka",
                                                    "autoCreate",
                                                    "clusterSpecOverrides",
                                                    "kafka",
                                                    "replicas",
                                                ],
                                                "label": "replicas: <unset>",
                                                "valueKind": "scalar",
                                                "valueType": "number",
                                                "presence": "optional",
                                                "description": "Broker replicas.",
                                                "status": "ok",
                                                "statusCounts": {},
                                            },
                                        ],
                                    },
                                ],
                            },
                        ],
                    },
                ],
            },
        ],
        "pendingSubmitChanges": [],
        "submittedRolloutChanges": [],
        "policyPreview": [],
        "validation": {"valid": True, "errors": []},
    }


def edit_state_with_workflow_config_kafka():
    return {
        "formatVersion": 1,
        "provenance": {"source": "pending-yaml", "lossy": False, "warnings": []},
        "nodes": [
            {
                "id": "edit:workflowConfiguration",
                "path": ["workflowConfiguration"],
                "label": "Workflow Configuration",
                "valueKind": "object",
                "description": "Shared workflow configuration.",
                "status": "ok",
                "statusCounts": {},
                "children": [
                    {
                        "id": "edit:kafkaClusterConfiguration",
                        "path": ["kafkaClusterConfiguration"],
                        "label": "Kafka Clusters",
                        "valueKind": "record",
                        "description": "Kafka cluster configurations.",
                        "status": "ok",
                        "statusCounts": {},
                        "children": [
                            {
                                "id": "edit:kafkaClusterConfiguration.kafka",
                                "path": ["kafkaClusterConfiguration", "kafka"],
                                "label": "kafka",
                                "valueKind": "object",
                                "description": "Kafka cluster configuration.",
                                "status": "ok",
                                "statusCounts": {},
                                "children": [
                                    {
                                        "id": "edit:kafkaClusterConfiguration.kafka.mode",
                                        "path": ["kafkaClusterConfiguration", "kafka", "mode"],
                                        "label": "mode: < autoCreate >",
                                        "value": "autoCreate",
                                        "valueKind": "union",
                                        "description": "Kafka cluster mode.",
                                        "status": "ok",
                                        "statusCounts": {},
                                        "variants": [{"label": "autoCreate", "value": "autoCreate"}],
                                    },
                                ],
                            },
                        ],
                    },
                ],
            },
        ],
        "pendingSubmitChanges": [],
        "submittedRolloutChanges": [],
        "policyPreview": [],
        "validation": {"valid": True, "errors": []},
    }


def edit_state_with_changed_capture_and_snapshot_migration():
    return {
        "formatVersion": 1,
        "provenance": {"source": "pending-yaml", "lossy": False, "warnings": []},
        "nodes": [
            {
                "id": "edit:snapshotMigration",
                "path": ["snapshotMigration"],
                "label": "Snapshot Migration",
                "valueKind": "object",
                "status": "changed",
                "statusCounts": {"changed": 1},
                "children": [
                    {
                        "id": "edit:snapshotMigrationConfigs",
                        "path": ["snapshotMigrationConfigs"],
                        "label": "Backfill",
                        "valueKind": "record",
                        "status": "changed",
                        "statusCounts": {"changed": 1},
                        "children": [
                            {
                                "id": "edit:snapshotMigrationConfigs.source-target",
                                "path": ["snapshotMigrationConfigs", "source -> target"],
                                "label": "snapshot migration: source -> target",
                                "valueKind": "object",
                                "status": "changed",
                                "statusCounts": {"changed": 1},
                                "children": [
                                    {
                                        "id": "edit:snapshotMigrationConfigs.source-target.fromSource",
                                        "path": ["snapshotMigrationConfigs", "source -> target", "fromSource"],
                                        "label": "fromSource: source",
                                        "valueKind": "scalar",
                                        "status": "changed",
                                        "statusCounts": {"changed": 1},
                                    },
                                ],
                            },
                        ],
                    },
                ],
            },
            {
                "id": "edit:traffic",
                "path": ["traffic"],
                "label": "Live Traffic Migration",
                "valueKind": "object",
                "status": "changed",
                "statusCounts": {"changed": 1},
                "children": [
                    {
                        "id": "edit:traffic.proxies",
                        "path": ["traffic", "proxies"],
                        "label": "Capture",
                        "valueKind": "record",
                        "status": "changed",
                        "statusCounts": {"changed": 1},
                        "children": [
                            {
                                "id": "edit:traffic.proxies.cap",
                                "path": ["traffic", "proxies", "cap"],
                                "label": "cap",
                                "valueKind": "object",
                                "status": "changed",
                                "statusCounts": {"changed": 1},
                                "children": [
                                    {
                                        "id": "edit:traffic.proxies.cap.proxyConfig",
                                        "path": ["traffic", "proxies", "cap", "proxyConfig"],
                                        "label": "proxyConfig",
                                        "valueKind": "object",
                                        "status": "changed",
                                        "statusCounts": {"changed": 1},
                                    },
                                ],
                            },
                        ],
                    },
                ],
            },
        ],
        "pendingSubmitChanges": [],
        "submittedRolloutChanges": [],
        "policyPreview": [],
        "validation": {"valid": True, "errors": []},
    }


def edit_state_with_capture_defaulted_kafka():
    return {
        "formatVersion": 1,
        "provenance": {"source": "pending-yaml", "lossy": False, "warnings": []},
        "nodes": [
            {
                "id": "edit:traffic",
                "path": ["traffic"],
                "label": "Live Traffic Migration",
                "valueKind": "object",
                "status": "ok",
                "children": [
                    {
                        "id": "edit:traffic.proxies",
                        "path": ["traffic", "proxies"],
                        "label": "Capture",
                        "valueKind": "record",
                        "status": "ok",
                        "children": [
                            {
                                "id": "edit:traffic.proxies.cap",
                                "path": ["traffic", "proxies", "cap"],
                                "label": "cap",
                                "valueKind": "object",
                                "status": "ok",
                                "children": [
                                    {
                                        "id": "edit:traffic.proxies.cap.source",
                                        "path": ["traffic", "proxies", "cap", "source"],
                                        "label": "source: source",
                                        "value": "source",
                                        "valueKind": "scalar",
                                        "status": "ok",
                                    },
                                    {
                                        "id": "edit:traffic.proxies.cap.kafka",
                                        "path": ["traffic", "proxies", "cap", "kafka"],
                                        "label": "kafka: default",
                                        "value": "default",
                                        "valueDefaulted": True,
                                        "valueKind": "scalar",
                                        "status": "ok",
                                    },
                                    {
                                        "id": "edit:traffic.proxies.cap.kafkaTopic",
                                        "path": ["traffic", "proxies", "cap", "kafkaTopic"],
                                        "label": "kafkaTopic: aa",
                                        "value": "aa",
                                        "valueKind": "scalar",
                                        "status": "ok",
                                    },
                                ],
                            },
                        ],
                    },
                ],
            },
        ],
        "validation": {"valid": True, "errors": []},
    }


def edit_state_with_field_visibility():
    return {
        "formatVersion": 1,
        "provenance": {"source": "pending-yaml", "lossy": False, "warnings": []},
        "nodes": [
            {
                "id": "edit:sourceClusters",
                "path": ["sourceClusters"],
                "label": "[REQ 1] Sources",
                "valueKind": "record",
                "description": "Source Elasticsearch or OpenSearch clusters to migrate from.",
                "status": "required",
                "statusCounts": {"required": 1},
                "children": [
                    {
                        "id": "edit:sourceClusters.legacy",
                        "path": ["sourceClusters", "legacy"],
                        "label": "[REQ 1] legacy",
                        "valueKind": "object",
                        "description": "Connection and snapshot configuration for a source cluster.",
                        "status": "required",
                        "statusCounts": {"required": 1},
                        "children": [
                            {
                                "id": "edit:sourceClusters.legacy.endpoint",
                                "path": ["sourceClusters", "legacy", "endpoint"],
                                "label": "[REQ] endpoint: <required>",
                                "valueKind": "scalar",
                                "description": "Required endpoint.",
                                "required": True,
                                "presence": "required",
                                "status": "required",
                                "statusCounts": {"required": 1},
                            },
                            {
                                "id": "edit:sourceClusters.legacy.allowInsecure",
                                "path": ["sourceClusters", "legacy", "allowInsecure"],
                                "label": "[OK] allowInsecure: false",
                                "value": False,
                                "valueAuthored": True,
                                "valueKind": "boolean",
                                "description": "Optional TLS verification toggle.",
                                "presence": "optional",
                                "status": "ok",
                                "statusCounts": {},
                            },
                            {
                                "id": "edit:sourceClusters.legacy.serviceType",
                                "path": ["sourceClusters", "legacy", "serviceType"],
                                "label": "[OK] serviceType: LoadBalancer",
                                "value": "LoadBalancer",
                                "valueDefaulted": True,
                                "valueKind": "scalar",
                                "description": "Expert setting for exposure.",
                                "presence": "optional",
                                "expert": True,
                                "status": "ok",
                                "statusCounts": {},
                            },
                            {
                                "id": "edit:sourceClusters.legacy.description",
                                "path": ["sourceClusters", "legacy", "description"],
                                "label": "[OK] description: <unset>",
                                "valueKind": "scalar",
                                "description": "Optional display description.",
                                "presence": "optional",
                                "status": "ok",
                                "statusCounts": {},
                            },
                            {
                                "id": "edit:sourceClusters.legacy.aliases",
                                "path": ["sourceClusters", "legacy", "aliases"],
                                "label": "[OK] aliases: 0 items",
                                "value": [],
                                "valueKind": "array",
                                "description": "Optional source aliases.",
                                "presence": "optional",
                                "status": "ok",
                                "statusCounts": {},
                                "children": [
                                    {
                                        "id": "edit:sourceClusters.legacy.aliases:add",
                                        "path": ["sourceClusters", "legacy", "aliases"],
                                        "label": "+ Add alias",
                                        "valueKind": "command",
                                        "description": "Add an alias.",
                                        "status": "ok",
                                    }
                                ],
                            },
                            {
                                "id": "edit:sourceClusters.legacy.expertAliases",
                                "path": ["sourceClusters", "legacy", "expertAliases"],
                                "label": "[OK] expertAliases: 0 items",
                                "value": [],
                                "valueKind": "array",
                                "description": "Expert source aliases.",
                                "presence": "optional",
                                "expert": True,
                                "status": "ok",
                                "statusCounts": {},
                                "children": [
                                    {
                                        "id": "edit:sourceClusters.legacy.expertAliases:add",
                                        "path": ["sourceClusters", "legacy", "expertAliases"],
                                        "label": "+ Add expert alias",
                                        "valueKind": "command",
                                        "description": "Add an expert alias.",
                                        "expert": True,
                                        "status": "ok",
                                    }
                                ],
                            },
                        ],
                    }
                ],
            }
        ],
        "pendingSubmitChanges": [],
        "submittedRolloutChanges": [],
        "policyPreview": [],
        "validation": {"valid": False, "errors": ["endpoint is required"]},
    }


def test_structured_value_modal_parses_yaml_objects_and_arrays():
    object_modal = StructuredValueModal("Edit object", expected_kind="object")
    array_modal = StructuredValueModal("Edit array", expected_kind="array")

    assert object_modal._parse_value("kafka:\n  replicas: 3\n") == {"kafka": {"replicas": 3}}
    assert object_modal._parse_value("") == {}
    assert array_modal._parse_value("- one\n- two\n") == ["one", "two"]
    assert array_modal._parse_value("") == []

    with pytest.raises(ValueError, match="YAML object"):
        object_modal._parse_value("- not\n- an\n- object\n")

    with pytest.raises(ValueError, match="YAML array"):
        array_modal._parse_value("key: value\n")


def test_text_input_modal_builds_regex101_url_with_multiline_samples():
    url = TextInputModal._regex101_url("GET|HEAD", ["GET", "HEAD", "POST"])
    parsed = urlparse(url)
    query = parse_qs(parsed.query)

    assert parsed.scheme == "https"
    assert parsed.netloc == "regex101.com"
    assert query["regex"] == ["GET|HEAD"]
    assert query["testString"] == ["GET\nHEAD\nPOST"]


def test_text_input_modal_regex101_help_markup_avoids_wrapped_url():
    url = TextInputModal._regex101_url("", ["GET", "HEAD", "POST"])
    markup = TextInputModal._regex_help_markup("Java regex used by the capture proxy.")

    rendered = render(markup)

    assert "Test (t)" in rendered.plain
    assert url not in rendered.plain


@pytest.mark.asyncio
async def test_text_input_modal_test_regex_opens_regex101_url():
    class TestApp(App):
        def compose(self) -> ComposeResult:
            yield Button("placeholder")

    app = TestApp()
    async with app.run_test() as pilot:
        modal = TextInputModal(
            "Edit regex",
            initial_value="GET|HEAD",
            regex_help={"testStrings": ["GET", "HEAD", "POST"]},
        )
        await app.push_screen(modal)
        app.open_url = MagicMock()

        await pilot.click("#test")

        app.open_url.assert_called_once()
        opened_url = app.open_url.call_args.args[0]
        parsed = urlparse(opened_url)
        query = parse_qs(parsed.query)
        assert parsed.netloc == "regex101.com"
        assert query["regex"] == ["GET|HEAD"]
        assert query["testString"] == ["GET\nHEAD\nPOST"]


def resource_sections_for_manage_tests():
    return [
        ResourceSection(
            name="Snapshot Migration",
            groups=[
                ResourceGroup(
                    plural="datasnapshots",
                    display_name="Snapshot",
                    resources=[
                        ResourceNode(
                            name="snapshot-a",
                            plural="datasnapshots",
                            phase="Completed",
                            depends_on=[],
                            spec={},
                            status={},
                        )
                    ],
                )
            ],
        )
    ]


@pytest.mark.asyncio
async def test_config_edit_loading_ignores_late_resource_refresh(mock_workflow_with_two_pods):
    """A resource refresh finishing after edit starts must not repaint Migration Status."""

    argo_service = MagicMock(spec=ArgoService(None, None))
    argo_service.get_workflow.return_value = ({"success": True}, mock_workflow_with_two_pods)

    pod_scraper = MagicMock(spec=PodScraperInterface(None, None, None))
    pod_scraper.fetch_pods_metadata.return_value = []

    app = WorkflowTreeApp(
        namespace="default",
        name="test-wf",
        argo_service=argo_service,
        pod_scraper=pod_scraper,
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0,
        resource_view=True,
    )
    app.action_refresh_workflow = lambda: None

    async with app.run_test() as pilot:
        tree = app.query_one("#workflow-tree")
        await pilot.pause()
        app._tree_state.rebuild(resource_sections_for_manage_tests(), {})
        assert get_clean_text_label(tree.root) == "Migration Status"
        assert tree.show_root is False
        assert tree.disabled is False

        app._show_config_edit_loading()
        app._handle_resource_data(resource_sections_for_manage_tests(), {}, force_reload=True)

        assert get_clean_text_label(tree.root) == "Migration Status"
        assert tree.show_root is False
        assert tree.disabled is True
        assert app.title == "Workflow Config Edit"
        assert "Loading configuration editor" in str(app.query_one("#edit-help").content)


def resource_sections_with_kafka_config():
    return [
        ResourceSection(
            name="Live Traffic Migration",
            groups=[
                ResourceGroup(
                    plural="kafkaclusters",
                    display_name="Buffer",
                    resources=[
                        ResourceNode(
                            name="default",
                            plural="kafkaclusters",
                            phase="Ready",
                            depends_on=[],
                            spec={"version": "3.6.0", "auth": {"type": "none"}},
                            status={},
                        )
                    ],
                )
            ],
        )
    ]


async def wait_until(pilot, predicate, timeout=5.0, interval=0.1):
    """
    Global utility to poll a condition within a Textual test.

    Args:
        pilot: The Textual Pilot instance.
        predicate: A callable that returns True when the condition is met.
        timeout: Maximum time to wait in seconds.
        interval: Time to sleep between polls.
    """
    end_time = time.time() + timeout
    while time.time() < end_time:
        if predicate():
            return True
        await pilot.pause(interval)
    return False


# --- Tests ---

def test_manage_tree_schema_orders_roots_like_workflow_config():
    """Top-level manage roots should track the authored config shape."""

    assert [section_name for section_name, _ in RESOURCE_SECTIONS] == [
        "Sources",
        "Targets",
        "Snapshot Migration",
        "Kafka Clusters",
        "Live Traffic Migration",
    ]


def test_source_config_diff_order_matches_edit_schema_order():
    """Status config diffs should follow the same field order as the edit schema."""

    sections = [
        ResourceSection(
            name="Workflow Configuration",
            groups=[ResourceGroup(plural="sourceconfigs", display_name="Sources")],
        )
    ]
    apply_config_overlays(
        sections,
        pending_console_config={
            "sources": [
                {
                    "refName": "source",
                    "clientConfig": {
                        "endpoint": "https://source.example.com:9200",
                        "version": "ES 7.10.2",
                        "allow_insecure": True,
                        "basic_auth": {"k8s_secret_name": "source-creds"},
                    },
                    "parameterProvenance": {
                        "endpoint": {"sourcePath": ["sourceClusters", "source", "endpoint"]},
                        "allow_insecure": {"sourcePath": ["sourceClusters", "source", "allowInsecure"]},
                        "version": {"sourcePath": ["sourceClusters", "source", "version"]},
                        "basic_auth.k8s_secret_name": {
                            "sourcePath": ["sourceClusters", "source", "authConfig", "basic", "secretName"]
                        },
                    },
                    "displayFields": ["endpoint", "allow_insecure", "version", "basic_auth.k8s_secret_name"],
                }
            ]
        },
    )

    source = sections[0].groups[0].resources[0]
    labels = [line.split(":", 1)[0] for line in format_config_diff_fields(source)]
    assert labels == ["endpoint", "allowInsecure", "version", "authConfig.basic.secretName"]


@pytest.mark.asyncio
async def test_waiter_loop_and_rediscovery(mock_workflow_with_two_pods):
    """Test the discovery, deletion, and re-discovery lifecycle via WaiterInterface."""

    env_state = {"workflow_exists": False}
    workflow = mock_workflow_with_two_pods
    del workflow["status"]["nodes"]["node-1"]["inputs"]
    del workflow["status"]["nodes"]["node-2"]["inputs"]
    argo_service = ArgoService(get_workflow=lambda name, namespace:
                               ({"success": True}, workflow)
                               if env_state["workflow_exists"] else ({"success": False, "error": "not found"}, {}),
                               approve_step=MagicMock())

    pod_scraper = MagicMock(spec=PodScraperInterface(None, None, None))
    pod_scraper.fetch_pods_metadata.return_value = []

    mock_waiter = WaiterInterface(
        trigger=MagicMock(),
        checker=lambda: env_state["workflow_exists"],
        reset=MagicMock()
    )

    app = WorkflowTreeApp(
        namespace="default",
        name="test-wf",
        argo_service=argo_service,
        pod_scraper=pod_scraper,
        workflow_waiter=mock_waiter,
        refresh_interval=1.0
    )

    async with app.run_test() as pilot:
        tree = app.query_one("#workflow-tree")
        await pilot.pause()
        await pilot.wait_for_scheduled_animations()
        assert "Waiting for Workflow" in get_clean_text_label(tree.root)

        logger.info("Waiting for workflow detection trigger to fire")
        assert await wait_until(pilot, lambda: mock_waiter.trigger.call_count == 1)

        logger.info("Confirmed that no workflow was found and the UI indicated so.")
        env_state["workflow_exists"] = True

        logger.info("Waiting for the newly disclosed workflow to appear")
        assert await wait_until(pilot, lambda: "Workflow Steps" in get_clean_text_label(tree.root))

        assert len(tree.root.children) == 2
        mock_waiter.reset.assert_called()

        logger.info("The UI had two nodes in it, will remove the workflow next.")

        env_state["workflow_exists"] = False
        mock_waiter.trigger.reset_mock()

        assert await wait_until(pilot, lambda: "Waiting for Workflow" in get_clean_text_label(tree.root))
        assert await wait_until(pilot, lambda: mock_waiter.trigger.call_count >= 1)


@pytest.mark.asyncio
async def test_resource_view_renders_resources_without_workflow():
    """Resource view should render deployed/configured resources even when no workflow exists."""

    class FakeConfigEditService:
        pass

    argo_service = MagicMock(spec=ArgoService(None, None))
    argo_service.get_workflow.return_value = ({"success": False, "error": "not found"}, {})

    pod_scraper = MagicMock(spec=PodScraperInterface(None, None, None))
    pod_scraper.fetch_pods_metadata.return_value = []

    mock_waiter = WaiterInterface(
        trigger=MagicMock(),
        checker=MagicMock(return_value=False),
        reset=MagicMock(),
    )

    app = WorkflowTreeApp(
        namespace="default",
        name="test-wf",
        argo_service=argo_service,
        pod_scraper=pod_scraper,
        workflow_waiter=mock_waiter,
        refresh_interval=100.0,
        resource_view=True,
        config_edit_service=FakeConfigEditService(),
    )

    with patch("console_link.workflow.resource_tree.build_resource_tree",
               return_value=resource_sections_with_kafka_config()):
        async with app.run_test() as pilot:
            tree = app.query_one("#workflow-tree")
            tree.focus()
            assert await wait_until(
                pilot,
                lambda: (
                    get_clean_text_label(tree.root) == "Migration Status"
                    and find_tree_node_by_id(tree.root, "resource:default") is not None
                ),
                timeout=5.0,
            )

            assert "Waiting for Workflow" not in get_clean_text_label(tree.root)
            assert "Values: All" in str(app.query_one("#pod-status").content)
            assert binding_descriptions(app, "v") == ["Value Mode"]

            await pilot.press("v")
            assert await wait_until(
                pilot,
                lambda: "Values: Deployed" in str(app.query_one("#pod-status").content),
            )
            mock_waiter.trigger.assert_not_called()


@pytest.mark.asyncio
async def test_resource_view_left_right_expand_and_collapse_on_launch():
    """Status mode should bind left/right to tree navigation before entering edit mode."""

    argo_service = MagicMock(spec=ArgoService(None, None))
    argo_service.get_workflow.return_value = ({"success": False, "error": "not found"}, {})

    pod_scraper = MagicMock(spec=PodScraperInterface(None, None, None))
    pod_scraper.fetch_pods_metadata.return_value = []

    app = WorkflowTreeApp(
        namespace="default",
        name="test-wf",
        argo_service=argo_service,
        pod_scraper=pod_scraper,
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0,
        resource_view=True,
        config_edit_service=object(),
    )

    with patch("console_link.workflow.resource_tree.build_resource_tree",
               return_value=resource_sections_with_kafka_config()):
        async with app.run_test() as pilot:
            tree = app.query_one("#workflow-tree")
            assert await wait_until(
                pilot,
                lambda: find_tree_node_by_id(tree.root, "group:Buffer") is not None,
                timeout=5.0,
            )

            buffer_node = find_tree_node_by_id(tree.root, "group:Buffer")
            assert buffer_node.is_expanded
            tree.move_cursor(buffer_node)

            await pilot.press("left")
            assert not buffer_node.is_expanded

            await pilot.press("right")
            assert buffer_node.is_expanded


@pytest.mark.asyncio
async def test_manage_toggles_mouse_reporting_for_text_selection(mock_workflow_with_two_pods):
    """The manage UI can temporarily release terminal mouse handling for text selection."""

    argo_service = MagicMock(spec=ArgoService(None, None))
    argo_service.get_workflow.return_value = ({"success": True}, mock_workflow_with_two_pods)

    pod_scraper = MagicMock(spec=PodScraperInterface(None, None, None))
    pod_scraper.fetch_pods_metadata.return_value = []

    app = WorkflowTreeApp(
        namespace="default",
        name="test-wf",
        argo_service=argo_service,
        pod_scraper=pod_scraper,
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0,
    )

    async with app.run_test() as pilot:
        tree = app.query_one("#workflow-tree")
        tree.focus()
        assert await wait_until(pilot, lambda: len(tree.root.children) > 0, timeout=5.0)

        disable_mouse = MagicMock()
        enable_mouse = MagicMock()
        enable_mouse_pixels = MagicMock()
        setattr(app._driver, "_mouse_pixels", True)

        with patch.object(app._driver, "_disable_mouse_support", disable_mouse, create=True), \
                patch.object(app._driver, "_enable_mouse_support", enable_mouse, create=True), \
                patch.object(app._driver, "_enable_mouse_pixels", enable_mouse_pixels, create=True):
            assert binding_descriptions(app, "m") == ["Mouse Off"]

            await pilot.press("m")
            assert await wait_until(
                pilot,
                lambda: app._mouse_input_enabled is False
                and binding_descriptions(app, "m") == ["Mouse On"],
            )
            disable_mouse.assert_called_once()
            enable_mouse.assert_not_called()

            await pilot.press("m")
            assert await wait_until(
                pilot,
                lambda: app._mouse_input_enabled is True
                and binding_descriptions(app, "m") == ["Mouse Off"],
            )
            enable_mouse.assert_called_once()
            enable_mouse_pixels.assert_called_once()


def test_mouse_reporting_falls_back_to_raw_escape_sequences():
    """Mouse reporting can be toggled even when a driver has no private helper methods."""

    class FakeDriver:
        def __init__(self):
            self.writes = []
            self.flushes = 0

        def write(self, value):
            self.writes.append(value)

        def flush(self):
            self.flushes += 1

    driver = FakeDriver()
    WorkflowTreeApp._write_mouse_reporting(driver, enabled=False)
    WorkflowTreeApp._write_mouse_reporting(driver, enabled=True)

    assert driver.writes == [DISABLE_MOUSE_SEQUENCES, ENABLE_MOUSE_SEQUENCES]
    assert driver.flushes == 2


def test_terminal_mouse_reporting_reset_writes_raw_disable_sequences():
    """The command shutdown guard always sends terminal mouse modes off."""

    class FakeOutput:
        def __init__(self):
            self.writes = []
            self.flushes = 0

        def write(self, value):
            self.writes.append(value)

        def flush(self):
            self.flushes += 1

    output = FakeOutput()
    reset_terminal_mouse_reporting(output)

    assert output.writes == [DISABLE_MOUSE_SEQUENCES]
    assert output.flushes == 1
    assert "\x1b[?1002l" in DISABLE_MOUSE_SEQUENCES


def test_mouse_reporting_private_disable_also_releases_pixel_mode():
    """Pixel mouse reporting is disabled explicitly when a driver helper omits that mode."""

    class FakeDriver:
        def __init__(self):
            self.disable_mouse = MagicMock()
            self.writes = []
            self.flushes = 0

        def _disable_mouse_support(self):
            self.disable_mouse()

        def write(self, value):
            self.writes.append(value)

        def flush(self):
            self.flushes += 1

    driver = FakeDriver()
    WorkflowTreeApp._write_mouse_reporting(driver, enabled=False)

    driver.disable_mouse.assert_called_once()
    assert driver.writes == [DISABLE_MOUSE_PIXELS_SEQUENCE]
    assert driver.flushes == 1


def test_config_scalar_value_coercion_handles_number_nodes():
    number_node = {
        "path": ["traffic", "proxies", "cap", "proxyConfig", "listenPort"],
        "valueType": "number",
    }

    assert WorkflowTreeApp._coerce_config_scalar_value(number_node, "9201") == 9201
    assert WorkflowTreeApp._coerce_config_scalar_value(number_node, "1.5") == 1.5
    assert WorkflowTreeApp._coerce_config_scalar_value({"valueType": "string"}, "9201") == "9201"

    with pytest.raises(ValueError, match="traffic.proxies.cap.proxyConfig.listenPort must be a number"):
        WorkflowTreeApp._coerce_config_scalar_value(number_node, "not-a-number")


@pytest.mark.asyncio
async def test_functional_keybindings_execution(mock_workflow_with_pod_and_suspend):
    """Verify that injected K8sInterface and ArgoService methods are called by keys."""

    # Mock data for K8s pod discovery
    mock_pod = MagicMock()
    mock_pod.spec.init_containers = []
    mock_pod.spec.containers = [MagicMock(name="main")]

    k8s_interface = MagicMock(spec=PodScraperInterface(None, None, None))
    k8s_interface.fetch_pods_metadata.return_value = [
        {"metadata": {"name": "pod-1", "annotations": {"workflows.argoproj.io/node-id": "node-1"}}}
    ]
    k8s_interface.read_pod.return_value = mock_pod
    k8s_interface.read_pod_log.return_value = "pod logs here"

    argo_service = MagicMock(spec=ArgoService(None, None))
    argo_service.get_workflow.return_value = ({"success": True}, mock_workflow_with_pod_and_suspend)

    app = WorkflowTreeApp(
        namespace="default",
        name="test-wf",
        argo_service=argo_service,
        pod_scraper=k8s_interface,
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0
    )

    async with app.run_test() as pilot:
        tree = app.query_one("#workflow-tree")
        assert await wait_until(pilot, lambda: len(tree.root.children) > 0, timeout=5.0)
        tree.focus()

        # Navigate to Pod (node-1)
        await pilot.press("down")
        await pilot.pause()

        # Test Log Viewing (triggers read_pod and read_pod_log)
        # We check the app's call to _get_pod_logs indirectly via the scraper mocks
        with patch.object(app, "_show_logs_in_pager") as mock_pager_method:
            await pilot.press("l")
            await pilot.pause()
            mock_pager_method.assert_called_once()

        with patch(
            "console_link.workflow.tui.workflow_manage_app.read_managed_output"
        ) as mock_read_output, patch.object(app._logs, "show_output_texts_in_pager") as mock_output_pager:
            mock_read_output.return_value.content = "archived output"
            mock_read_output.return_value.ref = {
                "s3Key": "migration-outputs/snapshotmigration/migration-0/uid/metadataEvaluate/wf.log"
            }
            await pilot.press("o")
            await pilot.pause()
            mock_read_output.assert_called_once_with(
                "default", "snapshotmigration.migration-0", "metadataEvaluate"
            )
            mock_output_pager.assert_called_once()
            assert mock_output_pager.call_args.args[1] == [
                ("snapshotmigration.migration-0 / metadataEvaluate", "archived output")
            ]
            assert mock_output_pager.call_args.kwargs == {"clean": True}

        # Test Clipboard (triggers external utility)
        with patch("console_link.workflow.tui.workflow_manage_app.copy_to_clipboard", return_value=True) as mock_cp:
            await pilot.press("c")
            await pilot.pause()
            mock_cp.assert_called_once_with("pod-1")

        # Test Approval (triggers argo_service.approve_step)
        await pilot.press("down")  # Move to patch-output child
        await pilot.press("down")  # Move to Suspend (node-2)
        await pilot.pause()

        await pilot.press("a")
        await pilot.pause()
        # Check if the modal is now the active screen
        assert isinstance(app.screen, ConfirmModal)

        await pilot.press("y")
        await pilot.pause()

        argo_service.approve_step.assert_called_once()


@pytest.mark.asyncio
async def test_non_edit_enter_opens_approval_confirmation(mock_workflow_with_pod_and_suspend):
    """Enter should activate the selected approval row in the normal manage tree."""

    k8s_interface = MagicMock(spec=PodScraperInterface(None, None, None))
    k8s_interface.fetch_pods_metadata.return_value = []

    argo_service = MagicMock(spec=ArgoService(None, None))
    argo_service.get_workflow.return_value = ({"success": True}, mock_workflow_with_pod_and_suspend)

    app = WorkflowTreeApp(
        namespace="default",
        name="test-wf",
        argo_service=argo_service,
        pod_scraper=k8s_interface,
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0,
    )

    async with app.run_test() as pilot:
        tree = app.query_one("#workflow-tree")
        tree.focus()
        assert await wait_until(pilot, lambda: len(tree.root.children) > 0, timeout=5.0)

        for _ in range(3):
            await pilot.press("down")
        await pilot.pause()

        await pilot.press("enter")
        assert await wait_until(pilot, lambda: isinstance(app.screen, ConfirmModal))
        assert app.screen.query_one("#yes", Button).label.plain == "Yes (y)"
        assert app.screen.query_one("#no", Button).label.plain == "No (n)"
        await pilot.press("enter")
        await pilot.pause()
        argo_service.approve_step.assert_called_once()


@pytest.mark.asyncio
async def test_resource_view_edit_mode_shows_branch_diagnostics(mock_workflow_with_two_pods):
    """Pressing e in resource view renders the TS-provided edit tree and bottom help."""

    class FakeConfigEditService:
        def load_edit_state(self):
            return edit_state_with_missing_basic_auth()

    argo_service = ArgoService(
        get_workflow=lambda name, namespace: ({"success": True}, mock_workflow_with_two_pods),
        approve_step=MagicMock(),
    )
    pod_scraper = MagicMock(spec=PodScraperInterface(None, None, None))
    pod_scraper.fetch_pods_metadata.return_value = []

    sections = [
        ResourceSection(
            name="Snapshot Migration",
            groups=[
                ResourceGroup(
                    plural="datasnapshots",
                    display_name="Snapshot",
                    resources=[
                        ResourceNode(
                            name="snapshot-a",
                            plural="datasnapshots",
                            phase="Completed",
                            depends_on=[],
                            spec={},
                            status={},
                        )
                    ],
                )
            ],
        )
    ]

    app = WorkflowTreeApp(
        namespace="default",
        name="test-wf",
        argo_service=argo_service,
        pod_scraper=pod_scraper,
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0,
        resource_view=True,
        config_edit_service=FakeConfigEditService(),
    )

    with patch("console_link.workflow.resource_tree.build_resource_tree", return_value=sections):
        async with app.run_test() as pilot:
            tree = app.query_one("#workflow-tree")
            tree.focus()
            assert await wait_until(pilot, lambda: len(tree.root.children) > 0, timeout=5.0)

            await pilot.press("e")
            assert await wait_until(pilot, lambda: get_clean_text_label(tree.root) == "Workflow Config Edit")
            assert app.screen.focused is tree
            assert tree.cursor_node is not None
            assert (tree.cursor_node.data or {}).get("type") == "config-edit"

            source_group = find_tree_node_by_id(tree.root, "edit:sourceClusters")
            assert source_group is not None
            assert "Sources [REQ 1]" in get_clean_text_label(source_group)
            assert "yellow" in get_label_style(source_group)

            app._select_tree_node_by_id("edit:sourceClusters.legacy.authConfig")
            await pilot.pause()

            selected = get_clean_text_label(tree.cursor_node)
            help_text = app.query_one("#edit-help").content
            assert "authConfig: < basic > [REQ 1]" in selected
            assert "yellow" in get_label_style(tree.cursor_node)
            assert "secretName is required" in str(help_text)
            assert "Authentication configuration" in str(help_text)

            await pilot.press("escape")
            assert await wait_until(pilot, lambda: get_clean_text_label(tree.root) == "Migration Status")


@pytest.mark.asyncio
async def test_resource_view_edit_mode_preserves_expanded_resource_nodes():
    """Expanded status resources should stay expanded when projected into the edit tree."""

    class FakeConfigEditService:
        def load_edit_session(self):
            return {
                "raw_yaml": "snapshotMigrationConfigs: []\ntraffic:\n  proxies: {}\n",
                "edit_state": edit_state_with_changed_capture_and_snapshot_migration(),
            }

    argo_service = MagicMock(spec=ArgoService(None, None))
    argo_service.get_workflow.return_value = ({"success": False, "error": "not found"}, {})

    pod_scraper = MagicMock(spec=PodScraperInterface(None, None, None))
    pod_scraper.fetch_pods_metadata.return_value = []

    sections = [
        ResourceSection(
            name="Snapshot Migration",
            groups=[
                ResourceGroup(
                    plural="snapshotmigrations",
                    display_name="Backfill",
                    resources=[
                        ResourceNode(
                            name="snapshot migration: source -> target",
                            plural="snapshotmigrations",
                            phase="Pending Config",
                            depends_on=[],
                            spec={},
                            status={},
                            config_diff={"has_pending_submit_changes": True, "fields": [{"label": "fromSource"}]},
                        )
                    ],
                )
            ],
        ),
        ResourceSection(
            name="Live Traffic Migration",
            groups=[
                ResourceGroup(
                    plural="captureproxies",
                    display_name="Capture",
                    resources=[
                        ResourceNode(
                            name="cap",
                            plural="captureproxies",
                            phase="Pending Config",
                            depends_on=[],
                            spec={},
                            status={},
                            config_diff={"has_pending_submit_changes": True, "fields": [{"label": "listenPort"}]},
                        )
                    ],
                )
            ],
        ),
    ]

    app = WorkflowTreeApp(
        namespace="default",
        name="migration",
        argo_service=argo_service,
        pod_scraper=pod_scraper,
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0,
        resource_view=True,
        config_edit_service=FakeConfigEditService(),
    )

    with patch("console_link.workflow.resource_tree.build_resource_tree", return_value=sections):
        async with app.run_test() as pilot:
            tree = app.query_one("#workflow-tree")
            tree.focus()
            assert await wait_until(
                pilot,
                lambda: (
                    find_tree_node_by_id(tree.root, "resource:snapshot migration: source -> target") is not None
                    and find_tree_node_by_id(tree.root, "resource:cap") is not None
                ),
                timeout=5.0,
            )
            assert find_tree_node_by_id(tree.root, "resource:snapshot migration: source -> target").is_expanded
            assert find_tree_node_by_id(tree.root, "resource:cap").is_expanded

            await pilot.press("e")
            assert await wait_until(pilot, lambda: get_clean_text_label(tree.root) == "Workflow Config Edit")

            assert find_tree_node_by_id(tree.root, "edit:snapshotMigrationConfigs.source-target").is_expanded
            assert find_tree_node_by_id(tree.root, "edit:traffic.proxies.cap").is_expanded


@pytest.mark.asyncio
async def test_resource_view_edit_mode_applies_variant_and_saves(mock_workflow_with_two_pods):
    """Edit mode applies committed UI operations to draft YAML and saves on s or Ctrl+s."""

    class FakeConfigEditService:
        def __init__(self):
            self.apply_calls = []
            self.validate_calls = []
            self.saved_yaml = []

        def load_edit_session(self):
            return {
                "raw_yaml": "initial-yaml",
                "edit_state": edit_state_with_missing_basic_auth(),
            }

        def apply_operation(self, raw_yaml, operation):
            self.apply_calls.append((raw_yaml, operation))
            return {
                "raw_yaml": "updated-yaml",
                "edit_state": edit_state_with_sigv4_auth(),
            }

        def save_raw_yaml(self, raw_yaml):
            self.saved_yaml.append(raw_yaml)
            return "Configuration saved"

    service = FakeConfigEditService()
    argo_service = ArgoService(
        get_workflow=lambda name, namespace: ({"success": True}, mock_workflow_with_two_pods),
        approve_step=MagicMock(),
    )
    pod_scraper = MagicMock(spec=PodScraperInterface(None, None, None))
    pod_scraper.fetch_pods_metadata.return_value = []
    sections = [
        ResourceSection(
            name="Snapshot Migration",
            groups=[
                ResourceGroup(
                    plural="datasnapshots",
                    display_name="Snapshot",
                    resources=[
                        ResourceNode(
                            name="snapshot-a",
                            plural="datasnapshots",
                            phase="Completed",
                            depends_on=[],
                            spec={},
                            status={},
                        )
                    ],
                )
            ],
        )
    ]

    app = WorkflowTreeApp(
        namespace="default",
        name="test-wf",
        argo_service=argo_service,
        pod_scraper=pod_scraper,
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0,
        resource_view=True,
        config_edit_service=service,
    )

    with patch("console_link.workflow.resource_tree.build_resource_tree", return_value=sections):
        async with app.run_test() as pilot:
            tree = app.query_one("#workflow-tree")
            tree.focus()
            assert await wait_until(pilot, lambda: len(tree.root.children) > 0, timeout=5.0)

            await pilot.press("e")
            assert await wait_until(pilot, lambda: get_clean_text_label(tree.root) == "Workflow Config Edit")

            app._select_tree_node_by_id("edit:sourceClusters.legacy.authConfig")
            await pilot.pause()
            app._update_dynamic_bindings()

            assert binding_descriptions(app, "s") == ["Save"]
            assert "Next Option" not in binding_descriptions(app, "right")

            await pilot.press("left")
            await pilot.pause()
            assert service.apply_calls == []
            assert not tree.cursor_node.is_expanded

            await pilot.press("right")
            await pilot.pause()
            assert service.apply_calls == []
            assert tree.cursor_node.is_expanded

            await pilot.press("enter")
            assert await wait_until(pilot, lambda: isinstance(app.screen, ChoiceSelectModal))
            assert "Authentication configuration" in str(app.screen.query_one("#documentation").content)
            assert app.screen.query_one("#cancel", Button).label.plain == "Cancel (Esc)"
            await pilot.press("right")
            assert "AWS SigV4 request signing" in str(app.screen.query_one("#choice-doc").content)
            await pilot.press("left")
            assert "AWS SigV4 request signing" not in str(app.screen.query_one("#choice-doc").content)
            await pilot.press("right")
            await pilot.press("enter")
            assert await wait_until(pilot, lambda: len(service.apply_calls) == 1)
            assert service.apply_calls[0] == (
                "initial-yaml",
                {
                    "op": "set",
                    "path": ["sourceClusters", "legacy", "authConfig"],
                    "value": "sigv4",
                },
            )
            assert await wait_until(pilot, lambda: isinstance(app.screen, TextInputModal))
            assert "Edit sourceClusters.legacy.authConfig.sigv4.region" in str(app.screen.query_one("#prompt").content)
            assert app.screen.query_one("#save", Button).label.plain == "Save (<Enter>)"
            assert app.screen.query_one("#cancel", Button).label.plain == "Cancel (Esc)"
            assert isinstance(app.screen.focused, Input)
            await pilot.press("right")
            assert isinstance(app.screen.focused, Input)
            await pilot.press("escape")
            assert await wait_until(
                pilot,
                lambda: get_clean_text_label(tree.cursor_node) == "region: <required> [REQ 1]",
            )

            await pilot.press("s")
            assert await wait_until(pilot, lambda: service.saved_yaml == ["updated-yaml"])

            await pilot.press("ctrl+s")
            assert await wait_until(pilot, lambda: service.saved_yaml == ["updated-yaml", "updated-yaml"])


@pytest.mark.asyncio
async def test_resource_view_edit_mode_variant_opens_single_required_child_picker(mock_workflow_with_two_pods):
    """Selecting a variant drills into its one required child without hard-coding the auth path."""

    class FakeConfigEditService:
        def __init__(self):
            self.apply_calls = []

        def load_edit_session(self):
            return {
                "raw_yaml": "initial-yaml",
                "edit_state": edit_state_with_no_auth(),
            }

        def apply_operation(self, raw_yaml, operation):
            self.apply_calls.append((raw_yaml, operation))
            return {
                "raw_yaml": "updated-yaml",
                "edit_state": edit_state_with_missing_basic_auth(),
            }

        def list_external_resources(self, external_ref, current_value=None):
            return [{
                "name": "source-creds",
                "kind": "Secret",
                "type": "kubernetes.io/basic-auth",
                "keys": ["username", "password"],
                "status": "matching",
                "message": "",
                "current": False,
            }]

    service = FakeConfigEditService()
    argo_service = ArgoService(
        get_workflow=lambda name, namespace: ({"success": True}, mock_workflow_with_two_pods),
        approve_step=MagicMock(),
    )
    pod_scraper = MagicMock(spec=PodScraperInterface(None, None, None))
    pod_scraper.fetch_pods_metadata.return_value = []

    app = WorkflowTreeApp(
        namespace="default",
        name="test-wf",
        argo_service=argo_service,
        pod_scraper=pod_scraper,
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0,
        resource_view=True,
        config_edit_service=service,
    )

    with patch("console_link.workflow.resource_tree.build_resource_tree",
               return_value=resource_sections_for_manage_tests()):
        async with app.run_test() as pilot:
            tree = app.query_one("#workflow-tree")
            tree.focus()
            assert await wait_until(pilot, lambda: len(tree.root.children) > 0, timeout=5.0)

            await pilot.press("e")
            assert await wait_until(pilot, lambda: get_clean_text_label(tree.root) == "Workflow Config Edit")
            app._select_tree_node_by_id("edit:sourceClusters.legacy.authConfig")
            app._update_dynamic_bindings()

            await pilot.press("enter")
            assert await wait_until(pilot, lambda: isinstance(app.screen, ChoiceSelectModal))
            await pilot.press("down")
            await pilot.press("enter")

            assert await wait_until(pilot, lambda: len(service.apply_calls) == 1)
            assert service.apply_calls[0][1] == {
                "op": "set",
                "path": ["sourceClusters", "legacy", "authConfig"],
                "value": "basic",
            }
            assert await wait_until(pilot, lambda: isinstance(app.screen, ExternalResourcePickerModal))
            assert get_clean_text_label(tree.cursor_node) == "secretName: <required> [REQ 1]"


@pytest.mark.asyncio
async def test_resource_view_edit_mode_external_secret_picker_creates_and_applies(mock_workflow_with_two_pods):
    """External Secret refs open a picker, bind c to create, and apply the created Secret name."""

    class FakeConfigEditService:
        def __init__(self):
            self.apply_calls = []
            self.saved_external = []

        def load_edit_session(self):
            return {
                "raw_yaml": "initial-yaml",
                "edit_state": edit_state_with_missing_basic_auth(),
            }

        def list_external_resources(self, external_ref, current_value=None):
            return [
                {
                    "name": "source-creds",
                    "kind": "Secret",
                    "type": "kubernetes.io/basic-auth",
                    "keys": ["username", "password"],
                    "status": "matching",
                    "message": "",
                    "current": False,
                },
                {
                    "name": "admin-creds",
                    "kind": "Secret",
                    "type": "Opaque",
                    "keys": ["username"],
                    "status": "warn",
                    "message": "missing password",
                    "current": False,
                },
            ]

        def save_external_resource(self, external_ref, values, existing_name=None):
            self.saved_external.append((external_ref["purpose"], values, existing_name))
            return {"name": values["secretName"], "message": f"Secret created: {values['secretName']}"}

        def apply_operation(self, raw_yaml, operation):
            self.apply_calls.append((raw_yaml, operation))
            return {
                "raw_yaml": "updated-yaml",
                "edit_state": edit_state_with_basic_auth_secret(operation["value"]),
            }

    service = FakeConfigEditService()
    argo_service = ArgoService(
        get_workflow=lambda name, namespace: ({"success": True}, mock_workflow_with_two_pods),
        approve_step=MagicMock(),
    )
    pod_scraper = MagicMock(spec=PodScraperInterface(None, None, None))
    pod_scraper.fetch_pods_metadata.return_value = []

    app = WorkflowTreeApp(
        namespace="default",
        name="test-wf",
        argo_service=argo_service,
        pod_scraper=pod_scraper,
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0,
        resource_view=True,
        config_edit_service=service,
    )

    with patch("console_link.workflow.resource_tree.build_resource_tree",
               return_value=resource_sections_for_manage_tests()):
        async with app.run_test() as pilot:
            tree = app.query_one("#workflow-tree")
            tree.focus()
            assert await wait_until(pilot, lambda: len(tree.root.children) > 0, timeout=5.0)

            await pilot.press("e")
            assert await wait_until(pilot, lambda: get_clean_text_label(tree.root) == "Workflow Config Edit")
            app._select_tree_node_by_id("edit:sourceClusters.legacy.authConfig.basic.secretName")
            app._update_dynamic_bindings()

            await pilot.press("enter")
            assert await wait_until(pilot, lambda: isinstance(app.screen, ExternalResourcePickerModal))
            assert "Required Keys: username, password" in str(app.screen.query_one("#requirement").content)
            assert app.screen.query_one("#select", Button).label.plain == "Select (<Enter>)"
            assert app.screen.focused.id == "row-2"
            await pilot.press("right")
            assert app.screen.focused.id == "row-2"
            await pilot.press("left")
            assert app.screen.focused.id == "row-2"
            row_labels = [
                button.label.plain if hasattr(button.label, "plain") else str(button.label)
                for button in app.screen.query(Button)
                if button.id and button.id.startswith("row-") and button.display
            ]
            assert row_labels == [
                "+ Create New (c)",
                "Matching",
                "  source-creds",
                "▶ Non-Matching Secrets",
            ]
            assert "Opaque" not in " ".join(row_labels)
            assert "missing password" not in " ".join(row_labels)
            assert not app.screen.query_one("#row-doc").display

            app.screen.set_focus(app.screen.query_one("#row-3", Button))
            await pilot.press("right")
            assert await wait_until(
                pilot,
                lambda: any(
                    (button.label.plain if hasattr(button.label, "plain") else str(button.label)) == "  admin-creds (missing password)"
                    for button in app.screen.query(Button)
                    if button.id and button.id.startswith("row-") and button.display
                ),
            )
            assert app.screen.query_one("#row-3", Button).label.plain == "▼ Non-Matching Secrets"
            await pilot.press("left")
            assert app.screen.query_one("#row-3", Button).label.plain == "▶ Non-Matching Secrets"
            await pilot.press("right")
            app.screen.set_focus(app.screen.query_one("#row-4", Button))
            app.screen._update_row_doc()
            assert "Missing keys: password" in str(app.screen.query_one("#row-doc").content)

            await pilot.press("c")
            assert await wait_until(pilot, lambda: isinstance(app.screen, ExternalResourceFormModal))
            assert app.screen.query_one("#save", Button).label.plain == "Create (<Enter>)"
            assert app.screen.query_one("#cancel", Button).label.plain == "Cancel (Esc)"
            await pilot.press("escape")
            assert await wait_until(pilot, lambda: isinstance(app.screen, ExternalResourcePickerModal))

            await pilot.press("c")
            assert await wait_until(pilot, lambda: isinstance(app.screen, ExternalResourceFormModal))
            app.screen.query_one("#field-0").value = "new-creds"
            app.screen.query_one("#field-1").value = "admin"
            app.screen.query_one("#field-2").value = "secret"
            app.screen.query_one("#field-2-confirm").value = "secret"
            await pilot.press("enter")

            assert await wait_until(pilot, lambda: len(service.apply_calls) == 1)
            assert service.saved_external == [(
                "http-basic-auth",
                {"secretName": "new-creds", "username": "admin", "password": "secret"},
                None,
            )]
            assert service.apply_calls[0] == (
                "initial-yaml",
                {
                    "op": "set",
                    "path": ["sourceClusters", "legacy", "authConfig", "basic", "secretName"],
                    "value": "new-creds",
                },
            )


@pytest.mark.asyncio
async def test_resource_view_edit_mode_left_from_external_ref_leaf_moves_to_parent_without_dialog(
        mock_workflow_with_two_pods):
    """Left arrow should navigate up from edit leaves without activating the parent."""

    class FakeConfigEditService:
        def __init__(self):
            self.apply_calls = []
            self.list_calls = 0

        def load_edit_session(self):
            return {
                "raw_yaml": "initial-yaml",
                "edit_state": edit_state_with_basic_auth_secret("source-creds"),
            }

        def list_external_resources(self, external_ref, current_value=None):
            self.list_calls += 1
            return []

        def apply_operation(self, raw_yaml, operation):
            self.apply_calls.append((raw_yaml, operation))
            return {
                "raw_yaml": "updated-yaml",
                "edit_state": edit_state_with_basic_auth_secret(operation["value"]),
            }

    service = FakeConfigEditService()
    argo_service = ArgoService(
        get_workflow=lambda name, namespace: ({"success": True}, mock_workflow_with_two_pods),
        approve_step=MagicMock(),
    )
    pod_scraper = MagicMock(spec=PodScraperInterface(None, None, None))
    pod_scraper.fetch_pods_metadata.return_value = []

    app = WorkflowTreeApp(
        namespace="default",
        name="test-wf",
        argo_service=argo_service,
        pod_scraper=pod_scraper,
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0,
        resource_view=True,
        config_edit_service=service,
    )

    with patch("console_link.workflow.resource_tree.build_resource_tree",
               return_value=resource_sections_for_manage_tests()):
        async with app.run_test() as pilot:
            tree = app.query_one("#workflow-tree")
            tree.focus()
            assert await wait_until(pilot, lambda: len(tree.root.children) > 0, timeout=5.0)

            await pilot.press("e")
            assert await wait_until(pilot, lambda: get_clean_text_label(tree.root) == "Workflow Config Edit")
            app._select_tree_node_by_id("edit:sourceClusters.legacy.authConfig.basic.secretName")
            app._update_dynamic_bindings()

            await pilot.press("left")
            await pilot.pause()

            assert tree.cursor_node.data["id"] == "edit:sourceClusters.legacy.authConfig"
            assert not isinstance(app.screen, (ChoiceSelectModal, ExternalResourcePickerModal))
            assert service.list_calls == 0
            assert service.apply_calls == []


@pytest.mark.asyncio
async def test_resource_view_edit_mode_external_secret_update_hides_password(mock_workflow_with_two_pods):
    """Picker update reads non-sensitive values and preserves hidden passwords."""

    class FakeConfigEditService:
        def __init__(self):
            self.apply_calls = []
            self.saved_external = []

        def load_edit_session(self):
            return {
                "raw_yaml": "initial-yaml",
                "edit_state": edit_state_with_basic_auth_secret("source-creds"),
            }

        def list_external_resources(self, external_ref, current_value=None):
            return [{
                "name": "source-creds",
                "kind": "Secret",
                "type": "kubernetes.io/basic-auth",
                "keys": ["username", "password"],
                "status": "matching",
                "message": "",
                "current": True,
            }]

        def read_external_resource(self, external_ref, name):
            return {
                "kind": "Secret",
                "name": name,
                "type": "kubernetes.io/basic-auth",
                "keys": ["username", "password"],
                "values": {"username": "admin", "password": "super-secret"},
            }

        def save_external_resource(self, external_ref, values, existing_name=None):
            self.saved_external.append((values, existing_name))
            return {"name": existing_name or values["secretName"], "message": "Secret updated: source-creds"}

        def apply_operation(self, raw_yaml, operation):
            self.apply_calls.append((raw_yaml, operation))
            return {
                "raw_yaml": "updated-yaml",
                "edit_state": edit_state_with_basic_auth_secret(operation["value"]),
            }

    service = FakeConfigEditService()
    argo_service = ArgoService(
        get_workflow=lambda name, namespace: ({"success": True}, mock_workflow_with_two_pods),
        approve_step=MagicMock(),
    )
    pod_scraper = MagicMock(spec=PodScraperInterface(None, None, None))
    pod_scraper.fetch_pods_metadata.return_value = []

    app = WorkflowTreeApp(
        namespace="default",
        name="test-wf",
        argo_service=argo_service,
        pod_scraper=pod_scraper,
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0,
        resource_view=True,
        config_edit_service=service,
    )

    with patch("console_link.workflow.resource_tree.build_resource_tree",
               return_value=resource_sections_for_manage_tests()):
        async with app.run_test() as pilot:
            tree = app.query_one("#workflow-tree")
            tree.focus()
            assert await wait_until(pilot, lambda: len(tree.root.children) > 0, timeout=5.0)

            await pilot.press("e")
            assert await wait_until(pilot, lambda: get_clean_text_label(tree.root) == "Workflow Config Edit")
            app._select_tree_node_by_id("edit:sourceClusters.legacy.authConfig.basic.secretName")
            app._update_dynamic_bindings()

            await pilot.press("enter")
            assert await wait_until(pilot, lambda: isinstance(app.screen, ExternalResourcePickerModal))
            await pilot.press("u")
            assert await wait_until(pilot, lambda: isinstance(app.screen, ExternalResourceFormModal))
            assert app.screen.query_one("#save", Button).label.plain == "Update (<Enter>)"
            assert app.screen.query_one("#cancel", Button).label.plain == "Cancel (Esc)"
            await pilot.press("escape")
            assert await wait_until(pilot, lambda: isinstance(app.screen, ExternalResourcePickerModal))

            await pilot.press("u")
            assert await wait_until(pilot, lambda: isinstance(app.screen, ExternalResourceFormModal))
            assert app.screen.query_one("#field-1").value == "admin"
            assert app.screen.query_one("#field-2").value == ""
            assert "super-secret" not in str(app.screen.query_one("#field-2").value)
            assert isinstance(app.screen.focused, Input)
            await pilot.press("right")
            assert isinstance(app.screen.focused, Input)
            app.screen.query_one("#save").focus()
            await pilot.press("right")
            assert app.screen.focused.id == "cancel"
            await pilot.press("left")
            assert app.screen.focused.id == "save"
            app.screen.query_one("#field-1").value = "root"
            await pilot.press("enter")

            assert await wait_until(pilot, lambda: len(service.saved_external) == 1)
            assert service.saved_external == [(
                {"secretName": "source-creds", "username": "root", "password": ""},
                "source-creds",
            )]
            assert await wait_until(pilot, lambda: len(service.apply_calls) == 1)
            assert service.apply_calls[0][1]["value"] == "source-creds"


@pytest.mark.asyncio
async def test_resource_view_edit_mode_external_config_map_picker_creates_and_applies(mock_workflow_with_two_pods):
    """ConfigMap refs use the schema descriptor for picker requirements and multiline create forms."""

    class FakeConfigEditService:
        def __init__(self):
            self.apply_calls = []
            self.saved_external = []

        def load_edit_session(self):
            return {
                "raw_yaml": "initial-yaml",
                "edit_state": edit_state_with_proxy_logging_config(),
            }

        def list_external_resources(self, external_ref, current_value=None):
            return [
                {
                    "name": "valid-log4j",
                    "kind": "ConfigMap",
                    "keys": ["log4j2.properties"],
                    "status": "matching",
                    "message": "",
                    "current": False,
                },
                {
                    "name": "missing-key",
                    "kind": "ConfigMap",
                    "keys": ["application.properties"],
                    "status": "warn",
                    "message": "missing log4j2.properties",
                    "current": False,
                },
            ]

        def save_external_resource(self, external_ref, values, existing_name=None):
            self.saved_external.append((external_ref["purpose"], values, existing_name))
            return {"name": values["configMapName"], "message": f"ConfigMap created: {values['configMapName']}"}

        def apply_operation(self, raw_yaml, operation):
            self.apply_calls.append((raw_yaml, operation))
            return {
                "raw_yaml": "updated-yaml",
                "edit_state": edit_state_with_proxy_logging_config(operation["value"]),
            }

    service = FakeConfigEditService()
    argo_service = ArgoService(
        get_workflow=lambda name, namespace: ({"success": True}, mock_workflow_with_two_pods),
        approve_step=MagicMock(),
    )
    pod_scraper = MagicMock(spec=PodScraperInterface(None, None, None))
    pod_scraper.fetch_pods_metadata.return_value = []

    app = WorkflowTreeApp(
        namespace="default",
        name="test-wf",
        argo_service=argo_service,
        pod_scraper=pod_scraper,
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0,
        resource_view=True,
        config_edit_service=service,
    )

    with patch("console_link.workflow.resource_tree.build_resource_tree",
               return_value=resource_sections_for_manage_tests()):
        async with app.run_test() as pilot:
            tree = app.query_one("#workflow-tree")
            tree.focus()
            assert await wait_until(pilot, lambda: len(tree.root.children) > 0, timeout=5.0)

            await pilot.press("e")
            assert await wait_until(pilot, lambda: get_clean_text_label(tree.root) == "Workflow Config Edit")
            app._select_tree_node_by_id(
                "edit:traffic.proxies.cap.proxyConfig.loggingConfigurationOverrideConfigMap"
            )
            app._update_dynamic_bindings()

            await pilot.press("enter")
            assert await wait_until(pilot, lambda: isinstance(app.screen, ExternalResourcePickerModal))
            assert "Required Keys: log4j2.properties" in str(app.screen.query_one("#requirement").content)
            assert app.screen.query_one("#row-2", Button).label.plain == "  valid-log4j"

            await pilot.press("c")
            assert await wait_until(pilot, lambda: isinstance(app.screen, ExternalResourceFormModal))
            assert "Create Log4j2 ConfigMap" in str(app.screen.query_one("#title").content)
            assert isinstance(app.screen.query_one("#field-1"), TextArea)

            app.screen.query_one("#field-0", Input).value = "new-log4j"
            app.screen.query_one("#field-1", TextArea).load_text("not a properties file")
            app.screen.action_submit()
            assert "Log4j2 property assignment" in str(app.screen.query_one("#validation").content)

            app.screen.query_one("#field-1", TextArea).load_text("status = warn\nrootLogger.level = info\n")
            app.screen.action_submit()

            assert await wait_until(pilot, lambda: len(service.apply_calls) == 1)
            assert service.saved_external == [(
                "log4j-config",
                {"configMapName": "new-log4j", "properties": "status = warn\nrootLogger.level = info\n"},
                None,
            )]
            assert service.apply_calls[0][1] == {
                "op": "set",
                "path": [
                    "traffic", "proxies", "cap", "proxyConfig", "loggingConfigurationOverrideConfigMap"
                ],
                "value": "new-log4j",
            }


@pytest.mark.asyncio
async def test_resource_view_edit_mode_proxy_console_client_secret_picker_creates_and_applies(
        mock_workflow_with_two_pods):
    """Proxy clientAuth console Secret refs use TLS Secret picker/create descriptors."""

    class FakeConfigEditService:
        def __init__(self):
            self.apply_calls = []
            self.saved_external = []

        def load_edit_session(self):
            return {
                "raw_yaml": "initial-yaml",
                "edit_state": edit_state_with_proxy_console_client_secret(),
            }

        def list_external_resources(self, external_ref, current_value=None):
            return [{
                "name": "client-cert",
                "kind": "Secret",
                "type": "kubernetes.io/tls",
                "keys": ["tls.crt", "tls.key"],
                "status": "matching",
                "message": "",
                "current": False,
            }]

        def save_external_resource(self, external_ref, values, existing_name=None):
            self.saved_external.append((external_ref["purpose"], values, existing_name))
            return {"name": values["secretName"], "message": f"Secret created: {values['secretName']}"}

        def apply_operation(self, raw_yaml, operation):
            self.apply_calls.append((raw_yaml, operation))
            return {
                "raw_yaml": "updated-yaml",
                "edit_state": edit_state_with_proxy_console_client_secret(operation.get("value", "")),
            }

    service = FakeConfigEditService()
    argo_service = ArgoService(
        get_workflow=lambda name, namespace: ({"success": True}, mock_workflow_with_two_pods),
        approve_step=MagicMock(),
    )
    pod_scraper = MagicMock(spec=PodScraperInterface(None, None, None))
    pod_scraper.fetch_pods_metadata.return_value = []

    app = WorkflowTreeApp(
        namespace="default",
        name="test-wf",
        argo_service=argo_service,
        pod_scraper=pod_scraper,
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0,
        resource_view=True,
        config_edit_service=service,
    )

    with patch("console_link.workflow.resource_tree.build_resource_tree",
               return_value=resource_sections_for_manage_tests()):
        async with app.run_test() as pilot:
            tree = app.query_one("#workflow-tree")
            tree.focus()
            assert await wait_until(pilot, lambda: len(tree.root.children) > 0, timeout=5.0)

            await pilot.press("e")
            assert await wait_until(pilot, lambda: get_clean_text_label(tree.root) == "Workflow Config Edit")
            await pilot.press("f")
            assert await wait_until(
                pilot,
                lambda: find_tree_node_by_id(
                    tree.root,
                    "edit:traffic.proxies.cap.proxyConfig.tls.clientAuth.consoleClientSecretName",
                ) is not None,
            )
            app._select_tree_node_by_id(
                "edit:traffic.proxies.cap.proxyConfig.tls.clientAuth.consoleClientSecretName"
            )
            app._update_dynamic_bindings()

            await pilot.press("enter")
            assert await wait_until(pilot, lambda: isinstance(app.screen, ExternalResourcePickerModal))
            assert "Required Keys: tls.crt, tls.key" in str(app.screen.query_one("#requirement").content)
            assert app.screen.query_one("#clear", Button).label.plain == "Clear"
            await pilot.press("c")
            assert await wait_until(pilot, lambda: isinstance(app.screen, ExternalResourceFormModal))
            assert "Create Proxy Client Certificate Secret" in str(app.screen.query_one("#title").content)

            app.screen.query_one("#field-0", Input).value = "new-client-cert"
            app.screen.query_one("#field-1", TextArea).load_text("bad cert")
            app.screen.query_one("#field-2", TextArea).load_text("bad key")
            app.screen.query_one("#field-2-confirm", TextArea).load_text("bad key")
            app.screen.action_submit()
            assert "PEM CERTIFICATE" in str(app.screen.query_one("#validation").content)

            app.screen.query_one("#field-1", TextArea).load_text(
                "-----BEGIN CERTIFICATE-----\nabc\n-----END CERTIFICATE-----\n"
            )
            app.screen.query_one("#field-2", TextArea).load_text(
                "-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----\n"
            )
            app.screen.query_one("#field-2-confirm", TextArea).load_text(
                "-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----\n"
            )
            app.screen.action_submit()

            assert await wait_until(pilot, lambda: len(service.apply_calls) == 1)
            assert service.saved_external[0][0] == "proxy-console-client-tls"
            assert service.apply_calls[0][1]["path"] == [
                "traffic", "proxies", "cap", "proxyConfig", "tls", "clientAuth", "consoleClientSecretName"
            ]
            assert service.apply_calls[0][1]["value"] == "new-client-cert"

            app._select_tree_node_by_id(
                "edit:traffic.proxies.cap.proxyConfig.tls.clientAuth.consoleClientSecretName"
            )
            app._update_dynamic_bindings()
            await pilot.press("enter")
            assert await wait_until(pilot, lambda: isinstance(app.screen, ExternalResourcePickerModal))
            await pilot.click("#clear")

            assert await wait_until(pilot, lambda: len(service.apply_calls) == 2)
            assert service.apply_calls[1] == (
                "updated-yaml",
                {
                    "op": "unset",
                    "path": [
                        "traffic",
                        "proxies",
                        "cap",
                        "proxyConfig",
                        "tls",
                        "clientAuth",
                        "consoleClientSecretName",
                    ],
                },
            )


@pytest.mark.asyncio
async def test_resource_view_edit_mode_proxy_tls_secret_picker_creates_and_applies(mock_workflow_with_two_pods):
    """Proxy TLS existingSecret refs use TLS Secret picker/create/update descriptors."""

    class FakeConfigEditService:
        def __init__(self):
            self.apply_calls = []
            self.saved_external = []

        def load_edit_session(self):
            return {
                "raw_yaml": "initial-yaml",
                "edit_state": edit_state_with_proxy_tls_secret(),
            }

        def list_external_resources(self, external_ref, current_value=None):
            return [{
                "name": "proxy-tls",
                "kind": "Secret",
                "type": "kubernetes.io/tls",
                "keys": ["tls.crt", "tls.key"],
                "status": "matching",
                "message": "",
                "current": False,
            }]

        def save_external_resource(self, external_ref, values, existing_name=None):
            self.saved_external.append((external_ref["purpose"], values, existing_name))
            return {"name": values["secretName"], "message": f"Secret created: {values['secretName']}"}

        def apply_operation(self, raw_yaml, operation):
            self.apply_calls.append((raw_yaml, operation))
            return {
                "raw_yaml": "updated-yaml",
                "edit_state": edit_state_with_proxy_tls_secret(operation["value"]),
            }

    service = FakeConfigEditService()
    argo_service = ArgoService(
        get_workflow=lambda name, namespace: ({"success": True}, mock_workflow_with_two_pods),
        approve_step=MagicMock(),
    )
    pod_scraper = MagicMock(spec=PodScraperInterface(None, None, None))
    pod_scraper.fetch_pods_metadata.return_value = []

    app = WorkflowTreeApp(
        namespace="default",
        name="test-wf",
        argo_service=argo_service,
        pod_scraper=pod_scraper,
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0,
        resource_view=True,
        config_edit_service=service,
    )

    with patch("console_link.workflow.resource_tree.build_resource_tree",
               return_value=resource_sections_for_manage_tests()):
        async with app.run_test() as pilot:
            tree = app.query_one("#workflow-tree")
            tree.focus()
            assert await wait_until(pilot, lambda: len(tree.root.children) > 0, timeout=5.0)

            await pilot.press("e")
            assert await wait_until(pilot, lambda: get_clean_text_label(tree.root) == "Workflow Config Edit")
            app._select_tree_node_by_id("edit:traffic.proxies.cap.proxyConfig.tls.secretName")
            app._update_dynamic_bindings()

            await pilot.press("enter")
            assert await wait_until(pilot, lambda: isinstance(app.screen, ExternalResourcePickerModal))
            await pilot.press("c")
            assert await wait_until(pilot, lambda: isinstance(app.screen, ExternalResourceFormModal))
            assert "Create TLS Certificate Secret" in str(app.screen.query_one("#title").content)

            app.screen.query_one("#field-0", Input).value = "new-proxy-tls"
            app.screen.query_one("#field-1", TextArea).load_text(
                "-----BEGIN CERTIFICATE-----\nabc\n-----END CERTIFICATE-----\n"
            )
            app.screen.query_one("#field-2", TextArea).load_text(
                "-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----\n"
            )
            app.screen.query_one("#field-2-confirm", TextArea).load_text(
                "-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----\n"
            )
            app.screen.action_submit()

            assert await wait_until(pilot, lambda: len(service.apply_calls) == 1)
            assert service.saved_external[0][0] == "proxy-server-tls"
            assert service.apply_calls[0][1] == {
                "op": "set",
                "path": ["traffic", "proxies", "cap", "proxyConfig", "tls", "secretName"],
                "value": "new-proxy-tls",
            }


@pytest.mark.asyncio
async def test_resource_view_edit_mode_confirms_discard_on_escape(mock_workflow_with_two_pods):
    """Esc and q should offer return, save, or discard for dirty config edit drafts."""

    class FakeConfigEditService:
        def __init__(self):
            self.apply_calls = []
            self.saved_yaml = []

        def load_edit_session(self):
            return {
                "raw_yaml": "initial-yaml",
                "edit_state": edit_state_with_missing_basic_auth(),
            }

        def apply_operation(self, raw_yaml, operation):
            self.apply_calls.append((raw_yaml, operation))
            return {
                "raw_yaml": "updated-yaml",
                "edit_state": edit_state_with_sigv4_auth(),
            }

        def save_raw_yaml(self, raw_yaml):
            self.saved_yaml.append(raw_yaml)
            return "Configuration saved"

    service = FakeConfigEditService()
    argo_service = ArgoService(
        get_workflow=lambda name, namespace: ({"success": True}, mock_workflow_with_two_pods),
        approve_step=MagicMock(),
    )
    pod_scraper = MagicMock(spec=PodScraperInterface(None, None, None))
    pod_scraper.fetch_pods_metadata.return_value = []

    app = WorkflowTreeApp(
        namespace="default",
        name="test-wf",
        argo_service=argo_service,
        pod_scraper=pod_scraper,
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0,
        resource_view=True,
        config_edit_service=service,
    )

    with patch("console_link.workflow.resource_tree.build_resource_tree",
               return_value=resource_sections_for_manage_tests()):
        async with app.run_test() as pilot:
            tree = app.query_one("#workflow-tree")
            tree.focus()
            assert await wait_until(pilot, lambda: len(tree.root.children) > 0, timeout=5.0)

            await pilot.press("e")
            assert await wait_until(pilot, lambda: get_clean_text_label(tree.root) == "Workflow Config Edit")

            app._select_tree_node_by_id("edit:sourceClusters.legacy.authConfig")
            app._update_dynamic_bindings()
            await pilot.pause()

            await pilot.press("enter")
            assert await wait_until(pilot, lambda: isinstance(app.screen, ChoiceSelectModal))
            assert app.screen.query_one("#cancel", Button).label.plain == "Cancel (Esc)"
            await pilot.press("down")
            await pilot.press("enter")
            assert await wait_until(pilot, lambda: len(service.apply_calls) == 1)
            assert app._edit_dirty is True
            assert await wait_until(pilot, lambda: isinstance(app.screen, TextInputModal))
            assert app.screen.query_one("#save", Button).label.plain == "Save (<Enter>)"
            await pilot.press("escape")
            assert await wait_until(
                pilot,
                lambda: get_clean_text_label(tree.cursor_node) == "region: <required> [REQ 1]",
            )

            await pilot.press("escape")
            assert await wait_until(pilot, lambda: isinstance(app.screen, ConfigEditExitModal))
            assert "Validation still reports" in str(app.screen.query_one("#status").content)
            assert app.screen.focused.id == "return"
            assert app.screen.query_one("#discard", Button).label.plain == "Discard (d)"
            assert app.screen.query_one("#save", Button).label.plain == "Save (s)"
            assert app.screen.query_one("#return", Button).label.plain == "Return (r)"
            await pilot.press("left")
            assert app.screen.focused.id == "save"
            await pilot.press("right")
            assert app.screen.focused.id == "return"
            await pilot.press("enter")
            assert await wait_until(pilot, lambda: get_clean_text_label(tree.root) == "Workflow Config Edit")
            assert app._edit_mode is True
            assert app._edit_dirty is True

            await pilot.press("q")
            assert await wait_until(pilot, lambda: isinstance(app.screen, ConfigEditExitModal))
            await pilot.press("enter")
            assert await wait_until(pilot, lambda: get_clean_text_label(tree.root) == "Workflow Config Edit")
            assert app._edit_mode is True
            assert app._edit_dirty is True

            with patch.object(app, "exit") as exit_mock:
                await pilot.press("q")
                assert await wait_until(pilot, lambda: isinstance(app.screen, ConfigEditExitModal))
                await pilot.press("d")
                await pilot.pause()
                exit_mock.assert_called_once()

            await pilot.press("escape")
            assert await wait_until(pilot, lambda: isinstance(app.screen, ConfigEditExitModal))
            await pilot.press("d")
            assert await wait_until(pilot, lambda: get_clean_text_label(tree.root) == "Migration Status")
            assert app._edit_mode is False


@pytest.mark.asyncio
async def test_resource_view_edit_mode_dirty_exit_defaults_to_save_when_valid(mock_workflow_with_two_pods):
    """Dirty edit exit defaults to save when validation has no outstanding issues."""

    class FakeConfigEditService:
        def __init__(self):
            self.apply_calls = []
            self.saved_yaml = []

        def load_edit_session(self):
            return {
                "raw_yaml": "initial-yaml",
                "edit_state": edit_state_with_editable_source_fields(),
            }

        def apply_operation(self, raw_yaml, operation):
            self.apply_calls.append((raw_yaml, operation))
            return {
                "raw_yaml": "updated-yaml",
                "edit_state": edit_state_with_editable_source_fields(),
            }

        def save_raw_yaml(self, raw_yaml):
            self.saved_yaml.append(raw_yaml)
            return "Configuration saved"

    service = FakeConfigEditService()
    argo_service = ArgoService(
        get_workflow=lambda name, namespace: ({"success": True}, mock_workflow_with_two_pods),
        approve_step=MagicMock(),
    )
    pod_scraper = MagicMock(spec=PodScraperInterface(None, None, None))
    pod_scraper.fetch_pods_metadata.return_value = []

    app = WorkflowTreeApp(
        namespace="default",
        name="test-wf",
        argo_service=argo_service,
        pod_scraper=pod_scraper,
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0,
        resource_view=True,
        config_edit_service=service,
    )

    with patch("console_link.workflow.resource_tree.build_resource_tree",
               return_value=resource_sections_for_manage_tests()):
        async with app.run_test() as pilot:
            tree = app.query_one("#workflow-tree")
            tree.focus()
            assert await wait_until(pilot, lambda: len(tree.root.children) > 0, timeout=5.0)

            await pilot.press("e")
            assert await wait_until(pilot, lambda: get_clean_text_label(tree.root) == "Workflow Config Edit")

            app._select_tree_node_by_id("edit:sourceClusters.legacy.allowInsecure")
            app._update_dynamic_bindings()
            await pilot.pause()
            await pilot.press("enter")
            assert await wait_until(pilot, lambda: isinstance(app.screen, ChoiceSelectModal))
            app.screen.set_focus(app.screen.query_one("#choice-1", Button))
            await pilot.press("enter")
            assert await wait_until(pilot, lambda: app._edit_dirty is True)

            await pilot.press("escape")
            assert await wait_until(pilot, lambda: isinstance(app.screen, ConfigEditExitModal))
            assert "No validation errors" in str(app.screen.query_one("#status").content)
            await pilot.press("enter")

            assert await wait_until(pilot, lambda: service.saved_yaml == ["updated-yaml"])
            assert await wait_until(pilot, lambda: get_clean_text_label(tree.root) == "Migration Status")
            assert app._edit_mode is False


@pytest.mark.asyncio
async def test_resource_view_edit_mode_colors_and_fixed_data_modes(mock_workflow_with_two_pods):
    """Edit rows use one status color and keep value/status projection hotkeys disabled."""

    class FakeConfigEditService:
        def load_edit_session(self):
            return {
                "raw_yaml": "initial-yaml",
                "edit_state": edit_state_with_editable_source_fields(),
            }

    argo_service = ArgoService(
        get_workflow=lambda name, namespace: ({"success": True}, mock_workflow_with_two_pods),
        approve_step=MagicMock(),
    )
    pod_scraper = MagicMock(spec=PodScraperInterface(None, None, None))
    pod_scraper.fetch_pods_metadata.return_value = []

    app = WorkflowTreeApp(
        namespace="default",
        name="test-wf",
        argo_service=argo_service,
        pod_scraper=pod_scraper,
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0,
        resource_view=True,
        config_edit_service=FakeConfigEditService(),
    )

    with patch("console_link.workflow.resource_tree.build_resource_tree",
               return_value=resource_sections_for_manage_tests()):
        async with app.run_test() as pilot:
            tree = app.query_one("#workflow-tree")
            tree.focus()
            assert await wait_until(pilot, lambda: len(tree.root.children) > 0, timeout=5.0)

            await pilot.press("e")
            assert await wait_until(pilot, lambda: get_clean_text_label(tree.root) == "Workflow Config Edit")

            source_node = find_tree_node_by_id(tree.root, "edit:sourceClusters")
            assert source_node is not None
            assert "Sources (changed)" in get_clean_text_label(source_node)
            assert get_label_style(source_node) == ""
            assert not source_node.is_expanded

            endpoint_node = find_tree_node_by_id(tree.root, "edit:sourceClusters.legacy.endpoint")
            assert endpoint_node is not None

            assert "deployed/workflow=https://old.example.com:9200" in get_clean_text_label(endpoint_node)
            assert "pending=https://new.example.com:9200" in get_clean_text_label(endpoint_node)
            assert get_label_style(endpoint_node) == ""
            assert binding_descriptions(app, "v") == []
            assert binding_descriptions(app, "t") == []
            initial_label = get_clean_text_label(endpoint_node)
            initial_style = get_label_style(endpoint_node)

            await pilot.press("v")
            await pilot.pause()
            assert get_clean_text_label(endpoint_node) == initial_label
            assert get_label_style(endpoint_node) == initial_style
            assert "Values: All" in str(app.query_one("#pod-status").content)

            await pilot.press("t")
            await pilot.pause()
            assert get_clean_text_label(endpoint_node) == initial_label
            assert get_label_style(endpoint_node) == initial_style
            assert "Status: All" in str(app.query_one("#pod-status").content)


@pytest.mark.asyncio
async def test_resource_view_edit_mode_enriches_deployed_values_from_console_snapshots(mock_workflow_with_two_pods):
    """Edit mode uses submitted console resources as the deployed/current baseline."""

    state = edit_state_with_editable_source_fields()
    source = state["nodes"][0]
    legacy = source["children"][0]
    endpoint = legacy["children"][0]
    for node in (source, legacy, endpoint):
        node["status"] = "ok"
        node["statusCounts"] = {}
    endpoint.pop("states", None)

    class FakeConfigEditService:
        def load_edit_session(self):
            return {
                "raw_yaml": (
                    "sourceClusters:\n"
                    "  legacy:\n"
                    "    endpoint: https://new.example.com:9200\n"
                    "    allowInsecure: false\n"
                ),
                "edit_state": state,
            }

        def load_resource_config_snapshots(self, workflow_name):
            return {
                "submitted": {},
                "submitted_console": {
                    "sources": [{
                        "refName": "legacy",
                        "clientConfig": {
                            "endpoint": "https://old.example.com:9200",
                            "allow_insecure": False,
                        },
                    }],
                    "targets": [],
                    "kafkas": [],
                    "consumerGroups": [],
                },
            }

    argo_service = ArgoService(
        get_workflow=lambda name, namespace: ({"success": True}, mock_workflow_with_two_pods),
        approve_step=MagicMock(),
    )
    pod_scraper = MagicMock(spec=PodScraperInterface(None, None, None))
    pod_scraper.fetch_pods_metadata.return_value = []

    app = WorkflowTreeApp(
        namespace="default",
        name="test-wf",
        argo_service=argo_service,
        pod_scraper=pod_scraper,
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0,
        resource_view=True,
        config_edit_service=FakeConfigEditService(),
    )

    with patch("console_link.workflow.resource_tree.build_resource_tree",
               return_value=resource_sections_for_manage_tests()):
        async with app.run_test() as pilot:
            tree = app.query_one("#workflow-tree")
            tree.focus()
            assert await wait_until(pilot, lambda: len(tree.root.children) > 0, timeout=5.0)

            await pilot.press("e")
            assert await wait_until(pilot, lambda: get_clean_text_label(tree.root) == "Workflow Config Edit")

            source_node = find_tree_node_by_id(tree.root, "edit:sourceClusters")
            endpoint_node = find_tree_node_by_id(tree.root, "edit:sourceClusters.legacy.endpoint")
            assert source_node is not None
            assert endpoint_node is not None
            assert "Sources (changed)" in get_clean_text_label(source_node)
            assert (
                "endpoint: deployed/workflow=https://old.example.com:9200 | "
                "pending=https://new.example.com:9200 (changed)"
            ) == get_clean_text_label(endpoint_node)


@pytest.mark.asyncio
async def test_resource_view_edit_mode_preserves_matching_resource_expansion(mock_workflow_with_two_pods):
    """Entering edit mode maps the resource-view expansion shape onto matching edit nodes."""

    class FakeConfigEditService:
        def load_edit_session(self):
            return {
                "raw_yaml": "kafkaClusterConfiguration:\n  kafka:\n    autoCreate: {}\n",
                "edit_state": edit_state_with_workflow_config_kafka(),
            }

    argo_service = ArgoService(
        get_workflow=lambda name, namespace: ({"success": True}, mock_workflow_with_two_pods),
        approve_step=MagicMock(),
    )
    pod_scraper = MagicMock(spec=PodScraperInterface(None, None, None))
    pod_scraper.fetch_pods_metadata.return_value = []
    sections = [
        ResourceSection(
            name="Kafka Clusters",
            groups=[
                ResourceGroup(
                    plural="kafkaconfigs",
                    display_name="Kafka Clusters",
                    resources=[
                        ResourceNode(
                            name="kafka",
                            plural="kafkaconfigs",
                            phase="Pending Config",
                            depends_on=[],
                            spec={"type": "autoCreate"},
                            status={},
                        )
                    ],
                ),
            ],
        )
    ]

    app = WorkflowTreeApp(
        namespace="default",
        name="test-wf",
        argo_service=argo_service,
        pod_scraper=pod_scraper,
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0,
        resource_view=True,
        config_edit_service=FakeConfigEditService(),
    )

    with patch("console_link.workflow.resource_tree.build_resource_tree", return_value=sections):
        async with app.run_test() as pilot:
            tree = app.query_one("#workflow-tree")
            tree.focus()
            assert await wait_until(pilot, lambda: find_tree_node_by_id(tree.root, "resource:kafka") is not None)
            find_tree_node_by_id(tree.root, "resource:kafka").collapse()

            await pilot.press("e")
            assert await wait_until(pilot, lambda: get_clean_text_label(tree.root) == "Workflow Config Edit")

            kafka_root = find_tree_node_by_id(tree.root, "edit:kafkaClusterConfiguration")
            assert kafka_root is not None
            assert kafka_root.parent is tree.root
            assert kafka_root.is_expanded
            assert find_tree_node_by_id(tree.root, "edit:workflowConfiguration") is None
            assert not find_tree_node_by_id(tree.root, "edit:kafkaClusterConfiguration.kafka").is_expanded


def test_workflow_config_value_state_uses_schema_default_only_when_parent_exists():
    node = {
        "path": ["traffic", "proxies", "cap", "kafka"],
        "value": "default",
        "valueDefaulted": True,
        "valueKind": "scalar",
    }

    assert WorkflowTreeApp._workflow_config_value_state(
        {"traffic": {"proxies": {"cap": {"source": "source"}}}},
        node,
        allow_node_default=True,
    ) == {"present": True, "value": "default", "defaulted": True}
    assert WorkflowTreeApp._workflow_config_value_state(
        {},
        node,
        allow_node_default=True,
    ) == {"present": False}
    assert WorkflowTreeApp._workflow_config_value_state(
        {"traffic": {"proxies": {"cap": {"source": "source"}}}},
        {**node, "valueDefaulted": False},
        allow_node_default=True,
    ) == {"present": False}


@pytest.mark.asyncio
async def test_resource_view_edit_mode_renders_defaulted_capture_kafka_value(mock_workflow_with_two_pods):
    class FakeConfigEditService:
        def load_edit_session(self):
            return {
                "raw_yaml": (
                    "traffic:\n"
                    "  proxies:\n"
                    "    cap:\n"
                    "      source: source\n"
                    "      kafkaTopic: aa\n"
                ),
                "edit_state": edit_state_with_capture_defaulted_kafka(),
            }

        def load_resource_config_snapshots(self, workflow_name):
            return {
                "submitted": {"workflowConfig": {}},
                "submitted_console": {},
                "pending_console": {},
            }

    argo_service = ArgoService(
        get_workflow=lambda name, namespace: ({"success": True}, mock_workflow_with_two_pods),
        approve_step=MagicMock(),
    )
    pod_scraper = MagicMock(spec=PodScraperInterface(None, None, None))
    pod_scraper.fetch_pods_metadata.return_value = []

    app = WorkflowTreeApp(
        namespace="default",
        name="test-wf",
        argo_service=argo_service,
        pod_scraper=pod_scraper,
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0,
        resource_view=True,
        config_edit_service=FakeConfigEditService(),
    )

    with patch("console_link.workflow.resource_tree.build_resource_tree",
               return_value=resource_sections_for_manage_tests()):
        async with app.run_test() as pilot:
            tree = app.query_one("#workflow-tree")
            tree.focus()
            assert await wait_until(pilot, lambda: len(tree.root.children) > 0, timeout=5.0)

            await pilot.press("e")
            assert await wait_until(pilot, lambda: get_clean_text_label(tree.root) == "Workflow Config Edit")

            assert find_tree_node_by_id(tree.root, "edit:traffic.proxies.cap.kafka") is None

            await pilot.press("f")
            kafka = find_tree_node_by_id(tree.root, "edit:traffic.proxies.cap.kafka")
            assert kafka is not None
            assert get_clean_text_label(kafka) == "kafka: deployed/workflow=<absent> | pending=default"


@pytest.mark.asyncio
async def test_resource_view_edit_mode_does_not_count_absent_kafka_override_scaffolding(mock_workflow_with_two_pods):
    """Schema children under absent optional Kafka override blocks are not pending edits."""

    state = edit_state_with_unset_kafka_override_children()
    replicas = (
        state["nodes"][0]["children"][0]["children"][0]["children"][0]["children"][0]
    )
    replicas["value"] = 3
    replicas["label"] = "replicas: 3"
    kafka_override = state["nodes"][0]["children"][0]["children"][0]["children"][0]
    kafka_override["children"].append({
        "id": (
            "edit:kafkaClusterConfiguration.kafka.autoCreate"
            ".clusterSpecOverrides.kafka.gcLoggingEnabled"
        ),
        "path": [
            "kafkaClusterConfiguration",
            "kafka",
            "autoCreate",
            "clusterSpecOverrides",
            "kafka",
            "gcLoggingEnabled",
        ],
        "label": "gcLoggingEnabled: false",
        "value": False,
        "valueKind": "boolean",
        "presence": "optional",
        "description": "GC logging.",
        "status": "ok",
        "statusCounts": {},
    })

    class FakeConfigEditService:
        def load_edit_session(self):
            return {
                "raw_yaml": "kafkaClusterConfiguration:\n  kafka:\n    autoCreate: {}\n",
                "edit_state": state,
            }

        def load_resource_config_snapshots(self, workflow_name):
            return {
                "submitted": {
                    "workflowConfig": {
                        "kafkaClusterConfiguration": {
                            "kafka": {"autoCreate": {}},
                        },
                    },
                },
            }

    argo_service = ArgoService(
        get_workflow=lambda name, namespace: ({"success": True}, mock_workflow_with_two_pods),
        approve_step=MagicMock(),
    )
    pod_scraper = MagicMock(spec=PodScraperInterface(None, None, None))
    pod_scraper.fetch_pods_metadata.return_value = []

    app = WorkflowTreeApp(
        namespace="default",
        name="test-wf",
        argo_service=argo_service,
        pod_scraper=pod_scraper,
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0,
        resource_view=True,
        config_edit_service=FakeConfigEditService(),
    )

    with patch("console_link.workflow.resource_tree.build_resource_tree",
               return_value=resource_sections_for_manage_tests()):
        async with app.run_test() as pilot:
            tree = app.query_one("#workflow-tree")
            tree.focus()
            assert await wait_until(pilot, lambda: len(tree.root.children) > 0, timeout=5.0)

            await pilot.press("e")
            assert await wait_until(pilot, lambda: get_clean_text_label(tree.root) == "Workflow Config Edit")

            assert "change" not in get_clean_text_label(
                find_tree_node_by_id(
                    tree.root,
                    "edit:kafkaClusterConfiguration.kafka.autoCreate.clusterSpecOverrides",
                )
            )
            assert "change" not in get_clean_text_label(
                find_tree_node_by_id(
                    tree.root,
                    "edit:kafkaClusterConfiguration.kafka.autoCreate.clusterSpecOverrides.kafka",
                )
            )


@pytest.mark.asyncio
async def test_resource_view_edit_mode_collapses_unset_blocks_one_level_at_a_time(mock_workflow_with_two_pods):
    """Unset optional containers are collapsed until the user expands each level."""

    class FakeConfigEditService:
        def load_edit_session(self):
            return {
                "raw_yaml": "initial-yaml",
                "edit_state": edit_state_with_unset_kafka_override_children(),
            }

    argo_service = ArgoService(
        get_workflow=lambda name, namespace: ({"success": True}, mock_workflow_with_two_pods),
        approve_step=MagicMock(),
    )
    pod_scraper = MagicMock(spec=PodScraperInterface(None, None, None))
    pod_scraper.fetch_pods_metadata.return_value = []

    app = WorkflowTreeApp(
        namespace="default",
        name="test-wf",
        argo_service=argo_service,
        pod_scraper=pod_scraper,
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0,
        resource_view=True,
        config_edit_service=FakeConfigEditService(),
    )

    with patch("console_link.workflow.resource_tree.build_resource_tree",
               return_value=resource_sections_for_manage_tests()):
        async with app.run_test() as pilot:
            tree = app.query_one("#workflow-tree")
            tree.focus()
            assert await wait_until(pilot, lambda: len(tree.root.children) > 0, timeout=5.0)

            await pilot.press("e")
            assert await wait_until(pilot, lambda: get_clean_text_label(tree.root) == "Workflow Config Edit")
            await pilot.press("f")
            assert await wait_until(
                pilot,
                lambda: find_tree_node_by_id(
                    tree.root,
                    "edit:kafkaClusterConfiguration.kafka.autoCreate.clusterSpecOverrides",
                ) is not None,
            )

            cluster_overrides = find_tree_node_by_id(
                tree.root,
                "edit:kafkaClusterConfiguration.kafka.autoCreate.clusterSpecOverrides",
            )
            kafka_overrides = find_tree_node_by_id(
                tree.root,
                "edit:kafkaClusterConfiguration.kafka.autoCreate.clusterSpecOverrides.kafka",
            )
            assert cluster_overrides is not None
            assert kafka_overrides is not None
            assert find_tree_node_by_id(tree.root, "edit:kafkaClusterConfiguration").is_expanded
            assert find_tree_node_by_id(tree.root, "edit:kafkaClusterConfiguration.kafka").is_expanded
            assert not cluster_overrides.is_expanded
            assert not kafka_overrides.is_expanded

            app._select_tree_node_by_id("edit:kafkaClusterConfiguration.kafka.autoCreate.clusterSpecOverrides")
            app._update_dynamic_bindings()
            await pilot.press("enter")
            await pilot.pause()

            assert cluster_overrides.is_expanded
            assert not kafka_overrides.is_expanded


@pytest.mark.asyncio
async def test_resource_view_edit_mode_optional_and_expert_visibility(mock_workflow_with_two_pods):
    """Edit mode cycles field visibility from essential to standard to all fields."""

    class FakeConfigEditService:
        def load_edit_session(self):
            return {
                "raw_yaml": "initial-yaml",
                "edit_state": edit_state_with_field_visibility(),
            }

    argo_service = ArgoService(
        get_workflow=lambda name, namespace: ({"success": True}, mock_workflow_with_two_pods),
        approve_step=MagicMock(),
    )
    pod_scraper = MagicMock(spec=PodScraperInterface(None, None, None))
    pod_scraper.fetch_pods_metadata.return_value = []

    app = WorkflowTreeApp(
        namespace="default",
        name="test-wf",
        argo_service=argo_service,
        pod_scraper=pod_scraper,
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0,
        resource_view=True,
        config_edit_service=FakeConfigEditService(),
    )

    with patch("console_link.workflow.resource_tree.build_resource_tree",
               return_value=resource_sections_for_manage_tests()):
        async with app.run_test() as pilot:
            tree = app.query_one("#workflow-tree")
            tree.focus()
            assert await wait_until(pilot, lambda: len(tree.root.children) > 0, timeout=5.0)

            await pilot.press("e")
            assert await wait_until(pilot, lambda: get_clean_text_label(tree.root) == "Workflow Config Edit")

            assert find_tree_node_by_id(tree.root, "edit:sourceClusters.legacy.endpoint") is not None
            assert find_tree_node_by_id(tree.root, "edit:sourceClusters.legacy.allowInsecure") is not None
            assert find_tree_node_by_id(tree.root, "edit:sourceClusters.legacy.description") is None
            assert find_tree_node_by_id(tree.root, "edit:sourceClusters.legacy.aliases") is None
            assert find_tree_node_by_id(tree.root, "edit:sourceClusters.legacy.expertAliases") is None
            assert find_tree_node_by_id(tree.root, "edit:sourceClusters.legacy.serviceType") is None
            assert "Fields: Essential" in str(app.query_one("#pod-status").content)
            assert binding_descriptions(app, "f") == ["Show Standard Fields"]
            assert binding_descriptions(app, "o") == []
            assert binding_descriptions(app, "O") == []
            assert binding_descriptions(app, "x") == []
            assert binding_descriptions(app, "X") == []
            assert binding_descriptions(app, "v") == []
            assert binding_descriptions(app, "t") == []

            await pilot.press("f")
            assert find_tree_node_by_id(tree.root, "edit:sourceClusters.legacy.endpoint") is not None
            assert find_tree_node_by_id(tree.root, "edit:sourceClusters.legacy.allowInsecure") is not None
            assert find_tree_node_by_id(tree.root, "edit:sourceClusters.legacy.description") is not None
            assert find_tree_node_by_id(tree.root, "edit:sourceClusters.legacy.aliases") is not None
            assert find_tree_node_by_id(tree.root, "edit:sourceClusters.legacy.expertAliases") is None
            assert find_tree_node_by_id(tree.root, "edit:sourceClusters.legacy.serviceType") is None
            assert "Fields: Standard" in str(app.query_one("#pod-status").content)
            assert binding_descriptions(app, "f") == ["Show All Fields"]

            await pilot.press("f")
            assert await wait_until(
                pilot,
                lambda: find_tree_node_by_id(tree.root, "edit:sourceClusters.legacy.serviceType") is not None,
            )
            assert find_tree_node_by_id(tree.root, "edit:sourceClusters.legacy.allowInsecure") is not None
            assert find_tree_node_by_id(tree.root, "edit:sourceClusters.legacy.description") is not None
            assert find_tree_node_by_id(tree.root, "edit:sourceClusters.legacy.expertAliases") is not None
            assert "Fields: All" in str(app.query_one("#pod-status").content)
            assert binding_descriptions(app, "f") == ["Show Essential"]

            await pilot.press("f")
            assert await wait_until(
                pilot,
                lambda: find_tree_node_by_id(tree.root, "edit:sourceClusters.legacy.allowInsecure") is not None,
            )
            assert find_tree_node_by_id(tree.root, "edit:sourceClusters.legacy.description") is None
            assert find_tree_node_by_id(tree.root, "edit:sourceClusters.legacy.serviceType") is None
            assert find_tree_node_by_id(tree.root, "edit:sourceClusters.legacy.expertAliases") is None
            assert "Fields: Essential" in str(app.query_one("#pod-status").content)


@pytest.mark.asyncio
async def test_resource_view_shows_config_phases_and_submits_workflow(mock_workflow_with_two_pods):
    """Resource view shows deployed/pending/to-submit values and submits saved config."""

    class FakeConfigEditService:
        def __init__(self):
            self.submit_calls = []

        def load_resource_config_snapshots(self, workflow_name):
            return {
                "submitted": {
                    "resources": [{
                        "kind": "KafkaCluster",
                        "name": "default",
                        "parameters": {"version": "3.7.0", "auth": {"type": "none"}},
                    }]
                },
                "pending": {
                    "resources": [{
                        "kind": "KafkaCluster",
                        "name": "default",
                        "parameters": {"version": "3.8.0", "auth": {"type": "none"}},
                    }]
                },
            }

        def submit_saved_config(self, workflow_name):
            self.submit_calls.append(workflow_name)
            return {"workflow_name": workflow_name}

    service = FakeConfigEditService()
    argo_service = ArgoService(
        get_workflow=lambda name, namespace: ({"success": True}, mock_workflow_with_two_pods),
        approve_step=MagicMock(),
    )
    pod_scraper = MagicMock(spec=PodScraperInterface(None, None, None))
    pod_scraper.fetch_pods_metadata.return_value = []

    app = WorkflowTreeApp(
        namespace="default",
        name="migration",
        argo_service=argo_service,
        pod_scraper=pod_scraper,
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0,
        resource_view=True,
        config_edit_service=service,
    )

    with patch("console_link.workflow.resource_tree.build_resource_tree",
               return_value=resource_sections_with_kafka_config()):
        async with app.run_test() as pilot:
            tree = app.query_one("#workflow-tree")
            tree.focus()
            assert await wait_until(pilot, lambda: len(tree.root.children) > 0, timeout=5.0)

            resource_node = find_tree_node_by_id(tree.root, "resource:default")
            assert resource_node is not None
            assert "to submit" in get_clean_text_label(resource_node)
            labels = [get_clean_text_label(child) for child in resource_node.children]
            assert "version: deployed=3.6.0 | pending=3.7.0 | to-submit=3.8.0" in labels
            assert "Config changes:" in str(app.query_one("#pod-status").content)
            assert binding_descriptions(app, "s") == ["Submit"]

            group_node = find_tree_node_by_id(tree.root, "group:Buffer")
            assert group_node is not None
            group_node.collapse()
            resource_node.collapse()
            assert not group_node.is_expanded
            assert not resource_node.is_expanded

            await pilot.press("v")
            assert await wait_until(
                pilot,
                lambda: any(
                    get_clean_text_label(child) == "version: deployed=3.6.0"
                    for child in find_tree_node_by_id(tree.root, "resource:default").children
                ),
            )
            assert not find_tree_node_by_id(tree.root, "group:Buffer").is_expanded
            assert not find_tree_node_by_id(tree.root, "resource:default").is_expanded
            await pilot.press("v")
            await pilot.press("v")
            assert await wait_until(
                pilot,
                lambda: any(
                    get_clean_text_label(child) == "version: to-submit=3.8.0"
                    for child in find_tree_node_by_id(tree.root, "resource:default").children
                ),
            )

            await pilot.press("s")
            assert await wait_until(pilot, lambda: isinstance(app.screen, ConfirmModal))
            assert app.screen.focused.id == "yes"
            await pilot.press("right")
            assert app.screen.focused.id == "no"
            await pilot.press("left")
            assert app.screen.focused.id == "yes"
            await pilot.press("enter")
            assert await wait_until(pilot, lambda: service.submit_calls == ["migration"])


@pytest.mark.asyncio
async def test_resource_view_collapses_submitted_projection_after_workflow_succeeds():
    """Submitted projections are rollout state only while the workflow is active."""

    class FakeConfigEditService:
        def load_resource_config_snapshots(self, workflow_name):
            return {
                "submitted": {
                    "resources": [{
                        "kind": "KafkaCluster",
                        "name": "default",
                        "parameters": {"version": "3.7.0", "auth": {"type": "none"}},
                    }]
                },
                "pending": {
                    "resources": [{
                        "kind": "KafkaCluster",
                        "name": "default",
                        "parameters": {"version": "3.8.0", "auth": {"type": "none"}},
                    }]
                },
            }

    workflow = {
        "metadata": {"name": "migration", "resourceVersion": "123"},
        "status": {
            "phase": PHASE_SUCCEEDED,
            "nodes": {
                "node-1": {"id": "node-1", "displayName": "step-1", "type": "Pod", "phase": PHASE_SUCCEEDED}
            },
        },
    }
    argo_service = ArgoService(
        get_workflow=lambda name, namespace: ({"success": True}, workflow),
        approve_step=MagicMock(),
    )
    pod_scraper = MagicMock(spec=PodScraperInterface(None, None, None))
    pod_scraper.fetch_pods_metadata.return_value = []

    app = WorkflowTreeApp(
        namespace="default",
        name="migration",
        argo_service=argo_service,
        pod_scraper=pod_scraper,
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0,
        resource_view=True,
        config_edit_service=FakeConfigEditService(),
    )

    with patch("console_link.workflow.resource_tree.build_resource_tree",
               return_value=resource_sections_with_kafka_config()):
        async with app.run_test() as pilot:
            tree = app.query_one("#workflow-tree")
            tree.focus()
            assert await wait_until(pilot, lambda: find_tree_node_by_id(tree.root, "resource:default") is not None)

            resource_node = find_tree_node_by_id(tree.root, "resource:default")
            labels = [get_clean_text_label(child) for child in resource_node.children]
            assert "version: deployed=3.6.0 | pending=3.6.0 | to-submit=3.8.0" in labels

            await pilot.press("v")
            await pilot.press("v")
            assert await wait_until(
                pilot,
                lambda: any(
                    get_clean_text_label(child) == "version: pending=3.6.0"
                    for child in find_tree_node_by_id(tree.root, "resource:default").children
                ),
            )


@pytest.mark.asyncio
async def test_resource_view_uses_submitted_console_as_deployed_virtual_config_after_success():
    """Terminal workflows use the latest submitted console config as the virtual deployed baseline."""

    class FakeConfigEditService:
        def load_resource_config_snapshots(self, workflow_name):
            return {
                "submitted_console": {
                    "sources": [{
                        "refName": "source",
                        "clientConfig": {"endpoint": "https://old.example.com"},
                    }],
                },
                "pending_console": {
                    "sources": [{
                        "refName": "source",
                        "clientConfig": {"endpoint": "https://new.example.com"},
                    }],
                },
            }

    workflow = {
        "metadata": {"name": "migration", "resourceVersion": "123"},
        "status": {"phase": PHASE_SUCCEEDED, "nodes": {}},
    }
    argo_service = ArgoService(
        get_workflow=lambda name, namespace: ({"success": True}, workflow),
        approve_step=MagicMock(),
    )
    pod_scraper = MagicMock(spec=PodScraperInterface(None, None, None))
    pod_scraper.fetch_pods_metadata.return_value = []

    app = WorkflowTreeApp(
        namespace="default",
        name="migration",
        argo_service=argo_service,
        pod_scraper=pod_scraper,
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0,
        resource_view=True,
        config_edit_service=FakeConfigEditService(),
    )

    with patch("console_link.workflow.resource_tree.build_resource_tree",
               return_value=_build_tree_from_raw({})):
        async with app.run_test() as pilot:
            tree = app.query_one("#workflow-tree")
            tree.focus()
            assert await wait_until(pilot, lambda: find_tree_node_by_id(tree.root, "resource:source") is not None)

            source_node = find_tree_node_by_id(tree.root, "resource:source")
            assert "Deployed Config" in get_clean_text_label(source_node)
            labels = [get_clean_text_label(child) for child in source_node.children]
            assert "endpoint: deployed=https://old.example.com | pending=https://old.example.com | to-submit=https://new.example.com" in labels


@pytest.mark.asyncio
async def test_resource_view_preserves_collapsed_config_changes_after_edit_exit_without_workflow():
    """Returning from edit mode preserves collapsed resource branches and badges the root."""

    class FakeConfigEditService:
        def load_edit_session(self):
            return {
                "raw_yaml": "initial-yaml",
                "edit_state": edit_state_with_missing_basic_auth(),
            }

        def load_resource_config_snapshots(self, workflow_name):
            return {
                "pending": {
                    "resources": [{
                        "kind": "KafkaCluster",
                        "name": "default",
                        "parameters": {"version": "3.8.0", "auth": {"type": "none"}},
                    }]
                },
            }

    argo_service = MagicMock(spec=ArgoService(None, None))
    argo_service.get_workflow.return_value = ({"success": False, "error": "not found"}, {})

    pod_scraper = MagicMock(spec=PodScraperInterface(None, None, None))
    pod_scraper.fetch_pods_metadata.return_value = []

    app = WorkflowTreeApp(
        namespace="default",
        name="migration",
        argo_service=argo_service,
        pod_scraper=pod_scraper,
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0,
        resource_view=True,
        config_edit_service=FakeConfigEditService(),
    )

    with patch("console_link.workflow.resource_tree.build_resource_tree",
               return_value=resource_sections_with_kafka_config()):
        async with app.run_test() as pilot:
            tree = app.query_one("#workflow-tree")
            tree.focus()
            assert await wait_until(pilot, lambda: find_tree_node_by_id(tree.root, "resource:default") is not None)

            find_tree_node_by_id(tree.root, "group:Buffer").collapse()
            find_tree_node_by_id(tree.root, "resource:default").collapse()
            await pilot.press("v")
            assert await wait_until(
                pilot,
                lambda: "Values: Deployed" in str(app.query_one("#pod-status").content),
            )

            await pilot.press("e")
            assert await wait_until(pilot, lambda: get_clean_text_label(tree.root) == "Workflow Config Edit")
            assert "Values: All" in str(app.query_one("#pod-status").content)
            await pilot.press("escape")
            assert await wait_until(
                pilot,
                lambda: (
                    get_clean_text_label(tree.root) == "Migration Status"
                    and not find_tree_node_by_id(tree.root, "group:Buffer").is_expanded
                    and not find_tree_node_by_id(tree.root, "resource:default").is_expanded
                    and "Values: All" in str(app.query_one("#pod-status").content)
                ),
            )
            assert "(to submit)" in get_clean_text_label(find_tree_node_by_id(tree.root, "group:Buffer"))


@pytest.mark.asyncio
async def test_resource_view_shows_pending_only_config_resources_without_workflow():
    """Saved config resources that do not exist in K8s should still render in resource view."""

    class FakeConfigEditService:
        def load_resource_config_snapshots(self, workflow_name):
            return {
                "pending": {
                    "resources": [{
                        "kind": "TrafficReplay",
                        "name": "replay-new",
                        "parameters": {"podReplicas": 2, "speedupFactor": 1.5, "removeAuthHeader": False},
                    }]
                },
            }

    argo_service = MagicMock(spec=ArgoService(None, None))
    argo_service.get_workflow.return_value = ({"success": False, "error": "not found"}, {})

    pod_scraper = MagicMock(spec=PodScraperInterface(None, None, None))
    pod_scraper.fetch_pods_metadata.return_value = []

    app = WorkflowTreeApp(
        namespace="default",
        name="migration",
        argo_service=argo_service,
        pod_scraper=pod_scraper,
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0,
        resource_view=True,
        config_edit_service=FakeConfigEditService(),
    )

    with patch("console_link.workflow.resource_tree.build_resource_tree",
               return_value=_build_tree_from_raw({})):
        async with app.run_test() as pilot:
            tree = app.query_one("#workflow-tree")
            tree.focus()
            assert await wait_until(
                pilot,
                lambda: find_tree_node_by_id(tree.root, "resource:replay-new") is not None,
                timeout=5.0,
            )

            replay_node = find_tree_node_by_id(tree.root, "resource:replay-new")
            assert "Pending Config" in get_clean_text_label(replay_node)
            assert replay_node.is_expanded
            labels = [get_clean_text_label(child) for child in replay_node.children]
            assert "podReplicas: deployed=<absent> | pending=<absent> | to-submit=2" in labels

            await pilot.press("v")
            assert await wait_until(
                pilot,
                lambda: (
                    "Values: Deployed" in str(app.query_one("#pod-status").content)
                    and find_tree_node_by_id(tree.root, "resource:replay-new") is None
                ),
            )

            await pilot.press("v")
            assert await wait_until(
                pilot,
                lambda: (
                    "Values: Pending" in str(app.query_one("#pod-status").content)
                    and find_tree_node_by_id(tree.root, "resource:replay-new") is None
                ),
            )

            await pilot.press("v")
            assert await wait_until(
                pilot,
                lambda: (
                    "Values: To Submit" in str(app.query_one("#pod-status").content)
                    and find_tree_node_by_id(tree.root, "resource:replay-new") is not None
                ),
            )


@pytest.mark.asyncio
async def test_resource_view_shows_loose_projection_diagnostics_without_workflow():
    """Incomplete saved config resources render in resource view with validation diagnostics."""

    class FakeConfigEditService:
        def load_resource_config_snapshots(self, workflow_name):
            return {
                "pending": {
                    "resources": [{
                        "kind": "CaptureProxy",
                        "name": "capture-new",
                        "parameters": {"dependsOn": ["capture-new-topic"]},
                        "diagnostics": [{
                            "severity": "required",
                            "path": ["traffic", "proxies", "capture-new", "proxyConfig"],
                            "message": "Invalid input: expected object, received undefined",
                        }],
                    }]
                },
            }

    argo_service = MagicMock(spec=ArgoService(None, None))
    argo_service.get_workflow.return_value = ({"success": False, "error": "not found"}, {})

    pod_scraper = MagicMock(spec=PodScraperInterface(None, None, None))
    pod_scraper.fetch_pods_metadata.return_value = []

    app = WorkflowTreeApp(
        namespace="default",
        name="migration",
        argo_service=argo_service,
        pod_scraper=pod_scraper,
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0,
        resource_view=True,
        config_edit_service=FakeConfigEditService(),
    )

    with patch("console_link.workflow.resource_tree.build_resource_tree",
               return_value=_build_tree_from_raw({})):
        async with app.run_test() as pilot:
            tree = app.query_one("#workflow-tree")
            tree.focus()
            assert await wait_until(
                pilot,
                lambda: find_tree_node_by_id(tree.root, "resource:capture-new") is not None,
                timeout=5.0,
            )

            capture_node = find_tree_node_by_id(tree.root, "resource:capture-new")
            assert "(required)" in get_clean_text_label(capture_node)
            labels = [get_clean_text_label(child) for child in capture_node.children]
            assert (
                "required: traffic.proxies.capture-new.proxyConfig: "
                "Invalid input: expected object, received undefined"
            ) in labels


@pytest.mark.asyncio
async def test_resource_view_edit_mode_add_row_bindings_do_not_offer_delete(mock_workflow_with_two_pods):
    """Synthetic add rows expose Add actions and never expose remove bindings."""

    class FakeConfigEditService:
        def __init__(self):
            self.apply_calls = []

        def load_edit_session(self):
            return {
                "raw_yaml": "initial-yaml",
                "edit_state": edit_state_with_editable_source_fields(),
            }

        def apply_operation(self, raw_yaml, operation):
            self.apply_calls.append((raw_yaml, operation))
            return {
                "raw_yaml": "added-yaml",
                "edit_state": edit_state_with_editable_source_fields(),
            }

    service = FakeConfigEditService()
    argo_service = ArgoService(
        get_workflow=lambda name, namespace: ({"success": True}, mock_workflow_with_two_pods),
        approve_step=MagicMock(),
    )
    pod_scraper = MagicMock(spec=PodScraperInterface(None, None, None))
    pod_scraper.fetch_pods_metadata.return_value = []

    app = WorkflowTreeApp(
        namespace="default",
        name="test-wf",
        argo_service=argo_service,
        pod_scraper=pod_scraper,
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0,
        resource_view=True,
        config_edit_service=service,
    )

    with patch("console_link.workflow.resource_tree.build_resource_tree",
               return_value=resource_sections_for_manage_tests()):
        async with app.run_test() as pilot:
            tree = app.query_one("#workflow-tree")
            tree.focus()
            assert await wait_until(pilot, lambda: len(tree.root.children) > 0, timeout=5.0)

            await pilot.press("e")
            assert await wait_until(pilot, lambda: get_clean_text_label(tree.root) == "Workflow Config Edit")

            app._select_tree_node_by_id("edit:sourceClusters:add")
            app._update_dynamic_bindings()
            await pilot.pause()

            assert binding_descriptions(app, "a") == ["Add"]
            assert binding_descriptions(app, "delete") == []
            assert binding_descriptions(app, "backspace") == []

            await pilot.press("delete")
            await pilot.pause()
            assert not isinstance(app.screen, ConfirmModal)
            assert service.apply_calls == []

            await pilot.press("enter")
            assert await wait_until(pilot, lambda: isinstance(app.screen, TextInputModal))
            app.screen.query_one("#value").value = "new-source"
            await pilot.press("enter")

            assert await wait_until(pilot, lambda: len(service.apply_calls) == 1)
            assert await wait_until(pilot, lambda: not isinstance(app.screen, TextInputModal))
            assert service.apply_calls[0] == (
                "initial-yaml",
                {
                    "op": "add",
                    "path": ["sourceClusters"],
                    "value": {"name": "new-source"},
                },
            )


@pytest.mark.asyncio
async def test_resource_view_edit_mode_array_items_expand_add_and_delete(mock_workflow_with_two_pods):
    """Schema array rows render existing items, expose add rows, and remove indexed items."""

    class FakeConfigEditService:
        def __init__(self):
            self.apply_calls = []

        def load_edit_session(self):
            return {
                "raw_yaml": "initial-yaml",
                "edit_state": edit_state_with_array_items(),
            }

        def apply_operation(self, raw_yaml, operation):
            self.apply_calls.append((raw_yaml, operation))
            include_provisional_item = operation == {
                "op": "add",
                "path": ["roles"],
                "value": {},
            }
            return {
                "raw_yaml": f"updated-yaml-{len(self.apply_calls)}",
                "edit_state": edit_state_with_array_items(include_provisional_item=include_provisional_item),
            }

    service = FakeConfigEditService()
    argo_service = ArgoService(
        get_workflow=lambda name, namespace: ({"success": True}, mock_workflow_with_two_pods),
        approve_step=MagicMock(),
    )
    pod_scraper = MagicMock(spec=PodScraperInterface(None, None, None))
    pod_scraper.fetch_pods_metadata.return_value = []

    app = WorkflowTreeApp(
        namespace="default",
        name="test-wf",
        argo_service=argo_service,
        pod_scraper=pod_scraper,
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0,
        resource_view=True,
        config_edit_service=service,
    )

    with patch("console_link.workflow.resource_tree.build_resource_tree",
               return_value=resource_sections_for_manage_tests()):
        async with app.run_test() as pilot:
            tree = app.query_one("#workflow-tree")
            tree.focus()
            assert await wait_until(pilot, lambda: len(tree.root.children) > 0, timeout=5.0)

            await pilot.press("e")
            assert await wait_until(pilot, lambda: get_clean_text_label(tree.root) == "Workflow Config Edit")

            app._select_tree_node_by_id("edit:roles.0")
            app._update_dynamic_bindings()
            await pilot.pause()
            item_node = find_tree_node_by_id(tree.root, "edit:roles.0")
            assert item_node is not None
            assert not item_node.is_expanded
            assert binding_descriptions(app, "delete") == ["Remove"]

            await pilot.press("enter")
            await pilot.pause()
            assert item_node.is_expanded

            app._select_tree_node_by_id("edit:roles:add")
            app._update_dynamic_bindings()
            await pilot.pause()
            assert binding_descriptions(app, "a") == ["Add"]
            await pilot.press("enter")
            assert await wait_until(pilot, lambda: len(service.apply_calls) == 1)
            assert service.apply_calls[0] == (
                "initial-yaml",
                {
                    "op": "add",
                    "path": ["roles"],
                    "value": {},
                },
            )
            assert await wait_until(pilot, lambda: isinstance(app.screen, TextInputModal))
            assert app.screen.query_one("#value").value == ""
            assert "Role name." in str(app.screen.query_one("#documentation").content)
            await pilot.press("escape")
            assert await wait_until(pilot, lambda: len(service.apply_calls) == 2)
            assert service.apply_calls[1] == (
                "updated-yaml-1",
                {
                    "op": "removeConfig",
                    "path": ["roles", "1"],
                },
            )

            app._select_tree_node_by_id("edit:roles.0")
            app._update_dynamic_bindings()
            await pilot.pause()
            await pilot.press("delete")
            assert await wait_until(pilot, lambda: isinstance(app.screen, ConfirmModal))
            await pilot.press("y")

            assert await wait_until(pilot, lambda: len(service.apply_calls) == 3)
            assert service.apply_calls[2] == (
                "updated-yaml-2",
                {
                    "op": "removeConfig",
                    "path": ["roles", "0"],
                },
            )


@pytest.mark.asyncio
async def test_resource_view_edit_mode_edits_leaf_object_fields_as_yaml(mock_workflow_with_two_pods):
    """Enter opens a YAML editor for object/array edit rows that have no rendered children."""

    class FakeConfigEditService:
        def __init__(self):
            self.apply_calls = []

        def load_edit_session(self):
            return {
                "raw_yaml": "initial-yaml",
                "edit_state": edit_state_with_kafka_override_leaf(),
            }

        def apply_operation(self, raw_yaml, operation):
            self.apply_calls.append((raw_yaml, operation))
            state = edit_state_with_kafka_override_leaf()
            if operation["op"] == "set":
                state["nodes"][0]["children"][0]["children"][0]["value"] = operation["value"]
            return {
                "raw_yaml": f"updated-yaml-{len(self.apply_calls)}",
                "edit_state": state,
            }

    service = FakeConfigEditService()
    argo_service = ArgoService(
        get_workflow=lambda name, namespace: ({"success": True}, mock_workflow_with_two_pods),
        approve_step=MagicMock(),
    )
    pod_scraper = MagicMock(spec=PodScraperInterface(None, None, None))
    pod_scraper.fetch_pods_metadata.return_value = []

    app = WorkflowTreeApp(
        namespace="default",
        name="test-wf",
        argo_service=argo_service,
        pod_scraper=pod_scraper,
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0,
        resource_view=True,
        config_edit_service=service,
    )

    with patch("console_link.workflow.resource_tree.build_resource_tree",
               return_value=resource_sections_for_manage_tests()):
        async with app.run_test() as pilot:
            tree = app.query_one("#workflow-tree")
            tree.focus()
            assert await wait_until(pilot, lambda: len(tree.root.children) > 0, timeout=5.0)

            await pilot.press("e")
            assert await wait_until(pilot, lambda: get_clean_text_label(tree.root) == "Workflow Config Edit")
            await pilot.press("f")
            assert await wait_until(
                pilot,
                lambda: find_tree_node_by_id(
                    tree.root,
                    "edit:kafkaClusterConfiguration.kafka.autoCreate.clusterSpecOverrides",
                ) is not None,
            )

            app._select_tree_node_by_id("edit:kafkaClusterConfiguration.kafka.autoCreate.clusterSpecOverrides")
            app._update_dynamic_bindings()
            await pilot.pause()

            selected = tree.cursor_node.data["edit_node"]
            assert app._config_edit_enter_description(selected) == "Edit YAML"
            await pilot.press("enter")
            assert await wait_until(pilot, lambda: isinstance(app.screen, StructuredValueModal))
            assert app.screen.query_one("#value", TextArea).text == "{}\n"

            app.screen.query_one("#value", TextArea).load_text("kafka:\n  replicas: 3\n")
            await pilot.press("ctrl+s")

            assert await wait_until(pilot, lambda: len(service.apply_calls) == 1)
            assert await wait_until(pilot, lambda: not isinstance(app.screen, StructuredValueModal))
            assert service.apply_calls[0] == (
                "initial-yaml",
                {
                    "op": "set",
                    "path": [
                        "kafkaClusterConfiguration",
                        "kafka",
                        "autoCreate",
                        "clusterSpecOverrides",
                    ],
                    "value": {"kafka": {"replicas": 3}},
                },
            )

            app._select_tree_node_by_id("edit:kafkaClusterConfiguration.kafka.autoCreate.clusterSpecOverrides")
            app._update_dynamic_bindings()
            await pilot.pause()
            await pilot.press("enter")
            assert await wait_until(pilot, lambda: isinstance(app.screen, StructuredValueModal))
            assert app.screen.query_one("#clear", Button).label.plain == "Clear"
            app.screen.set_focus(app.screen.query_one("#clear", Button))
            await pilot.press("enter")

            assert await wait_until(pilot, lambda: len(service.apply_calls) == 2)
            assert service.apply_calls[1] == (
                "updated-yaml-1",
                {
                    "op": "unset",
                    "path": [
                        "kafkaClusterConfiguration",
                        "kafka",
                        "autoCreate",
                        "clusterSpecOverrides",
                    ],
                },
            )


@pytest.mark.asyncio
async def test_resource_view_edit_mode_edits_scalar_and_boolean_fields(mock_workflow_with_two_pods):
    """Enter edits scalar values, clears optional values, and opens boolean choices."""

    class FakeConfigEditService:
        def __init__(self):
            self.apply_calls = []
            self.validate_calls = []

        def load_edit_session(self):
            return {
                "raw_yaml": "initial-yaml",
                "edit_state": edit_state_with_editable_source_fields(),
            }

        def apply_operation(self, raw_yaml, operation):
            self.apply_calls.append((raw_yaml, operation))
            return {
                "raw_yaml": f"updated-yaml-{len(self.apply_calls)}",
                "edit_state": edit_state_with_editable_source_fields(),
            }

        def validate_operation(self, raw_yaml, operation):
            self.validate_calls.append((raw_yaml, operation))
            state = edit_state_with_editable_source_fields()
            state["validation"] = {
                "valid": False,
                "errors": ["Endpoint will require connectivity review"],
                "diagnostics": [
                    {
                        "severity": "warning",
                        "message": "Endpoint will require connectivity review",
                        "path": ["sourceClusters", "legacy", "endpoint"],
                    }
                ],
            }
            return {
                "raw_yaml": "preview-yaml",
                "edit_state": state,
            }

    service = FakeConfigEditService()
    argo_service = ArgoService(
        get_workflow=lambda name, namespace: ({"success": True}, mock_workflow_with_two_pods),
        approve_step=MagicMock(),
    )
    pod_scraper = MagicMock(spec=PodScraperInterface(None, None, None))
    pod_scraper.fetch_pods_metadata.return_value = []

    app = WorkflowTreeApp(
        namespace="default",
        name="test-wf",
        argo_service=argo_service,
        pod_scraper=pod_scraper,
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0,
        resource_view=True,
        config_edit_service=service,
    )

    with patch("console_link.workflow.resource_tree.build_resource_tree",
               return_value=resource_sections_for_manage_tests()):
        async with app.run_test() as pilot:
            tree = app.query_one("#workflow-tree")
            tree.focus()
            assert await wait_until(pilot, lambda: len(tree.root.children) > 0, timeout=5.0)

            await pilot.press("e")
            assert await wait_until(pilot, lambda: get_clean_text_label(tree.root) == "Workflow Config Edit")

            app._select_tree_node_by_id("edit:sourceClusters.legacy.endpoint")
            app._update_dynamic_bindings()
            await pilot.pause()
            await pilot.press("enter")
            assert await wait_until(pilot, lambda: isinstance(app.screen, TextInputModal))
            value_input = app.screen.query_one("#value")
            original_value = "https://new.example.com:9200"
            assert value_input.value == original_value
            assert "HTTP(S) endpoint URL for the cluster." in str(app.screen.query_one("#documentation").content)
            await pilot.press("backspace")
            assert value_input.value == original_value[:-1]
            value_input.value = "not-an-endpoint"
            await pilot.press("enter")
            assert await wait_until(
                pilot,
                lambda: "Use an http:// or https:// endpoint" in str(app.screen.query_one("#validation").content),
            )
            assert len(service.apply_calls) == 0
            value_input.value = "https://edited.example.com:9200"
            assert await wait_until(pilot, lambda: len(service.validate_calls) == 1, timeout=3.0)
            assert await wait_until(
                pilot,
                lambda: "Endpoint will require connectivity review" in str(
                    app.screen.query_one("#remote-validation").content
                ),
            )
            await pilot.press("enter")

            assert await wait_until(pilot, lambda: len(service.apply_calls) == 1)
            assert await wait_until(pilot, lambda: not isinstance(app.screen, TextInputModal))
            assert await wait_until(pilot, lambda: app._edit_draft_yaml == "updated-yaml-1")
            assert service.apply_calls[0] == (
                "initial-yaml",
                {
                    "op": "set",
                    "path": ["sourceClusters", "legacy", "endpoint"],
                    "value": "https://edited.example.com:9200",
                },
            )

            app._select_tree_node_by_id("edit:sourceClusters.legacy.endpoint")
            app._update_dynamic_bindings()
            await pilot.pause()
            await pilot.press("enter")
            assert await wait_until(pilot, lambda: isinstance(app.screen, TextInputModal))
            assert app.screen.query_one("#clear", Button).label.plain == "Clear"
            app.screen.set_focus(app.screen.query_one("#clear", Button))
            await pilot.press("enter")

            assert await wait_until(pilot, lambda: len(service.apply_calls) == 2)
            assert service.apply_calls[1] == (
                "updated-yaml-1",
                {
                    "op": "unset",
                    "path": ["sourceClusters", "legacy", "endpoint"],
                },
            )

            app._select_tree_node_by_id("edit:sourceClusters.legacy.allowInsecure")
            app._update_dynamic_bindings()
            await pilot.pause()
            assert binding_descriptions(app, "space") == ["Toggle"]

            await pilot.press("enter")
            assert await wait_until(pilot, lambda: isinstance(app.screen, ChoiceSelectModal))
            assert len(service.apply_calls) == 2
            assert app.screen.query_one("#choice-0", Button).label.plain == "unset"
            assert app.screen.query_one("#choice-1", Button).label.plain == "true"
            assert app.screen.query_one("#choice-2", Button).label.plain == "false (current)"
            app.screen.set_focus(app.screen.query_one("#choice-0", Button))
            await pilot.press("enter")

            assert await wait_until(pilot, lambda: len(service.apply_calls) == 3)
            assert service.apply_calls[2] == (
                "updated-yaml-2",
                {
                    "op": "unset",
                    "path": ["sourceClusters", "legacy", "allowInsecure"],
                },
            )


@pytest.mark.asyncio
async def test_show_output_falls_back_to_artifact_s3_key(mock_workflow_with_pod_and_suspend):
    workflow = copy.deepcopy(mock_workflow_with_pod_and_suspend)

    k8s_interface = MagicMock(spec=PodScraperInterface(None, None, None))
    k8s_interface.fetch_pods_metadata.return_value = [
        {"metadata": {"name": "pod-1", "annotations": {"workflows.argoproj.io/node-id": "node-1"}}}
    ]

    argo_service = MagicMock(spec=ArgoService(None, None))
    argo_service.get_workflow.return_value = ({"success": True}, workflow)

    app = WorkflowTreeApp(
        namespace="default",
        name="test-wf",
        argo_service=argo_service,
        pod_scraper=k8s_interface,
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0
    )

    async with app.run_test() as pilot:
        tree = app.query_one("#workflow-tree")
        assert await wait_until(pilot, lambda: len(tree.root.children) > 0, timeout=5.0)
        tree.focus()
        await pilot.press("down")
        await pilot.pause()

        with patch("console_link.workflow.tui.workflow_manage_app.read_managed_output") as mock_read_output, \
                patch.object(app._logs, "show_output_texts_in_pager") as mock_output_pager:
            mock_read_output.return_value.content = "archived s3 output"
            mock_read_output.return_value.ref = {
                "s3Key": "migration-outputs/snapshotmigration/migration-0/uid/metadataEvaluate/wf.log"
            }
            await pilot.press("o")
            await pilot.pause()

            mock_read_output.assert_called_once_with(
                "default", "snapshotmigration.migration-0", "metadataEvaluate"
            )
            mock_output_pager.assert_called_once()
            assert mock_output_pager.call_args.args[1] == [
                ("snapshotmigration.migration-0 / metadataEvaluate", "archived s3 output")
            ]
            assert mock_output_pager.call_args.kwargs == {"clean": True}


def test_managed_output_ref_map_indexes_all_patch_steps(mock_workflow_with_pod_and_suspend):
    workflow = copy.deepcopy(mock_workflow_with_pod_and_suspend)
    workflow["status"]["nodes"]["node-3-patch"] = {
        "id": "node-3-patch",
        "displayName": "patchMetadataMigrateOutput",
        "type": "Pod",
        "phase": PHASE_SUCCEEDED,
        "children": [],
        "inputs": {"parameters": [{"name": "resourceName", "value": "migration-1"}]},
    }

    app = WorkflowTreeApp(
        namespace="default",
        name="test-wf",
        argo_service=MagicMock(spec=ArgoService(None, None)),
        pod_scraper=MagicMock(spec=PodScraperInterface(None, None, None)),
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0,
    )
    app._tree_state._workflow_data = workflow

    assert app._find_output_refs_in_workflow_data("migration-0") == [
        ("snapshotmigration.migration-0", "metadataEvaluate")
    ]
    assert app._find_output_refs_in_workflow_data("migration-1") == [
        ("snapshotmigration.migration-1", "metadataMigrate")
    ]


@pytest.mark.asyncio
async def test_follow_logs_binding_responds_to_phase_changes(mock_workflow_with_two_pods):
    """Verify follow logs only works for Running pods and updates as phase changes."""

    workflow = copy.deepcopy(mock_workflow_with_two_pods)
    # Start with node-2 as Pending (not running)
    workflow["status"]["nodes"]["node-2"]["phase"] = "Pending"

    mock_pod = MagicMock()
    mock_pod.spec.init_containers = []
    mock_pod.spec.containers = [MagicMock(name="main")]

    k8s_interface = MagicMock(spec=PodScraperInterface(None, None, None))
    k8s_interface.fetch_pods_metadata.return_value = [
        {"metadata": {"name": "pod-1", "annotations": {"workflows.argoproj.io/node-id": "node-1"}}},
        {"metadata": {"name": "pod-2", "annotations": {"workflows.argoproj.io/node-id": "node-2"}}}
    ]
    k8s_interface.read_pod.return_value = mock_pod

    argo_service = MagicMock(spec=ArgoService(None, None))
    argo_service.get_workflow.return_value = ({"success": True}, copy.deepcopy(workflow))

    app = WorkflowTreeApp(
        namespace="default",
        name="test-wf",
        argo_service=argo_service,
        pod_scraper=k8s_interface,
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0
    )

    async with app.run_test() as pilot:
        tree = app.query_one("#workflow-tree")
        assert await wait_until(pilot, lambda: len(tree.root.children) > 0, timeout=5.0)
        tree.focus()

        # Navigate to node-2 (Pending)
        await pilot.press("down")
        await pilot.press("down")
        await pilot.pause()

        # 'f' should NOT trigger follow_logs for Pending pod
        with patch.object(app._logs, "follow_logs") as mock_follow:
            await pilot.press("f")
            await pilot.pause()
            mock_follow.assert_not_called()

        # Update workflow: node-2 is now Running
        workflow["status"]["nodes"]["node-2"]["phase"] = PHASE_RUNNING
        workflow["metadata"]["resourceVersion"] = "124"
        argo_service.get_workflow.return_value = ({"success": True}, copy.deepcopy(workflow))

        # Trigger refresh without moving focus
        await pilot.press("r")
        assert await wait_until(pilot, lambda: app.current_node_data and
                                app.current_node_data.get('phase') == PHASE_RUNNING, timeout=5.0)

        # Now 'f' SHOULD trigger follow_logs
        with patch.object(app._logs, "follow_logs") as mock_follow:
            await pilot.press("f")
            await pilot.pause()
            mock_follow.assert_called_once()

        # Update workflow: node-2 is now Succeeded
        workflow["status"]["nodes"]["node-2"]["phase"] = PHASE_SUCCEEDED
        workflow["metadata"]["resourceVersion"] = "125"
        argo_service.get_workflow.return_value = ({"success": True}, copy.deepcopy(workflow))

        # Trigger refresh without moving focus
        await pilot.press("r")
        assert await wait_until(pilot, lambda: app.current_node_data and
                                app.current_node_data.get('phase') == PHASE_SUCCEEDED, timeout=5.0)

        # 'f' should NOT trigger follow_logs for Succeeded pod
        with patch.object(app._logs, "follow_logs") as mock_follow:
            await pilot.press("f")
            await pilot.pause()
            mock_follow.assert_not_called()


@pytest.mark.asyncio
async def test_manual_refresh_consistency(mock_workflow_with_two_pods):
    """Verify manual refresh forces a non-cached (strongly consistent) K8s metadata fetch."""

    fetch_log = []

    def fetch_metadata_mock(wf_name, ns, use_cache):
        fetch_log.append(use_cache)
        if use_cache:
            # Cached only sees pod-1
            return [{"metadata": {"name": "p1", "annotations": {"workflows.argoproj.io/node-id": "node-1"}}}]
        # Strong sees both
        return [
            {"metadata": {"name": "p1", "annotations": {"workflows.argoproj.io/node-id": "node-1"}}},
            {"metadata": {"name": "p2", "annotations": {"workflows.argoproj.io/node-id": "node-2"}}}
        ]

    k8s_interface = MagicMock(spec=PodScraperInterface(None, None, None))
    k8s_interface.fetch_pods_metadata.side_effect = fetch_metadata_mock

    argo_service = MagicMock(spec=ArgoService(None, None))
    argo_service.get_workflow.return_value = ({"success": True}, mock_workflow_with_two_pods)

    app = WorkflowTreeApp(
        namespace="default", name="test-wf",
        argo_service=argo_service,
        pod_scraper=k8s_interface,
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0
    )

    async with app.run_test() as pilot:
        # Initial auto-refresh (cached)
        await pilot.pause()

        # Manual refresh (r)
        await pilot.press("r")
        await pilot.pause()

        # Check the log: the most recent call should have use_cache=False
        assert False in fetch_log
        assert app._pods.cache["node-2"] == "p2"


@pytest.mark.asyncio
async def test_live_check_lifecycle(mock_workflow_with_two_pods):
    """
    Test that Live Status appears when the last node is Running
    and disappears when a new Succeeded node is added.
    """
    # Setup with two pods, where the second one is still running
    workflow = mock_workflow_with_two_pods
    workflow_nodes = workflow["status"]["nodes"]
    workflow["status"]["nodes"] = {}

    argo_service = MagicMock(spec=ArgoService(None, None))

    def mocked_get_workflow(*args, **kwargs):

        logging.info("Mock: get_workflow called!")
        logging.info(f"Mock: Current node count in 'workflow' variable: "
                     f"{len(workflow['status'].get('nodes', {}))}")

        # Create the result
        result = ({"success": True}, copy.deepcopy(workflow))

        logging.debug(f"Mock: Returning: {result}")
        return result
    argo_service.get_workflow.side_effect = mocked_get_workflow

    app = WorkflowTreeApp(
        namespace="default", name="test",
        argo_service=argo_service,
        pod_scraper=MagicMock(),
        workflow_waiter=MagicMock(),
        refresh_interval=0.10
    )

    async with app.run_test() as pilot:
        tree = app.query_one("#workflow-tree")

        # Helper for checking node existence
        def has_live_status():
            return any("Live Status" in str(c.label) for c in tree.root.children)

        # start empty
        await pilot.pause()
        assert not has_live_status()

        logging.info("No Live Status node after the initial load")
        logging.info("Adding one running node in, so a live status node should appear")
        workflow["status"]["nodes"] = workflow_nodes
        workflow["metadata"]["resourceVersion"] = "124"
        logging.info("Updated resourceVersion to 124")
        assert await wait_until(pilot, has_live_status), "Live Status never appeared"

        # Verify status check is called and results rendered when expanded
        mock_status_result = {"success": True, "value": "Progress: 50%\nDocs: 1000/2000"}
        with patch("console_link.workflow.tui.live_status_manager.StatusCheckRunner.run_status_check",
                   return_value=mock_status_result) as mock_check, \
             patch("console_link.workflow.tui.live_status_manager.ConfigConverter.convert_with_jq",
                   return_value="{}"), \
             patch("console_link.workflow.tui.live_status_manager.Environment"):
            live_node = next(c for c in tree.root.children if "Live Status" in str(c.label))
            live_node.expand()
            assert await wait_until(pilot, lambda: mock_check.call_count > 0, timeout=30.0), \
                "StatusCheckRunner.run_status_check was never called"
            assert await wait_until(
                pilot, lambda: any("Progress" in str(c.label) for c in live_node.children), timeout=3.0
            ), f"Status results not rendered. Children: {[str(c.label) for c in live_node.children]}"

            # Verify continued polling - wait for at least one more call
            prev_count = mock_check.call_count
            assert await wait_until(pilot, lambda: mock_check.call_count > prev_count, timeout=3.0), \
                "Status check not called again while expanded"

            # Collapse and verify no more calls.
            # Allow any in-flight check to finish before capturing the baseline.
            live_node.collapse()
            await pilot.pause(0.5)
            count_after_collapse = mock_check.call_count
            await pilot.pause(0.5)
            assert mock_check.call_count == count_after_collapse, \
                f"Status check called while collapsed: {mock_check.call_count} > {count_after_collapse}"

        logging.info("Found live status node, marking the last item as succeeded.")
        logging.info("Will wait for Live Status node to disappear.")
        workflow["status"]["nodes"]["node-2"]["phase"] = PHASE_SUCCEEDED
        workflow["metadata"]["resourceVersion"] = "125"
        assert await wait_until(pilot, lambda: not has_live_status()), "Live Status didn't disappear"


@pytest.mark.asyncio
async def test_workflow_restart_clears_and_rebuilds_tree():
    """Verify that when a workflow restarts (new startedAt), the tree is cleared and rebuilt."""

    workflow_v1 = {
        "metadata": {"name": "test-wf", "resourceVersion": "123"},
        "status": {
            "startedAt": "2023-01-01T00:00:00Z",
            "nodes": {
                "node-1": {"id": "node-1", "displayName": "step-1", "type": "Pod",
                           "phase": PHASE_RUNNING, "children": []},
                "node-2": {"id": "node-2", "displayName": "step-2", "type": "Pod",
                           "phase": PHASE_RUNNING, "children": []}
            }
        }
    }

    workflow_v2 = {
        "metadata": {"name": "test-wf", "resourceVersion": "999"},
        "status": {
            "startedAt": "2023-01-02T00:00:00Z",  # New run
            "nodes": {
                "node-new": {"id": "node-new", "displayName": "fresh-step", "type": "Pod",
                             "phase": PHASE_RUNNING, "children": []}
            }
        }
    }

    current_workflow = [workflow_v1]

    argo_service = MagicMock(spec=ArgoService(None, None))
    argo_service.get_workflow.side_effect = lambda *a, **kw: ({"success": True}, copy.deepcopy(current_workflow[0]))

    app = WorkflowTreeApp(
        namespace="default", name="test-wf",
        argo_service=argo_service,
        pod_scraper=MagicMock(),
        workflow_waiter=FAILING_WAITER,
        refresh_interval=0.1
    )

    async with app.run_test() as pilot:
        tree = app.query_one("#workflow-tree")
        assert await wait_until(pilot, lambda: len(tree.root.children) == 2, timeout=5.0), \
            f"Initial workflow nodes not loaded. Children: {len(tree.root.children)}"

        old_labels = {get_clean_text_label(c) for c in tree.root.children}
        assert any("step-1" in lbl for lbl in old_labels)

        # Simulate workflow restart
        current_workflow[0] = workflow_v2

        assert await wait_until(pilot, lambda: len(tree.root.children) == 1, timeout=5.0), \
            f"Tree not rebuilt after workflow restart. Children: {len(tree.root.children)}"

        new_labels = {get_clean_text_label(c) for c in tree.root.children}
        assert any("fresh-step" in lbl for lbl in new_labels), \
            f"New node not found. Labels: {new_labels}"


@pytest.mark.asyncio
async def test_node_phase_update_preserves_tree_structure():
    """Verify that phase changes update nodes in-place without rebuilding the tree."""

    workflow = {
        "metadata": {"name": "test-wf", "resourceVersion": "123"},
        "status": {
            "startedAt": "2023-01-01T00:00:00Z",
            "nodes": {
                "node-1": {"id": "node-1", "displayName": "step-1", "type": "Pod",
                           "phase": "Failed", "children": []},
                "node-2": {"id": "node-2", "displayName": "step-2", "type": "Pod",
                           "phase": PHASE_RUNNING, "children": []}
            }
        }
    }

    argo_service = MagicMock(spec=ArgoService(None, None))
    argo_service.get_workflow.side_effect = lambda *a, **kw: ({"success": True}, copy.deepcopy(workflow))

    app = WorkflowTreeApp(
        namespace="default", name="test-wf",
        argo_service=argo_service,
        pod_scraper=MagicMock(),
        workflow_waiter=FAILING_WAITER,
        refresh_interval=0.1
    )

    async with app.run_test() as pilot:
        tree = app.query_one("#workflow-tree")
        assert await wait_until(pilot, lambda: len(tree.root.children) == 2, timeout=5.0)

        # Find the running node
        def get_node_2_label():
            for c in tree.root.children:
                if "step-2" in get_clean_text_label(c):
                    return get_clean_text_label(c)
            return ""

        assert await wait_until(pilot, lambda: "Running" in get_node_2_label(), timeout=5.0), \
            f"Expected Running phase in label: {get_node_2_label()}"

        # Update phase
        workflow["status"]["nodes"]["node-2"]["phase"] = PHASE_SUCCEEDED
        workflow["metadata"]["resourceVersion"] = "124"

        assert await wait_until(pilot, lambda: "Succeeded" in get_node_2_label(), timeout=5.0), \
            f"Phase not updated. Current label: {get_node_2_label()}"

        # Tree structure preserved
        assert len(tree.root.children) == 2, "Tree structure changed unexpectedly"


@pytest.mark.asyncio
async def test_live_check_lifecycle_with_group():
    """
    Test that Live Status appears as a sibling AFTER a group node with groupName=checks,
    not inside the group.
    """
    workflow = {
        "metadata": {"name": "test-wf", "resourceVersion": "123"},
        "status": {
            "startedAt": "2023-01-01T00:00:00Z",
            "nodes": {
                "group-1": {
                    "id": "group-1", "displayName": "waitingBlock", "type": "StepGroup",
                    "phase": PHASE_RUNNING, "children": ["node-1", "node-2"],
                    "inputs": {"parameters": [{"name": "groupName_view", "value": "checks"}]}
                },
                "node-1": {"id": "node-1", "displayName": "step-1", "type": "Pod", "phase": "Failed",
                           "children": [], "startedAt": "2023-01-01T00:01:00Z",
                           "boundaryID": "group-1",
                           "inputs": {"parameters": [{"name": "configContents", "value": "cfg"}]}},
                "node-2": {"id": "node-2", "displayName": "step-2", "type": "Pod", "phase": PHASE_RUNNING,
                           "children": [], "startedAt": "2023-01-01T00:02:00Z",
                           "boundaryID": "group-1",
                           "inputs": {"parameters": [{"name": "configContents", "value": "cfg"}]}}
            }
        }
    }

    argo_service = MagicMock(spec=ArgoService(None, None))
    argo_service.get_workflow.side_effect = lambda *a, **kw: ({"success": True}, copy.deepcopy(workflow))

    app = WorkflowTreeApp(
        namespace="default", name="test",
        argo_service=argo_service,
        pod_scraper=MagicMock(),
        workflow_waiter=MagicMock(),
        refresh_interval=0.10
    )

    async with app.run_test() as pilot:
        tree = app.query_one("#workflow-tree")

        def has_live_status_at_root():
            return any("Live Status" in str(c.label) for c in tree.root.children)

        def has_live_status_in_group():
            for c in tree.root.children:
                if "waiting block" in str(c.label).lower():
                    return any("Live Status" in str(gc.label) for gc in c.children)
            return False

        await pilot.pause()
        assert await wait_until(pilot, has_live_status_at_root), \
            "Live Status should appear as sibling to group node"
        assert not has_live_status_in_group(), \
            "Live Status should NOT appear inside the checks group"

        # Verify Live Status is positioned after the group node
        root_labels = [str(c.label) for c in tree.root.children]
        group_idx = next((i for i, lbl in enumerate(root_labels) if "waiting block" in lbl.lower()), None)
        assert group_idx is not None, f"waiting block not found in root. Labels: {root_labels}"
        live_idx = next(i for i, lbl in enumerate(root_labels) if "Live Status" in lbl)
        assert live_idx > group_idx, "Live Status should appear after the group node"


@pytest.mark.parametrize("env_updates, expected_wrapper", [
    ({"SSH_TTY": "/dev/pts/0", "TERM": "xterm-256color"}, "{osc}"),
    ({"SSH_TTY": "/dev/pts/0", "TERM": "xterm-256color", "TMUX": "1"}, "\x1bPtmux;\x1b{osc}\x1b\\")
], ids=["standard_ssh", "tmux_ssh"])
def test_copy_to_clipboard_protocol_logic(env_updates, expected_wrapper, mocker):
    """Verifies raw escape sequences for terminal-based clipboard."""
    test_text = "test-pod"
    b64_val = base64.b64encode(test_text.encode()).decode()
    raw_osc = f"\x1b]52;c;{b64_val}\x07"
    expected_output = expected_wrapper.format(osc=raw_osc)

    mocker.patch.dict("os.environ", env_updates, clear=False)
    mock_stdout = mocker.patch("sys.stdout.write")

    assert copy_to_clipboard(test_text) is True
    mock_stdout.assert_called_with(expected_output)


@pytest.mark.asyncio
async def test_tree_renders_with_artifact_outputs():
    """Verify workflow manage renders without crashing when nodes have artifact-based statusOutput.

    Regression test for TypeError: can only concatenate str (not "ArtifactRef") to str.
    When Argo stores statusOutput as an S3 artifact instead of an inline parameter,
    get_step_status_output returns an ArtifactRef object. The TUI cannot resolve artifacts
    (that requires an API call), so the status output is omitted from the label.

    Contrast with parameter-based statusOutput (node-3), which IS shown inline.
    """
    workflow = {
        "metadata": {"name": "test-wf", "resourceVersion": "123"},
        "status": {
            "startedAt": "2023-01-01T00:00:00Z",
            "nodes": {
                "node-1": {
                    "id": "node-1", "displayName": "Check Snapshot", "type": "Pod",
                    "phase": PHASE_SUCCEEDED, "children": [],
                    "startedAt": "2023-01-01T00:01:00Z",
                    "finishedAt": "2023-01-01T00:02:00Z",
                    "inputs": {"parameters": [{"name": "configContents", "value": "cfg"}]},
                    "outputs": {
                        "parameters": [
                            {"name": "overriddenPhase", "value": "", "valueFrom": {"path": "/tmp/phase-output.txt"}}
                        ],
                        "artifacts": [
                            {"name": "statusOutput", "path": "/tmp/status-output.txt",
                             "s3": {"key": "argo-artifacts/test-wf/node-1/statusOutput"},
                             "archive": {"none": {}}}
                        ]
                    }
                },
                "node-2": {
                    "id": "node-2", "displayName": "Migrate Data", "type": "Pod",
                    "phase": PHASE_RUNNING, "children": [],
                    "startedAt": "2023-01-01T00:02:00Z",
                    "inputs": {"parameters": []},
                    "outputs": {"parameters": [], "artifacts": []}
                },
                "node-3": {
                    "id": "node-3", "displayName": "Create Snapshot", "type": "Pod",
                    "phase": PHASE_SUCCEEDED, "children": [],
                    "startedAt": "2023-01-01T00:00:30Z",
                    "finishedAt": "2023-01-01T00:01:00Z",
                    "inputs": {"parameters": [{"name": "configContents", "value": "cfg"}]},
                    "outputs": {
                        "parameters": [
                            {"name": "statusOutput", "value": "snapshot completed successfully"}
                        ],
                        "artifacts": []
                    }
                }
            }
        }
    }

    argo_service = MagicMock(spec=ArgoService(None, None))
    argo_service.get_workflow.return_value = ({"success": True}, workflow)

    app = WorkflowTreeApp(
        namespace="default", name="test-wf",
        argo_service=argo_service,
        pod_scraper=MagicMock(),
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0
    )

    async with app.run_test() as pilot:
        tree = app.query_one("#workflow-tree")
        assert await wait_until(pilot, lambda: len(tree.root.children) == 3, timeout=5.0), \
            f"Expected 3 nodes, got {len(tree.root.children)}"

        labels = {get_clean_text_label(c) for c in tree.root.children}

        # node-1: artifact-based statusOutput — NOT shown in TUI label (can't resolve without API call)
        check_label = next(lbl for lbl in labels if "Check Snapshot" in lbl)
        assert "ArtifactRef" not in check_label, f"Raw ArtifactRef leaked into label: {check_label}"
        assert "argo-artifacts" not in check_label, f"S3 key leaked into label: {check_label}"

        # node-3: parameter-based statusOutput — IS shown inline in TUI label
        create_label = next(lbl for lbl in labels if "Create Snapshot" in lbl)
        assert "snapshot completed successfully" in create_label, \
            f"Parameter statusOutput should appear in label. Got: {create_label}"

        # node-2: no statusOutput at all
        migrate_label = next(lbl for lbl in labels if "Migrate Data" in lbl)
        assert "Running" in migrate_label, f"Expected Running phase. Got: {migrate_label}"
