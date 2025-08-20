import logging
from ..cluster_version import ElasticsearchV5_X, OpensearchV2_X
from .ma_argo_test_base import MATestBase, MigrationType

logger = logging.getLogger(__name__)


# This test case is subject to removal, as its value looks limited
class Test0001SingleDocumentBackfill(MATestBase):
    def __init__(self, source_version: str, target_version: str, unique_id: str):
        allow_combinations = [
            (ElasticsearchV5_X, OpensearchV2_X),
        ]
        migrations_required = [MigrationType.BACKFILL]
        description = "Performs backfill migration for a single document."
        super().__init__(source_version=source_version,
                         target_version=target_version,
                         unique_id=unique_id,
                         description=description,
                         migrations_required=migrations_required,
                         allow_source_target_combinations=allow_combinations)
        self.index_name = f"test_0001_{self.unique_id}"
        self.doc_id = "test_0001_doc"
        self.doc_type = "sample_type"
        self.source_cluster = None
        self.target_cluster = None

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
