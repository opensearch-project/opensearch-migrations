"""Solr externally-managed-snapshot IMPORT integration test (Argo workflow pipeline).

Exercises the production import-prepare wiring: an externally-managed Solr snapshot whose
`importConfig` makes the workflow run CreateSnapshot --mode import — fetching the schema from the
live Solr source and uploading it into the snapshot's repo (zk_backup_0/configs/...) — before
metadata migration reads it. The live Solr source stays up because import requires it.

Kept in its own file (separate from the standard solr_tests.py backfill suite) because it is a
bring-your-own-snapshot scenario with its own selection/env-var contract.
"""
import logging
import os

from console_link.middleware.clusters import cat_indices

from ..cluster_version import SolrV8_X, SolrV9_X, OpensearchV2_X, OpensearchV3_X
from .ma_argo_test_base import MATestBase, MigrationType, MATestUserArguments

logger = logging.getLogger(__name__)

# The import path uploads the Solr schema into the snapshot, so it only applies to S3-capable Solr
# (8.10+ / 9.x). Solr 6/7 cannot back up to S3 and are excluded.
SOLR_IMPORT_ALLOW_COMBINATIONS = [
    (SolrV8_X, OpensearchV2_X),
    (SolrV8_X, OpensearchV3_X),
    (SolrV9_X, OpensearchV2_X),
    (SolrV9_X, OpensearchV3_X),
]


class TestSolr0070ExternalSnapshotImport(MATestBase):
    """Solr externally-managed snapshot IMPORT path, end-to-end through the deployed Argo workflow.

    The `importConfig` on the externally-managed snapshot branch is what triggers
    CreateSnapshot --mode import for the Solr source. That step fetches each collection/core's
    schema (managed-schema.xml) from the live Solr cluster and uploads it into the snapshot's
    zk_backup_0/configs/ layout, so the downstream metadata migration can derive OpenSearch
    mappings from a snapshot that did not originally carry the schema.

    Because referencing a pre-existing snapshot needs one staged in S3, this is a bring-your-own-
    snapshot test (requires_explicit_selection): point it at a Solr snapshot + repo via env vars,
    with the matching live Solr source endpoint so the import step can fetch the schema.

    Required environment variables:
    - SOLR_IMPORT_SNAPSHOT_NAME: name of the externally-managed Solr snapshot in S3
    - SOLR_IMPORT_S3_REPO_URI: S3 URI of the snapshot repo (e.g. s3://bucket/folder/)
    - SOLR_IMPORT_COLLECTION: collection/core to verify on the target after migration
    Optional:
    - SOLR_IMPORT_S3_REGION (default us-west-2), SOLR_IMPORT_S3_ENDPOINT
    - SOLR_IMPORT_POD_REPLICAS (default 1)
    """
    requires_explicit_selection = True

    def __init__(self, user_args: MATestUserArguments):
        super().__init__(
            user_args=user_args,
            description=("Solr externally-managed snapshot import: --mode import uploads the live "
                         "source's schema into a pre-existing snapshot, then metadata + backfill run."),
            migrations_required=[MigrationType.METADATA, MigrationType.BACKFILL],
            allow_source_target_combinations=SOLR_IMPORT_ALLOW_COMBINATIONS,
        )
        self._load_import_config()

    def _load_import_config(self):
        self.snapshot_name = os.environ['SOLR_IMPORT_SNAPSHOT_NAME']
        self.s3_repo_uri = os.environ['SOLR_IMPORT_S3_REPO_URI']
        self.verify_collection = os.environ['SOLR_IMPORT_COLLECTION']
        self.s3_region = os.environ.get('SOLR_IMPORT_S3_REGION', 'us-west-2')
        self.s3_endpoint = os.environ.get('SOLR_IMPORT_S3_ENDPOINT', '')
        self.pod_replicas = int(os.environ.get('SOLR_IMPORT_POD_REPLICAS', '1'))

    def prepare_workflow_snapshot_and_migration_config(self):
        # importConfig on the externally-managed branch triggers CreateSnapshot --mode import.
        self.workflow_snapshot_and_migration_config = [{
            "snapshotConfig": {
                "snapshotNameConfig": {
                    "externallyManagedSnapshotName": self.snapshot_name,
                    "importConfig": {}
                }
            },
            "migrations": [{
                "metadataMigrationConfig": {},
                "documentBackfillConfig": {"podReplicas": self.pod_replicas}
            }]
        }]

    def prepare_workflow_parameters(self, keep_workflows: bool = False):
        snapshot_repo = {"awsRegion": self.s3_region, "s3RepoPathUri": self.s3_repo_uri}
        if self.s3_endpoint:
            snapshot_repo["endpoint"] = self.s3_endpoint
        # Keep the live Solr source endpoint so the import step can fetch the schema from it.
        source_config = {
            "endpoint": self.source_cluster.endpoint if self.source_cluster else "",
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
        """Snapshot is externally staged; nothing to index here."""
        pass

    def display_final_cluster_state(self):
        target_response = cat_indices(cluster=self.target_cluster, refresh=True)
        logger.info("Target cluster indices after Solr import migration:")
        logger.info(target_response)

    def verify_clusters(self):
        # Workflow success (the import step uploaded the schema and metadata migration consumed it)
        # is the primary exit criteria; confirm the verify collection landed on the target.
        self.target_operations.get_index(
            cluster=self.target_cluster, index_name=self.verify_collection,
            max_attempts=20, delay=3.0)
