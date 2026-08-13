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

# The workflow `workflow submit` creates, from DEFAULT_WORKFLOW_NAME in
# console_link.workflow.commands.autocomplete_workflows. Fixed, not generated, so only
# one can exist at a time and each extra run must delete it before the next submits.
INNER_WORKFLOW_NAME = "migration-workflow"


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


class Test0008OptionalSnapshotStages(MATestBase):
    """Verifies that omitting metadataMigrationConfig or documentBackfillConfig skips the
    corresponding workflow step rather than running it.

    Regression coverage for the `when` gate on the metadataMigrate / bulkLoadDocuments
    steps. Before the fix, an absent config was defaulted to `{}`, which serialized to
    the non-empty string "{}" and so always evaluated truthy — every stage ran
    regardless of whether the user asked for it.

    After the baseline run (both stages, verified end-to-end), two more workflows are
    submitted against the same provisioned clusters. Each seeds its own fresh index and
    is scoped with indexAllowlist so the runs cannot interfere with each other. The
    baseline is scoped too — see prepare_workflow_snapshot_and_migration_config, without
    which it migrates the metadata-only run's document and invalidates that run's central
    assertion. Each extra run takes its own snapshot: the repo path is suffixed with
    the workflow uid (see fullMigrationImportedClusters.yaml), so a snapshot from one
    workflow is not readable by another and reuse is not an option.

    Because the repo path differs per workflow, each run must also carry its own snapshot
    label — the label is what names the DataSnapshot / SnapshotMigration CRs, whose repo
    paths are immutable. See _extra_run_parameters.

    - metadata-only run: metadataMigrationConfig present, documentBackfillConfig absent.
      metadataMigrate Succeeds, bulkLoadDocuments is Skipped. The index mappings land on
      the target but the document does not — the functional proof that backfill was
      genuinely skipped rather than merely reported as such.

    - backfill-only run: documentBackfillConfig present, metadataMigrationConfig absent.
      metadataMigrate is Skipped, bulkLoadDocuments Succeeds.

    Both extra runs are expected to succeed overall, so a Skipped phase is unambiguous
    evidence the gate fired rather than a side effect of some upstream failure.
    """

    def __init__(self, user_args: MATestUserArguments):
        description = (
            "Verifies that omitting metadataMigrationConfig or documentBackfillConfig "
            "skips the corresponding workflow step."
        )
        super().__init__(
            user_args=user_args,
            description=description,
            migrations_required=[MigrationType.METADATA, MigrationType.BACKFILL],
            allow_source_target_combinations=RFS_MIGRATION_COMBINATIONS,
        )
        run_suffix = uuid.uuid4().hex[:4]
        self.index_name = f"test_0008_{self.unique_id}-{run_suffix}"
        # Each extra run gets its own index so the runs stay independent of one another
        # and of the baseline (metadata migration fails on an already-existing index).
        self.metadata_only_index = f"test_0008_meta_{self.unique_id}-{run_suffix}"
        self.backfill_only_index = f"test_0008_backfill_{self.unique_id}-{run_suffix}"
        # ...and its own snapshot label, which is what keeps the runs on distinct
        # DataSnapshot/SnapshotMigration CRs. See _extra_run_parameters.
        self.metadata_only_snapshot_label = f"meta-{run_suffix}"
        self.backfill_only_snapshot_label = f"backfill-{run_suffix}"
        self.doc_id = "test_0008_doc"
        self.doc_type = "sample_type"
        self.source_cluster = None
        self.target_cluster = None
        self._extra_workflow_names: list[str] = []

    def prepare_workflow_snapshot_and_migration_config(self):
        """Scope the baseline run to its own index.

        The default config carries no indexAllowlist, so the baseline migrates every index
        on the source — including the two this test seeds for the extra runs. That put the
        document into metadata_only_index on the target before the metadata-only run ever
        started, so its "backfill was skipped, so the document must be absent" assertion
        saw a 200 and failed. Scoping the baseline is what makes that assertion measure the
        metadata-only run instead of the baseline.
        """
        super().prepare_workflow_snapshot_and_migration_config()
        for migration in self.workflow_snapshot_and_migration_config[0]["migrations"]:
            migration["metadataMigrationConfig"]["indexAllowlist"] = [self.index_name]
            migration["documentBackfillConfig"]["indexAllowlist"] = [self.index_name]

    def prepare_clusters(self):
        for index_name in (self.index_name, self.metadata_only_index, self.backfill_only_index):
            self.source_operations.create_document(
                cluster=self.source_cluster, index_name=index_name,
                doc_id=self.doc_id, doc_type=self.doc_type,
            )

    def verify_clusters(self):
        # Baseline: both stages ran and the document arrived on target.
        self.target_operations.get_document(
            cluster=self.target_cluster, index_name=self.index_name,
            doc_id=self.doc_id, max_attempts=10, delay=3.0,
        )

        # The baseline is allowlisted to self.index_name, so the extra runs' indices must
        # not be on the target yet. Asserting it here localizes a regression in that
        # scoping to the baseline, instead of surfacing it as a confusing 200-vs-404
        # failure inside the metadata-only run below.
        for index_name in (self.metadata_only_index, self.backfill_only_index):
            self.target_operations.get_index(
                cluster=self.target_cluster, index_name=index_name,
                expected_status_code=404, max_attempts=3, delay=2.0,
            )

        self._run_and_assert_metadata_only()
        self._run_and_assert_backfill_only()

    def cleanup(self):
        # keepMigrationWorkflow=true means the wrapper left the inner workflow behind for
        # us to read, so this test owns deleting it.
        self._delete_inner_workflow()
        for wf_name in self._extra_workflow_names:
            try:
                self.argo_service.delete_workflow(workflow_name=wf_name)
            except Exception as e:
                logger.warning("Failed to delete extra workflow %s: %s", wf_name, e)
        self._extra_workflow_names.clear()

    def _delete_inner_workflow(self):
        """Delete the inner workflow, which each run recreates under the same fixed name.

        Best-effort: it may already be gone (an early failure, or the run that created it
        never got that far), and a failure to clean up should not mask the real assertion.
        """
        try:
            self.argo_service.delete_workflow(workflow_name=INNER_WORKFLOW_NAME)
        except Exception as e:
            logger.warning("Failed to delete inner workflow %s: %s", INNER_WORKFLOW_NAME, e)

    # ------------------------------------------------------------------
    # Helpers
    # ------------------------------------------------------------------

    def _extra_run_parameters(self, migration_config: dict, snapshot_label: str) -> dict:
        """Build parameters for an extra run against the already-provisioned clusters.

        Uses imported-clusters mode so no new clusters are stood up. The run creates its
        own snapshot (the repo path is per-workflow, so the baseline's snapshot is not
        reachable from here).

        snapshot_label must be unique per workflow. It names the DataSnapshot CR
        (source1-<label>) and the SnapshotMigration CR (source1-target1-<label>-migration-N),
        and those CRs' repoPathUri / snapshotRepoPathUri are immutable per the
        ValidatingAdmissionPolicies. Since the repo path carries the workflow uid, a second
        run reusing the baseline's default "testsnapshot" label resolves to the baseline's
        CRs and its apply is rejected outright ("cannot be changed. Delete and recreate.").
        """
        return {
            "source-configs": [{
                "source": self.source_cluster.config,
                "snapshot-and-migration-configs": [{
                    "snapshotConfig": {"snapshotLabel": snapshot_label},
                    "migrations": [migration_config],
                }],
            }],
            "target-config": self.target_cluster.config,
            # The stage phases this test asserts on live in the inner workflow, which
            # full-migration-with-workflow-cli's last step deletes unless this is set.
            # Keeping it means we own the deletion; cleanup() does that.
            "keepMigrationWorkflow": "true",
        }

    def _submit_and_wait(self, parameters: dict, label: str) -> dict:
        """Submit a full-migration-imported-clusters workflow and wait for it to end.

        Returns the *inner* workflow's status JSON, which is where the stage phases are.
        The submitted workflow is only an outer wrapper: its own steps are
        generate-migration-configs / configureAndSubmitWorkflow / monitorWorkflow /
        evaluateWorkflowResult / deleteMigrationWorkflow (see testMigrationWithWorkflowCli.ts).
        configureAndSubmitWorkflow.sh runs `workflow submit`, which creates a separate
        workflow named INNER_WORKFLOW_NAME holding metadataMigrate and bulkLoadDocuments.
        """
        start_result = self.argo_service.start_workflow(
            workflow_template_name="full-migration-imported-clusters",
            parameters=parameters,
        )
        assert start_result.success, f"{label}: failed to start workflow: {start_result}"
        wf_name = start_result.value
        self._extra_workflow_names.append(wf_name)
        logger.info("%s: submitted workflow %s", label, wf_name)
        self.argo_service.wait_for_ending_phase(
            workflow_name=wf_name,
            timeout_seconds=MIGRATION_COMPLETION_TIMEOUT_SECONDS,
        )
        outer_json = self.argo_service._get_workflow_status_json(wf_name)
        outer_phase = outer_json.get("status", {}).get("phase")
        # Log both workflows' node phases before asserting so a failure here is
        # diagnosable from the Jenkins console alone, without a live cluster.
        logger.info("%s: outer workflow %s ended in phase=%s; node phases: %s",
                    label, wf_name, outer_phase,
                    json.dumps(self._node_phases_by_display_name(outer_json), indent=2, sort_keys=True))
        assert outer_phase == "Succeeded", (
            f"{label}: workflow {wf_name} ended in phase {outer_phase!r}, expected 'Succeeded'. "
            f"A non-Succeeded run makes 'Skipped' assertions meaningless, since a step can "
            f"also be skipped as a consequence of an upstream failure."
        )

        # keepMigrationWorkflow=true keeps the inner workflow alive past the wrapper, so
        # its phases are still readable here.
        inner_json = self.argo_service._get_workflow_status_json(INNER_WORKFLOW_NAME)
        inner_phase = inner_json.get("status", {}).get("phase")
        logger.info("%s: inner workflow %s ended in phase=%s; node phases: %s",
                    label, INNER_WORKFLOW_NAME, inner_phase,
                    json.dumps(self._node_phases_by_display_name(inner_json), indent=2, sort_keys=True))
        # INNER_WORKFLOW_NAME is fixed, so the baseline and both extra runs all use it in
        # turn. Reading a leftover from an earlier run would assert against the wrong
        # stages and could pass for the wrong reason, so pin it to this wrapper.
        self._assert_inner_workflow_is_not_stale(outer_json, inner_json, label)
        assert inner_phase == "Succeeded", (
            f"{label}: inner workflow {INNER_WORKFLOW_NAME} ended in phase {inner_phase!r}, "
            f"expected 'Succeeded'."
        )
        return inner_json

    @staticmethod
    def _assert_inner_workflow_is_not_stale(outer_json: dict, inner_json: dict, label: str):
        """Assert the inner workflow was created by this wrapper, not an earlier run.

        The wrapper's configureAndSubmitWorkflow step is what runs `workflow submit`, so
        this run's inner workflow is always created after the wrapper itself.
        """
        outer_created = outer_json.get("metadata", {}).get("creationTimestamp")
        inner_created = inner_json.get("metadata", {}).get("creationTimestamp")
        assert outer_created and inner_created, (
            f"{label}: missing creationTimestamp (outer={outer_created!r}, inner={inner_created!r}); "
            f"cannot confirm the inner workflow belongs to this run"
        )
        # RFC 3339 with a fixed 'Z' offset, so lexicographic order is chronological.
        assert inner_created >= outer_created, (
            f"{label}: inner workflow {INNER_WORKFLOW_NAME} was created at {inner_created}, before "
            f"this run's wrapper at {outer_created} — it is a leftover from an earlier run, so its "
            f"stage phases say nothing about this one."
        )

    @staticmethod
    def _node_phases_by_display_name(workflow_json: dict) -> dict[str, str]:
        """Return {displayName: phase} for every node in the workflow."""
        nodes = workflow_json.get("status", {}).get("nodes", {})
        return {
            node.get("displayName", node_id): node.get("phase", "")
            for node_id, node in nodes.items()
        }

    @staticmethod
    def _assert_node_phase(phases: dict[str, str], step_name: str, expected: str, label: str):
        matching = {name: phase for name, phase in phases.items() if step_name in name}
        if not matching:
            raise AssertionError(
                f"{label}: no workflow node whose displayName contains '{step_name}'. "
                f"Available names: {sorted(phases)}"
            )
        for name, phase in matching.items():
            if phase != expected:
                raise AssertionError(
                    f"{label}: node '{name}' phase={phase!r}, expected {expected!r}. "
                    f"All node phases: {phases}"
                )
        logger.info("%s: node(s) matching '%s' all have phase=%s", label, step_name, expected)

    def _run_and_assert_metadata_only(self):
        label = "metadata-only"
        allowlist = [self.metadata_only_index]
        params = self._extra_run_parameters({
            "metadataMigrationConfig": {"indexAllowlist": allowlist},
        }, snapshot_label=self.metadata_only_snapshot_label)
        wf = self._submit_and_wait(params, label)
        phases = self._node_phases_by_display_name(wf)
        self._assert_node_phase(phases, "metadataMigrate", "Succeeded", label)
        self._assert_node_phase(phases, "bulkLoadDocuments", "Skipped", label)

        # Functional confirmation: metadata created the index on the target, but with
        # backfill skipped the document itself must not have been copied.
        self.target_operations.get_index(
            cluster=self.target_cluster, index_name=self.metadata_only_index,
            max_attempts=10, delay=3.0,
        )
        self.target_operations.get_document(
            cluster=self.target_cluster, index_name=self.metadata_only_index,
            doc_id=self.doc_id, doc_type=self.doc_type,
            expected_status_code=404, max_attempts=3, delay=2.0,
        )
        logger.info("%s: target index exists with no documents backfilled", label)

    def _run_and_assert_backfill_only(self):
        label = "backfill-only"
        allowlist = [self.backfill_only_index]
        params = self._extra_run_parameters({
            "documentBackfillConfig": {
                "indexAllowlist": allowlist,
                "maxShardSizeBytes": 16000000,
                "resources": {
                    "requests": {"cpu": "25m", "memory": "1Gi", "ephemeral-storage": "5Gi"},
                    "limits": {"cpu": "1000m", "memory": "2Gi", "ephemeral-storage": "5Gi"},
                },
            }
        }, snapshot_label=self.backfill_only_snapshot_label)
        wf = self._submit_and_wait(params, label)
        phases = self._node_phases_by_display_name(wf)
        self._assert_node_phase(phases, "metadataMigrate", "Skipped", label)
        self._assert_node_phase(phases, "bulkLoadDocuments", "Succeeded", label)


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
        # Unlike every other test, this one runs with skipApprovals=false and means
        # to block at each of these gates until it approves them by hand, so the
        # wait loops must not treat them as a workflow stuck on an approval nobody
        # will give. Any gate outside this list still fails the test.
        self.argo_service.expected_parked_gate_names = self._approval_gate_names()

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
        parked_gate_watcher = self.argo_service.parked_gate_watcher_for(self.workflow_name)
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
            # A workflow parked on a VAP-denial approval gate stays 'Running'
            # forever; without this the only exit is the full timeout.
            parked_gate_watcher.check()
            time.sleep(10)
        raise TimeoutError(
            f"Workflow did not reach suspend or ending phase within {timeout_seconds}s "
        )

    def verify_clusters(self):
        self.target_operations.get_document(cluster=self.target_cluster, index_name=self.index_name,
                                            doc_id=self.doc_id, max_attempts=10, delay=3.0)
