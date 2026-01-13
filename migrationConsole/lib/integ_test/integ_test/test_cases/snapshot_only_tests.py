import logging
import os
from ..cluster_version import ElasticsearchV5_X, ElasticsearchV6_X, ElasticsearchV7_X, OpensearchV2_X, OpensearchV3_X
from .ma_argo_test_base import MATestBase, MigrationType, MATestUserArguments

logger = logging.getLogger(__name__)


class Test0010ExternalSnapshotMigration(MATestBase):
    """
    Test migration from an externally managed snapshot (no source cluster).
    Requires snapshot to already exist in S3/LocalStack.
    
    Snapshot configuration via environment variables (for EKS/Jenkins):
    - BYOS_SNAPSHOT_NAME: Name of the snapshot
    - BYOS_S3_REPO_URI: S3 URI to snapshot repository
    - BYOS_S3_REGION: AWS region for S3
    - BYOS_S3_ENDPOINT: Optional, for LocalStack (e.g., localstack://localstack:4566)
    
    If not set, defaults to LocalStack testing configuration.
    """
    def __init__(self, user_args: MATestUserArguments):
        allow_combinations = [
            (ElasticsearchV5_X, OpensearchV2_X),
            (ElasticsearchV5_X, OpensearchV3_X),
            (ElasticsearchV6_X, OpensearchV2_X),
            (ElasticsearchV6_X, OpensearchV3_X),
            (ElasticsearchV7_X, OpensearchV2_X),
            (ElasticsearchV7_X, OpensearchV3_X),
        ]
        description = "Performs migration from an existing S3 snapshot (no source cluster)."
        super().__init__(user_args=user_args,
                         description=description,
                         migrations_required=[MigrationType.METADATA, MigrationType.BACKFILL],
                         allow_source_target_combinations=allow_combinations)
        self._load_snapshot_config()

    def _load_snapshot_config(self):
        """Load snapshot configuration from environment variables or defaults."""
        if os.environ.get('BYOS_SNAPSHOT_NAME'):
            logger.info("Loading BYOS config from environment variables")
            self.snapshot_name = os.environ['BYOS_SNAPSHOT_NAME']
            self.s3_repo_uri = os.environ['BYOS_S3_REPO_URI']
            self.s3_region = os.environ.get('BYOS_S3_REGION', 'us-west-2')
            self.s3_endpoint = os.environ.get('BYOS_S3_ENDPOINT', '')
        else:
            logger.info("Using default LocalStack BYOS config")
            self.snapshot_name = "large-snapshot"
            self.s3_repo_uri = "s3://test-snapshots/large-snapshot-es6x"
            self.s3_endpoint = "localstack://localstack:4566"
            self.s3_region = "us-east-2"

    def import_existing_clusters(self):
        """Override - only import target cluster, no source needed."""
        if self.reuse_clusters:
            target_cluster = self.argo_service.get_cluster_from_configmap(
                f"target-{self.target_version.full_cluster_type}-"
                f"{self.target_version.major_version}-{self.target_version.minor_version}"
            )
            if target_cluster:
                self.imported_clusters = True
                self.target_cluster = target_cluster
                self.source_cluster = None

    def prepare_workflow_snapshot_and_migration_config(self):
        """Configure for external snapshot - use externallyManagedSnapshot to skip snapshot creation."""
        self.workflow_snapshot_and_migration_config = [{
            "snapshotConfig": {
                "snapshotNameConfig": {
                    "externallyManagedSnapshot": self.snapshot_name
                }
            },
            "migrations": [{
                "metadataMigrationConfig": {},
                "documentBackfillConfig": {"podReplicas": 1}
            }]
        }]

    def prepare_workflow_parameters(self):
        """Build parameters for snapshot-only migration using full-migration-imported-clusters."""
        snapshot_repo = {"awsRegion": self.s3_region, "s3RepoPathUri": self.s3_repo_uri}
        if self.s3_endpoint:
            snapshot_repo["endpoint"] = self.s3_endpoint

        source_config = {
            "endpoint": "",
            "version": f"{self.source_version.cluster_type} "
                       f"{self.source_version.major_version}.{self.source_version.minor_version}",
            "snapshotRepo": snapshot_repo
        }

        self.workflow_template = "full-migration-imported-clusters"
        self.imported_clusters = True
        self.parameters["source-configs"] = [{
            "source": source_config,
            "snapshot-and-migration-configs": self.workflow_snapshot_and_migration_config
        }]
        self.parameters["target-config"] = self.target_cluster.config

    def prepare_clusters(self):
        """No source cluster to prepare - snapshot already exists."""
        pass

    def display_final_cluster_state(self):
        """Only show target cluster (no source)."""
        from console_link.middleware.clusters import cat_indices
        target_response = cat_indices(cluster=self.target_cluster, refresh=True).decode("utf-8")
        logger.info("Target cluster indices after migration:")
        print("TARGET CLUSTER")
        print(target_response)

    def verify_clusters(self):
        """Verify expected indices exist on target."""
        self.target_operations.get_index(
            cluster=self.target_cluster,
            index_name="basic_index",
            max_attempts=5,
            delay=2.0
        )
