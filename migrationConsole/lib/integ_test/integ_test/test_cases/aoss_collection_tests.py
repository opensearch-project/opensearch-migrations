"""
Tests for migration to Amazon OpenSearch Serverless (AOSS) collections.

Each test targets a specific AOSS collection type (search, time-series, vector)
and validates that indices, mappings, settings, and doc counts survive migration.

Uses pre-staged S3 snapshots (BYOS pattern) — no source cluster is deployed.

Required environment variables:
- AOSS_SEARCH_ENDPOINT: AOSS search collection endpoint (Test0021)
- AOSS_TIMESERIES_ENDPOINT: AOSS time-series collection endpoint (Test0022)
- AOSS_VECTOR_ENDPOINT: AOSS vector collection endpoint (Test0023)
- AOSS_SNAPSHOT_NAME: Name of the pre-staged snapshot
- AOSS_S3_REPO_URI: S3 URI to snapshot repository
- AOSS_S3_REGION: AWS region for S3 (default: us-east-1)
- AOSS_MONITOR_RETRY_LIMIT: Max retries for workflow monitoring (default: 33)
"""
import logging
import os

from console_link.middleware.clusters import cat_indices, connection_check
from console_link.models.cluster import Cluster
from .ma_argo_test_base import MATestBase, MigrationType, MATestUserArguments

logger = logging.getLogger(__name__)


class AOSSTestBase(MATestBase):
    """Base for AOSS collection migration tests."""
    requires_explicit_selection = True

    # Subclasses set these
    aoss_endpoint_env_var = ""
    collection_type = ""
    expected_indices = []
    mapping_assertions = {}
    settings_absent = {}
    settings_present = {}

    def __init__(self, user_args: MATestUserArguments, description: str):
        MATestBase.__init__(self, user_args=user_args,
                            description=description,
                            migrations_required=[MigrationType.METADATA, MigrationType.BACKFILL],
                            allow_source_target_combinations=[])

    def workflow_perform_migrations(self, timeout_seconds: int = 1800):  # 30 min for EKS node churn
        super().workflow_perform_migrations(timeout_seconds=timeout_seconds)

    def _load_snapshot_config(self):
        """Load snapshot configuration from environment variables."""
        self.snapshot_name = os.environ['AOSS_SNAPSHOT_NAME']
        self.s3_repo_uri = os.environ['AOSS_S3_REPO_URI']
        self.s3_region = os.environ.get('AOSS_S3_REGION', 'us-east-1')
        self.monitor_retry_limit = int(os.environ.get('AOSS_MONITOR_RETRY_LIMIT', '33'))

    def test_before(self):
        """Pre-flight: verify target cluster is reachable."""
        pass

    def display_final_cluster_state(self):
        target_response = cat_indices(cluster=self.target_cluster, refresh=True)
        if isinstance(target_response, bytes):
            target_response = target_response.decode("utf-8")
        logger.info(f"TARGET CLUSTER (AOSS)\n{target_response}")

    def import_existing_clusters(self):
        endpoint = os.environ.get(self.aoss_endpoint_env_var)
        if not endpoint:
            raise ValueError(
                f"{self.aoss_endpoint_env_var} environment variable is required. "
                f"Ensure the pipeline injects it via 'kubectl set env' on the migration-console statefulset."
            )
        region = os.environ.get('AWS_DEFAULT_REGION', 'us-east-1')
        self.target_cluster = Cluster(config={
            "endpoint": endpoint,
            "allow_insecure": False,
            "sigv4": {"region": region, "service": "aoss"}
        })
        self.source_cluster = None
        self.imported_clusters = True
        self._load_snapshot_config()
        logger.info(f"Imported AOSS target: {endpoint}")
        logger.info(f"Using snapshot: {self.snapshot_name} from {self.s3_repo_uri}")

        # Connection check on target only (no source cluster)
        target_result = connection_check(self.target_cluster)
        assert target_result.connection_established, f"Target connection failed: {target_result.connection_message}"

    def prepare_clusters(self):
        """No source cluster to prepare — using pre-staged snapshot."""
        pass

    def prepare_workflow_snapshot_and_migration_config(self):
        """Configure for external snapshot with index allowlists."""
        self.workflow_snapshot_and_migration_config = [{
            "snapshotConfig": {
                "snapshotNameConfig": {
                    "externallyManagedSnapshotName": self.snapshot_name
                }
            },
            "migrations": [{
                "metadataMigrationConfig": {
                    "indexAllowlist": self.expected_indices
                },
                "documentBackfillConfig": {
                    "useTargetClusterForWorkCoordination": False,
                    "indexAllowlist": self.expected_indices
                }
            }]
        }]

    def prepare_workflow_parameters(self, keep_workflows: bool = False):
        """Build workflow parameters for snapshot-based AOSS migration."""
        source_config = {
            "endpoint": "",
            "version": f"{self.source_version.cluster_type} "
                       f"{self.source_version.major_version}.{self.source_version.minor_version}",
            "snapshotRepo": {
                "awsRegion": self.s3_region,
                "s3RepoPathUri": self.s3_repo_uri
            }
        }

        self.workflow_template = "full-migration-imported-clusters"
        self.parameters["source-configs"] = [{
            "source": source_config,
            "snapshot-and-migration-configs": self.workflow_snapshot_and_migration_config
        }]
        self.parameters["target-config"] = self.target_cluster.config
        self.parameters["monitor-retry-limit"] = str(self.monitor_retry_limit)

    # --- Verification helpers ---

    @staticmethod
    def _get_nested(d, dotted_path):
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
        for index in self.expected_indices:
            self._assert_index_exists(index)
            self._assert_doc_count_positive(index)
        for index, checks in self.mapping_assertions.items():
            for path, expected in checks.items():
                self._assert_mapping(index, path, expected)
        for index, paths in self.settings_absent.items():
            for path in paths:
                self._assert_setting_absent(index, path)
        for index, checks in self.settings_present.items():
            for path, expected in checks.items():
                self._assert_setting_value(index, path, expected)
        r = self.target_cluster.call_api("/_count")
        total = r.json().get("count", 0)
        assert total > 0, "No documents found on AOSS target"
        logger.info(f"All {len(self.expected_indices)} indices verified on AOSS target")


