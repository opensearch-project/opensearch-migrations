"""Service boundary for schema-driven workflow config edit state."""

import base64
import json
import re
import subprocess
import tempfile
import time
from dataclasses import dataclass
from typing import Any, Dict, Optional

from kubernetes import client
from kubernetes.client.rest import ApiException

from ..commands.argo_utils import workflow_exists, stop_workflow, delete_workflow, wait_until_workflow_deleted
from ..commands.crd_utils import list_resources_full
from ..commands.secret_utils import get_credentials_secret_store_for_namespace, verify_configured_secrets_exist
from ..models.config import WorkflowConfig
from ..models.utils import load_k8s_config
from ..models.workflow_config_store import WorkflowConfigStore
from .script_runner import ScriptRunner


@dataclass
class ConfigEditSession:
    raw_yaml: str
    edit_state: Dict[str, Any]


@dataclass
class ConfigEditApplyResult:
    raw_yaml: str
    edit_state: Dict[str, Any]


@dataclass
class ConfigEditService:
    """Loads edit-state DTOs from config-processor.

    Python intentionally treats the returned state as a generic rendering
    contract. The workflow schema, descriptions, variants, validation, and
    branch diagnostics are computed by the TypeScript config-processor.
    """

    namespace: str
    store: Optional[WorkflowConfigStore] = None
    runner: Optional[ScriptRunner] = None
    session_name: str = "default"

    def load_edit_session(self) -> ConfigEditSession:
        store = self.store or WorkflowConfigStore(namespace=self.namespace)
        config = store.load_config(self.session_name)
        raw_yaml = config.raw_yaml if config else ""
        return ConfigEditSession(
            raw_yaml=raw_yaml,
            edit_state=self._run_edit_state(raw_yaml),
        )

    def load_edit_state(self) -> Dict[str, Any]:
        return self.load_edit_session().edit_state

    def apply_operation(self, raw_yaml: str, operation: Dict[str, Any]) -> ConfigEditApplyResult:
        with tempfile.NamedTemporaryFile(mode="w", suffix=".yaml", delete=True) as operation_file:
            json.dump(operation, operation_file)
            operation_file.flush()

            with tempfile.NamedTemporaryFile(mode="w", suffix=".yaml", delete=True) as config_file:
                config_file.write(raw_yaml)
                config_file.flush()
                output = self._run_config_processor_node_script(
                    "editConfig",
                    "apply",
                    "--pending-config",
                    config_file.name,
                    "--operation",
                    operation_file.name,
                )

        result = json.loads(output)
        return ConfigEditApplyResult(
            raw_yaml=result["yaml"],
            edit_state=result["editState"],
        )

    def validate_operation(self, raw_yaml: str, operation: Dict[str, Any]) -> ConfigEditApplyResult:
        """Preview one operation through TS validation without saving the result."""
        return self.apply_operation(raw_yaml, operation)

    def save_raw_yaml(self, raw_yaml: str) -> str:
        store = self.store or WorkflowConfigStore(namespace=self.namespace)
        return store.save_config(WorkflowConfig(raw_yaml=raw_yaml), self.session_name)

    def list_external_resources(
        self,
        external_ref: Dict[str, Any],
        current_value: Optional[Any] = None,
    ) -> list[Dict[str, Any]]:
        """List Kubernetes resources matching an edit-node externalRef hint."""
        k8s_hint = _normalized_k8s_hint(external_ref)
        resource_types = _external_resource_types(external_ref, k8s_hint)
        current = _external_current_ref(current_value)
        rows: list[Dict[str, Any]] = []
        for resource_type in resource_types:
            rows.extend(self._list_external_kubernetes_rows(k8s_hint, resource_type, current))
        if current.get("name") and not any(_row_matches_current(row, current) for row in rows):
            first_type = resource_types[0] if resource_types else {}
            rows.append({
                "name": current["name"],
                "kind": current.get("kind") or first_type.get("kind") or "Resource",
                "group": current.get("group") or first_type.get("group") or "",
                "version": first_type.get("version") or "",
                "keys": [],
                "status": "warn",
                "message": "current YAML value was not found in Kubernetes",
                "current": True,
            })
        return _sort_external_rows(rows)

    def read_external_resource(self, external_ref: Dict[str, Any], name: str) -> Dict[str, Any]:
        """Read one external resource for descriptor-driven view/update panes."""
        resource_type = _create_output_resource_type(external_ref)
        kind = resource_type.get("kind")
        if kind == "Secret":
            secret = self._core_v1().read_namespaced_secret(name=name, namespace=self.namespace)
            values = {
                key: _decode_k8s_data_value(value)
                for key, value in (secret.data or {}).items()
            }
            return {
                "kind": "Secret",
                "name": name,
                "type": secret.type or "Opaque",
                "keys": sorted(values.keys()),
                "values": values,
            }
        if kind == "ConfigMap":
            config_map = self._core_v1().read_namespaced_config_map(name=name, namespace=self.namespace)
            values = dict(config_map.data or {})
            return {
                "kind": "ConfigMap",
                "name": name,
                "keys": sorted(values.keys()),
                "values": values,
            }
        raise ValueError(f"External resource kind is not supported: {kind}")

    def save_external_resource(
        self,
        external_ref: Dict[str, Any],
        values: Dict[str, str],
        existing_name: Optional[str] = None,
    ) -> Dict[str, str]:
        """Create or update the resource described by an externalRef create descriptor."""
        create = external_ref.get("create") or {}
        output = create.get("output") or {}
        name_field = (create.get("apply") or {}).get("nameField")
        name = str(values.get(name_field) or existing_name or "").strip()
        if not name:
            raise ValueError("External resource name is required")
        if output.get("kind") == "Secret":
            return self._save_external_secret(external_ref, output, values, name, existing_name)
        if output.get("kind") == "ConfigMap":
            return self._save_external_config_map(external_ref, output, values, name)
        raise ValueError(f"External resource output kind is not supported: {output.get('kind')}")

    def load_resource_config_snapshots(self, workflow_name: Optional[str] = None) -> Dict[str, Optional[Dict[str, Any]]]:
        """Load resolved resource snapshots for current submitted and saved config."""
        submitted = self.load_latest_submitted_resolved_config(workflow_name)
        pending = self.load_pending_resolved_config(workflow_name, validation_mode="loose")
        submitted_console = (submitted or {}).get("consoleResources")
        if submitted and submitted_console is None and submitted.get("workflowConfig"):
            submitted_console = self._run_resolve_console_resources(submitted, "--resolved-config")
        pending_console = (pending or {}).get("consoleResources")
        if pending and pending_console is None and pending.get("workflowConfig"):
            pending_console = self._run_resolve_console_resources(pending, "--resolved-config")
        return {
            "submitted": submitted,
            "pending": pending,
            "submitted_console": submitted_console,
            "pending_console": pending_console,
        }

    def load_pending_resolved_config(
        self,
        workflow_name: Optional[str] = None,
        validation_mode: str = "strict",
    ) -> Optional[Dict[str, Any]]:
        store = self.store or WorkflowConfigStore(namespace=self.namespace)
        config = store.load_config(self.session_name)
        raw_yaml = config.raw_yaml if config else ""
        if not raw_yaml.strip():
            return None
        return self._run_resolve_migration_resources(
            raw_yaml,
            "--user-config",
            workflow_name,
            validation_mode=validation_mode,
        )

    def load_latest_submitted_resolved_config(self, workflow_name: Optional[str] = None) -> Optional[Dict[str, Any]]:
        runs = list_resources_full(self.namespace, ["migrationruns"]).get("migrationruns", [])
        if workflow_name:
            runs = [
                run for run in runs
                if (run.get("spec") or {}).get("workflowName") == workflow_name
            ]
        if not runs:
            return None
        latest = sorted(runs, key=_migration_run_sort_key, reverse=True)[0]
        return latest.get("spec", {}).get("resolvedConfig")

    def submit_saved_config(
        self,
        workflow_name: str = "migration",
        unique_run_nonce: Optional[str] = None,
    ) -> Dict[str, Any]:
        store = self.store or WorkflowConfigStore(namespace=self.namespace)
        config = store.load_config(self.session_name)
        if not config or not config.raw_yaml.strip():
            raise ValueError(f"No workflow configuration found for session '{self.session_name}'")

        load_k8s_config()
        secret_store = get_credentials_secret_store_for_namespace(self.namespace)
        verify_configured_secrets_exist(secret_store, config.raw_yaml)

        if workflow_exists(self.namespace, workflow_name):
            stop_workflow(self.namespace, workflow_name)
            delete_workflow(self.namespace, workflow_name)
            if not wait_until_workflow_deleted(self.namespace, workflow_name):
                raise TimeoutError(f"Timed out waiting for workflow '{workflow_name}' to be deleted")

        runner = self.runner or ScriptRunner()
        return runner.submit_workflow(
            config.raw_yaml,
            [
                "--workflow-name", workflow_name,
                "--unique-run-nonce", unique_run_nonce or str(int(time.time())),
            ],
        )

    def _run_edit_state(self, raw_yaml: str) -> Dict[str, Any]:
        with tempfile.NamedTemporaryFile(mode="w", suffix=".yaml", delete=True) as temp_file:
            temp_file.write(raw_yaml)
            temp_file.flush()
            output = self._run_config_processor_node_script(
                "editConfig",
                "state",
                "--pending-config",
                temp_file.name,
            )

        return json.loads(output)

    def _run_resolve_migration_resources(
        self,
        input_data: str,
        input_arg: str,
        workflow_name: Optional[str] = None,
        validation_mode: str = "strict",
    ) -> Dict[str, Any]:
        with tempfile.NamedTemporaryFile(mode="w", suffix=".yaml", delete=True) as temp_file:
            temp_file.write(input_data)
            temp_file.flush()
            args = [
                "resolveMigrationResources",
                input_arg,
                temp_file.name,
            ]
            if workflow_name:
                args.extend(["--workflow-name", workflow_name])
            if validation_mode != "strict":
                args.extend(["--validation-mode", validation_mode])
            output = self._run_config_processor_node_script(*args)

        return json.loads(output)

    def _run_resolve_console_resources(
        self,
        input_data: Dict[str, Any],
        input_arg: str,
    ) -> Dict[str, Any]:
        with tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=True) as temp_file:
            temp_file.write(json.dumps(input_data))
            temp_file.flush()
            output = self._run_config_processor_node_script(
                "resolveConsoleResources",
                input_arg,
                temp_file.name,
            )

        return json.loads(output)

    def _run_config_processor_node_script(self, *args: str) -> str:
        runner = self.runner or ScriptRunner()
        try:
            return runner.run_config_processor_node_script(*args)
        except subprocess.CalledProcessError as e:
            raise RuntimeError(_format_config_processor_error(e)) from e

    def _core_v1(self):
        load_k8s_config()
        api_client = client.ApiClient(client.Configuration.get_default_copy())
        return client.CoreV1Api(api_client)

    def _custom_objects(self):
        load_k8s_config()
        api_client = client.ApiClient(client.Configuration.get_default_copy())
        return client.CustomObjectsApi(api_client)

    def _list_external_kubernetes_rows(
        self,
        k8s_hint: Dict[str, Any],
        resource_type: Dict[str, Any],
        current: Dict[str, str],
    ) -> list[Dict[str, Any]]:
        group = str(resource_type.get("group") or "")
        version = str(resource_type.get("version") or "v1")
        kind = str(resource_type.get("kind") or "")
        if group == "" and version == "v1" and kind == "Secret":
            return self._list_external_secret_rows(k8s_hint, current)
        if group == "" and version == "v1" and kind == "ConfigMap":
            return self._list_external_config_map_rows(k8s_hint, current)
        return self._list_external_custom_resource_rows(resource_type, current)

    def _list_external_secret_rows(
        self,
        k8s_hint: Dict[str, Any],
        current: Dict[str, str],
    ) -> list[Dict[str, Any]]:
        required_keys = list(_k8s_match_value(k8s_hint, "requiredKeys") or [])
        accepted_types = set(_k8s_match_value(k8s_hint, "acceptedSecretTypes") or [])
        rows: list[Dict[str, Any]] = []
        secrets = self._core_v1().list_namespaced_secret(namespace=self.namespace)
        for secret in secrets.items:
            name = getattr(secret.metadata, "name", None)
            if not name:
                continue
            secret_type = secret.type or "Opaque"
            values = {
                key: _decode_k8s_data_value(value)
                for key, value in (secret.data or {}).items()
            }
            keys = sorted(values.keys())
            status, message = _external_secret_status(secret_type, keys, required_keys, accepted_types)
            status, message = _merge_external_status(
                status,
                message,
                _external_content_validation_messages(k8s_hint, values),
            )
            rows.append({
                "name": name,
                "kind": "Secret",
                "group": "",
                "version": "v1",
                "namespaced": True,
                "type": secret_type,
                "keys": keys,
                "status": status,
                "message": message,
                "current": _row_matches_current({
                    "name": name,
                    "kind": "Secret",
                    "group": "",
                }, current),
            })
        return rows

    def _list_external_config_map_rows(
        self,
        k8s_hint: Dict[str, Any],
        current: Dict[str, str],
    ) -> list[Dict[str, Any]]:
        required_keys = list(_k8s_match_value(k8s_hint, "requiredKeys") or [])
        rows: list[Dict[str, Any]] = []
        config_maps = self._core_v1().list_namespaced_config_map(namespace=self.namespace)
        for config_map in config_maps.items:
            name = getattr(config_map.metadata, "name", None)
            if not name:
                continue
            values = dict(config_map.data or {})
            keys = sorted(values.keys())
            missing = [key for key in required_keys if key not in keys]
            content_messages = _external_content_validation_messages(k8s_hint, values)
            messages = ([f"missing {', '.join(missing)}"] if missing else []) + content_messages
            rows.append({
                "name": name,
                "kind": "ConfigMap",
                "group": "",
                "version": "v1",
                "namespaced": True,
                "keys": keys,
                "status": "warn" if messages else "matching",
                "message": "; ".join(messages),
                "current": _row_matches_current({
                    "name": name,
                    "kind": "ConfigMap",
                    "group": "",
                }, current),
            })
        return rows

    def _list_external_custom_resource_rows(
        self,
        resource_type: Dict[str, Any],
        current: Dict[str, str],
    ) -> list[Dict[str, Any]]:
        group = str(resource_type.get("group") or "")
        version = str(resource_type.get("version") or "v1")
        kind = str(resource_type.get("kind") or "Resource")
        plural = str(resource_type.get("plural") or _plural_for_kind(kind))
        namespaced = bool(resource_type.get("namespaced"))
        api = self._custom_objects()
        try:
            if namespaced:
                listed = api.list_namespaced_custom_object(group, version, self.namespace, plural)
            else:
                listed = api.list_cluster_custom_object(group, version, plural)
        except ApiException as e:
            if current.get("name") and _current_matches_resource_type(current, resource_type):
                return [{
                    "name": current["name"],
                    "kind": current.get("kind") or kind,
                    "group": current.get("group") or group,
                    "version": version,
                    "namespaced": namespaced,
                    "keys": [],
                    "status": "warn",
                    "message": f"could not list {kind}: {e.reason or e.status}",
                    "current": True,
                }]
            return []

        rows: list[Dict[str, Any]] = []
        for item in listed.get("items") or []:
            metadata = item.get("metadata") or {}
            name = metadata.get("name")
            if not name:
                continue
            status, message = _custom_resource_match_status(item)
            rows.append({
                "name": name,
                "kind": kind,
                "group": group,
                "version": version,
                "apiVersion": f"{group}/{version}" if group else version,
                "namespaced": namespaced,
                "keys": [],
                "status": status,
                "message": message,
                "current": _row_matches_current({
                    "name": name,
                    "kind": kind,
                    "group": group,
                }, current),
            })
        return rows

    def _save_external_secret(
        self,
        external_ref: Dict[str, Any],
        output: Dict[str, Any],
        values: Dict[str, str],
        name: str,
        existing_name: Optional[str],
    ) -> Dict[str, str]:
        string_data: Dict[str, str] = {}
        existing_values: Dict[str, str] = {}
        if existing_name:
            try:
                existing_values = self.read_external_resource(external_ref, existing_name).get("values", {})
            except ApiException as e:
                if e.status != 404:
                    raise
        for key, source in (output.get("stringData") or {}).items():
            field_name = source.get("fromField")
            value = values.get(field_name)
            if value == "" and key in existing_values:
                continue
            if value is not None:
                string_data[key] = value
        body = client.V1Secret(
            metadata=client.V1ObjectMeta(
                name=name,
                labels=_external_resource_labels(external_ref),
            ),
            type=output.get("type") or "Opaque",
            string_data=string_data,
        )
        core = self._core_v1()
        try:
            core.patch_namespaced_secret(name=name, namespace=self.namespace, body=body)
            message = f"Secret updated: {name}"
        except ApiException as e:
            if e.status != 404:
                raise
            core.create_namespaced_secret(namespace=self.namespace, body=body)
            message = f"Secret created: {name}"
        return {"name": name, "message": message}

    def _save_external_config_map(
        self,
        external_ref: Dict[str, Any],
        output: Dict[str, Any],
        values: Dict[str, str],
        name: str,
    ) -> Dict[str, str]:
        data = {
            key: values.get(source.get("fromField"), "")
            for key, source in (output.get("data") or {}).items()
            if values.get(source.get("fromField")) is not None
        }
        body = client.V1ConfigMap(
            metadata=client.V1ObjectMeta(
                name=name,
                labels=_external_resource_labels(external_ref),
            ),
            data=data,
        )
        core = self._core_v1()
        try:
            core.patch_namespaced_config_map(name=name, namespace=self.namespace, body=body)
            message = f"ConfigMap updated: {name}"
        except ApiException as e:
            if e.status != 404:
                raise
            core.create_namespaced_config_map(namespace=self.namespace, body=body)
            message = f"ConfigMap created: {name}"
        return {"name": name, "message": message}


