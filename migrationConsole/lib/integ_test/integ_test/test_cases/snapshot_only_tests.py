import logging
from ..cluster_version import ElasticsearchV6_X, OpensearchV2_X, OpensearchV3_X
from .ma_argo_test_base import MATestBase, MigrationType, MATestUserArguments

logger = logging.getLogger(__name__)


class Test0010ExternalSnapshotMigration(MATestBase):
    """
    Test migration from an externally managed snapshot (no source cluster).
    Requires snapshot to already exist in S3/LocalStack.
    """
    def __init__(self, user_args: MATestUserArguments):
        allow_combinations = [
            (ElasticsearchV6_X, OpensearchV2_X),
            (ElasticsearchV6_X, OpensearchV3_X),
        ]
        description = "Performs migration from an existing S3 snapshot (no source cluster)."
        super().__init__(user_args=user_args,
                         description=description,
                         migrations_required=[MigrationType.METADATA, MigrationType.BACKFILL],
                         allow_source_target_combinations=allow_combinations)

        # Snapshot config for LocalStack testing
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
            # No createSnapshotConfig - skips snapshot creation
            # Repo location comes from source cluster's snapshotRepo
            "migrations": [{
                "metadataMigrationConfig": {},
                "documentBackfillConfig": {
                    "podReplicas": 1
                }
            }]
        }]

    def prepare_workflow_parameters(self):
        """Build parameters for snapshot-only migration using full-migration-imported-clusters."""
        source_config = {
            "endpoint": "",
            "version": f"{self.source_version.cluster_type} "
                       f"{self.source_version.major_version}.{self.source_version.minor_version}",
            "snapshotRepo": {
                "awsRegion": self.s3_region,
                "endpoint": self.s3_endpoint,
                "s3RepoPathUri": self.s3_repo_uri
            }
        }

        self.workflow_template = "full-migration-imported-clusters"
        self.imported_clusters = True  # Force imported clusters path
        source_configs = [{
            "source": source_config,
            "snapshot-and-migration-configs": self.workflow_snapshot_and_migration_config
        }]
        self.parameters["source-configs"] = source_configs
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