class Test0021SearchCollectionMigration(AOSSTestBase):
    aoss_endpoint_env_var = "AOSS_SEARCH_ENDPOINT"
    collection_type = "search"
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
        super().__init__(user_args, "Migration from ES to AOSS search collection.")


class Test0022TimeSeriesCollectionMigration(AOSSTestBase):
    aoss_endpoint_env_var = "AOSS_TIMESERIES_ENDPOINT"
    collection_type = "timeseries"
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
        super().__init__(user_args, "Migration from ES to AOSS time-series collection.")


class Test0023VectorCollectionMigration(AOSSTestBase):
    aoss_endpoint_env_var = "AOSS_VECTOR_ENDPOINT"
    collection_type = "vector"
    # vectors_lucene_filtered is excluded: lucene KNN engine was added in OS 2.2
    # and is not present in the OS 1.3 source snapshot used for these tests.
    expected_indices = ["vectors_faiss"]

    mapping_assertions = {
        "vectors_faiss": {
            "properties.target_field.type": "knn_vector",
            "properties.target_field.dimension": 768,
            "properties.target_field.method.engine": "faiss",
            "properties.target_field.method.name": "hnsw",
            "properties.target_field.method.space_type": "l2",
            "dynamic": "strict",
        },
    }

    settings_present = {
        "vectors_faiss": {"index.knn": "true"},
    }

    def __init__(self, user_args: MATestUserArguments):
        super().__init__(user_args, "Migration from ES to AOSS vector collection.")
