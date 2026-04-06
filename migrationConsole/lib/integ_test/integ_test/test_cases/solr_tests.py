"""Solr-to-OpenSearch integration test cases."""
import logging
import time

import requests

from ..cluster_version import SolrV8_X, OpensearchV2_X, OpensearchV3_X
from .ma_argo_test_base import MATestBase, MigrationType, MATestUserArguments

logger = logging.getLogger(__name__)

SOLR_ALLOW_COMBINATIONS = [
    (SolrV8_X, OpensearchV2_X),
    (SolrV8_X, OpensearchV3_X),
]


class SolrTestBase(MATestBase):
    """Base class for Solr tests — overrides Argo workflow methods."""

    def __init__(self, user_args, description):
        super().__init__(
            user_args=user_args,
            description=description,
            migrations_required=[MigrationType.METADATA, MigrationType.BACKFILL],
            allow_source_target_combinations=SOLR_ALLOW_COMBINATIONS,
        )

    def import_existing_clusters(self):
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
        pass

    def prepare_workflow_parameters(self, keep_workflows: bool = False):
        pass

    def workflow_start(self):
        self.workflow_name = "solr-direct-migration"

    def workflow_setup_clusters(self):
        pass

    def workflow_perform_migrations(self, timeout_seconds: int = 300):
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

    def workflow_finish(self):
        pass

    def test_after(self):
        logger.info("Solr migration test completed successfully")

    def _solr_api(self, path, method="get", json_data=None):
        url = f"{self.source_cluster.endpoint}{path}"
        if method == "post":
            r = requests.post(url, json=json_data,
                              headers={"Content-Type": "application/json"},
                              timeout=30)
        else:
            r = requests.get(url, timeout=30)
        r.raise_for_status()
        return r.json()

    def _os_count(self, index):
        requests.post(
            f"{self.target_cluster.endpoint}/_refresh", timeout=10)
        time.sleep(1)
        r = requests.get(
            f"{self.target_cluster.endpoint}/{index}/_count", timeout=10)
        r.raise_for_status()
        return r.json().get("count", 0)


class TestSolr0001BasicMigration(SolrTestBase):
    """Basic Solr migration: create collection, load docs, migrate, verify."""

    def __init__(self, user_args: MATestUserArguments):
        super().__init__(user_args=user_args,
                         description="Basic Solr migration with simple docs.")
        self.collection_name = f"test_solr_{self.unique_id}".replace("-", "_")
        self.doc_count = 5

    def prepare_clusters(self):
        self.source_operations.create_index(
            index_name=self.collection_name, cluster=self.source_cluster)
        for i in range(self.doc_count):
            self.source_operations.create_document(
                index_name=self.collection_name, doc_id=f"doc_{i}",
                cluster=self.source_cluster,
                data={"title": f"Document {i}", "content": f"Content {i}"})

    def verify_clusters(self):
        actual = self._os_count(self.collection_name)
        assert actual == self.doc_count, (
            f"Expected {self.doc_count} docs, got {actual}")
        r = requests.get(
            f"{self.target_cluster.endpoint}/{self.collection_name}/_doc/doc_0",
            timeout=10)
        r.raise_for_status()
        src = r.json().get("_source", {})
        assert "Document 0" in str(src.get("title", "")), (
            f"Unexpected doc: {src}")
        logger.info(f"Verified {actual} docs migrated")


