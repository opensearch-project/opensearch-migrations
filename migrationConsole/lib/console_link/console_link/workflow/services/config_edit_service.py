"""Service boundary for schema-driven workflow config edit state."""

import json
import subprocess
import tempfile
import time
from dataclasses import dataclass
from typing import Any, Dict, Optional

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

    def load_resource_config_snapshots(self, workflow_name: Optional[str] = None) -> Dict[str, Optional[Dict[str, Any]]]:
        """Load resolved resource snapshots for current submitted and saved config."""
        submitted = self.load_latest_submitted_resolved_config()
        pending = self.load_pending_resolved_config(workflow_name, validation_mode="loose")
        pending_console = (pending or {}).get("consoleResources")
        if pending and pending_console is None and pending.get("workflowConfig"):
            pending_console = self._run_resolve_console_resources(pending, "--resolved-config")
        return {
            "submitted": submitted,
            "pending": pending,
            "submitted_console": self._run_resolve_console_resources(submitted, "--resolved-config")
            if submitted else None,
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

    def load_latest_submitted_resolved_config(self) -> Optional[Dict[str, Any]]:
        runs = list_resources_full(self.namespace, ["migrationruns"]).get("migrationruns", [])
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
