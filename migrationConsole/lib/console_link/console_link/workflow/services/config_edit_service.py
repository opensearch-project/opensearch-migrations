"""Service boundary for schema-driven workflow config edit state."""

import base64
import json
import subprocess
import tempfile
import time
from dataclasses import dataclass
from typing import Any, Dict, Optional

import yaml
from kubernetes import client
from kubernetes.client.rest import ApiException

from ..commands.argo_utils import workflow_exists, stop_workflow, delete_workflow, wait_until_workflow_deleted
from ..commands.crd_utils import list_resources_full
from ..commands.secret_utils import get_credentials_secret_store_for_namespace, verify_configured_secrets_exist
from ..external_resource_validation import (
    looks_like_log4j_properties,
    looks_like_pem_certificate_chain,
    looks_like_pem_private_key,
)
from ..models.config import WorkflowConfig
from ..models.utils import load_k8s_config
from ..models.workflow_config_store import WorkflowConfigStore
from .script_runner import GeneratedResourceValidationError, ScriptRunner


STATUS_PRIORITY = {
    "ok": 0,
    "changed": 1,
    "warning": 2,
    "gated": 3,
    "required": 4,
    "error": 5,
    "blocked": 6,
}

STATUS_COUNT_KEY = {
    "changed": "changed",
    "warning": "warnings",
    "gated": "gated",
    "required": "required",
    "error": "errors",
    "blocked": "blocked",
}


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
            edit_state=self._run_edit_state(raw_yaml, validate_external_refs=True),
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
        self._annotate_external_resource_diagnostics(
            result["editState"],
            _parse_raw_yaml(result.get("yaml") or ""),
        )
        self._annotate_generated_resource_diagnostics(result["editState"], result.get("yaml") or "")
        return ConfigEditApplyResult(
            raw_yaml=result["yaml"],
            edit_state=result["editState"],
        )

    def validate_operation(self, raw_yaml: str, operation: Dict[str, Any]) -> ConfigEditApplyResult:
        """Preview one operation through TS validation without saving the result."""
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
                "status": "error",
                "message": "ERROR: current YAML value was not found in Kubernetes",
                "current": True,
            })
        return _sort_external_rows(rows)

    def read_external_resource(self, external_ref: Dict[str, Any], name: str) -> Dict[str, Any]:
        """Read one external resource for descriptor-driven view/update panes."""
        resource_type = _create_output_resource_type(external_ref)
        kind = resource_type.get("kind")
        if kind == "Secret":
            try:
                secret = self._core_v1().read_namespaced_secret(name=name, namespace=self.namespace)
            except ApiException as e:
                if e.status != 404:
                    raise
                return _missing_external_resource_payload("Secret", name, self.namespace)
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
            try:
                config_map = self._core_v1().read_namespaced_config_map(name=name, namespace=self.namespace)
            except ApiException as e:
                if e.status != 404:
                    raise
                return _missing_external_resource_payload("ConfigMap", name, self.namespace)
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
        skip_dry_run: bool = False,
    ) -> Dict[str, Any]:
        store = self.store or WorkflowConfigStore(namespace=self.namespace)
        config = store.load_config(self.session_name)
        if not config or not config.raw_yaml.strip():
            raise ValueError(f"No workflow configuration found for session '{self.session_name}'")

        if not skip_dry_run:
            self._validate_raw_config_for_submit(config.raw_yaml)

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
            skip_dry_run=True,
        )

    def _validate_raw_config_for_submit(self, raw_yaml: str) -> None:
        edit_state = self._run_edit_state(raw_yaml, validate_external_refs=True)
        validation = edit_state.get("validation") or {}
        if validation.get("valid", True):
            return
        raise ValueError(_format_submit_validation_error(validation))

    def _run_edit_state(self, raw_yaml: str, validate_external_refs: bool = False) -> Dict[str, Any]:
        with tempfile.NamedTemporaryFile(mode="w", suffix=".yaml", delete=True) as temp_file:
            temp_file.write(raw_yaml)
            temp_file.flush()
            output = self._run_config_processor_node_script(
                "editConfig",
                "state",
                "--pending-config",
                temp_file.name,
            )

        edit_state = json.loads(output)
        if validate_external_refs:
            self._annotate_external_resource_diagnostics(edit_state, _parse_raw_yaml(raw_yaml))
            self._annotate_generated_resource_diagnostics(edit_state, raw_yaml)
        return edit_state

    def _annotate_external_resource_diagnostics(
        self,
        edit_state: Dict[str, Any],
        pending_config: Optional[Dict[str, Any]] = None,
    ) -> None:
        """Mark configured Secret/ConfigMap references that cannot satisfy the schema hint."""
        cache: dict[tuple[str, str], Optional[Dict[str, Any]]] = {}
        validation_diagnostics: list[Dict[str, Any]] = []

        def visit(node: Dict[str, Any], ancestors: list[Dict[str, Any]]) -> None:
            diagnostic = self._external_resource_node_diagnostic(node, cache, pending_config or {})
            if diagnostic and _add_diagnostic_with_counts(node, ancestors, diagnostic):
                validation_diagnostics.append(diagnostic)
            for child in node.get("children") or []:
                visit(child, [*ancestors, node])

        for root in edit_state.get("nodes") or []:
            visit(root, [])

        if validation_diagnostics:
            validation = edit_state.setdefault("validation", {})
            existing = list(validation.get("diagnostics") or [])
            for diagnostic in validation_diagnostics:
                if not _diagnostic_exists(existing, diagnostic):
                    existing.append(diagnostic)
            validation["diagnostics"] = existing
            if any(diagnostic.get("severity") in {"error", "required", "blocked"} for diagnostic in existing):
                validation["valid"] = False

    def _external_resource_node_diagnostic(
        self,
        node: Dict[str, Any],
        cache: dict[tuple[str, str], Optional[Dict[str, Any]]],
        pending_config: Dict[str, Any],
    ) -> Optional[Dict[str, Any]]:
        external_ref = node.get("externalRef") or {}
        if not external_ref:
            return None
        current = _external_current_ref(_external_resource_config_value(node, pending_config))
        name = current.get("name")
        if not name or name == "unset":
            return None
        resource_type = _external_core_v1_resource_type(external_ref, current)
        if resource_type is None:
            return None
        kind = str(resource_type.get("kind") or "")
        cache_key = (kind, name)
        if cache_key not in cache:
            try:
                cache[cache_key] = self.read_external_resource(external_ref, name)
            except ApiException as e:
                return {
                    "severity": "warning",
                    "message": f"Could not validate {kind} '{name}': {_format_api_exception(e)}",
                    "path": node.get("path") or [],
                }
        resource = cache.get(cache_key) or {}
        diagnostic = _external_resource_diagnostic_for_payload(
            external_ref,
            resource,
            name,
            self.namespace,
        )
        if not diagnostic:
            return None
        return {**diagnostic, "path": node.get("path") or []}

    def _annotate_generated_resource_diagnostics(self, edit_state: Dict[str, Any], raw_yaml: str) -> None:
        if not raw_yaml.strip() or _edit_state_has_blocking_validation(edit_state):
            return
        runner = self.runner or ScriptRunner()
        try:
            runner.validate_generated_resources(raw_yaml, workflow_name="migration-workflow")
        except GeneratedResourceValidationError as e:
            diagnostics = _generated_resource_diagnostics(e)
            if not diagnostics:
                diagnostics = [{
                    "severity": "error",
                    "message": str(e),
                    "path": [],
                }]
            self._merge_validation_diagnostics(edit_state, diagnostics)

    def _merge_validation_diagnostics(self, edit_state: Dict[str, Any], diagnostics: list[Dict[str, Any]]) -> None:
        indexed_nodes = _index_edit_nodes_by_path(edit_state)
        added: list[Dict[str, Any]] = []
        for diagnostic in diagnostics:
            path = [str(part) for part in (diagnostic.get("path") or [])]
            target = _nearest_node_for_path(indexed_nodes, path)
            if target:
                node, ancestors = target
                if _add_diagnostic_with_counts(node, ancestors, diagnostic):
                    added.append(diagnostic)
            else:
                added.append(diagnostic)
        if not added:
            return
        validation = edit_state.setdefault("validation", {})
        existing = list(validation.get("diagnostics") or [])
        for diagnostic in added:
            if not _diagnostic_exists(existing, diagnostic):
                existing.append(diagnostic)
        validation["diagnostics"] = existing
        if any(diagnostic.get("severity") in {"error", "required", "blocked"} for diagnostic in existing):
            validation["valid"] = False

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
        try:
            secrets = self._core_v1().list_namespaced_secret(namespace=self.namespace)
        except ApiException as e:
            if current.get("name"):
                return [_external_current_warning_row(
                    current,
                    "Secret",
                    f"could not list Secrets: {_format_api_exception(e)}",
                )]
            return []
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
        try:
            config_maps = self._core_v1().list_namespaced_config_map(namespace=self.namespace)
        except ApiException as e:
            if current.get("name"):
                return [_external_current_warning_row(
                    current,
                    "ConfigMap",
                    f"could not list ConfigMaps: {_format_api_exception(e)}",
                )]
            return []
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
            existing_resource = self.read_external_resource(external_ref, existing_name)
            if not existing_resource.get("missing"):
                existing_values = existing_resource.get("values", {})
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


