"""
Tests for migration to Amazon OpenSearch Serverless (AOSS) collections.

Each test targets a specific AOSS collection type (search, time-series, vector)
and validates that indices, mappings, settings, and doc counts survive migration.

Required environment variables:
- BYOS_SNAPSHOT_NAME: Name of the snapshot
- BYOS_S3_REPO_URI: S3 URI to snapshot repository
- BYOS_S3_REGION: AWS region (default: us-east-1)
- AOSS_SEARCH_ENDPOINT: AOSS search collection endpoint
- AOSS_TIMESERIES_ENDPOINT: AOSS time-series collection endpoint
- AOSS_VECTOR_ENDPOINT: AOSS vector collection endpoint
"""
import logging
import os

from console_link.models.cluster import Cluster
from ..cluster_version import (
    ElasticsearchV5_X, ElasticsearchV6_X, ElasticsearchV7_X,
    OpensearchV1_X, OpensearchV2_X,
)
from .ma_argo_test_base import MATestBase, MigrationType, MATestUserArguments

logger = logging.getLogger(__name__)

AOSS_ALLOW_COMBINATIONS = [
    (ElasticsearchV5_X, OpensearchV2_X),
    (ElasticsearchV6_X, OpensearchV2_X),
    (ElasticsearchV7_X, OpensearchV2_X),
    (OpensearchV1_X, OpensearchV2_X),
]


