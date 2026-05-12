import logging

from .cdc_base import (
    MATestBase, MigrationType, MATestUserArguments,
    CDC_SOURCE_TARGET_COMBINATIONS, REPLAYER_LABEL_SELECTOR, PROXY_LABEL_SELECTOR,
    wait_for_pod_ready, wait_for_replayer_consuming,
    run_generate_data,
)

logger = logging.getLogger(__name__)


class Test0040CdcFullE2eSimpleBulk(MATestBase):
    """Full E2E with minimal bulk ingestion via 'console clusters generate-data' CLI.

    Pre-snapshot: generate-data --cluster proxy into a pre_snapshot index.
    Post-snapshot: generate-data --cluster proxy into a post_snapshot index.
    Verifies both indices appear on target with correct counts.
    """
    requires_explicit_selection = True

    PRE_SNAPSHOT_DOCS = 200
    POST_SNAPSHOT_DOCS = 100

    def __init__(self, user_args: MATestUserArguments):
        super().__init__(
            user_args=user_args,
            description="Full E2E: simple bulk ingestion via generate-data CLI before and after snapshot.",
            migrations_required=[MigrationType.METADATA, MigrationType.BACKFILL,
                                 MigrationType.CAPTURE_AND_REPLAY],
            allow_source_target_combinations=CDC_SOURCE_TARGET_COMBINATIONS,
        )
        uid = self.unique_id
        self.idx_pre = f"cdc0040-pre-snapshot-{uid}"
        self.idx_post = f"cdc0040-post-snapshot-{uid}"

    def prepare_workflow_parameters(self, keep_workflows: bool = False):
        # Use the "with-clusters" CDC variant so the workflow provisions source + target
        # clusters inline (same as backfill tests 0001/0002) and also wires up the capture
        # proxy + replayer. The base class's prepare_workflow_parameters() already supplies
        # the parameters this template expects (snapshot-and-migration-configs,
        # source-cluster-template, target-cluster-template, image-registry-prefix,
        # skip-cleanup) when imported_clusters is False, so we only need to swap the
        # template name.
        super().prepare_workflow_parameters(keep_workflows=keep_workflows)
        self.workflow_template = "cdc-e2e-migration-with-clusters"

    def prepare_clusters(self):
        pass

    def workflow_perform_migrations(self, timeout_seconds: int = 3600):
        if not self.workflow_name:
            raise ValueError("Workflow name is not available")
        ns = self.argo_service.namespace

        # The cdc-e2e-migration-with-clusters template suspends after provisioning
        # source + target clusters (so the framework can grab cluster configs) and
        # again after the migration completes (for verification). Resume the first
        # suspend so capture-proxy and replayer actually get deployed.
        logger.info("Resuming workflow past pause-for-test-data to start migration...")
        self.argo_service.resume_workflow(workflow_name=self.workflow_name)

        # --- Pre-snapshot: generate-data via proxy ---
        logger.info("Waiting for capture-proxy to be ready...")
        wait_for_pod_ready(ns, PROXY_LABEL_SELECTOR, timeout_seconds)

        logger.info("Pre-snapshot: generating %d docs into %s via proxy", self.PRE_SNAPSHOT_DOCS, self.idx_pre)
        run_generate_data("proxy", self.idx_pre, self.PRE_SNAPSHOT_DOCS)

        # --- Wait for replayer ---
        logger.info("Waiting for replayer to start...")
        wait_for_pod_ready(ns, REPLAYER_LABEL_SELECTOR, timeout_seconds)
        logger.info("Waiting for replayer to join Kafka consumer group...")
        wait_for_replayer_consuming(namespace=ns)

        # --- Post-snapshot: generate-data via proxy ---
        logger.info("Post-snapshot: generating %d docs into %s via proxy", self.POST_SNAPSHOT_DOCS, self.idx_post)
        run_generate_data("proxy", self.idx_post, self.POST_SNAPSHOT_DOCS)

        # Verify on source
        self.source_operations.check_doc_counts_match(
            cluster=self.source_cluster,
            expected_index_details={
                self.idx_pre: {"count": self.PRE_SNAPSHOT_DOCS},
                self.idx_post: {"count": self.POST_SNAPSHOT_DOCS},
            },
            max_attempts=10, delay=3.0,
        )

        # Wait for the outer workflow to reach the pause-for-migration-verification
        # suspend. Without this, workflow_finish() in the base class may race —
        # calling resume_workflow() before the second suspend is active is a no-op,
        # leaving the workflow stuck in Running state until test teardown aborts it.
        logger.info("Waiting for workflow to reach pause-for-migration-verification suspend...")
        self.argo_service.wait_for_suspend(workflow_name=self.workflow_name, timeout_seconds=600)

    def post_migration_actions(self):
        pass

    def verify_clusters(self):
        # Pre-snapshot docs appear on target twice: once from the snapshot/backfill
        # and once from the replayer. This happens because generate-data uses _bulk
        # without explicit _id fields — Elasticsearch auto-generates a new _id on
        # each request, so the replayed bulk creates new docs instead of overwriting
        # the snapshot-restored ones.
        expected_pre = self.PRE_SNAPSHOT_DOCS * 2
        logger.info("Verifying both indices on target (pre-snapshot expects %d due to duplication)...", expected_pre)
        self.target_operations.check_doc_counts_match(
            cluster=self.target_cluster,
            expected_index_details={
                self.idx_pre: {"count": expected_pre},
                self.idx_post: {"count": self.POST_SNAPSHOT_DOCS},
            },
            max_attempts=120, delay=10.0,
        )

    # Intentionally do NOT override test_after(): the base class asserts the outer
    # workflow reached phase=Succeeded, which is the end-to-end safety net for the
    # new cdc-e2e-migration-with-clusters template. If the workflow fails to finish
    # cleanly (e.g. a suspend/resume race, or a template step erroring), we want the
    # test to fail loudly rather than silently pass based on doc counts alone.
