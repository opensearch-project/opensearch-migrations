import logging
import json
import subprocess
import time
import uuid
from ..cluster_version import CDC_MIGRATION_COMBINATIONS, RFS_MIGRATION_COMBINATIONS
from ..integration_test_argo_service import ENDING_ARGO_PHASES
from .cdc_base import wait_for_proxy_ready
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
    """Covers RFS worker -> S3 -> console -> CompletedWithErrors with a terminally failing document."""

    # Mapped `long` on the target so a non-numeric value fails to parse.
    FAILING_FIELD = "quantity"

    def __init__(self, user_args: MATestUserArguments):
        migrations_required = [MigrationType.BACKFILL]
        description = ("Performs backfill migration for one valid and one terminally failing document "
                       "(default coordinator).")
        super().__init__(user_args=user_args,
                         description=description,
                         migrations_required=migrations_required,
                         allow_source_target_combinations=RFS_MIGRATION_COMBINATIONS)
        self.index_name = f"test_0002_{self.unique_id}-{uuid.uuid4().hex[:4]}"
        self.doc_id = "test_0002_doc"
        self.failing_doc_id = "test_0002_failing_doc"
        self.doc_type = "sample_type"
        self.source_cluster = None
        self.target_cluster = None

    def prepare_workflow_snapshot_and_migration_config(self):
        # Base default minus metadataMigrationConfig, which would replace the incompatible target
        # mapping. Backfill sizing and resources are kept as-is so EKS scheduling is unchanged.
        super().prepare_workflow_snapshot_and_migration_config()
        for migration in self.workflow_snapshot_and_migration_config[0]["migrations"]:
            migration.pop("metadataMigrationConfig", None)

    def prepare_clusters(self):
        # Failing document first: dynamic mapping pins the field to the version's string type from
        # the first doc. Numeric-looking first would pin it to `long` and the source would reject this.
        self.source_operations.create_document(cluster=self.source_cluster, index_name=self.index_name,
                                               doc_id=self.failing_doc_id, doc_type=self.doc_type,
                                               data={"title": "Fails to parse on the target",
                                                     self.FAILING_FIELD: "not-a-number"})
        self.source_operations.create_document(cluster=self.source_cluster, index_name=self.index_name,
                                               doc_id=self.doc_id, doc_type=self.doc_type,
                                               data={"title": "Migrates cleanly",
                                                     self.FAILING_FIELD: "123"})
        self.source_operations.get_document(cluster=self.source_cluster, index_name=self.index_name, doc_id=self.doc_id,
                                            doc_type=self.doc_type)
        self.source_operations.get_document(cluster=self.source_cluster, index_name=self.index_name,
                                            doc_id=self.failing_doc_id, doc_type=self.doc_type)

        # Seed only this index; auto-creation stays enabled globally.
        properties = {self.FAILING_FIELD: {"type": "long"}}
        doc_type = self.target_operations.resolve_doc_type(self.doc_type)
        mappings = {doc_type: {"properties": properties}} if self.target_operations.uses_typed_mappings() \
            else {"properties": properties}
        self.target_operations.create_index(index_name=self.index_name, cluster=self.target_cluster,
                                            data=json.dumps({"mappings": mappings}))

    def verify_clusters(self):
        # The valid document migrates; the failing one must never land on the target.
        self.target_operations.get_document(cluster=self.target_cluster, index_name=self.index_name,
                                            doc_id=self.doc_id, max_attempts=10, delay=3.0)
        self.target_operations.get_document(cluster=self.target_cluster, index_name=self.index_name,
                                            doc_id=self.failing_doc_id, expected_status_code=404,
                                            max_attempts=3, delay=2.0)

        workflow_uid = self.argo_service.get_workflow_uid(workflow_name=self.workflow_name)

        record = self._await_failed_document_record()
        self._assert_field(record, "documentId", self.failing_doc_id)
        self._assert_field(record, "failureType", "mapper_parsing_exception")
        self._assert_field(record, "failureClass", "NON_RETRYABLE")
        self._assert_field(record, "targetIndex", self.index_name)
        self._assert_field(record, "sessionId", workflow_uid)

        status = self._await_completed_with_errors()
        self._assert_field(status, "status", "CompletedWithErrors")
        self._assert_field(status, "failed_documents_present", True)
        self._assert_field(status, "percentage_completed", 100.0)
        shard_total = status.get("shard_total")
        shard_complete = status.get("shard_complete")
        if shard_total != shard_complete:
            raise AssertionError(
                f"Backfill reported CompletedWithErrors with shard_complete={shard_complete} != "
                f"shard_total={shard_total}: {status}"
            )
        self._assert_session_location(status, workflow_uid)
        logger.info("Failed document observed through the console: %s", record)

    @staticmethod
    def _assert_field(payload: dict, field: str, expected):
        actual = payload.get(field)
        if actual != expected:
            raise AssertionError(f"Expected {field}={expected!r}, got {actual!r}: {payload}")

    @staticmethod
    def _assert_session_location(status: dict, workflow_uid: str):
        """The reported stream location must belong to this run's session."""
        location = status.get("failed_document_stream_location")
        if not location:
            raise AssertionError(f"Deep status did not report a failed document stream location: {status}")
        if f"session={workflow_uid}" not in location:
            raise AssertionError(
                f"Failed document stream location {location!r} does not belong to session {workflow_uid!r}"
            )

    def _run_console_json(self, args: list, timeout: int = 180) -> dict | list:
        cmd = ["console", "--json"] + args
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        if result.returncode != 0:
            raise AssertionError(
                f"`{' '.join(cmd)}` failed (rc={result.returncode}). "
                f"stdout={result.stdout!r} stderr={result.stderr!r}"
            )
        try:
            return json.loads(result.stdout)
        except json.JSONDecodeError as e:
            raise AssertionError(f"`{' '.join(cmd)}` emitted non-JSON: {e}. stdout={result.stdout!r}") from e

    def _await_failed_document_record(self, max_attempts: int = 20, delay: float = 6.0) -> dict:
        """Poll: the workflow can report finished before the S3 object is listable."""
        records = []
        for _ in range(max_attempts):
            records = self._run_console_json(["failed-document-stream", "list", "--limit", "100"])
            for record in records:
                if record.get("documentId") == self.failing_doc_id:
                    return record
            time.sleep(delay)
        raise AssertionError(
            f"Document {self.failing_doc_id!r} never appeared in the failed document stream. "
            f"Last read {len(records)} record(s): {records}"
        )

    def _await_completed_with_errors(self, max_attempts: int = 20, delay: float = 6.0) -> dict:
        status = {}
        for _ in range(max_attempts):
            status = self._run_console_json(["backfill", "status", "--deep-check"])
            if status.get("status") == "CompletedWithErrors":
                return status
            time.sleep(delay)
        raise AssertionError(f"Backfill never reported CompletedWithErrors. Last status: {status}")


