import logging

from ..cluster_version import (
    ElasticsearchV7_X,
    ElasticsearchV8_X,
    OpensearchV2_X,
    OpensearchV3_X,
)
from .ma_argo_test_base import MATestBase, MigrationType, MATestUserArguments

logger = logging.getLogger(__name__)

AZURE_SNAPSHOT_CONFIG_MAP = "migrations-default-azure-config"
AZURE_CREDENTIALS_SECRET_NAME = "azurite-test-creds"


class Test0025AzureSnapshotMigration(MATestBase):
    """
    End-to-end migration test using an Azure Blob Storage snapshot repository.

    Exercises the create-snapshot → metadata-migration → document-backfill
    code path against a real Elasticsearch source cluster. The snapshot is
    written to an Azure container; for local kind testing the container is
    backed by Azurite (conditionalPackageInstalls.azurite in valuesForLocalK8s.yaml).

    Source versions: ES 7.10 and ES 8.x, matching Test0020.
    """

    def __init__(self, user_args: MATestUserArguments):
        allow_combinations = [
            (ElasticsearchV7_X, OpensearchV2_X),
            (ElasticsearchV7_X, OpensearchV3_X),
            (ElasticsearchV8_X, OpensearchV2_X),
            (ElasticsearchV8_X, OpensearchV3_X),
        ]
        description = "Performs full migration (snapshot + metadata + backfill) using an Azure repository."
        super().__init__(user_args=user_args,
                         description=description,
                         migrations_required=[MigrationType.METADATA, MigrationType.BACKFILL],
                         allow_source_target_combinations=allow_combinations)

    def prepare_workflow_snapshot_and_migration_config(self):
        super().prepare_workflow_snapshot_and_migration_config()
        if self.source_version.major_version == 7:
            # ES 7.10's repository-azure cannot verify a repository at Azurite's path-style URLs.
            self.workflow_snapshot_and_migration_config[0]["snapshotConfig"] = {
                "snapshotNameConfig": {"createSnapshotConfig": {"skipRepositoryVerification": True}}
            }

    def prepare_workflow_parameters(self, keep_workflows: bool = False):
        self.parameters["snapshot-and-migration-configs"] = self.workflow_snapshot_and_migration_config
        self.parameters["source-cluster-template"] = self.source_argo_cluster_template
        self.parameters["target-cluster-template"] = self.target_argo_cluster_template
        self.parameters["skip-cleanup"] = "true" if self.reuse_clusters else "false"
        self.parameters["snapshot-configmap"] = AZURE_SNAPSHOT_CONFIG_MAP
        self.parameters["azure-credentials-secret-name"] = AZURE_CREDENTIALS_SECRET_NAME
        if self.image_registry_prefix:
            self.parameters["image-registry-prefix"] = self.image_registry_prefix

    def verify_clusters(self):
        """Workflow success is the primary exit criterion for the Azure path."""
        pass
