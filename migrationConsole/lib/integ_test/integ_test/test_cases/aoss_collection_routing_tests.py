"""Migrate one snapshot through an OpenSearch Serverless per-account endpoint.

A single collection-routed target sends each source index to a NextGen collection
chosen by its static and regex collection routing lists. Verification reads each collection through its
own per-collection endpoint, so it does not depend on the routing code under test.

It expects AOSS_ACCOUNT_ENDPOINT, AOSS_COLLECTION_PREFIX, AOSS_NEXTGEN_SEARCH_ENDPOINT,
AOSS_NEXTGEN_VECTOR_ENDPOINT, AOSS_SNAPSHOT_NAME, and AOSS_S3_REPO_URI. The optional
AOSS_S3_REGION and AOSS_MONITOR_RETRY_LIMIT variables override their defaults.
"""

import logging
import os

from console_link.middleware.clusters import cat_indices, connection_check
from console_link.models.cluster import Cluster
from .aoss_collection_tests import AOSS_COLLECTIONS, Test0021AossCollectionMigrations, encode_migration_config
from .ma_argo_test_base import MATestUserArguments

logger = logging.getLogger(__name__)

# Collection names are <prefix>-search and <prefix>-vectors (see test/aossNextGen/collections.yaml)
SEARCH_SUFFIX = "search"
VECTOR_SUFFIX = "vectors"

# geonames uses a static entry, vectors_faiss the capture-group regex, and eventdata the catch-all regex.
# eventdata comes from a time series collection in Test0021; NextGen has no time series type.
ROUTED_INDICES = {
    SEARCH_SUFFIX: ["geonames", "eventdata"],
    VECTOR_SUFFIX: ["vectors_faiss"],
}
RFS_POD_REPLICAS = 3


def collection_names(prefix: str) -> dict:
    return {suffix: f"{prefix}-{suffix}" for suffix in ROUTED_INDICES}


def build_collection_routing(prefix: str) -> dict:
    """Exercises a static entry, a capture group, and a catch-all that must not shadow the static entry."""
    search = collection_names(prefix)[SEARCH_SUFFIX]
    return {
        "staticCollectionRouting": [
            {"sourceIndex": "geonames", "collection": search},
        ],
        "regexCollectionRouting": [
            {"sourceIndex": "(vectors)_.*", "collection": f"{prefix}-$1"},
            {"sourceIndex": ".*", "collection": search},
        ],
    }


def build_routed_migration_config(
    source_version: str,
    snapshot_name: str,
    s3_repo_uri: str,
    s3_region: str,
    account_endpoint: str,
    collection_prefix: str,
    s3_role_arn: str = "",
    s3_endpoint: str = "",
) -> dict:
    """One collection-routed target and one migration whose routing table covers every index."""
    repo_config = {
        "awsRegion": s3_region,
        "repoPathUri": s3_repo_uri,
    }
    if s3_role_arn:
        repo_config["s3RoleArn"] = s3_role_arn
    if s3_endpoint:
        repo_config["endpoint"] = s3_endpoint

    indices = [index for suffix_indices in ROUTED_INDICES.values() for index in suffix_indices]
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
        "targetClusters": {
            "routed": {
                "endpoint": account_endpoint,
                "allowInsecure": False,
                "collectionRouted": True,
                "authConfig": {
                    "sigv4": {
                        "region": s3_region,
                        "service": "aoss",
                    }
                },
            }
        },
        "snapshotMigrationConfigs": [{
            "fromSource": "source",
            "toTarget": "routed",
            "perSnapshotConfig": {
                snapshot_name: [{
                    **build_collection_routing(collection_prefix),
                    "metadataMigrationConfig": {"indexAllowlist": indices},
                    "documentBackfillConfig": {
                        "indexAllowlist": indices,
                        "podReplicas": RFS_POD_REPLICAS,
                    },
                }]
            },
        }],
    }


def index_checks(index: str) -> dict:
    """Test0021's per-index mapping and settings checks, whichever collection held the index there."""
    checks = {}
    for collection in AOSS_COLLECTIONS.values():
        for kind in ("mapping_assertions", "settings_absent", "settings_present"):
            if index in collection.get(kind, {}):
                checks[kind] = collection[kind][index]
    return checks


