import logging
from ..cluster_version import (ClusterVersion, ElasticsearchV6_X, ElasticsearchV7_X, OpensearchV1_X,
                               OpensearchV2_X)
from .ma_test_base import MATestBase
from console_link.environment import Environment
from console_link.models.command_result import CommandResult

logger = logging.getLogger(__name__)


class Test0001SingleDocumentBackfill(MATestBase):
    def __init__(self, console_config_path: str, console_link_env: Environment, unique_id: str):
        allow_combinations = [
            (ElasticsearchV6_X, OpensearchV1_X),
            (ElasticsearchV6_X, OpensearchV2_X),
            (ElasticsearchV7_X, OpensearchV1_X),
            (ElasticsearchV7_X, OpensearchV2_X),
        ]
        super().__init__(console_config_path=console_config_path,
                         console_link_env=console_link_env,
                         unique_id=unique_id,
                         allow_source_target_combinations=allow_combinations)
        self.index_name = f"test_0001_{self.unique_id}"
        self.doc_id = "test_0001_doc"

    def perform_initial_operations(self):
        # Create single document
        self.source_operations.create_document(cluster=self.source_cluster, index_name=self.index_name, doc_id=self.doc_id)
        self.source_operations.get_document(cluster=self.source_cluster, index_name=self.index_name, doc_id=self.doc_id)

    def perform_operations_during_backfill_migration(self):
        # Validate single document exists on target
        self.target_operations.get_document(cluster=self.target_cluster, index_name=self.index_name, doc_id=self.doc_id, max_attempts=10, delay=3.0)


class Test0002IndexWithNoDocumentsMetadataMigration(MATestBase):
    def __init__(self, console_config_path: str, console_link_env: Environment, unique_id: str):
        allow_combinations = [
            (ElasticsearchV6_X, OpensearchV1_X),
            (ElasticsearchV6_X, OpensearchV2_X),
            (ElasticsearchV7_X, OpensearchV1_X),
            (ElasticsearchV7_X, OpensearchV2_X),
        ]
        run_isolated = True
        super().__init__(console_config_path=console_config_path,
                         console_link_env=console_link_env,
                         unique_id=unique_id,
                         allow_source_target_combinations=allow_combinations,
                         run_isolated=run_isolated)
        self.index_name = f"test_0002_{self.unique_id}"

    def start_backfill_migration(self):
        logger.info("Skipping backfill migration")

    def start_live_capture_migration(self):
        logger.info("Skipping live capture migration")

    def stop_backfill_migration(self):
        pass

    def stop_live_capture_migration(self):
        pass

    def perform_initial_operations(self):
        # Create empty index
        self.source_operations.create_index(cluster=self.source_cluster, index_name=self.index_name)
        self.source_operations.get_index(cluster=self.source_cluster, index_name=self.index_name)

    def perform_operations_after_metadata_migration(self):
        # Validate index exists on target
        self.target_operations.get_index(cluster=self.target_cluster, index_name=self.index_name, max_attempts=5, delay=2.0)