def _migration_run_sort_key(item: Dict[str, Any]):
    spec = item.get("spec", {})
    metadata = item.get("metadata", {})
    run_number = spec.get("runNumber")
    try:
        run_number = int(run_number)
    except (TypeError, ValueError):
        run_number = -1
    return (
        run_number,
        spec.get("timestamp") or "",
        metadata.get("creationTimestamp") or "",
        metadata.get("name") or "",
    )


def _decode_k8s_data_value(value: str) -> str:
    try:
        return base64.b64decode(value).decode("utf-8")
    except Exception:
        return ""


def _normalized_k8s_hint(external_ref: Dict[str, Any]) -> Dict[str, Any]:
    k8s_hint = dict(external_ref.get("k8s") or {})
    match = dict(k8s_hint.get("match") or {})
    for legacy_key in (
        "acceptedSecretTypes",
        "requiredKeys",
        "recommendedKeys",
        "keyPatterns",
        "contentValidationIds",
    ):
        if legacy_key in k8s_hint and legacy_key not in match:
            match[legacy_key] = list(k8s_hint.get(legacy_key) or [])
    if match:
        k8s_hint["match"] = match
    return k8s_hint


def _external_resource_types(external_ref: Dict[str, Any], k8s_hint: Dict[str, Any]) -> list[Dict[str, Any]]:
    resource_types = list(k8s_hint.get("resourceTypes") or [])
    if resource_types:
        return resource_types

    resource = k8s_hint.get("resource")
    if resource == "Secret" or external_ref.get("kind") == "secret":
        return [{"group": "", "version": "v1", "kind": "Secret", "namespaced": True}]
    if resource == "ConfigMap" or external_ref.get("kind") == "configMap":
        return [{"group": "", "version": "v1", "kind": "ConfigMap", "namespaced": True}]
    if resource:
        return [{
            "group": "cert-manager.io",
            "version": "v1",
            "kind": str(resource),
            "namespaced": resource == "Issuer",
        }]
    return []


