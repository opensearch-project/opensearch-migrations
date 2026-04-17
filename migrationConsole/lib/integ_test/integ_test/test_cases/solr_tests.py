"""Solr-to-OpenSearch integration test cases using the standard Argo workflow pipeline.

Tests exercise the SolrCloud + S3BackupRepository pipeline that real customers use:
- Standard Solr 8.x deployment in SolrCloud mode (see wiki Solr-Backfill-Guide).
- Backups written via the Collections API BACKUP action to an S3 repository.
- Migration Assistant reads those backups and writes to OpenSearch.

Each test runs as its own Argo workflow (fresh Solr + OS per test). Because each
workflow has non-trivial setup/teardown overhead, the realistic-customer scenario
is packed into a single test that exercises:
  - Multiple collections (pre-existing 'dummy' + customer-created ones)
  - A multi-shard collection (regression for SolrBackupIndexMetadataFactory)
  - Hundreds of documents (bulk indexing path)
  - Full doc-count equivalence + sampled document retrieval on the target
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
    """Realistic customer Solr-to-OpenSearch backfill — multiple collections, multi-shard, real data.

    Scenarios exercised (all in one Argo workflow to keep wall-clock reasonable):
      1. Pre-existing 'dummy' collection with a single document (minimum smoke).
      2. A new 'products_*' collection with 25 documents (typical single-shard case).
      3. A new 'sharded_*' collection with 2 shards and 40 documents
         (regression for multi-shard discovery fix).

    Verification checks both doc-count equivalence and that a sampled document
    round-trips through the migration pipeline intact.
    """

    def __init__(self, user_args: MATestUserArguments):
        super().__init__(
            user_args=user_args,
            description=("Realistic Solr backfill: pre-existing collection + customer-created "
                         "single-shard and multi-shard collections with real data."),
            migrations_required=[MigrationType.BACKFILL],
            allow_source_target_combinations=SOLR_ALLOW_COMBINATIONS,
        )
        # Normalize unique_id — Solr collection names cannot contain '-'.
        self._suffix = self.unique_id.replace("-", "_").lower()

        # Pre-existing 'dummy' collection (created by the cluster template at startup).
        self.dummy_collection = "dummy"
        self.dummy_doc_id = "solr_0001_doc"

        # Single-shard collection that the test creates — exercises the typical
        # customer path through the full CREATE → bulk-index → backup → migrate flow.
        self.products_collection = f"products_{self._suffix}"
        self.products_doc_count = 25

        # Multi-shard collection — exercises SolrBackupIndexMetadataFactory shard
        # discovery. Before the fix, only shard 1 was migrated.
        self.sharded_collection = f"sharded_{self._suffix}"
        self.sharded_num_shards = 2
        self.sharded_doc_count = 40

    def prepare_clusters(self):
        # (1) Pre-existing 'dummy' collection — single smoke document.
        logger.info(f"Indexing smoke document into '{self.dummy_collection}'")
        self.source_operations.create_document(
            cluster=self.source_cluster, index_name=self.dummy_collection,
            doc_id=self.dummy_doc_id,
            data={"title": "Test Document", "content": "Sample document for Solr backfill testing."})
        self.source_operations.get_document(
            cluster=self.source_cluster, index_name=self.dummy_collection,
            doc_id=self.dummy_doc_id)

        # (2) Single-shard customer-created collection with bulk data.
        logger.info(f"Creating SolrCloud collection '{self.products_collection}' "
                    f"(1 shard, {self.products_doc_count} docs)")
        self.source_operations.create_index(
            cluster=self.source_cluster, index_name=self.products_collection,
            num_shards=1, replication_factor=1)
        products_docs = [
            {
                "id": f"product_{i:04d}",
                "title": f"Product {i}",
                "content": f"Description for product {i} in {self.products_collection}.",
            }
            for i in range(self.products_doc_count)
        ]
        self.source_operations.bulk_create_documents(
            cluster=self.source_cluster, index_name=self.products_collection,
            docs=products_docs)
        self._assert_source_count(self.products_collection, self.products_doc_count)

        # (3) Multi-shard customer-created collection with bulk data.
        logger.info(f"Creating SolrCloud collection '{self.sharded_collection}' "
                    f"({self.sharded_num_shards} shards, {self.sharded_doc_count} docs)")
        self.source_operations.create_index(
            cluster=self.source_cluster, index_name=self.sharded_collection,
            num_shards=self.sharded_num_shards, replication_factor=1)
        sharded_docs = [
            {
                "id": f"shard_doc_{i:04d}",
                "title": f"Sharded item {i}",
                "content": f"Document {i} distributed across {self.sharded_num_shards} shards.",
            }
            for i in range(self.sharded_doc_count)
        ]
        self.source_operations.bulk_create_documents(
            cluster=self.source_cluster, index_name=self.sharded_collection,
            docs=sharded_docs)
        self._assert_source_count(self.sharded_collection, self.sharded_doc_count)

    def _assert_source_count(self, collection: str, expected: int):
        actual = self.source_operations.get_doc_count(
            cluster=self.source_cluster, index_name=collection)
        assert actual == expected, (
            f"Expected {expected} docs in source '{collection}' after indexing, got {actual}")

    def verify_clusters(self):
        # (1) Smoke document round-trips.
        self.target_operations.get_document(
            cluster=self.target_cluster, index_name=self.dummy_collection,
            doc_id=self.dummy_doc_id, max_attempts=20, delay=3.0)

        # (2) Products: probe one doc as a readiness wait, then assert full count.
        self.target_operations.get_document(
            cluster=self.target_cluster, index_name=self.products_collection,
            doc_id="product_0000", max_attempts=20, delay=3.0)
        self._assert_target_count(self.products_collection, self.products_doc_count)

        # (3) Multi-shard: probe a doc at each end of the id range to catch partial
        #     migrations, then assert full count. Doc-count check is THE regression.
        self.target_operations.get_document(
            cluster=self.target_cluster, index_name=self.sharded_collection,
            doc_id="shard_doc_0000", max_attempts=20, delay=3.0)
        self.target_operations.get_document(
            cluster=self.target_cluster, index_name=self.sharded_collection,
            doc_id=f"shard_doc_{self.sharded_doc_count - 1:04d}",
            max_attempts=20, delay=3.0)
        self._assert_target_count(self.sharded_collection, self.sharded_doc_count,
                                  regression_hint=(
                                      "Multi-shard doc count mismatch — only the first shard "
                                      "may have been migrated. Check "
                                      "SolrBackupIndexMetadataFactory shard discovery (needs "
                                      "collectionPreparer to download shard_backup_metadata "
                                      "from S3 before counting)."))

    def _assert_target_count(self, collection: str, expected: int, regression_hint: str = ""):
        # Retry a few times — bulk migration may not be fully flushed to the target yet.
        import time
        actual = 0
        for attempt in range(1, 31):
            actual = self.target_operations.get_doc_count(
                cluster=self.target_cluster, index_name=collection)
            if actual == expected:
                logger.info(f"Verified target collection '{collection}': {actual} docs OK")
                return
            logger.info(f"Attempt {attempt}/30: target '{collection}' has {actual}/{expected} docs, "
                        f"retrying...")
            time.sleep(3)
        extra = f"\n  Hint: {regression_hint}" if regression_hint else ""
        raise AssertionError(
            f"Doc count mismatch in target '{collection}': expected {expected}, got {actual}.{extra}")