def _parse_raw_yaml(raw_yaml: str) -> Dict[str, Any]:
    if not raw_yaml.strip():
        return {}
    try:
        parsed = yaml.safe_load(raw_yaml) or {}
    except Exception:
        return {}
    return parsed if isinstance(parsed, dict) else {}


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


def _external_core_v1_resource_type(
    external_ref: Dict[str, Any],
    current: Dict[str, str],
) -> Optional[Dict[str, Any]]:
    for resource_type in _external_resource_types(external_ref, _normalized_k8s_hint(external_ref)):
        group = str(resource_type.get("group") or "")
        version = str(resource_type.get("version") or "v1")
        kind = str(resource_type.get("kind") or "")
        if group == "" and version == "v1" and kind in {"Secret", "ConfigMap"}:
            if _current_matches_resource_type(current, resource_type):
                return resource_type
    return None


def _external_resource_config_value(node: Dict[str, Any], pending_config: Dict[str, Any]) -> Any:
    found, value = _lookup_config_path(pending_config, node.get("path") or [])
    if found:
        return value
    return node.get("value")


def _lookup_config_path(source: Dict[str, Any], path: list[Any]) -> tuple[bool, Any]:
    current: Any = source
    for part in path:
        if isinstance(current, dict):
            if part not in current:
                return False, None
            current = current[part]
            continue
        if isinstance(current, list) and str(part).isdigit():
            index = int(str(part))
            if index >= len(current):
                return False, None
            current = current[index]
            continue
        return False, None
    return True, current