class TestSolr0002ExampleDataMigration(SolrTestBase):
    """Migrate Solr example datasets: techproducts (rich schema) + films."""

    def __init__(self, user_args: MATestUserArguments):
        super().__init__(
            user_args=user_args,
            description="Migrate techproducts (typed fields) and films (multi-valued, dates).")
        uid = self.unique_id.replace("-", "_")
        self.tp_collection = f"techproducts_{uid}"
        self.films_collection = f"films_{uid}"

    def prepare_clusters(self):
        self._create_techproducts()
        self._create_films()

    def _create_techproducts(self):
        self._solr_api(
            f"/solr/admin/collections?action=CREATE&name={self.tp_collection}"
            f"&numShards=1&replicationFactor=1&wt=json")
        fields = [
            {"name": "cat", "type": "string", "multiValued": True},
            {"name": "name", "type": "text_general"},
            {"name": "price", "type": "pfloat"},
            {"name": "inStock", "type": "boolean"},
            {"name": "author", "type": "string"},
            {"name": "series_t", "type": "text_general"},
            {"name": "sequence_i", "type": "pint"},
            {"name": "genre_s", "type": "string"},
            {"name": "pages_i", "type": "pint"},
        ]
        self._solr_api(
            f"/solr/{self.tp_collection}/schema",
            method="post", json_data={"add-field": fields})
        books = [
            {"id": "978-0641723445", "cat": ["book", "hardcover"],
             "name": "The Lightning Thief", "author": "Rick Riordan",
             "series_t": "Percy Jackson", "sequence_i": 1,
             "genre_s": "fantasy", "inStock": True,
             "price": 12.50, "pages_i": 384},
            {"id": "978-1423103349", "cat": ["book", "paperback"],
             "name": "The Sea of Monsters", "author": "Rick Riordan",
             "series_t": "Percy Jackson", "sequence_i": 2,
             "genre_s": "fantasy", "inStock": True,
             "price": 6.49, "pages_i": 304},
            {"id": "978-1857995879", "cat": ["book", "paperback"],
             "name": "Sophie's World", "author": "Jostein Gaarder",
             "genre_s": "novel", "inStock": True,
             "price": 3.07, "pages_i": 512},
            {"id": "978-1933988177", "cat": ["book", "paperback"],
             "name": "Lucene in Action", "author": "Michael McCandless",
             "genre_s": "IT", "inStock": True,
             "price": 30.50, "pages_i": 475},
        ]
        self._solr_api(
            f"/solr/{self.tp_collection}/update?commit=true",
            method="post", json_data=books)
        logger.info(f"Created {self.tp_collection} with {len(books)} docs")

    def _create_films(self):
        self._solr_api(
            f"/solr/admin/collections?action=CREATE&name={self.films_collection}"
            f"&numShards=1&replicationFactor=1&wt=json")
        fields = [
            {"name": "name", "type": "text_general"},
            {"name": "directed_by", "type": "string", "multiValued": True},
            {"name": "genre", "type": "string", "multiValued": True},
            {"name": "initial_release_date", "type": "pdate"},
        ]
        self._solr_api(
            f"/solr/{self.films_collection}/schema",
            method="post", json_data={"add-field": fields})
        films = []
        for i in range(50):
            films.append({
                "id": f"film_{i}",
                "name": f"Film Title {i}",
                "directed_by": [f"Director {i % 10}"],
                "genre": [["Drama", "Comedy", "Action",
                           "Thriller", "Sci-Fi"][i % 5]],
                "initial_release_date":
                    f"20{i % 24:02d}-{(i % 12) + 1:02d}-15T00:00:00Z",
            })
        self._solr_api(
            f"/solr/{self.films_collection}/update?commit=true",
            method="post", json_data=films)
        logger.info(f"Created {self.films_collection} with {len(films)} docs")

    def verify_clusters(self):
        # Verify techproducts
        tp_count = self._os_count(self.tp_collection)
        assert tp_count == 4, f"Expected 4 techproducts docs, got {tp_count}"

        r = requests.get(
            f"{self.target_cluster.endpoint}/{self.tp_collection}/_mapping",
            timeout=10)
        r.raise_for_status()
        props = r.json()[self.tp_collection]["mappings"]["properties"]
        assert props["price"]["type"] == "float", (
            f"Expected price=float, got {props.get('price')}")
        assert props["inStock"]["type"] == "boolean", (
            f"Expected inStock=boolean, got {props.get('inStock')}")
        assert props["pages_i"]["type"] == "integer", (
            f"Expected pages_i=integer, got {props.get('pages_i')}")

        r = requests.get(
            f"{self.target_cluster.endpoint}/{self.tp_collection}"
            f"/_doc/978-0641723445", timeout=10)
        r.raise_for_status()
        src = r.json()["_source"]
        assert "Lightning" in str(src.get("name", "")), (
            f"Unexpected techproducts doc: {src}")
        logger.info(f"Verified techproducts: {tp_count} docs, mappings OK")

        # Verify films
        films_count = self._os_count(self.films_collection)
        assert films_count == 50, (
            f"Expected 50 films docs, got {films_count}")

        r = requests.get(
            f"{self.target_cluster.endpoint}/{self.films_collection}/_mapping",
            timeout=10)
        r.raise_for_status()
        props = r.json()[self.films_collection]["mappings"]["properties"]
        assert props["initial_release_date"]["type"] == "date", (
            f"Expected date, got {props.get('initial_release_date')}")
        assert props["genre"]["type"] == "keyword", (
            f"Expected keyword, got {props.get('genre')}")
        logger.info(f"Verified films: {films_count} docs, mappings OK")
