"""Solr-to-OpenSearch integration test cases."""
import logging
import time

from ..cluster_version import SolrV8_X, OpensearchV2_X, OpensearchV3_X
from .ma_argo_test_base import MATestBase, MigrationType, MATestUserArguments

logger = logging.getLogger(__name__)


class TestSolr0001BasicMigration(MATestBase):
    """Test basic Solr-to-OpenSearch migration: metadata + backfill via console commands."""

    def __init__(self, user_args: MATestUserArguments):
        allow_combinations = [
            (SolrV8_X, OpensearchV2_X),
            (SolrV8_X, OpensearchV3_X),
        ]
        description = "Performs Solr-to-OpenSearch metadata and backfill migration via console commands."
        super().__init__(
            user_args=user_args,
            description=description,
            migrations_required=[MigrationType.METADATA, MigrationType.BACKFILL],
            allow_source_target_combinations=allow_combinations,
        )
        self.collection_name = f"test_solr_{self.unique_id}".replace("-", "_")
        self.doc_count = 5

    def import_existing_clusters(self):
        """Connect to pre-deployed Solr and OpenSearch clusters."""
        self.imported_clusters = True
        from console_link.models.cluster import Cluster
        self.source_cluster = Cluster({
            "endpoint": "http://solr-source:8983",
            "no_auth": None,
            "version": "SOLR 8.11.4",
        })
        self.target_cluster = Cluster({
            "endpoint": "http://opensearch-target:9200",
            "no_auth": None,
        })

    def prepare_workflow_snapshot_and_migration_config(self):
        """No Argo workflow config needed for Solr."""
        pass

    def prepare_workflow_parameters(self, keep_workflows: bool = False):
        """No Argo workflow parameters needed for Solr."""
        pass

    def workflow_start(self):
        """No Argo workflow to start — set a dummy workflow_name to satisfy teardown."""
        self.workflow_name = "solr-direct-migration"

    def workflow_setup_clusters(self):
        """Clusters already deployed in import_existing_clusters."""
        pass

    def prepare_clusters(self):
        """Create a Solr collection and load test documents."""
        self.source_operations.create_index(
            index_name=self.collection_name, cluster=self.source_cluster
        )
        for i in range(self.doc_count):
            self.source_operations.create_document(
                index_name=self.collection_name,
                doc_id=f"doc_{i}",
                cluster=self.source_cluster,
                data={"title": f"Document {i}", "content": f"Content for document {i}"},
            )

    def workflow_perform_migrations(self, timeout_seconds: int = 300):
        """Run metadata + backfill via console_link directly."""
        from console_link.models.solr_metadata import SolrMetadata
        from console_link.models.solr_backfill import SolrBackfill

        logger.info("Running Solr metadata migration...")
        metadata = SolrMetadata(self.source_cluster, self.target_cluster)
        result = metadata.migrate()
        logger.info(f"Metadata result: {result.value}")
        assert result.success, f"Metadata migration failed: {result.value}"

        logger.info("Running Solr backfill...")
        backfill = SolrBackfill(self.source_cluster, self.target_cluster)
        result = backfill.start()
        logger.info(f"Backfill result: {result.value}")
        assert result.success, f"Backfill failed: {result.value}"

    def verify_clusters(self):
        """Verify documents migrated to OpenSearch."""
        import requests
        # Refresh
        requests.post(f"{self.target_cluster.endpoint}/_refresh", timeout=10)
        time.sleep(2)

        # Check doc count
        r = requests.get(
            f"{self.target_cluster.endpoint}/{self.collection_name}/_count",
            timeout=10
        )
        r.raise_for_status()
        actual_count = r.json().get("count", 0)
        assert actual_count == self.doc_count, (
            f"Expected {self.doc_count} docs in '{self.collection_name}', got {actual_count}"
        )

        # Spot-check a document
        r = requests.get(
            f"{self.target_cluster.endpoint}/{self.collection_name}/_doc/doc_0",
            timeout=10
        )
        r.raise_for_status()
        source = r.json().get("_source", {})
        assert "Document 0" in str(source.get("title", "")), f"Unexpected doc content: {source}"
        logger.info(f"Verified {actual_count} docs migrated to OpenSearch")

    def workflow_finish(self):
        """No Argo workflow to finish."""
        pass

    def test_after(self):
        """Skip Argo workflow status check."""
        logger.info("Solr migration test completed successfully")
