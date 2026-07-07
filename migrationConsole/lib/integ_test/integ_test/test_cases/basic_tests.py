import logging
import json
import subprocess
import time
import uuid
from ..cluster_version import RFS_MIGRATION_COMBINATIONS
from ..integration_test_argo_service import ENDING_ARGO_PHASES
from .ma_argo_test_base import MATestBase, MigrationType, MATestUserArguments, MIGRATION_COMPLETION_TIMEOUT_SECONDS

logger = logging.getLogger(__name__)


# This test case is subject to removal, as its value looks limited
class Test0001SingleDocumentBackfill(MATestBase):
    def __init__(self, user_args: MATestUserArguments):
        migrations_required = [MigrationType.BACKFILL]
        description = "Performs backfill migration for a single document (target cluster as coordinator)."
        super().__init__(user_args=user_args,
                         description=description,
                         migrations_required=migrations_required,
                         allow_source_target_combinations=RFS_MIGRATION_COMBINATIONS)
        # Use an index name containing the work-coordinator separator ('__') to pin the fix
        # for opensearch-project/opensearch-migrations#2880 — prior to that fix, any index
        # whose name contained '__' was silently unmigratable because the work-item id
        # parser split on the first two occurrences of the separator.
        self.index_name = f"test__0001__{self.unique_id}-{uuid.uuid4().hex[:4]}"
        self.doc_id = "test_0001_doc"
        self.doc_type = "sample_type"
        self.source_cluster = None
        self.target_cluster = None

    def prepare_workflow_snapshot_and_migration_config(self):
        snapshot_and_migration_configs = [{
            "migrations": [{
                "metadataMigrationConfig": {},
                "documentBackfillConfig": {
                    "useTargetClusterForWorkCoordination": True
                }
            }]
        }]
        self.workflow_snapshot_and_migration_config = snapshot_and_migration_configs

    def prepare_clusters(self):
        # Create single document
        self.source_operations.create_document(cluster=self.source_cluster, index_name=self.index_name,
                                               doc_id=self.doc_id, doc_type=self.doc_type)
        self.source_operations.get_document(cluster=self.source_cluster, index_name=self.index_name, doc_id=self.doc_id,
                                            doc_type=self.doc_type)

    def verify_clusters(self):
        # Validate single document exists on target
        self.target_operations.get_document(cluster=self.target_cluster, index_name=self.index_name,
                                            doc_id=self.doc_id, max_attempts=10, delay=3.0)


class Test0002SingleDocumentBackfillWithRfsCoordinatorCluster(MATestBase):
    def __init__(self, user_args: MATestUserArguments):
        migrations_required = [MigrationType.BACKFILL]
        description = "Performs backfill migration for a single document (default coordinator)."
        super().__init__(user_args=user_args,
                         description=description,
                         migrations_required=migrations_required,
                         allow_source_target_combinations=RFS_MIGRATION_COMBINATIONS)
        self.index_name = f"test_0002_{self.unique_id}-{uuid.uuid4().hex[:4]}"
        self.doc_id = "test_0002_doc"
        self.doc_type = "sample_type"
        self.source_cluster = None
        self.target_cluster = None

    def prepare_clusters(self):
        # Create single document
        self.source_operations.create_document(cluster=self.source_cluster, index_name=self.index_name,
                                               doc_id=self.doc_id, doc_type=self.doc_type)
        self.source_operations.get_document(cluster=self.source_cluster, index_name=self.index_name, doc_id=self.doc_id,
                                            doc_type=self.doc_type)

    def verify_clusters(self):
        # Validate single document exists on target
        self.target_operations.get_document(cluster=self.target_cluster, index_name=self.index_name,
                                            doc_id=self.doc_id, max_attempts=10, delay=3.0)


