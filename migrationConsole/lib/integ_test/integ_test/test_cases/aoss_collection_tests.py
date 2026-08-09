"""Migrate one snapshot to search, time-series, and vector AOSS collections.

The combined test submits three concurrent migration branches with disjoint
index allowlists. It expects AOSS_SEARCH_ENDPOINT, AOSS_TIMESERIES_ENDPOINT,
AOSS_VECTOR_ENDPOINT, AOSS_SNAPSHOT_NAME, and AOSS_S3_REPO_URI. The optional
AOSS_S3_REGION and AOSS_MONITOR_RETRY_LIMIT variables override their defaults.
"""

import base64
import json
import logging
import os

from console_link.middleware.clusters import cat_indices, connection_check
from console_link.models.cluster import Cluster
from .ma_argo_test_base import MATestBase, MigrationType, MATestUserArguments

logger = logging.getLogger(__name__)


AOSS_COLLECTIONS = {
    "search": {
        "endpoint_env_var": "AOSS_SEARCH_ENDPOINT",
        "expected_indices": ["geonames", "pmc", "so"],
        "mapping_assertions": {
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
        },
        "settings_absent": {
            "geonames": ["index.store.type"],
        },
    },
    "timeseries": {
        "endpoint_env_var": "AOSS_TIMESERIES_ENDPOINT",
        "expected_indices": [
            "logs-181998", "logs-191998", "logs-201998", "logs-211998",
            "logs-221998", "logs-231998", "logs-241998", "eventdata",
        ],
        "mapping_assertions": {
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
        },
        "settings_absent": {
            "logs-181998": ["index.sort.field", "index.sort.order"],
            "logs-191998": ["index.sort.field", "index.sort.order"],
            "logs-201998": ["index.sort.field", "index.sort.order"],
            "logs-211998": ["index.sort.field", "index.sort.order"],
            "logs-221998": ["index.sort.field", "index.sort.order"],
            "logs-231998": ["index.sort.field", "index.sort.order"],
            "logs-241998": ["index.sort.field", "index.sort.order"],
            "eventdata": ["index.sort.field", "index.sort.order"],
        },
    },
    "vector": {
        "endpoint_env_var": "AOSS_VECTOR_ENDPOINT",
        # vectors_lucene_filtered requires the Lucene KNN engine added in OS 2.2.
        "expected_indices": ["vectors_faiss"],
        "mapping_assertions": {
            "vectors_faiss": {
                "properties.target_field.type": "knn_vector",
                "properties.target_field.dimension": 768,
                "properties.target_field.method.engine": "faiss",
                "properties.target_field.method.name": "hnsw",
                "properties.target_field.method.space_type": "l2",
                "dynamic": "strict",
            },
        },
        "settings_present": {
            "vectors_faiss": {"index.knn": "true"},
        },
    },
}


def build_aoss_migration_config(
    source_version: str,
    snapshot_name: str,
    s3_repo_uri: str,
    s3_region: str,
    target_endpoints: dict,
    s3_role_arn: str = "",
    s3_endpoint: str = "",
) -> dict:
    """Build one source and one concurrent migration branch per AOSS target."""
    repo_config = {
        "awsRegion": s3_region,
        "repoPathUri": s3_repo_uri,
    }
    if s3_role_arn:
        repo_config["s3RoleArn"] = s3_role_arn
    if s3_endpoint:
        repo_config["endpoint"] = s3_endpoint

    target_clusters = {
        target_name: {
            "endpoint": target_endpoints[target_name],
            "allowInsecure": False,
            "authConfig": {
                "sigv4": {
                    "region": s3_region,
                    "service": "aoss",
                }
            },
        }
        for target_name in AOSS_COLLECTIONS
    }
    snapshot_migrations = []
    for target_name, collection in AOSS_COLLECTIONS.items():
        indices = collection["expected_indices"]
        snapshot_migrations.append({
            "fromSource": "source",
            "toTarget": target_name,
            "perSnapshotConfig": {
                snapshot_name: [{
                    "metadataMigrationConfig": {"indexAllowlist": indices},
                    "documentBackfillConfig": {"indexAllowlist": indices},
                }]
            },
        })

    return {
        "skipApprovals": True,
        "sourceClusters": {
            "source": {
                "endpoint": "",
                "version": source_version,
                "snapshotInfo": {
                    "repos": {
                        "default": repo_config,
                    },
                    "snapshots": {
                        snapshot_name: {
                            "repoName": "default",
                            "config": {
                                "externallyManagedSnapshotName": snapshot_name,
                            },
                        }
                    },
                },
            }
        },
        "targetClusters": target_clusters,
        "snapshotMigrationConfigs": snapshot_migrations,
    }