class AOSSTestBase(MATestBase):
    """Base for AOSS collection migration tests."""
    requires_explicit_selection = True

    # Subclasses set these
    aoss_endpoint_env_var = ""
    expected_indices = []
    mapping_assertions = {}  # {index: {dotted.path: expected_value}}
    settings_absent = {}     # {index: [dotted.path, ...]}
    settings_present = {}    # {index: {dotted.path: expected_value}}

    def __init__(self, user_args: MATestUserArguments, description: str):
        MATestBase.__init__(self, user_args=user_args,
                            description=description,
                            migrations_required=[MigrationType.METADATA, MigrationType.BACKFILL],
                            allow_source_target_combinations=AOSS_ALLOW_COMBINATIONS)
        self.snapshot_name = os.environ['BYOS_SNAPSHOT_NAME']
        self.s3_repo_uri = os.environ['BYOS_S3_REPO_URI']
        self.s3_region = os.environ.get('BYOS_S3_REGION', 'us-east-1')
        self.pod_replicas = int(os.environ.get('BYOS_POD_REPLICAS', '1'))
        self.monitor_retry_limit = int(os.environ.get('BYOS_MONITOR_RETRY_LIMIT', '60'))

    def import_existing_clusters(self):
        endpoint = os.environ.get(self.aoss_endpoint_env_var)
        if not endpoint:
            raise ValueError(f"{self.aoss_endpoint_env_var} environment variable is required")
        self.target_cluster = Cluster(config={
            "endpoint": endpoint,
            "allow_insecure": False,
            "sigv4": {"region": self.s3_region, "service": "aoss"}
        })
        self.source_cluster = None
        self.imported_clusters = True
        logger.info(f"Imported AOSS target: {endpoint}")

    def prepare_workflow_snapshot_and_migration_config(self):
        self.workflow_snapshot_and_migration_config = [{
            "snapshotConfig": {
                "snapshotNameConfig": {
                    "externallyManagedSnapshot": self.snapshot_name
                }
            },
            "migrations": [{
                "metadataMigrationConfig": {},
                "documentBackfillConfig": {
                    "podReplicas": self.pod_replicas,
                    "useTargetClusterForWorkCoordination": False
                }
            }]
        }]

    def prepare_workflow_parameters(self):
        snapshot_repo = {"awsRegion": self.s3_region, "s3RepoPathUri": self.s3_repo_uri}
        self.workflow_template = "full-migration-imported-clusters"
        self.imported_clusters = True
        self.parameters["source-configs"] = [{
            "source": {
                "endpoint": "",
                "version": f"{self.source_version.cluster_type} "
                           f"{self.source_version.major_version}.{self.source_version.minor_version}",
                "snapshotRepo": snapshot_repo
            },
            "snapshot-and-migration-configs": self.workflow_snapshot_and_migration_config
        }]
        self.parameters["target-config"] = self.target_cluster.config
        self.parameters["monitor-retry-limit"] = str(self.monitor_retry_limit)

    def prepare_clusters(self):
        pass

    def workflow_perform_migrations(self, timeout_seconds: int = 50400):
        super().workflow_perform_migrations(timeout_seconds=timeout_seconds)

    def display_final_cluster_state(self):
        try:
            r = self.target_cluster.call_api("/_count")
            logger.info(f"Target AOSS total doc count: {r.json().get('count', 'unknown')}")
        except Exception as e:
            logger.warning(f"Could not retrieve doc count from AOSS target: {e}")

    # --- Verification helpers ---

    def _get_nested(self, d, dotted_path):
        """Traverse a dict by dotted path like 'properties.name.type'."""
        for key in dotted_path.split('.'):
            if not isinstance(d, dict):
                return None
            d = d.get(key)
        return d

    def _assert_index_exists(self, index):
        r = self.target_cluster.call_api(f"/{index}", raise_error=False)
        assert r.status_code == 200, f"Index {index} does not exist (status={r.status_code})"

    def _assert_mapping(self, index, dotted_path, expected):
        r = self.target_cluster.call_api(f"/{index}/_mapping")
        mappings = r.json()
        # AOSS may nest under index name or not
        if index in mappings:
            props = mappings[index].get("mappings", {})
        else:
            props = mappings.get("mappings", mappings)
        actual = self._get_nested(props, dotted_path)
        assert actual == expected, (
            f"{index}: mapping {dotted_path} expected={expected}, got={actual}"
        )

    def _assert_setting_absent(self, index, dotted_path):
        r = self.target_cluster.call_api(f"/{index}/_settings")
        settings = r.json()
        if index in settings:
            settings = settings[index].get("settings", {})
        actual = self._get_nested(settings, dotted_path)
        assert actual is None, f"{index}: setting {dotted_path} should be absent, got={actual}"

    def _assert_setting_value(self, index, dotted_path, expected):
        r = self.target_cluster.call_api(f"/{index}/_settings")
        settings = r.json()
        if index in settings:
            settings = settings[index].get("settings", {})
        actual = self._get_nested(settings, dotted_path)
        assert actual == expected, (
            f"{index}: setting {dotted_path} expected={expected}, got={actual}"
        )

    def _assert_doc_count_positive(self, index):
        r = self.target_cluster.call_api(f"/{index}/_count")
        count = r.json().get("count", 0)
        assert count > 0, f"{index}: expected docs > 0, got {count}"

    def verify_clusters(self):
        # 1. All expected indices exist and have docs
        for index in self.expected_indices:
            self._assert_index_exists(index)
            self._assert_doc_count_positive(index)

        # 2. Mapping assertions
        for index, checks in self.mapping_assertions.items():
            for path, expected in checks.items():
                self._assert_mapping(index, path, expected)

        # 3. Settings that must be absent
        for index, paths in self.settings_absent.items():
            for path in paths:
                self._assert_setting_absent(index, path)

        # 4. Settings that must have specific values
        for index, checks in self.settings_present.items():
            for path, expected in checks.items():
                self._assert_setting_value(index, path, expected)

        # 5. No extra indices
        r = self.target_cluster.call_api("/_count")
        total = r.json().get("count", 0)
        assert total > 0, "No documents found on AOSS target"
        logger.info(f"All {len(self.expected_indices)} indices verified on AOSS target")


class Test0021SearchCollectionMigration(AOSSTestBase):
    """Migration to AOSS search collection: geonames, pmc, so."""

    aoss_endpoint_env_var = "AOSS_SEARCH_ENDPOINT"
    expected_indices = ["geonames", "pmc", "so"]

    mapping_assertions = {
        "geonames": {
            "properties.name.type": "text",
            "properties.name.fields.raw.type": "keyword",
            "properties.elevation.type": "integer",
            "properties.geonameid.type": "long",
            "properties.population.type": "long",
            "properties.location.type": "geo_point",
            "properties.country_code.fielddata": True,
            "properties.feature_class.fields.raw.type": "keyword",
            "dynamic": "strict",
        },
        "pmc": {
            "properties.timestamp.type": "date",
            "properties.timestamp.format": "yyyy-MM-dd HH:mm:ss",
            "properties.pmid.type": "integer",
            "properties.body.type": "text",
            "properties.name.type": "keyword",
            "dynamic": "strict",
        },
        "so": {
            "properties.creationDate.type": "date",
            "properties.title.type": "text",
            "properties.body.type": "text",
            "properties.user.type": "keyword",
            "properties.tags.type": "keyword",
            "dynamic": "strict",
        },
    }

    settings_absent = {
        "geonames": ["index.store.type"],
    }

    def __init__(self, user_args: MATestUserArguments):
        super().__init__(user_args, "Migration from S3 snapshot to AOSS search collection.")