def _create_output_resource_type(external_ref: Dict[str, Any]) -> Dict[str, Any]:
    output = (external_ref.get("create") or {}).get("output") or {}
    output_kind = output.get("kind")
    if output_kind:
        return {"group": "", "version": "v1", "kind": output_kind, "namespaced": True}
    resource_types = _external_resource_types(external_ref, _normalized_k8s_hint(external_ref))
    return resource_types[0] if resource_types else {}


def _k8s_match_value(k8s_hint: Dict[str, Any], key: str) -> list[str]:
    match = k8s_hint.get("match") or {}
    return [str(value) for value in match.get(key) or k8s_hint.get(key) or []]


def _external_current_ref(current_value: Optional[Any]) -> Dict[str, str]:
    if isinstance(current_value, dict):
        return {
            "name": str(current_value.get("name") or ""),
            "kind": str(current_value.get("kind") or ""),
            "group": str(current_value.get("group") or ""),
        }
    if current_value:
        return {"name": str(current_value), "kind": "", "group": ""}
    return {"name": "", "kind": "", "group": ""}


def _row_matches_current(row: Dict[str, Any], current: Dict[str, str]) -> bool:
    if not current.get("name") or row.get("name") != current.get("name"):
        return False
    if current.get("kind") and row.get("kind") != current.get("kind"):
        return False
    if current.get("group") and row.get("group") != current.get("group"):
        return False
    return True