class Test0003ApprovalGateIntegration(MATestBase):
    """Exercises the workflow approve CLI against a real approval gate.

    Runs with skipApprovals=false so the full snapshot migration blocks at each
    expected step approval gate. The test uses `workflow approve step --list`
    to verify the active gate, approves each gate by name, and verifies the
    migration completes successfully.
    """

    def __init__(self, user_args: MATestUserArguments):
        description = "Verifies workflow approve CLI can approve a real gate."
        super().__init__(user_args=user_args,
                         description=description,
                         migrations_required=[MigrationType.METADATA, MigrationType.BACKFILL],
                         allow_source_target_combinations=RFS_MIGRATION_COMBINATIONS)
        self.index_name = f"test_0003_{self.unique_id}-{uuid.uuid4().hex[:4]}"
        self.doc_id = "test_0003_doc"
        self.doc_type = "sample_type"

    def prepare_workflow_parameters(self, keep_workflows: bool = False):
        super().prepare_workflow_parameters(keep_workflows=keep_workflows)
        self.parameters["skip-approvals"] = "false"

    def prepare_workflow_snapshot_and_migration_config(self):
        self.workflow_snapshot_and_migration_config = [{
            "migrations": [{
                "metadataMigrationConfig": {},
                "documentBackfillConfig": {
                    "maxShardSizeBytes": 16000000,
                    "resources": {
                        "requests": {"cpu": "25m", "memory": "1Gi", "ephemeral-storage": "5Gi"},
                        "limits": {"cpu": "1000m", "memory": "2Gi", "ephemeral-storage": "5Gi"}
                    }
                }
            }]
        }]

    def prepare_clusters(self):
        self.source_operations.create_document(cluster=self.source_cluster, index_name=self.index_name,
                                               doc_id=self.doc_id, doc_type=self.doc_type)

    def workflow_perform_migrations(self, timeout_seconds: int = MIGRATION_COMPLETION_TIMEOUT_SECONDS):
        self.argo_service.resume_workflow(workflow_name=self.workflow_name)
        self._approve_expected_step_gates(timeout_seconds)
        self._wait_until_suspended_or_ended(timeout_seconds)

    def _approval_gate_names(self):
        resource_path = "source1-target1-testsnapshot-migration-0"
        return [
            f"evaluatemetadata.{resource_path}",
            f"migratemetadata.{resource_path}",
            f"documentbackfill.{resource_path}",
        ]

    def _approve_expected_step_gates(self, timeout_seconds: int):
        for gate_name in self._approval_gate_names():
            self._wait_for_step_gate(gate_name, "waiting", timeout_seconds)
            self._approve_step_gate(gate_name)
            self._wait_for_step_gate(gate_name, "approved", timeout_seconds)

    def _wait_for_step_gate(self, gate_name: str, expected_status: str, timeout_seconds: int):
        deadline = time.time() + timeout_seconds
        last_gates = []
        while time.time() < deadline:
            gates = self._list_step_gates()
            last_gates = gates
            matching_gate = next((g for g in gates if g.get("name") == gate_name), None)
            if matching_gate and matching_gate.get("status") == expected_status:
                logger.info("Gate %s reached status %s", gate_name, expected_status)
                return matching_gate
            time.sleep(10)
        raise TimeoutError(
            f"Gate {gate_name} did not reach status {expected_status} within "
            f"{timeout_seconds}s. Last gates: {last_gates}"
        )

    def _list_step_gates(self):
        result = subprocess.run(
            ["workflow", "approve", "step", "--list", "--output", "json"],
            capture_output=True, text=True, timeout=30,
        )
        if result.returncode != 0:
            raise AssertionError(
                f"Failed to list approval gates (rc={result.returncode}). "
                f"stdout={result.stdout!r} stderr={result.stderr!r}"
            )
        try:
            return json.loads(result.stdout)
        except json.JSONDecodeError as e:
            raise AssertionError(
                f"Failed to parse approval gate list as JSON: {e}. "
                f"stdout={result.stdout!r} stderr={result.stderr!r}"
            ) from e

    def _approve_step_gate(self, gate_name: str):
        result = subprocess.run(
            ["workflow", "approve", "step", gate_name],
            capture_output=True, text=True, timeout=30,
        )
        if result.returncode != 0:
            raise AssertionError(
                f"Failed to approve {gate_name} (rc={result.returncode}). "
                f"stdout={result.stdout!r} stderr={result.stderr!r}"
            )
        logger.info("Approved gate %s: %s", gate_name, result.stdout.strip())

    def _wait_until_suspended_or_ended(self, timeout_seconds: int):
        """Wait until the workflow suspends for verification or ends."""
        deadline = time.time() + timeout_seconds
        while time.time() < deadline:
            status_result = self.argo_service.get_workflow_status(self.workflow_name)
            if status_result.success:
                phase = status_result.value.get("phase", "")
                has_suspended = status_result.value.get("has_suspended_nodes", False)
                if phase == "Running" and has_suspended:
                    logger.info("Workflow reached suspend (post-migration verification)")
                    return
                if phase in ENDING_ARGO_PHASES:
                    logger.info("Workflow reached ending phase: %s", phase)
                    return
            time.sleep(10)
        raise TimeoutError(
            f"Workflow did not reach suspend or ending phase within {timeout_seconds}s "
        )

    def verify_clusters(self):
        self.target_operations.get_document(cluster=self.target_cluster, index_name=self.index_name,
                                            doc_id=self.doc_id, max_attempts=10, delay=3.0)
