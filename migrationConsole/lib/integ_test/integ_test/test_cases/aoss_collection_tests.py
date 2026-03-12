"""
Tests for migration to Amazon OpenSearch Serverless (AOSS) collections.

Each test targets a specific AOSS collection type (search, time-series, vector)
and validates that indices, mappings, settings, and doc counts survive migration.

Required environment variables:
- AOSS_SEARCH_ENDPOINT: AOSS search collection endpoint (Test0021)
- AOSS_TIMESERIES_ENDPOINT: AOSS time-series collection endpoint (Test0022)
- AOSS_VECTOR_ENDPOINT: AOSS vector collection endpoint (Test0023)
"""
import json
import logging
import os
import subprocess

from console_link.middleware.clusters import connection_check
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

    def _run_cmd(self, command: str, stdin_input: str = None) -> str:
        """Run a shell command directly (tests execute inside the pod)."""
        result = subprocess.run(["bash", "-c", command], input=stdin_input,
                                capture_output=True, text=True, timeout=300)
        if result.returncode != 0:
            raise RuntimeError(f"Command failed (rc={result.returncode}): {result.stderr}\n{result.stdout}")
        return result.stdout

    def _console_cmd(self, args: str) -> str:
        return self._run_cmd(f"/.venv/bin/console {args}")

    def _workflow_cmd(self, args: str) -> str:
        return self._run_cmd(f"/.venv/bin/workflow {args}")

    def _get_workflow_config(self) -> dict:
        output = self._workflow_cmd("configure view")
        return json.loads(output)

    def _set_source_auth_no_auth(self):
        """Switch source to no_auth for OSB benchmarks (source has open access policy)."""
        config = self._get_workflow_config()
        source_key = list(config["sourceClusters"].keys())[0]
        config["sourceClusters"][source_key].pop("authConfig", None)
        self._run_cmd("/.venv/bin/workflow configure edit --stdin", stdin_input=json.dumps(config))
        output = self._console_cmd("clusters connection-check")
        assert "Successfully connected" in output, f"No-auth connection check failed: {output}"
        logger.info("Source switched to no_auth and connection verified")

    def _set_source_auth_sigv4(self):
        """Switch source cluster auth back to sigv4 in the workflow config."""
        config = self._get_workflow_config()
        source_key = list(config["sourceClusters"].keys())[0]
        config["sourceClusters"][source_key].pop("noAuth", None)
        region = os.environ.get('AWS_DEFAULT_REGION', 'us-east-1')
        config["sourceClusters"][source_key]["authConfig"] = {
            "sigv4": {"region": region, "service": "es"}
        }
        self._run_cmd("/.venv/bin/workflow configure edit --stdin", stdin_input=json.dumps(config))
        logger.info("Source switched back to sigv4 auth")

    def test_before(self):
        """Pre-flight: verify both clusters are reachable."""
        pass

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
        # Source cluster from configmap (set up by MA deployment)
        self.source_cluster = self.argo_service.get_cluster_from_configmap(
            f"source-{self.source_version.full_cluster_type}-"
            f"{self.source_version.major_version}-{self.source_version.minor_version}"
        )
        if not self.source_cluster:
            raise ValueError("Could not find source cluster configmap")
        self.imported_clusters = True
        logger.info(f"Imported source: {self.source_cluster.endpoint}")
        logger.info(f"Imported AOSS target: {endpoint}")

        # Connection check after import
        source_result = connection_check(self.source_cluster)
        assert source_result.connection_established, f"Source connection failed: {source_result.connection_message}"
        target_result = connection_check(self.target_cluster)
        assert target_result.connection_established, f"Target connection failed: {target_result.connection_message}"

    def prepare_clusters(self):
        """Switch to no_auth, load test data via CLI, switch back to sigv4."""
        self._set_source_auth_no_auth()
        output = self._console_cmd(f"clusters run-aoss-test-benchmarks --collection-type {self.collection_type}")
        logger.info(f"Benchmark output: {output}")
        self._set_source_auth_sigv4()

    def prepare_workflow_snapshot_and_migration_config(self):
        """Let the workflow create the snapshot — no externally managed snapshot."""
        self.workflow_snapshot_and_migration_config = [{
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

    # --- Verification helpers ---

    def _get_nested(self, d, dotted_path):
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
        super().__init__(user_args, "Migration from ES to AOSS vector collection.")
