"""Service boundary for schema-driven workflow config edit state."""

import json
import tempfile
from dataclasses import dataclass
from typing import Any, Dict, Optional

from ..models.config import WorkflowConfig
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
                runner = self.runner or ScriptRunner()
                output = runner.run_config_processor_node_script(
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

    def _run_edit_state(self, raw_yaml: str) -> Dict[str, Any]:
        with tempfile.NamedTemporaryFile(mode="w", suffix=".yaml", delete=True) as temp_file:
            temp_file.write(raw_yaml)
            temp_file.flush()
            runner = self.runner or ScriptRunner()
            output = runner.run_config_processor_node_script(
                "editConfig",
                "state",
                "--pending-config",
                temp_file.name,
            )

        return json.loads(output)