def _missing_external_resource_payload(kind: str, name: str, namespace: str) -> Dict[str, Any]:
    return {
        "kind": kind,
        "name": name,
        "keys": [],
        "values": {},
        "missing": True,
        "message": f"{kind} '{name}' was not found in namespace '{namespace}'.",
    }


def _external_resource_diagnostic_for_payload(
    external_ref: Dict[str, Any],
    resource: Dict[str, Any],
    name: str,
    namespace: str,
) -> Optional[Dict[str, str]]:
    kind = str(resource.get("kind") or _create_output_resource_type(external_ref).get("kind") or "Resource")
    if resource.get("missing"):
        return {
            "severity": "error",
            "message": f"{kind} '{name}' was not found in namespace '{namespace}'. Create it or choose another {kind}.",
        }

    k8s_hint = _normalized_k8s_hint(external_ref)
    values = dict(resource.get("values") or {})
    keys = sorted(str(key) for key in (resource.get("keys") or values.keys()))
    if kind == "Secret":
        messages = _external_secret_validation_messages(
            str(resource.get("type") or "Opaque"),
            keys,
            k8s_hint,
            values,
        )
    elif kind == "ConfigMap":
        messages = _external_config_map_validation_messages(keys, k8s_hint, values)
    else:
        messages = []
    if not messages:
        return None
    severity = "error" if any(item.get("severity") == "error" for item in messages) else "warning"
    text = "; ".join(str(item.get("message") or "") for item in messages if item.get("message"))
    return {
        "severity": severity,
        "message": f"{kind} '{name}' does not satisfy this reference: {text}",
    }