def _current_matches_resource_type(current: Dict[str, str], resource_type: Dict[str, Any]) -> bool:
    if current.get("kind") and current.get("kind") != resource_type.get("kind"):
        return False
    if current.get("group") and current.get("group") != (resource_type.get("group") or ""):
        return False
    return True


def _plural_for_kind(kind: str) -> str:
    return f"{kind.lower()}s"


def _custom_resource_match_status(item: Dict[str, Any]) -> tuple[str, str]:
    conditions = ((item.get("status") or {}).get("conditions") or [])
    ready = next((condition for condition in conditions if condition.get("type") == "Ready"), None)
    if not ready:
        return "matching", ""
    if str(ready.get("status")) == "True":
        return "matching", ""
    message = ready.get("message") or ready.get("reason") or "Ready condition is not True"
    return "warn", str(message)


def _external_secret_status(
    secret_type: str,
    keys: list[str],
    required_keys: list[str],
    accepted_types: set[str],
) -> tuple[str, str]:
    missing = [key for key in required_keys if key not in keys]
    messages = []
    if accepted_types and secret_type not in accepted_types:
        messages.append(f"type {secret_type} is not one of {', '.join(sorted(accepted_types))}")
    if missing:
        messages.append(f"missing {', '.join(missing)}")
    return ("warn", "; ".join(messages)) if messages else ("matching", "")


