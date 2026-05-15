import logging
import uuid

from .cdc_base import (
    MATestBase, MigrationType, MATestUserArguments,
    CDC_SOURCE_TARGET_COMBINATIONS, REPLAYER_LABEL_SELECTOR, PROXY_LABEL_SELECTOR,
    wait_for_pod_ready, wait_for_replayer_consuming,
    run_generate_data,
)

logger = logging.getLogger(__name__)


class Test0040CdcFullE2eSimpleBulk(MATestBase):
    """Full E2E CDC with minimal bulk ingestion via 'console clusters generate-data'.

    Pre-snapshot: generate-data --cluster proxy into idx_pre.
    Post-snapshot: generate-data --cluster proxy into idx_post.
    Verifies 2x PRE on target (snapshot backfill + replay) and 1x POST (replay only).

    Template selection follows MATestBase.imported_clusters so the same test
    runs correctly in both environments:
      * imported_clusters=True  (EKS, --reuse-clusters) -> cdc-full-e2e-imported-clusters,
        runs straight through.
      * imported_clusters=False (k8s-local) -> cdc-e2e-migration-with-clusters,
        provisions source+target inline and suspends at pause-for-test-data
        and pause-for-migration-verification; resume_workflow/wait_for_suspend
        below mirror MATestBase's lifecycle for that shape.
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
        uid = f"{self.unique_id}-{uuid.uuid4().hex[:4]}"
        self.idx_pre = f"cdc0040-pre-snapshot-{uid}"
        self.idx_post = f"cdc0040-post-snapshot-{uid}"

    def prepare_workflow_parameters(self, keep_workflows: bool = False):
        super().prepare_workflow_parameters(keep_workflows=keep_workflows)
        self.workflow_template = (
            "cdc-full-e2e-imported-clusters" if self.imported_clusters
            else "cdc-e2e-migration-with-clusters"
        )

    def prepare_clusters(self):
        pass

    def workflow_perform_migrations(self, timeout_seconds: int = 3600):
        if not self.workflow_name:
            raise ValueError("Workflow name is not available")
        ns = self.argo_service.namespace

        # -with-clusters suspends at pause-for-test-data so the framework can
        # collect cluster configs. Resume past it to deploy proxy + replayer.
        # -imported-clusters has no suspend point.
        if not self.imported_clusters:
            logger.info("Resuming workflow past pause-for-test-data to start migration...")
            self.argo_service.resume_workflow(workflow_name=self.workflow_name)

        logger.info("Waiting for capture-proxy to be ready...")
        wait_for_pod_ready(ns, PROXY_LABEL_SELECTOR, timeout_seconds)

        logger.info("Pre-snapshot: generating %d docs into %s via proxy", self.PRE_SNAPSHOT_DOCS, self.idx_pre)
        run_generate_data("proxy", self.idx_pre, self.PRE_SNAPSHOT_DOCS)

        logger.info("Waiting for replayer to start...")
        wait_for_pod_ready(ns, REPLAYER_LABEL_SELECTOR, timeout_seconds)
        logger.info("Waiting for replayer to join Kafka consumer group...")
        wait_for_replayer_consuming(namespace=ns)

        logger.info("Post-snapshot: generating %d docs into %s via proxy", self.POST_SNAPSHOT_DOCS, self.idx_post)
        run_generate_data("proxy", self.idx_post, self.POST_SNAPSHOT_DOCS)

        # Source verification.
        self.source_operations.check_doc_counts_match(
            cluster=self.source_cluster,
            expected_index_details={
                self.idx_pre: {"count": self.PRE_SNAPSHOT_DOCS},
                self.idx_post: {"count": self.POST_SNAPSHOT_DOCS},
            },
            max_attempts=10, delay=3.0,
        )

        # -with-clusters has a second suspend (pause-for-migration-verification)
        # that MATestBase.workflow_finish() will resume past. Wait for the
        # suspend to be active so the resume isn't a no-op against a still-
        # running workflow. -imported-clusters needs no such synchronisation.
        if not self.imported_clusters:
            logger.info("Waiting for workflow to reach pause-for-migration-verification suspend...")
            self.argo_service.wait_for_suspend(workflow_name=self.workflow_name, timeout_seconds=600)

    def post_migration_actions(self):
        pass

    def verify_clusters(self):
        # Pre-snapshot docs appear on target twice: once from snapshot/backfill
        # and once from replay. generate-data uses _bulk with auto-generated
        # _ids, so the replay creates new docs instead of overwriting the
        # snapshot-restored ones.
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
