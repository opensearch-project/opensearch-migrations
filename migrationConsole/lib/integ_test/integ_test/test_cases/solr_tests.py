"""Solr-to-OpenSearch integration test cases using the standard Argo workflow pipeline.

NOTE: Standalone Solr does not support S3-based snapshots required by the RFS pipeline.
The full migration workflow will fail at the snapshot step until either:
1. SolrCloud mode is used (requires Solr Operator CRDs pre-installed), or
2. The RFS pipeline adds support for standalone Solr replication-based backups.

For now, this test validates the cluster creation and data loading steps.
The migration is expected to fail, so we resume the workflow and wait for it to
reach a terminal (Failed) phase rather than waiting for the second suspend step.
"""
import logging

from console_link.middleware.clusters import cat_indices

from ..cluster_version import SolrV8_X, OpensearchV2_X, OpensearchV3_X
from .ma_argo_test_base import MATestBase, MigrationType, MATestUserArguments

logger = logging.getLogger(__name__)

SOLR_ALLOW_COMBINATIONS = [
    (SolrV8_X, OpensearchV2_X),
    (SolrV8_X, OpensearchV3_X),
]


class TestSolr0001SingleDocumentBackfill(MATestBase):
    """Single document Solr backfill via Argo workflow (S3/backup-based RFS pipeline).

    The migration step will fail because standalone Solr doesn't support S3 snapshots.
    This test validates cluster creation and data loading only.
    """

    def __init__(self, user_args: MATestUserArguments):
        super().__init__(
            user_args=user_args,
            description="Performs backfill migration for a single Solr document via Argo workflow.",
            migrations_required=[MigrationType.BACKFILL],
            allow_source_target_combinations=SOLR_ALLOW_COMBINATIONS,
        )
        # Use the pre-created 'dummy' core (created by solr-precreate at container start)
        self.index_name = "dummy"
        self.doc_id = "solr_0001_doc"

    def prepare_clusters(self):
        self.source_operations.create_document(
            cluster=self.source_cluster, index_name=self.index_name,
            doc_id=self.doc_id,
            data={"title": "Test Document", "content": "Sample document for Solr backfill testing."})
        self.source_operations.get_document(
            cluster=self.source_cluster, index_name=self.index_name,
            doc_id=self.doc_id)

    def workflow_perform_migrations(self, timeout_seconds: int = 1800):
        """Resume the workflow and wait for it to reach a terminal phase.

        The migration will fail because standalone Solr doesn't support S3 snapshots.
        Instead of waiting for the second suspend step (which will never be reached),
        we wait for the workflow to end in a Failed state.
        """
        self.argo_service.resume_workflow(workflow_name=self.workflow_name)
        self.argo_service.wait_for_ending_phase(
            workflow_name=self.workflow_name, timeout_seconds=timeout_seconds)

    def display_final_cluster_state(self):
        """Only show source cluster state since migration didn't complete."""
        source_response = cat_indices(cluster=self.source_cluster, refresh=True).decode("utf-8")
        logger.info("Printing document counts for source cluster (migration expected to fail):")
        print("SOURCE CLUSTER")
        print(source_response)

    def verify_clusters(self):
        """Skip target verification — migration is expected to fail for standalone Solr."""
        logger.info("Skipping target verification: standalone Solr migration is expected to fail")

    def workflow_finish(self):
        """No-op — workflow already reached a terminal phase in workflow_perform_migrations."""
        pass

    def test_after(self):
        """Assert the workflow ended in Failed phase (expected for standalone Solr)."""
        status_result = self.argo_service.get_workflow_status(workflow_name=self.workflow_name)
        phase = status_result.value.get("phase", "")
        assert phase == "Failed", (
            f"Expected workflow to fail (standalone Solr doesn't support S3 snapshots), "
            f"but got phase: {phase}"
        )