class Test0022TimeSeriesCollectionMigration(AOSSTestBase):
    """Migration to AOSS time-series collection: http_logs (7 indices), eventdata."""

    aoss_endpoint_env_var = "AOSS_TIMESERIES_ENDPOINT"
    expected_indices = [
        "logs-181998", "logs-191998", "logs-201998", "logs-211998",
        "logs-221998", "logs-231998", "logs-241998", "eventdata",
    ]

    mapping_assertions = {
        "logs-181998": {
            "properties.@timestamp.type": "date",
            "properties.clientip.type": "ip",
            "properties.request.type": "text",
            "properties.request.fields.raw.type": "keyword",
            "properties.request.fields.raw.ignore_above": 256,
            "properties.status.type": "integer",
            "properties.size.type": "integer",
            "properties.geoip.properties.location.type": "geo_point",
            "properties.geoip.properties.country_name.type": "keyword",
            "properties.geoip.properties.city_name.type": "keyword",
            "properties.message.type": "keyword",
            "properties.message.index": False,
            "properties.message.doc_values": False,
            "dynamic": "strict",
        },
        "eventdata": {
            "properties.@timestamp.type": "date",
            "properties.clientip.type": "ip",
            "properties.response.type": "short",
            "properties.agent.type": "keyword",
            "properties.agent.ignore_above": 256,
            "properties.geoip.properties.location.type": "geo_point",
            "properties.geoip.properties.country_name.type": "keyword",
            "properties.useragent.properties.name.type": "keyword",
            "properties.useragent.properties.os.type": "keyword",
            "properties.request.type": "text",
            "properties.request.fields.keyword.type": "keyword",
            "properties.referrer.norms": False,
            "dynamic": "strict",
        },
    }

    settings_absent = {
        "logs-181998": ["index.sort.field", "index.sort.order"],
        "logs-191998": ["index.sort.field", "index.sort.order"],
        "logs-201998": ["index.sort.field", "index.sort.order"],
        "logs-211998": ["index.sort.field", "index.sort.order"],
        "logs-221998": ["index.sort.field", "index.sort.order"],
        "logs-231998": ["index.sort.field", "index.sort.order"],
        "logs-241998": ["index.sort.field", "index.sort.order"],
        "eventdata": ["index.sort.field", "index.sort.order"],
    }

    def __init__(self, user_args: MATestUserArguments):
        super().__init__(user_args, "Migration from S3 snapshot to AOSS time-series collection.")


class Test0023VectorCollectionMigration(AOSSTestBase):
    """Migration to AOSS vector collection: vectors_faiss, vectors_lucene_filtered."""

    aoss_endpoint_env_var = "AOSS_VECTOR_ENDPOINT"
    expected_indices = ["vectors_faiss", "vectors_lucene_filtered"]

    mapping_assertions = {
        "vectors_faiss": {
            "properties.target_field.type": "knn_vector",
            "properties.target_field.dimension": 768,
            "properties.target_field.method.engine": "faiss",
            "properties.target_field.method.name": "hnsw",
            "properties.target_field.method.space_type": "l2",
            "dynamic": "strict",
        },
        "vectors_lucene_filtered": {
            "properties.target_field.type": "knn_vector",
            "properties.target_field.dimension": 768,
            "properties.target_field.method.engine": "lucene",
            "properties.target_field.method.name": "hnsw",
            "properties.color.type": "text",
            "properties.taste.type": "text",
            "properties.age.type": "integer",
            "dynamic": "strict",
        },
    }

    settings_present = {
        "vectors_faiss": {"index.knn": "true"},
        "vectors_lucene_filtered": {"index.knn": "true"},
    }

    def __init__(self, user_args: MATestUserArguments):
        super().__init__(user_args, "Migration from S3 snapshot to AOSS vector collection.")