def _merge_external_status(status: str, message: str, extra_messages: list[str]) -> tuple[str, str]:
    messages = [message] if message else []
    messages.extend(extra_messages)
    return ("warn", "; ".join(messages)) if messages else (status, "")


def _external_content_validation_messages(k8s_hint: Dict[str, Any], values: Dict[str, str]) -> list[str]:
    validation_ids = set(_k8s_match_value(k8s_hint, "contentValidationIds"))
    required_keys = _k8s_match_value(k8s_hint, "requiredKeys")
    messages: list[str] = []
    if "non-empty-keys" in validation_ids:
        empty = [key for key in required_keys if key in values and not str(values.get(key) or "").strip()]
        if empty:
            messages.append(f"empty {', '.join(empty)}")
    if "tls-certificate-key-pair" in validation_ids:
        certificate = values.get("tls.crt")
        private_key = values.get("tls.key")
        if certificate and not _looks_like_pem_certificate_chain(certificate):
            messages.append("tls.crt is not a PEM certificate")
        if private_key and not _looks_like_pem_private_key(private_key):
            messages.append("tls.key is not a PEM private key")
    if "log4j-properties" in validation_ids:
        properties = values.get("log4j2.properties")
        if properties is not None and not _looks_like_log4j_properties(properties):
            messages.append("log4j2.properties does not look like Log4j2 properties")
    return messages


