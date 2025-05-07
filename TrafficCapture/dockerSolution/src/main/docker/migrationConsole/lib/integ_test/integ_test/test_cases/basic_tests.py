import logging
from ..cluster_version import ElasticsearchV6_X, ElasticsearchV7_X, ElasticsearchV8_X, OpensearchV1_X, OpensearchV2_X
from .ma_test_base import MATestBase, MigrationType
from console_link.environment import Environment

logger = logging.getLogger(__name__)


# This test case is subject to removal, as its value looks limited
class Test0001SingleDocumentBackfill(MATestBase):
    def __init__(self, console_config_path: str, console_link_env: Environment, unique_id: str):
        allow_combinations = [
            (ElasticsearchV8_X, OpensearchV2_X),
        ]
        migrations_required = [MigrationType.BACKFILL]
        description = "Performs backfill migration for a single document."
        super().__init__(console_config_path=console_config_path,
                         console_link_env=console_link_env,
                         unique_id=unique_id,
                         description=description,
                         migrations_required=migrations_required,
                         allow_source_target_combinations=allow_combinations)
        self.index_name = f"test_0001_{self.unique_id}"
        self.doc_id = "test_0001_doc"

    def test_before(self):
        # Create single document
        self.source_operations.create_document(cluster=self.source_cluster, index_name=self.index_name,
                                               doc_id=self.doc_id)
        self.source_operations.get_document(cluster=self.source_cluster, index_name=self.index_name, doc_id=self.doc_id)

    def backfill_during(self):
        # Validate single document exists on target
        self.target_operations.get_document(cluster=self.target_cluster, index_name=self.index_name,
                                            doc_id=self.doc_id, max_attempts=10, delay=3.0)


# This test case is subject to removal, as its value looks limited
class Test0002IndexWithNoDocumentsMetadataMigration(MATestBase):
    def __init__(self, console_config_path: str, console_link_env: Environment, unique_id: str):
        allow_combinations = [
            (ElasticsearchV6_X, OpensearchV1_X),
            (ElasticsearchV6_X, OpensearchV2_X),
            (ElasticsearchV7_X, OpensearchV1_X),
            (ElasticsearchV7_X, OpensearchV2_X),
        ]
        run_isolated = True
        migrations_required = [MigrationType.METADATA]
        description = "Performs metadata migration for index with no documents."
        super().__init__(console_config_path=console_config_path,
                         console_link_env=console_link_env,
                         unique_id=unique_id,
                         description=description,
                         migrations_required=migrations_required,
                         allow_source_target_combinations=allow_combinations,
                         run_isolated=run_isolated)
        self.index_name = f"test_0002_{self.unique_id}"

    def test_before(self):
        # Create empty index
        self.source_operations.create_index(cluster=self.source_cluster, index_name=self.index_name)
        self.source_operations.get_index(cluster=self.source_cluster, index_name=self.index_name)

    def metadata_after(self):
        # Validate index exists on target
        self.target_operations.get_index(cluster=self.target_cluster, index_name=self.index_name, max_attempts=5,
                                         delay=2.0)