def encode_migration_config(config: dict) -> str:
    return base64.b64encode(json.dumps(config).encode("utf-8")).decode("ascii")


class Test0021AossCollectionMigrations(MATestBase):
    """Migrate disjoint index sets from one snapshot to all AOSS collection types."""

    requires_explicit_selection = True

    def __init__(self, user_args: MATestUserArguments):
        super().__init__(
            user_args=user_args,
            description="Migration from one snapshot to search, time-series, and vector AOSS collections.",
            migrations_required=[MigrationType.METADATA, MigrationType.BACKFILL],
            allow_source_target_combinations=[],
        )
        self.target_clusters = {}
        self.target_endpoints = {}

    def _load_snapshot_config(self):
        self.snapshot_name = os.environ["AOSS_SNAPSHOT_NAME"]
        self.s3_repo_uri = os.environ["AOSS_S3_REPO_URI"]
        self.s3_region = os.environ.get(
            "AOSS_S3_REGION",
            os.environ.get("AWS_DEFAULT_REGION", "us-east-1"),
        )
        self.monitor_retry_limit = int(os.environ.get("AOSS_MONITOR_RETRY_LIMIT", "33"))

    def import_existing_clusters(self):
        self._load_snapshot_config()
        missing = [
            collection["endpoint_env_var"]
            for collection in AOSS_COLLECTIONS.values()
            if not os.environ.get(collection["endpoint_env_var"])
        ]
        if missing:
            raise ValueError(
                f"{', '.join(missing)} environment variables are required. "
                "Ensure the pipeline injects every AOSS endpoint into the migration-console statefulset."
            )

        for target_name, collection in AOSS_COLLECTIONS.items():
            endpoint = os.environ[collection["endpoint_env_var"]]
            target = Cluster(config={
                "endpoint": endpoint,
                "allow_insecure": False,
                "sigv4": {"region": self.s3_region, "service": "aoss"},
            })
            connection_result = connection_check(target)
            assert connection_result.connection_established, (
                f"{target_name} target connection failed: {connection_result.connection_message}"
            )
            self.target_clusters[target_name] = target
            self.target_endpoints[target_name] = endpoint
            logger.info("Imported AOSS %s target: %s", target_name, endpoint)

        self.source_cluster = None
        self.target_cluster = self.target_clusters["search"]
        self.imported_clusters = True
        logger.info("Using snapshot %s from %s", self.snapshot_name, self.s3_repo_uri)

    def prepare_clusters(self):
        pass

    def prepare_workflow_snapshot_and_migration_config(self):
        pass

    def prepare_workflow_parameters(self, keep_workflows: bool = False):
        source_version = (
            f"{self.source_version.cluster_type} "
            f"{self.source_version.major_version}.{self.source_version.minor_version}"
        )
        snapshot_config = self.argo_service.get_configmap_data("migrations-default-s3-config")
        image_config = self.argo_service.get_configmap_data("migration-image-config")
        migration_config = build_aoss_migration_config(
            source_version=source_version,
            snapshot_name=self.snapshot_name,
            s3_repo_uri=self.s3_repo_uri,
            s3_region=self.s3_region,
            target_endpoints=self.target_endpoints,
            s3_role_arn=snapshot_config.get("SNAPSHOT_ROLE_ARN", ""),
            s3_endpoint=snapshot_config.get("ENDPOINT", ""),
        )
        self.workflow_template = "full-migration-with-workflow-cli"
        self.parameters = {
            "migrationConfigBase64": encode_migration_config(migration_config),
            "imageMigrationConsoleLocation": image_config["migrationConsoleImage"],
            "imageMigrationConsolePullPolicy": image_config["migrationConsolePullPolicy"],
            "keepMigrationWorkflow": "true" if keep_workflows else "false",
            "monitor-retry-limit": str(self.monitor_retry_limit),
        }

    def workflow_perform_migrations(self, timeout_seconds: int = 3600):
        super().workflow_perform_migrations(timeout_seconds=timeout_seconds)

    def display_final_cluster_state(self):
        for target_name, target in self.target_clusters.items():
            response = cat_indices(cluster=target, refresh=True)
            logger.info("TARGET COLLECTION (%s)\n%s", target_name, response)

    @staticmethod
    def _get_nested(data, dotted_path):
        for key in dotted_path.split("."):
            if not isinstance(data, dict):
                return None
            data = data.get(key)
        return data

    def _assert_index_exists(self, target_name, target, index):
        response = target.call_api(f"/{index}", raise_error=False)
        assert response.status_code == 200, (
            f"{target_name}/{index}: index does not exist (status={response.status_code})"
        )

    def _assert_mapping(self, target_name, target, index, dotted_path, expected):
        mappings = target.call_api(f"/{index}/_mapping").json()
        properties = mappings[index].get("mappings", {}) if index in mappings else mappings.get("mappings", mappings)
        actual = self._get_nested(properties, dotted_path)
        assert actual == expected, (
            f"{target_name}/{index}: mapping {dotted_path} expected={expected}, got={actual}"
        )

    def _assert_setting_absent(self, target_name, target, index, dotted_path):
        settings = target.call_api(f"/{index}/_settings").json()
        settings = settings[index].get("settings", {}) if index in settings else settings
        actual = self._get_nested(settings, dotted_path)
        assert actual is None, (
            f"{target_name}/{index}: setting {dotted_path} should be absent, got={actual}"
        )

    def _assert_setting_value(self, target_name, target, index, dotted_path, expected):
        settings = target.call_api(f"/{index}/_settings").json()
        settings = settings[index].get("settings", {}) if index in settings else settings
        actual = self._get_nested(settings, dotted_path)
        assert actual == expected, (
            f"{target_name}/{index}: setting {dotted_path} expected={expected}, got={actual}"
        )

    @staticmethod
    def _assert_doc_count_positive(target_name, target, index):
        count = target.call_api(f"/{index}/_count").json().get("count", 0)
        assert count > 0, f"{target_name}/{index}: expected docs > 0, got {count}"

    def verify_clusters(self):
        for target_name, collection in AOSS_COLLECTIONS.items():
            target = self.target_clusters[target_name]
            for index in collection["expected_indices"]:
                self._assert_index_exists(target_name, target, index)
                self._assert_doc_count_positive(target_name, target, index)
            for index, checks in collection.get("mapping_assertions", {}).items():
                for path, expected in checks.items():
                    self._assert_mapping(target_name, target, index, path, expected)
            for index, paths in collection.get("settings_absent", {}).items():
                for path in paths:
                    self._assert_setting_absent(target_name, target, index, path)
            for index, checks in collection.get("settings_present", {}).items():
                for path, expected in checks.items():
                    self._assert_setting_value(target_name, target, index, path, expected)

            total = target.call_api("/_count").json().get("count", 0)
            assert total > 0, f"No documents found on {target_name} AOSS target"
            logger.info(
                "Verified %d indices on the %s AOSS target",
                len(collection["expected_indices"]),
                target_name,
            )
