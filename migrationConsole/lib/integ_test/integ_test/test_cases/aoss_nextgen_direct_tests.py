import logging
import os

from console_link.middleware.clusters import cat_indices, connection_check
from console_link.models.cluster import Cluster, HttpMethod
from .aoss_collection_routing_tests import index_checks
from .aoss_collection_tests import Test0021AossCollectionMigrations, encode_migration_config
from .ma_argo_test_base import MATestUserArguments

logger = logging.getLogger(__name__)

DIRECT_TARGET = "nextgen_vectors"
DIRECT_INDICES = ["vectors_faiss"]


def build_direct_migration_config(
    source_version: str,
    snapshot_name: str,
    s3_repo_uri: str,
    s3_region: str,
    target_endpoint: str,
    s3_role_arn: str = "",
    s3_endpoint: str = "",
) -> dict:
    repo_config = {
        "awsRegion": s3_region,
        "repoPathUri": s3_repo_uri,
    }
    if s3_role_arn:
        repo_config["s3RoleArn"] = s3_role_arn
    if s3_endpoint:
        repo_config["endpoint"] = s3_endpoint
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
            DIRECT_TARGET: {
                "endpoint": target_endpoint,
                "allowInsecure": False,
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
            "toTarget": DIRECT_TARGET,
            "perSnapshotConfig": {
                snapshot_name: [{
                    "metadataMigrationConfig": {"indexAllowlist": DIRECT_INDICES},
                    "documentBackfillConfig": {"indexAllowlist": DIRECT_INDICES},
                }]
            },
        }],
    }


class Test0025AossNextGenDirectVector(Test0021AossCollectionMigrations):
    requires_explicit_selection = True

    def __init__(self, user_args: MATestUserArguments):
        super().__init__(user_args)
        self.description = "Migration of a knn_vector index to a NextGen vector collection through its own endpoint."

    def import_existing_clusters(self):
        self._load_snapshot_config()
        endpoint = os.environ.get("AOSS_NEXTGEN_VECTOR_ENDPOINT")
        if not endpoint:
            raise ValueError(
                "AOSS_NEXTGEN_VECTOR_ENDPOINT environment variable is required. "
                "Ensure the pipeline injects it into the migration-console statefulset."
            )
        target = Cluster(config={
            "endpoint": endpoint,
            "allow_insecure": False,
            "sigv4": {"region": self.s3_region, "service": "aoss"},
        })
        connection_result = connection_check(target)
        assert connection_result.connection_established, (
            f"{DIRECT_TARGET} connection failed: {connection_result.connection_message}"
        )
        self.target_clusters[DIRECT_TARGET] = target
        self.target_endpoints[DIRECT_TARGET] = endpoint
        self.source_cluster = None
        self.target_cluster = target
        self.imported_clusters = True
        logger.info("Imported NextGen vector collection endpoint %s as a direct target", endpoint)

    def prepare_clusters(self):
        for index in DIRECT_INDICES:
            response = self.target_cluster.call_api(f"/{index}", method=HttpMethod.DELETE, raise_error=False)
            logger.info("Deleted %s from %s before migrating (status=%s)", index, DIRECT_TARGET, response.status_code)

    def prepare_workflow_parameters(self, keep_workflows: bool = False):
        source_version = (
            f"{self.source_version.cluster_type} "
            f"{self.source_version.major_version}.{self.source_version.minor_version}"
        )
        snapshot_config = self.argo_service.get_configmap_data("migrations-default-s3-config")
        image_config = self.argo_service.get_configmap_data("migration-image-config")
        migration_config = build_direct_migration_config(
            source_version=source_version,
            snapshot_name=self.snapshot_name,
            s3_repo_uri=self.s3_repo_uri,
            s3_region=self.s3_region,
            target_endpoint=self.target_endpoints[DIRECT_TARGET],
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
        response = cat_indices(cluster=self.target_cluster, refresh=True)
        logger.info("NEXTGEN VECTOR COLLECTION (direct)\n%s", response)

    def verify_clusters(self):
        for index in DIRECT_INDICES:
            self._assert_index_exists(DIRECT_TARGET, self.target_cluster, index)
            self._assert_doc_count_positive(DIRECT_TARGET, self.target_cluster, index)
            checks = index_checks(index)
            for path, expected in checks.get("mapping_assertions", {}).items():
                self._assert_mapping(DIRECT_TARGET, self.target_cluster, index, path, expected)
            for path in checks.get("settings_absent", []):
                self._assert_setting_absent(DIRECT_TARGET, self.target_cluster, index, path)
            for path, expected in checks.get("settings_present", {}).items():
                self._assert_setting_value(DIRECT_TARGET, self.target_cluster, index, path, expected)
        logger.info("Verified %d indices on the NextGen vector collection through its own endpoint",
                    len(DIRECT_INDICES))
