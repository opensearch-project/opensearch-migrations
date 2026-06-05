import logging
import subprocess
import time
import uuid
from ..cluster_version import RFS_MIGRATION_COMBINATIONS
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

    Runs with skipApprovals=false so the workflow blocks at the evaluatemetadata
    approval gate. The test then uses `workflow approve step --all` to unblock it
    and verifies the migration completes successfully.
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

    def prepare_clusters(self):
        self.source_operations.create_document(cluster=self.source_cluster, index_name=self.index_name,
                                               doc_id=self.doc_id, doc_type=self.doc_type)

    def workflow_perform_migrations(self, timeout_seconds: int = MIGRATION_COMPLETION_TIMEOUT_SECONDS):
        self.argo_service.resume_workflow(workflow_name=self.workflow_name)
        self._approve_gates_until_suspend(timeout_seconds)

    def _approve_gates_until_suspend(self, timeout_seconds: int):
        """Approve gates on every poll while waiting for the workflow to reach its suspend."""
        deadline = time.time() + timeout_seconds
        interval = 10
        while time.time() < deadline:
            # Try to approve any available gates (best-effort, ignore failures)
            result = subprocess.run(
                ["workflow", "approve", "step", "--all"],
                capture_output=True, text=True, timeout=30,
            )
            if result.returncode == 0:
                logger.info("Approval gates approved: %s", result.stdout.strip())

            # Check if workflow reached its suspend point
            status_result = self.argo_service.get_workflow_status(self.workflow_name)
            if status_result.success:
                status_info = status_result.value
                if status_info.get("phase") == "Running" and status_info.get("has_suspended_nodes"):
                    return
                if status_info.get("phase") in ("Succeeded", "Failed", "Error"):
                    return

            time.sleep(interval)
        raise TimeoutError(f"Workflow did not reach suspended state within {timeout_seconds}s")

    def verify_clusters(self):
        self.target_operations.get_document(cluster=self.target_cluster, index_name=self.index_name,
                                            doc_id=self.doc_id, max_attempts=10, delay=3.0)
