import logging

from .cdc_base import (
    MATestBase, MigrationType, MATestUserArguments,
    CDC_SOURCE_TARGET_COMBINATIONS, REPLAYER_LABEL_SELECTOR,
    wait_for_pod_ready, wait_for_replayer_consuming,
    make_proxy_cluster,
)

logger = logging.getLogger(__name__)

CDC_NUM_DOCS = 10


class Test0031CdcOnlyLiveTraffic(MATestBase):
    """CDC-only test: proxy captures from start, no snapshot migration.

    Validates the capture-proxy → Kafka → replayer pipeline in isolation.
    No backfill, no snapshot — all docs flow through the proxy.
    """
    requires_explicit_selection = True

    def __init__(self, user_args: MATestUserArguments):
        super().__init__(
            user_args=user_args,
            description="CDC-only live traffic capture and replay via proxy, no backfill.",
            migrations_required=[MigrationType.CAPTURE_AND_REPLAY],
            allow_source_target_combinations=CDC_SOURCE_TARGET_COMBINATIONS,
        )
        self.cdc_index = f"cdc0031-captureproxy-{self.unique_id}"

    def prepare_workflow_parameters(self, keep_workflows: bool = False):
        super().prepare_workflow_parameters(keep_workflows=keep_workflows)
        self.workflow_template = "cdc-only-imported-clusters"

    def prepare_clusters(self):
        pass  # No pre-loaded data — all docs go through the proxy

    def workflow_perform_migrations(self, timeout_seconds: int = 3600):
        if not self.workflow_name:
            raise ValueError("Workflow name is not available")
        logger.info("Waiting for replayer to start...")
        wait_for_pod_ready(self.argo_service.namespace, REPLAYER_LABEL_SELECTOR, timeout_seconds)
        logger.info("Replayer is running, ready for CDC traffic")

    def post_migration_actions(self):
        logger.info("Waiting for replayer to join Kafka consumer group...")
        wait_for_replayer_consuming(namespace=self.argo_service.namespace)

        proxy_cluster = make_proxy_cluster(self.source_cluster)
        logger.info("Creating %d CDC documents through proxy...", CDC_NUM_DOCS)
        for i in range(CDC_NUM_DOCS):
            self.source_operations.create_document(
                cluster=proxy_cluster,
                index_name=self.cdc_index,
                doc_id=f"cdc_doc_{i}",
            )

        self.source_operations.check_doc_counts_match(
            cluster=self.source_cluster,
            expected_index_details={self.cdc_index: {"count": CDC_NUM_DOCS}},
            max_attempts=5, delay=2.0,
        )
        logger.info("Verified %d CDC docs on source cluster", CDC_NUM_DOCS)

    def verify_clusters(self):
        logger.info("Verifying CDC docs on target...")
        self.target_operations.check_doc_counts_match(
            cluster=self.target_cluster,
            expected_index_details={self.cdc_index: {"count": CDC_NUM_DOCS}},
            max_attempts=120, delay=10.0,
        )

    def test_after(self):
        pass
