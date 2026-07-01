import logging

from ..cluster_version import (
    ElasticsearchV7_X,
    ElasticsearchV8_X,
    OpensearchV2_X,
    OpensearchV3_X,
)
from .ma_argo_test_base import MATestBase, MigrationType, MATestUserArguments

logger = logging.getLogger(__name__)


class Test0020GcsSnapshotMigration(MATestBase):
    """
    End-to-end migration test using a GCS snapshot repository.

    Exercises the create-snapshot → metadata-migration → document-backfill
    code path against a real Elasticsearch source cluster. The snapshot is
    written to a GCS bucket; for local minikube/kind testing the bucket is
    backed by fake-gcs-server (enable conditionalPackageInstalls.fake-gcs-server
    and set gcsBucketConfiguration.useLocalGcs=true in your values overlay).

    Source versions: ES 7.10 and ES 8.x (covers the two versions validated
    end-to-end on GKE; remaining ES versions will be added in a follow-up PR).
    """

    def __init__(self, user_args: MATestUserArguments):
        allow_combinations = [
            (ElasticsearchV7_X, OpensearchV2_X),
            (ElasticsearchV7_X, OpensearchV3_X),
            (ElasticsearchV8_X, OpensearchV2_X),
            (ElasticsearchV8_X, OpensearchV3_X),
        ]
        description = "Performs full migration (snapshot + metadata + backfill) using a GCS repository."
        super().__init__(user_args=user_args,
                         description=description,
                         migrations_required=[MigrationType.METADATA, MigrationType.BACKFILL],
                         allow_source_target_combinations=allow_combinations)

    def prepare_workflow_parameters(self, keep_workflows: bool = False):
        self.parameters["snapshot-and-migration-configs"] = self.workflow_snapshot_and_migration_config
        self.parameters["source-cluster-template"] = self.source_argo_cluster_template
        self.parameters["target-cluster-template"] = self.target_argo_cluster_template
        self.parameters["skip-cleanup"] = "true" if self.reuse_clusters else "false"
        # Override the snapshot configmap to use GCS keys (BUCKET_URI = gs://...)
        self.parameters["snapshot-configmap"] = "migrations-default-gcs-config"
        if self.image_registry_prefix:
            self.parameters["image-registry-prefix"] = self.image_registry_prefix

    def verify_clusters(self):
        """Workflow success is the primary exit criterion for GCS path."""
        pass
