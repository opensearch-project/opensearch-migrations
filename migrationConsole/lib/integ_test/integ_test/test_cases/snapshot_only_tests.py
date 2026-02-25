import logging
import os
from console_link.middleware.clusters import cat_indices
from ..cluster_version import (
    ElasticsearchV1_X,
    ElasticsearchV2_X,
    ElasticsearchV5_X,
    ElasticsearchV6_X,
    ElasticsearchV7_X,
    ElasticsearchV8_X,
    OpensearchV1_X,
    OpensearchV2_X,
    OpensearchV3_X,
)
from .ma_argo_test_base import MATestBase, MigrationType, MATestUserArguments

logger = logging.getLogger(__name__)


class Test0010ExternalSnapshotMigration(MATestBase):
    """
    Test migration from an externally managed snapshot (BYOS - Bring Your Own Snapshot).
    No source cluster is deployed - migration reads directly from an existing S3 snapshot.
    
    Required environment variables:
    - BYOS_SNAPSHOT_NAME: Name of the snapshot
    - BYOS_S3_REPO_URI: S3 URI to snapshot repository (e.g., s3://bucket/folder/)
    - BYOS_S3_REGION: AWS region for S3 (default: us-west-2)
    - BYOS_S3_ENDPOINT: S3 endpoint (optional, for custom endpoints)
    - BYOS_POD_REPLICAS: Number of RFS worker pods (default: 1)
    - BYOS_MONITOR_RETRY_LIMIT: Max retries for workflow monitoring (default: 900 ≈ 15 hours)
    """
    requires_explicit_selection = True

    def __init__(self, user_args: MATestUserArguments):
        allow_combinations = [
            (ElasticsearchV1_X, OpensearchV2_X),
            (ElasticsearchV1_X, OpensearchV3_X),
            (ElasticsearchV2_X, OpensearchV2_X),
            (ElasticsearchV2_X, OpensearchV3_X),
            (ElasticsearchV5_X, OpensearchV2_X),
            (ElasticsearchV5_X, OpensearchV3_X),
            (ElasticsearchV6_X, OpensearchV2_X),
            (ElasticsearchV6_X, OpensearchV3_X),
            (ElasticsearchV7_X, OpensearchV2_X),
            (ElasticsearchV7_X, OpensearchV3_X),
            (ElasticsearchV8_X, OpensearchV2_X),
            (ElasticsearchV8_X, OpensearchV3_X),
            (OpensearchV1_X, OpensearchV2_X),
            (OpensearchV1_X, OpensearchV3_X),
        ]
        description = "Performs migration from an existing S3 snapshot (no source cluster)."
        super().__init__(user_args=user_args,
                         description=description,
                         migrations_required=[MigrationType.METADATA, MigrationType.BACKFILL],
                         allow_source_target_combinations=allow_combinations)
        self._load_snapshot_config()

    def _load_snapshot_config(self):
        """Load snapshot configuration from environment variables."""
        self.snapshot_name = os.environ['BYOS_SNAPSHOT_NAME']
        self.s3_repo_uri = os.environ['BYOS_S3_REPO_URI']
        self.s3_region = os.environ.get('BYOS_S3_REGION', 'us-west-2')
        self.s3_endpoint = os.environ.get('BYOS_S3_ENDPOINT', '')
        self.pod_replicas = int(os.environ.get('BYOS_POD_REPLICAS', '1'))
        # Monitor retry limit: number of 60-second workflow monitor intervals (default 900 ≈ 15 hours)
        self.monitor_retry_limit = int(os.environ.get('BYOS_MONITOR_RETRY_LIMIT', '900'))

    def import_existing_clusters(self):
        """Import target cluster from configmap."""
        if self.reuse_clusters:
            configmap_prefix = (f"target-{self.target_version.full_cluster_type}-"
                                f"{self.target_version.major_version}-{self.target_version.minor_version}")
            logger.info(f"Looking for target cluster configmap with prefix: {configmap_prefix}")
            target_cluster = self.argo_service.get_cluster_from_configmap(configmap_prefix)
            if target_cluster:
                logger.info(f"Found target cluster: {target_cluster.endpoint}")
                self.imported_clusters = True
                self.target_cluster = target_cluster
                self.source_cluster = None
            else:
                raise ValueError(f"BYOS test requires existing target cluster. "
                                 f"No configmap found with prefix '{configmap_prefix}'.")

    def prepare_workflow_snapshot_and_migration_config(self):
        """Configure for external snapshot with template exclusion for invalid names."""
        self.workflow_snapshot_and_migration_config = [{
            "snapshotConfig": {
                "snapshotNameConfig": {
                    "externallyManagedSnapshot": self.snapshot_name
                }
            },
            "migrations": [{
                "metadataMigrationConfig": {},
                "documentBackfillConfig": {"podReplicas": self.pod_replicas}
            }]
        }]

    def prepare_workflow_parameters(self):
        """Build workflow parameters for snapshot-only migration."""
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
        self.parameters["monitor-retry-limit"] = str(self.monitor_retry_limit)

    def prepare_clusters(self):
        """No source cluster to prepare."""
        pass

    def workflow_perform_migrations(self, timeout_seconds: int = 50400):  # 14 hours for large snapshots
        super().workflow_perform_migrations(timeout_seconds=timeout_seconds)

    def display_final_cluster_state(self):
        """Display target cluster indices."""
        target_response = cat_indices(cluster=self.target_cluster, refresh=True).decode("utf-8")
        logger.info("Target cluster indices after migration:")
        logger.info("TARGET CLUSTER")
        logger.info(target_response)

    def verify_clusters(self):
        """Verify target cluster has indices with documents after migration."""
        target_response = cat_indices(cluster=self.target_cluster, refresh=True).decode("utf-8")
        raw_lines = target_response.strip().split('\n')
        user_index_lines = []
        for line in raw_lines:
            if not line.strip():
                continue
            parts = line.split()
            if len(parts) < 3:
                logger.debug("Skipping malformed cat_indices line: %r", line)
                continue
            if parts[2].startswith('.'):
                continue
            user_index_lines.append(parts)

        assert len(user_index_lines) > 0, "No user indices found on target cluster after migration"

        total_docs = 0
        for parts in user_index_lines:
            if len(parts) < 7:
                logger.debug("Skipping cat_indices line without doc count column: %r", " ".join(parts))
                continue
            try:
                total_docs += int(parts[6])
            except ValueError:
                logger.debug("Non-integer doc count %r in: %r", parts[6], " ".join(parts))
                continue

        assert total_docs > 0, f"Target cluster has {len(user_index_lines)} indices but 0 documents"
        logger.info(f"Verified: {len(user_index_lines)} indices with {total_docs} total documents on target")
