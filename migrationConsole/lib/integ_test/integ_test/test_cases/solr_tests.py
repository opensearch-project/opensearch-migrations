"""Solr-to-OpenSearch integration test cases using the standard Argo workflow pipeline.

NOTE: Standalone Solr does not support S3-based snapshots required by the RFS pipeline.
The full migration workflow will fail at the snapshot step until either:
1. SolrCloud mode is used (requires Solr Operator CRDs pre-installed), or
2. The RFS pipeline adds support for standalone Solr replication-based backups.

For now, this test validates the cluster creation and data loading steps.
"""
import logging

from ..cluster_version import SolrV8_X, OpensearchV2_X, OpensearchV3_X
from .ma_argo_test_base import MATestBase, MigrationType, MATestUserArguments

logger = logging.getLogger(__name__)

SOLR_ALLOW_COMBINATIONS = [
    (SolrV8_X, OpensearchV2_X),
    (SolrV8_X, OpensearchV3_X),
]


class TestSolr0001SingleDocumentBackfill(MATestBase):
    """Single document Solr backfill via Argo workflow (S3/backup-based RFS pipeline)."""

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

    def verify_clusters(self):
        self.target_operations.get_document(
            cluster=self.target_cluster, index_name=self.index_name,
            doc_id=self.doc_id, max_attempts=10, delay=3.0)