def _looks_like_pem_certificate_chain(value: str) -> bool:
    return bool(re.search(r"-----BEGIN CERTIFICATE-----[\s\S]+?-----END CERTIFICATE-----", value.strip()))


def _looks_like_pem_private_key(value: str) -> bool:
    return bool(re.search(r"-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]+?-----END [A-Z ]*PRIVATE KEY-----", value.strip()))


def _looks_like_log4j_properties(value: str) -> bool:
    lines = [
        line.strip()
        for line in value.splitlines()
        if line.strip() and not line.lstrip().startswith(("#", "!"))
    ]
    return bool(lines) and any("=" in line for line in lines)


def _sort_external_rows(rows: list[Dict[str, Any]]) -> list[Dict[str, Any]]:
    return sorted(rows, key=lambda row: (0 if row.get("status") == "matching" else 1, row.get("name") or ""))


def _external_resource_labels(external_ref: Dict[str, Any]) -> Dict[str, str]:
    labels = {
        "app": "migration-assistant",
        "component": "workflow-external-reference",
    }
    purpose = external_ref.get("purpose")
    if purpose:
        labels["workflow.opensearch.org/external-ref-purpose"] = str(purpose)
    return labels


def _format_config_processor_error(error: subprocess.CalledProcessError) -> str:
    details = []
    stderr = (error.stderr or "").strip()
    stdout = (error.stdout or "").strip()
    if stderr:
        details.append(stderr)
    if stdout:
        details.append(f"stdout: {stdout}")
    detail = "\n".join(details) or str(error)
    return f"config processor failed with exit code {error.returncode}: {detail}"