def _external_secret_validation_messages(
    secret_type: str,
    keys: list[str],
    k8s_hint: Dict[str, Any],
    values: Dict[str, str],
) -> list[Dict[str, str]]:
    required_keys = list(_k8s_match_value(k8s_hint, "requiredKeys") or [])
    accepted_types = set(_k8s_match_value(k8s_hint, "acceptedSecretTypes") or [])
    messages: list[Dict[str, str]] = []
    if accepted_types and secret_type not in accepted_types:
        messages.append({
            "severity": "warning",
            "message": f"type {secret_type} is not one of {', '.join(sorted(accepted_types))}",
        })
    missing = [key for key in required_keys if key not in keys]
    if missing:
        messages.append({"severity": "error", "message": f"missing {', '.join(missing)}"})
    messages.extend({
        "severity": "error",
        "message": message,
    } for message in _external_content_validation_messages(k8s_hint, values))
    return messages


def _external_config_map_validation_messages(
    keys: list[str],
    k8s_hint: Dict[str, Any],
    values: Dict[str, str],
) -> list[Dict[str, str]]:
    required_keys = list(_k8s_match_value(k8s_hint, "requiredKeys") or [])
    missing = [key for key in required_keys if key not in keys]
    messages: list[Dict[str, str]] = []
    if missing:
        messages.append({"severity": "error", "message": f"missing {', '.join(missing)}"})
    messages.extend({
        "severity": "error",
        "message": message,
    } for message in _external_content_validation_messages(k8s_hint, values))
    return messages


def _add_diagnostic_with_counts(
    node: Dict[str, Any],
    ancestors: list[Dict[str, Any]],
    diagnostic: Dict[str, Any],
) -> bool:
    diagnostics = list(node.get("diagnostics") or [])
    if _diagnostic_exists(diagnostics, diagnostic):
        return False
    node["diagnostics"] = [*diagnostics, diagnostic]
    severity = str(diagnostic.get("severity") or "error")
    for target in [*ancestors, node]:
        target["essential"] = True
        counts = dict(target.get("statusCounts") or {})
        count_key = STATUS_COUNT_KEY.get(severity)
        if count_key:
            counts[count_key] = int(counts.get(count_key) or 0) + 1
            target["statusCounts"] = counts
        target["status"] = _highest_status(str(target.get("status") or "ok"), severity)
    return True


def _diagnostic_exists(existing: list[Dict[str, Any]], diagnostic: Dict[str, Any]) -> bool:
    return any(
        item.get("message") == diagnostic.get("message")
        and [str(part) for part in (item.get("path") or [])] == [str(part) for part in (diagnostic.get("path") or [])]
        for item in existing
    )


def _highest_status(left: str, right: str) -> str:
    return left if STATUS_PRIORITY.get(left, 0) >= STATUS_PRIORITY.get(right, 0) else right


def _edit_state_has_blocking_validation(edit_state: Dict[str, Any]) -> bool:
    validation = edit_state.get("validation") or {}
    if validation.get("valid") is False:
        return True
    return bool(validation.get("diagnostics") or validation.get("errors"))


def _generated_resource_diagnostics(error: GeneratedResourceValidationError) -> list[Dict[str, Any]]:
    text = str(error)
    resources = {
        (str(resource.get("kind") or ""), str(resource.get("name") or "")): resource
        for resource in (error.resolved_resources.get("resources") or [])
        if isinstance(resource, dict)
    }
    diagnostics: list[Dict[str, Any]] = []
    for message in _kubectl_invalid_resource_messages(text):
        kind = message.get("kind") or ""
        name = message.get("name") or ""
        resource = resources.get((kind, name), {})
        diagnostics.append({
            "severity": "error",
            "message": message["message"],
            "path": _generated_resource_source_path(resource),
        })
    return diagnostics


def _kubectl_invalid_resource_messages(text: str) -> list[Dict[str, str]]:
    import re
    pattern = re.compile(r'^The (?P<kind>\S+) "(?P<name>[^"]+)" is invalid: (?P<detail>.*)$')
    messages: list[Dict[str, str]] = []
    current: Optional[Dict[str, str]] = None
    for raw_line in text.splitlines():
        line = raw_line.strip()
        match = pattern.match(line)
        if match:
            current = {
                "kind": match.group("kind"),
                "name": match.group("name"),
                "message": line,
            }
            messages.append(current)
            continue
        if current and line and not line.startswith("Generated Kubernetes resource validation failed"):
            current["message"] = f"{current['message']} {line}"
    return messages


