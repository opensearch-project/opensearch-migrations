import logging

from .cdc_base import (
    MATestBase, MigrationType, MATestUserArguments,
    CDC_SOURCE_TARGET_COMBINATIONS, REPLAYER_LABEL_SELECTOR, PROXY_LABEL_SELECTOR,
    wait_for_pod_ready, wait_for_replayer_consuming,
    cleanup_cdc_resources, make_proxy_cluster,
)

logger = logging.getLogger(__name__)

CDC_NUM_DOCS = 10


class Test0031CdcOnlyLiveTrafficTls(MATestBase):
    """CDC-only test with TLS enabled on the capture proxy.

    Validates the full TLS chain: cert-manager provisions certs,
    proxy serves HTTPS, client connects with TLS.
    Uses basic PUT operations (same as Test0030) — TLS is the variable under test.
    """
    requires_explicit_selection = True

    def __init__(self, user_args: MATestUserArguments):
        super().__init__(
            user_args=user_args,
            description="CDC-only with TLS on capture proxy.",
            migrations_required=[MigrationType.CAPTURE_AND_REPLAY],
            allow_source_target_combinations=CDC_SOURCE_TARGET_COMBINATIONS,
        )
        self.cdc_index = f"cdc0031-tls-{self.unique_id}"

    def prepare_workflow_parameters(self, keep_workflows: bool = False):
        super().prepare_workflow_parameters(keep_workflows=keep_workflows)
        self.workflow_template = "cdc-only-tls-imported-clusters"

    def prepare_clusters(self):
        pass

    def workflow_perform_migrations(self, timeout_seconds: int = 3600):
        if not self.workflow_name:
            raise ValueError("Workflow name is not available")
        logger.info("Waiting for replayer to start...")
        wait_for_pod_ready(self.argo_service.namespace, REPLAYER_LABEL_SELECTOR, timeout_seconds)
        logger.info("Replayer is running, ready for CDC traffic")

    def post_migration_actions(self):
        logger.info("Waiting for capture-proxy pod to be ready (TLS startup may take longer)...")
        wait_for_pod_ready(self.argo_service.namespace, PROXY_LABEL_SELECTOR)

        logger.info("Waiting for replayer to join Kafka consumer group...")
        wait_for_replayer_consuming(namespace=self.argo_service.namespace)

        proxy_cluster = make_proxy_cluster(self.source_cluster)
        logger.info("Creating %d CDC documents through TLS proxy...", CDC_NUM_DOCS)
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
        logger.info("Verified %d CDC docs on source cluster via TLS proxy", CDC_NUM_DOCS)

    def verify_clusters(self):
        logger.info("Verifying CDC docs on target...")
        self.target_operations.check_doc_counts_match(
            cluster=self.target_cluster,
            expected_index_details={self.cdc_index: {"count": CDC_NUM_DOCS}},
            max_attempts=120, delay=10.0,
        )

    def test_after(self):
        pass

    def cleanup(self):
        cleanup_cdc_resources(self.argo_service.namespace)
