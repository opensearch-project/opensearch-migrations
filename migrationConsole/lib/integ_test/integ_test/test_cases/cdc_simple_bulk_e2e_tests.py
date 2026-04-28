import logging

from .cdc_base import (
    MATestBase, MigrationType, MATestUserArguments,
    CDC_SOURCE_TARGET_COMBINATIONS, REPLAYER_LABEL_SELECTOR, PROXY_LABEL_SELECTOR,
    wait_for_pod_ready, wait_for_replayer_consuming,
    cleanup_cdc_resources, run_generate_data,
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
        super().prepare_workflow_parameters(keep_workflows=keep_workflows)
        self.workflow_template = "cdc-full-e2e-imported-clusters"

    def prepare_clusters(self):
        pass

    def workflow_perform_migrations(self, timeout_seconds: int = 3600):
        if not self.workflow_name:
            raise ValueError("Workflow name is not available")
        ns = self.argo_service.namespace

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

    def test_after(self):
        pass

    def cleanup(self):
        cleanup_cdc_resources(self.argo_service.namespace)