def _generated_resource_source_path(resource: Dict[str, Any]) -> list[str]:
    provenance = resource.get("parameterProvenance") or {}
    paths = [
        [str(part) for part in (entry.get("sourcePath") or [])]
        for entry in provenance.values()
        if isinstance(entry, dict) and entry.get("sourcePath")
    ]
    prefix = _common_path_prefix(paths)
    if prefix:
        return prefix
    return []


def _common_path_prefix(paths: list[list[str]]) -> list[str]:
    if not paths:
        return []
    prefix = list(paths[0])
    for path in paths[1:]:
        while prefix and prefix != path[:len(prefix)]:
            prefix.pop()
    return prefix


def _index_edit_nodes_by_path(edit_state: Dict[str, Any]) -> Dict[tuple[str, ...], tuple[Dict[str, Any], list[Dict[str, Any]]]]:
    indexed: Dict[tuple[str, ...], tuple[Dict[str, Any], list[Dict[str, Any]]]] = {}

    def visit(node: Dict[str, Any], ancestors: list[Dict[str, Any]]) -> None:
        path = tuple(str(part) for part in (node.get("path") or []))
        if path:
            indexed[path] = (node, ancestors)
        for child in node.get("children") or []:
            visit(child, [*ancestors, node])

    for root in edit_state.get("nodes") or []:
        visit(root, [])
    return indexed


def _nearest_node_for_path(
    indexed_nodes: Dict[tuple[str, ...], tuple[Dict[str, Any], list[Dict[str, Any]]]],
    path: list[str],
) -> Optional[tuple[Dict[str, Any], list[Dict[str, Any]]]]:
    current = tuple(path)
    while current:
        if current in indexed_nodes:
            return indexed_nodes[current]
        current = current[:-1]
    return None


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


def _external_current_warning_row(current: Dict[str, str], kind: str, message: str) -> Dict[str, Any]:
    return {
        "name": current["name"],
        "kind": current.get("kind") or kind,
        "group": current.get("group") or "",
        "version": "v1",
        "keys": [],
        "status": "warn",
        "message": message,
        "current": True,
    }


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
        if certificate and not looks_like_pem_certificate_chain(certificate):
            messages.append("tls.crt is not a PEM certificate")
        if private_key and not looks_like_pem_private_key(private_key):
            messages.append("tls.key is not a PEM private key")
    if "pem-certificate-chain" in validation_ids:
        for key in required_keys or sorted(values.keys()):
            certificate = values.get(key)
            if certificate and not looks_like_pem_certificate_chain(certificate):
                messages.append(f"{key} is not a PEM certificate")
    if "pem-private-key" in validation_ids:
        for key in required_keys or sorted(values.keys()):
            private_key = values.get(key)
            if private_key and not looks_like_pem_private_key(private_key):
                messages.append(f"{key} is not a PEM private key")
    if "log4j-properties" in validation_ids:
        properties = values.get("log4j2.properties")
        if properties is not None and not looks_like_log4j_properties(properties):
            messages.append("log4j2.properties does not look like Log4j2 properties")
    return messages


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
    if purpose == "http-basic-auth":
        labels["use-case"] = "http-basic-credentials"
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


def _format_api_exception(error: ApiException) -> str:
    status = f"HTTP {error.status}" if getattr(error, "status", None) else "Kubernetes API error"
    reason = str(getattr(error, "reason", "") or "").strip()
    return f"{status} {reason}".strip()


def _format_submit_validation_error(validation: Dict[str, Any]) -> str:
    messages: list[str] = []
    for diagnostic in validation.get("diagnostics") or []:
        message = str(diagnostic.get("message") or "").strip()
        if not message:
            continue
        path = ".".join(str(part) for part in (diagnostic.get("path") or []))
        messages.append(f"{path}: {message}" if path else message)
    for error in validation.get("errors") or []:
        message = str(error).strip()
        if message:
            messages.append(message)
    deduped = list(dict.fromkeys(messages))
    if not deduped:
        return "Workflow configuration is not valid; fix the highlighted config errors before submit."
    return "Workflow configuration is not valid; fix these config errors before submit: " + "; ".join(deduped[:5])