class Test0024AossCollectionRouting(Test0021AossCollectionMigrations):
    """Route indices from one snapshot to NextGen collections through the per-account endpoint."""

    requires_explicit_selection = True

    def __init__(self, user_args: MATestUserArguments):
        super().__init__(user_args)
        self.description = "Migration through the AOSS per-account endpoint, routed to NextGen collections."

    def import_existing_clusters(self):
        self._load_snapshot_config()
        required = ["AOSS_ACCOUNT_ENDPOINT", "AOSS_COLLECTION_PREFIX",
                    "AOSS_NEXTGEN_SEARCH_ENDPOINT", "AOSS_NEXTGEN_VECTOR_ENDPOINT"]
        missing = [name for name in required if not os.environ.get(name)]
        if missing:
            raise ValueError(
                f"{', '.join(missing)} environment variables are required. "
                "Ensure the pipeline injects them into the migration-console statefulset."
            )
        self.account_endpoint = os.environ["AOSS_ACCOUNT_ENDPOINT"]
        self.collection_prefix = os.environ["AOSS_COLLECTION_PREFIX"]

        verification_endpoints = {
            SEARCH_SUFFIX: os.environ["AOSS_NEXTGEN_SEARCH_ENDPOINT"],
            VECTOR_SUFFIX: os.environ["AOSS_NEXTGEN_VECTOR_ENDPOINT"],
        }
        for suffix, endpoint in verification_endpoints.items():
            target = Cluster(config={
                "endpoint": endpoint,
                "allow_insecure": False,
                "sigv4": {"region": self.s3_region, "service": "aoss"},
            })
            connection_result = connection_check(target)
            assert connection_result.connection_established, (
                f"{suffix} collection connection failed: {connection_result.connection_message}"
            )
            self.target_clusters[suffix] = target
            self.target_endpoints[suffix] = endpoint
            logger.info("Imported NextGen %s collection endpoint: %s", suffix, endpoint)

        self.source_cluster = None
        self.target_cluster = self.target_clusters[SEARCH_SUFFIX]
        self.imported_clusters = True
        logger.info("Routing through %s to collections %s", self.account_endpoint,
                    collection_names(self.collection_prefix))

    def prepare_workflow_parameters(self, keep_workflows: bool = False):
        source_version = (
            f"{self.source_version.cluster_type} "
            f"{self.source_version.major_version}.{self.source_version.minor_version}"
        )
        snapshot_config = self.argo_service.get_configmap_data("migrations-default-s3-config")
        image_config = self.argo_service.get_configmap_data("migration-image-config")
        migration_config = build_routed_migration_config(
            source_version=source_version,
            snapshot_name=self.snapshot_name,
            s3_repo_uri=self.s3_repo_uri,
            s3_region=self.s3_region,
            account_endpoint=self.account_endpoint,
            collection_prefix=self.collection_prefix,
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

    def display_final_cluster_state(self):
        for suffix, target in self.target_clusters.items():
            response = cat_indices(cluster=target, refresh=True)
            logger.info("NEXTGEN COLLECTION (%s)\n%s", suffix, response)

    def _assert_index_absent(self, suffix, target, index):
        response = target.call_api(f"/{index}", raise_error=False)
        assert response.status_code == 404, (
            f"{suffix}/{index}: index should not exist in this collection (status={response.status_code})"
        )

    @staticmethod
    def _template_names(target, path):
        response = target.call_api(path, raise_error=False)
        if response.status_code != 200:
            return None
        body = response.json()
        key = "index_templates" if "index_templates" in body else "component_templates"
        return {template["name"] for template in body.get(key, []) if not template["name"].startswith(".")}

    def _assert_templates_in_every_collection(self):
        for path in ("/_index_template", "/_component_template"):
            names = {suffix: self._template_names(target, path) for suffix, target in self.target_clusters.items()}
            if any(found is None for found in names.values()):
                logger.warning("Skipping %s comparison; not every collection answered: %s", path, names)
                continue
            distinct = {frozenset(found) for found in names.values()}
            assert len(distinct) == 1, f"{path} differs between collections: {names}"
            logger.info("Every collection has the same %d entries for %s", len(next(iter(distinct))), path)

    def verify_clusters(self):
        for suffix, indices in ROUTED_INDICES.items():
            target = self.target_clusters[suffix]
            for index in indices:
                self._assert_index_exists(suffix, target, index)
                self._assert_doc_count_positive(suffix, target, index)
                checks = index_checks(index)
                for path, expected in checks.get("mapping_assertions", {}).items():
                    self._assert_mapping(suffix, target, index, path, expected)
                for path in checks.get("settings_absent", []):
                    self._assert_setting_absent(suffix, target, index, path)
                for path, expected in checks.get("settings_present", {}).items():
                    self._assert_setting_value(suffix, target, index, path, expected)

            # Routing must place each index in exactly one collection, not copy it everywhere
            for other_suffix, other_target in self.target_clusters.items():
                if other_suffix != suffix:
                    for index in indices:
                        self._assert_index_absent(other_suffix, other_target, index)
            logger.info("Verified %d routed indices in the %s collection", len(indices), suffix)

        self._assert_templates_in_every_collection()
