import logging
import uuid

from .cdc_base import (
    MATestBase, MigrationType, MATestUserArguments,
    CDC_SOURCE_TARGET_COMBINATIONS, REPLAYER_LABEL_SELECTOR,
    wait_for_pod_ready, wait_for_replayer_consuming,
    run_generate_data,
)

logger = logging.getLogger(__name__)

CDC_NUM_DOCS = 500


class Test0032CdcOnlyGenerateData(MATestBase):
    """CDC-only test using 'console clusters generate-data' CLI to exercise
    the _bulk ingestion path through the capture proxy."""
    requires_explicit_selection = True

    def __init__(self, user_args: MATestUserArguments):
        super().__init__(
            user_args=user_args,
            description="CDC-only bulk ingestion via generate-data CLI through capture proxy.",
            migrations_required=[MigrationType.CAPTURE_AND_REPLAY],
            allow_source_target_combinations=CDC_SOURCE_TARGET_COMBINATIONS,
        )
        self.cdc_index = f"cdc0032-gendata-{self.unique_id}-{uuid.uuid4().hex[:4]}"

    def prepare_workflow_parameters(self, keep_workflows: bool = False):
        super().prepare_workflow_parameters(keep_workflows=keep_workflows)
        self.workflow_template = "cdc-only-imported-clusters"

    def prepare_clusters(self):
        pass

    def workflow_perform_migrations(self, timeout_seconds: int = 3600):
        if not self.workflow_name:
            raise ValueError("Workflow name is not available")
        logger.info("Waiting for replayer to start...")
        wait_for_pod_ready(self.argo_service.namespace, REPLAYER_LABEL_SELECTOR, timeout_seconds)
        logger.info("Replayer is running, ready for CDC traffic")

    def post_migration_actions(self):
        logger.info("Waiting for replayer to join Kafka consumer group...")
        wait_for_replayer_consuming(namespace=self.argo_service.namespace)

        logger.info("Generating %d docs via proxy into %s", CDC_NUM_DOCS, self.cdc_index)
        run_generate_data("proxy", self.cdc_index, CDC_NUM_DOCS)

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