class Test0003ApprovalGateIntegration(MATestBase):
    """Exercises the workflow approve CLI against a real approval gate.

    Runs a full CDC migration with skipApprovals=false so proxy setup,
    metadata evaluation, metadata migration, and document backfill each block at
    the expected step approval gate. The test uses `workflow approve step
    --list` to verify the active gate, approves each gate by name, and verifies
    the migration completes successfully.
    """

    def __init__(self, user_args: MATestUserArguments):
        description = "Verifies workflow approve CLI can approve a real gate."
        super().__init__(user_args=user_args,
                         description=description,
                         migrations_required=[MigrationType.METADATA, MigrationType.BACKFILL,
                                              MigrationType.CAPTURE_AND_REPLAY],
                         allow_source_target_combinations=CDC_MIGRATION_COMBINATIONS)
        self.index_name = f"test_0003_{self.unique_id}-{uuid.uuid4().hex[:4]}"
        self.doc_id = "test_0003_doc"
        self.doc_type = "sample_type"
        self.snapshot_migration_name = "source1-target1-testsnapshot-migration-0"

    def prepare_workflow_parameters(self, keep_workflows: bool = False):
        super().prepare_workflow_parameters(keep_workflows=keep_workflows)
        self.workflow_template = "cdc-e2e-migration-with-clusters"
        self.parameters["capture-proxy-service-type"] = self.capture_proxy_service_type
        self.parameters["skip-approvals"] = "false"
        self.parameters["require-begin-approval"] = "true"

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
        return [
            "begin",
            "captureproxysetup.capture-proxy",
            f"evaluatemetadata.{self.snapshot_migration_name}",
            f"migratemetadata.{self.snapshot_migration_name}",
            f"documentbackfill.{self.snapshot_migration_name}",
        ]

    def _approve_expected_step_gates(self, timeout_seconds: int):
        for gate_name in self._approval_gate_names():
            self._wait_for_step_gate(gate_name, "waiting", timeout_seconds)
            self._assert_gate_prerequisite_completed(gate_name)
            self._approve_step_gate(gate_name)
            self._wait_for_step_gate(gate_name, "approved", timeout_seconds)
            if gate_name.startswith("captureproxysetup."):
                wait_for_proxy_ready(self.argo_service.namespace, timeout_seconds)

    def _assert_gate_prerequisite_completed(self, gate_name: str):
        if gate_name == "begin":
            self._assert_capture_proxy_not_started()
        elif gate_name.startswith("captureproxysetup."):
            self._assert_capture_proxy_setup_pending()
        elif gate_name.startswith("evaluatemetadata."):
            self._assert_workflow_show_output_available("evaluatemetadata")
        elif gate_name.startswith("migratemetadata."):
            self._assert_workflow_show_output_available("migratemetadata")
            self._assert_document_backfill_not_started()
        elif gate_name.startswith("documentbackfill."):
            self._assert_document_backfill_completed()

    def _get_capture_proxy(self):
        result = subprocess.run(
            [
                "kubectl", "get", "captureproxy", "capture-proxy",
                "-n", self.argo_service.namespace,
                "-o", "json",
            ],
            capture_output=True, text=True, timeout=120,
        )
        if result.returncode != 0:
            raise AssertionError(
                f"Failed to inspect CaptureProxy "
                f"(rc={result.returncode}). stdout={result.stdout!r} stderr={result.stderr!r}"
            )
        try:
            return json.loads(result.stdout)
        except json.JSONDecodeError as e:
            raise AssertionError(
                f"Failed to parse CaptureProxy JSON: {e}. "
                f"stdout={result.stdout!r}"
            ) from e

    def _assert_capture_proxy_not_started(self):
        capture_proxy = self._get_capture_proxy()
        phase = capture_proxy.get("status", {}).get("phase")
        status = capture_proxy.get("status", {})
        if phase != "Created":
            raise AssertionError(
                f"CaptureProxy phase before begin approval was {phase!r}, expected 'Created': {status}"
            )
        if status.get("configChecksum"):
            raise AssertionError(f"CaptureProxy configChecksum was set before begin approval: {status}")
        if status.get("serviceEndpoint") or status.get("loadBalancerEndpoint"):
            raise AssertionError(f"CaptureProxy endpoint was set before begin approval: {status}")
        logger.info("CaptureProxy is still Created before begin approval")

    def _assert_capture_proxy_setup_pending(self):
        capture_proxy = self._get_capture_proxy()
        phase = capture_proxy.get("status", {}).get("phase")
        if phase != "Pending":
            raise AssertionError(
                f"CaptureProxy phase before proxy setup approval was {phase!r}, expected 'Pending': "
                f"{capture_proxy.get('status')}"
            )
        logger.info("CaptureProxy is Pending before proxy setup approval")

    def _assert_workflow_show_output_available(self, task_name: str):
        result = subprocess.run(
            [
                "workflow", "show",
                f"snapshotmigration.{self.snapshot_migration_name}",
                task_name,
                "--clean",
            ],
            capture_output=True, text=True, timeout=120,
        )
        if result.returncode != 0:
            raise AssertionError(
                f"Expected workflow show to find {task_name} output before its approval gate "
                f"(rc={result.returncode}). stdout={result.stdout!r} stderr={result.stderr!r}"
            )
        if not result.stdout.strip():
            raise AssertionError(f"Expected workflow show {task_name} output to be non-empty")

    def _assert_document_backfill_not_started(self):
        snapshot_migration = self._get_snapshot_migration()
        backfill_status = snapshot_migration.get("status", {}).get("documentBackfill")
        if backfill_status:
            raise AssertionError(
                "Document backfill status was set before the document backfill step ran: "
                f"{backfill_status}"
            )
        logger.info("Document backfill status is not set before the migrate metadata gate")

    def _assert_document_backfill_completed(self):
        snapshot_migration = self._get_snapshot_migration()
        status = snapshot_migration.get("status", {})
        backfill_status = status.get("documentBackfill")
        if not isinstance(backfill_status, dict):
            raise AssertionError(f"SnapshotMigration documentBackfill status was not set: {status}")

        if status.get("phase") != "Completed":
            raise AssertionError(f"SnapshotMigration phase was not Completed after backfill: {status}")
        if backfill_status.get("phase") != "Completed":
            raise AssertionError(f"Document backfill phase was not Completed: {backfill_status}")
        if not backfill_status.get("updatedAt"):
            raise AssertionError(f"Document backfill status did not include updatedAt: {backfill_status}")

        summary = backfill_status.get("summary", {})
        if summary.get("shardsTotal", 0) < 1:
            raise AssertionError(f"Document backfill status did not report any shards: {backfill_status}")
        if summary.get("shardsMigrated") != summary.get("shardsTotal"):
            raise AssertionError(f"Document backfill did not migrate all shards: {backfill_status}")

    def _get_snapshot_migration(self):
        result = subprocess.run(
            [
                "kubectl", "get", "snapshotmigration", self.snapshot_migration_name,
                "-n", self.argo_service.namespace,
                "-o", "json",
            ],
            capture_output=True, text=True, timeout=120,
        )
        if result.returncode != 0:
            raise AssertionError(
                f"Failed to get SnapshotMigration {self.snapshot_migration_name} "
                f"(rc={result.returncode}). stdout={result.stdout!r} stderr={result.stderr!r}"
            )
        try:
            return json.loads(result.stdout)
        except json.JSONDecodeError as e:
            raise AssertionError(
                f"Failed to parse SnapshotMigration JSON: {e}. stdout={result.stdout!r}"
            ) from e

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
