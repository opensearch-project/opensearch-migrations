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
  - A collection with flat field names containing '.' (e.g. category.name)
  - Hundreds of documents (bulk indexing path)
  - Full doc-count equivalence + sampled document retrieval on the target
"""
import logging
import uuid

import requests

from ..cluster_version import SolrV8_X, OpensearchV2_X, OpensearchV3_X
from ..common_utils import execute_api_call
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
        self._suffix = f"{self.unique_id}-{uuid.uuid4().hex[:4]}".replace("-", "_").lower()

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

        # Dotted-field collection — exercises Solr field names containing '.'.
        self.dotted_collection = f"dotted_{self._suffix}"
        self.dotted_doc_id = "dotted_doc_0001"

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

        # (4) Dotted-field collection — explicit schema fields whose names contain '.'.
        logger.info(f"Creating SolrCloud collection '{self.dotted_collection}' "
                    f"with explicit dotted-name fields")
        self.source_operations.create_index(
            cluster=self.source_cluster, index_name=self.dotted_collection,
            num_shards=1, replication_factor=1)
        self._add_solr_schema_fields(self.dotted_collection, [
            {"name": "category.name", "type": "string", "stored": True, "indexed": True, "docValues": True},
            {"name": "category.id", "type": "pint", "stored": True, "indexed": True, "docValues": True},
            {"name": "metric.cpu.percent", "type": "pfloat", "stored": True, "indexed": True, "docValues": True},
            {"name": "is.active", "type": "boolean", "stored": True, "docValues": True},
        ])
        self.source_operations.create_document(
            cluster=self.source_cluster, index_name=self.dotted_collection,
            doc_id=self.dotted_doc_id,
            data={
                "category.name": "books",
                "category.id": 7,
                "metric.cpu.percent": 42.5,
                "is.active": True,
            })
        self._assert_source_count(self.dotted_collection, 1)

    def _add_solr_schema_fields(self, collection: str, fields: list):
        """Declare explicit schema fields on a Solr collection via the Schema API."""
        url = f"{self.source_cluster.endpoint}/solr/{collection}/schema"
        r = requests.post(url, json={"add-field": fields},
                          headers={"Content-Type": "application/json"}, timeout=15)
        r.raise_for_status()
        # Solr returns 200 with errors embedded in the body on failure.
        result = r.json()
        if "errors" in result:
            raise AssertionError(
                f"Failed to add Solr schema fields for '{collection}': {result['errors']}")

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

        # (4) Dotted fields: doc-count, _source preservation, and query resolution.
        self._assert_target_count(self.dotted_collection, 1,
                                  regression_hint=(
                                      "Dotted-field collection failed to migrate. Check that "
                                      "SolrSchemaConverter emits dotted field names as literal "
                                      "keys under 'properties'."))
        self._assert_dotted_field_doc_round_trip()

    def _assert_dotted_field_doc_round_trip(self):
        """Verify _source preserves dotted keys and queries by dotted path resolve."""
        resp = execute_api_call(
            cluster=self.target_cluster,
            path=f"/{self.dotted_collection}/_search?q=id:{self.dotted_doc_id}&size=1")
        hits = resp.json().get("hits", {}).get("hits", [])
        assert len(hits) == 1, f"Expected to find dotted-field doc by id, got {len(hits)} hits."
        src = hits[0].get("_source", {})
        assert src.get("category.name") == "books", f"_source mismatch: {src!r}"
        assert src.get("category.id") == 7, f"_source mismatch: {src!r}"
        actual_pct = src.get("metric.cpu.percent")
        assert actual_pct is not None and abs(actual_pct - 42.5) < 0.01, f"_source mismatch: {src!r}"
        assert src.get("is.active") is True, f"_source mismatch: {src!r}"

        kw_hits = execute_api_call(
            cluster=self.target_cluster,
            path=f"/{self.dotted_collection}/_search?q=category.name:books&size=10",
        ).json().get("hits", {}).get("hits", [])
        assert len(kw_hits) == 1 and kw_hits[0].get("_id") == self.dotted_doc_id, (
            f"Query 'category.name:books' should match dotted-field doc, got: {kw_hits}")

        flt_hits = execute_api_call(
            cluster=self.target_cluster,
            path=f"/{self.dotted_collection}/_search?q=metric.cpu.percent:42.5&size=10",
        ).json().get("hits", {}).get("hits", [])
        assert len(flt_hits) == 1, f"Query on 'metric.cpu.percent' should match, got: {flt_hits}"

    def _assert_target_count(self, collection: str, expected: int, regression_hint: str = ""):
        # Retry a few times — bulk migration may not be fully flushed to the target yet.
        # Uses a direct _count API call per collection rather than get_all_index_details
        # (which crashes when called without index_prefix_ignore_list).
        import time
        actual = 0
        for attempt in range(1, 31):
            try:
                count_response = execute_api_call(
                    cluster=self.target_cluster,
                    path=f"/{collection}/_count?format=json")
                actual = count_response.json().get("count", 0)
            except Exception:
                actual = 0
            if actual == expected:
                logger.info(f"Verified target collection '{collection}': {actual} docs OK")
                return
            logger.info(f"Attempt {attempt}/30: target '{collection}' has {actual}/{expected} docs, "
                        f"retrying...")
            time.sleep(3)
        extra = f"\n  Hint: {regression_hint}" if regression_hint else ""
        raise AssertionError(
            f"Doc count mismatch in target '{collection}': expected {expected}, got {actual}.{extra}")
