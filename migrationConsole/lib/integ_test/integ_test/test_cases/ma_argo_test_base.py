from enum import Enum
import logging
import subprocess

from ..cluster_version import ClusterVersion, is_incoming_version_supported
from ..operations_library_factory import get_operations_library_by_version

from console_link.middleware.clusters import cat_indices, connection_check, clear_indices, ConnectionResult

from ..integration_test_argo_service import IntegrationTestArgoService

logger = logging.getLogger(__name__)

# Maps wildcard ClusterVersion (e.g. ES_7.x) to the concrete template name that exists in clusterWorkflows.yaml.
# When minor_version is 'x', we pick the canonical representative minor version for that major.
_WILDCARD_TEMPLATE_MAP = {
    ("elasticsearch", 1): 5,
    ("elasticsearch", 2): 4,
    ("elasticsearch", 5): 6,
    ("elasticsearch", 6): 8,
    ("elasticsearch", 7): 10,
    ("elasticsearch", 8): 19,
    ("opensearch", 1): 3,
    ("opensearch", 2): 19,
    ("opensearch", 3): 1,
}


def get_template_name(version: ClusterVersion) -> str:
    minor = version.minor_version
    if minor == 'x':
        minor = _WILDCARD_TEMPLATE_MAP.get((version.full_cluster_type, version.major_version))
        if minor is None:
            raise ValueError(f"No template mapping for wildcard version {version}. "
                             f"Add an entry to _WILDCARD_TEMPLATE_MAP.")
    return f"{version.full_cluster_type}-{version.major_version}-{minor}-single-node"


MigrationType = Enum("MigrationType", ["METADATA", "BACKFILL", "CAPTURE_AND_REPLAY"])


class ClusterVersionCombinationUnsupported(Exception):
    def __init__(self, source_version, target_version, message="Cluster version combination is unsupported"):
        self.source_version = source_version
        self.target_version = target_version
        self.message = f"{message}: Source version '{source_version}' and Target version '{target_version}'"
        super().__init__(self.message)


class MATestUserArguments:
    def __init__(self, source_version: str, target_version: str, unique_id: str, reuse_clusters: bool,
                 target_type: str = "OS", image_registry_prefix: str = "",
                 speedup_factor: int = 20, observed_packet_timeout: int = 30):
        self.source_version = source_version
        self.target_version = target_version
        self.target_type = target_type
        self.unique_id = unique_id
        self.reuse_clusters = reuse_clusters
        self.image_registry_prefix = image_registry_prefix
        self.speedup_factor = speedup_factor
        self.observed_packet_timeout = observed_packet_timeout


class MATestBase:
    # Tests with requires_explicit_selection=True are excluded from default runs.
    # They only run when explicitly specified via --test_ids.
    requires_explicit_selection = False

    def __init__(self, user_args: MATestUserArguments, description: str, migrations_required=None,
                 allow_source_target_combinations=None):
        self.allow_source_target_combinations = allow_source_target_combinations or []
        self.description = description
        self.reuse_clusters = user_args.reuse_clusters
        self.migrations_required = migrations_required if migrations_required else [MigrationType.METADATA,
                                                                                    MigrationType.BACKFILL]
        self.source_version = ClusterVersion(version_str=user_args.source_version)
        self.target_type = user_args.target_type
        self.target_version = (
            None if self.is_aoss
            else ClusterVersion(version_str=user_args.target_version)
        )
        self.argo_service = IntegrationTestArgoService()
        self.workflow_name = None
        self.source_cluster = None
        self.target_cluster = None
        self.imported_clusters = False

        if not self.is_aoss:
            supported_combo = False
            for (allowed_source, allowed_target) in allow_source_target_combinations:
                if (is_incoming_version_supported(allowed_source, self.source_version) and
                        is_incoming_version_supported(allowed_target, self.target_version)):
                    supported_combo = True
                    break
            if not supported_combo:
                raise ClusterVersionCombinationUnsupported(self.source_version, self.target_version)

        self.source_argo_cluster_template = get_template_name(self.source_version)
        self.target_argo_cluster_template = None if self.is_aoss else get_template_name(self.target_version)

        self.parameters = {}
        self.image_registry_prefix = user_args.image_registry_prefix
        self.speedup_factor = user_args.speedup_factor
        self.observed_packet_timeout = user_args.observed_packet_timeout
        self.workflow_template = "full-migration-with-clusters"
        self.workflow_snapshot_and_migration_config = None
        self.source_operations = get_operations_library_by_version(self.source_version)
        self.target_operations = (
            None if self.is_aoss
            else get_operations_library_by_version(self.target_version)
        )
        self.unique_id = user_args.unique_id

    @property
    def is_aoss(self):
        return self.target_type == "AOSS"

    def __repr__(self):
        target_str = self.target_type if self.is_aoss else str(self.target_version)
        return f"<{self.__class__.__name__}(source={self.source_version},target={target_str})>"

    def test_before(self):
        #check_ma_system_health()
        pass

    def import_existing_clusters(self):
        if self.reuse_clusters:
            source_cluster = self.argo_service.get_cluster_from_configmap(f"source-"
                                                                          f"{self.source_version.full_cluster_type}-"
                                                                          f"{self.source_version.major_version}-"
                                                                          f"{self.source_version.minor_version}")
            target_cluster = self.argo_service.get_cluster_from_configmap(f"target-"
                                                                          f"{self.target_version.full_cluster_type}-"
                                                                          f"{self.target_version.major_version}-"
                                                                          f"{self.target_version.minor_version}")
            if not source_cluster or not target_cluster:
                logger.info("Unable to locate an existing source and/or target cluster. Proceeding with creating these "
                            "clusters as part of workflow, and skipping their deletion for reuse")
            else:
                self.imported_clusters = True
                self.source_cluster = source_cluster
                self.target_cluster = target_cluster
                source_con_result: ConnectionResult = connection_check(source_cluster)
                assert source_con_result.connection_established is True
                target_con_result: ConnectionResult = connection_check(target_cluster)
                assert target_con_result.connection_established is True
                clear_indices(source_cluster)
                clear_indices(target_cluster)
                self.target_operations.clear_index_templates(cluster=target_cluster)

    def prepare_workflow_snapshot_and_migration_config(self):
        """
        Prepare the snapshot and migration configuration.

        The structure follows the NORMALIZED_SNAPSHOT_MIGRATION_CONFIG schema:
        [{
            "snapshotConfig": { ... },  # Optional, added by workflow
            "createSnapshotConfig": { ... },  # Optional
            "migrations": [{
                "metadataMigrationConfig": { ... },  # Optional
                "documentBackfillConfig": { ... }  # Optional
            }]
        }]

        Subclasses can override this to provide custom configurations,
        especially for transformer configs.
        """
        snapshot_and_migration_configs = [{
            "migrations": [{
                "metadataMigrationConfig": {},
                "documentBackfillConfig": {
                    "maxShardSizeBytes": 16000000,
                    "resources": {
                        "requests": {"cpu": "25m", "memory": "1Gi", "ephemeral-storage": "5Gi"},
                        "limits": {"cpu": "1000m", "memory": "2Gi", "ephemeral-storage": "5Gi"}
                    }
                }
            }]
        }]
        self.workflow_snapshot_and_migration_config = snapshot_and_migration_configs

    def prepare_workflow_parameters(self, keep_workflows: bool = False):
        # For existing clusters
        if self.imported_clusters:
            self.workflow_template = "full-migration-imported-clusters"
            source_configs = [
                {
                    "source": self.source_cluster.config,
                    "snapshot-and-migration-configs": self.workflow_snapshot_and_migration_config
                }
            ]
            self.parameters["source-configs"] = source_configs
            self.parameters["target-config"] = self.target_cluster.config
            self.parameters["keepMigrationWorkflow"] = "true" if keep_workflows else "false"
            self.parameters["speedup-factor"] = str(self.speedup_factor)
            self.parameters["observed-packet-timeout"] = str(self.observed_packet_timeout)
        else:
            self.parameters["snapshot-and-migration-configs"] = self.workflow_snapshot_and_migration_config
            self.parameters["source-cluster-template"] = self.source_argo_cluster_template
            self.parameters["target-cluster-template"] = self.target_argo_cluster_template
            self.parameters["skip-cleanup"] = "true" if self.reuse_clusters else "false"
            if self.image_registry_prefix:
                self.parameters["image-registry-prefix"] = self.image_registry_prefix

    def _ensure_approval_configmap(self):
        """Ensure the approval configmap exists for Argo v4.0+ (requires configmap-type label).
        In production, the config-processor creates this. In tests, we create a default."""
        kubectl_cmd = [
            "kubectl", "apply", "-n", self.argo_service.namespace, "-f", "-"
        ]
        configmap_yaml = (
            'apiVersion: v1\n'
            'kind: ConfigMap\n'
            'metadata:\n'
            '  name: approval-config\n'
            '  labels:\n'
            '    workflows.argoproj.io/configmap-type: Parameter\n'
            'data:\n'
            '  autoApprove: "{}"\n'
        )
        subprocess.run(kubectl_cmd, input=configmap_yaml, text=True, check=True)

    def workflow_start(self):
        self._ensure_approval_configmap()
        start_result = self.argo_service.start_workflow(workflow_template_name=self.workflow_template,
                                                        parameters=self.parameters)
        assert start_result.success is True
        self.workflow_name = start_result.value

    def workflow_setup_clusters(self):
        if not self.workflow_name:
            raise ValueError("Workflow name is not available, workflow may not have been started")
        if not self.imported_clusters:
            self.argo_service.wait_for_suspend(workflow_name=self.workflow_name, timeout_seconds=1000)
            self.source_cluster = self.argo_service.get_cluster_config_from_workflow(
                workflow_name=self.workflow_name, cluster_type="source")
            self.target_cluster = self.argo_service.get_cluster_config_from_workflow(
                workflow_name=self.workflow_name, cluster_type="target")

    def prepare_clusters(self):
        pass

    def workflow_perform_migrations(self, timeout_seconds: int = 1000):
        if not self.workflow_name:
            raise ValueError("Workflow name is not available, workflow may not have been started")
        if self.imported_clusters:
            self.argo_service.wait_for_ending_phase(workflow_name=self.workflow_name, timeout_seconds=timeout_seconds)
        else:
            self.argo_service.resume_workflow(workflow_name=self.workflow_name)
            self.argo_service.wait_for_suspend(workflow_name=self.workflow_name, timeout_seconds=timeout_seconds)

    def post_migration_actions(self):
        """Hook for actions after migration completes but before verification.
        CDC tests override this to send traffic through the capture proxy."""
        pass

    def cleanup(self):
        """Hook for test-specific resource cleanup after teardown.
        CDC tests override this to delete Kafka, proxy, and replayer resources."""
        pass

    def display_final_cluster_state(self):
        source_response = cat_indices(cluster=self.source_cluster, refresh=True)
        target_response = cat_indices(cluster=self.target_cluster, refresh=True)
        logger.info("Printing document counts for source and target clusters:")
        print("SOURCE CLUSTER")
        print(source_response)
        print("TARGET CLUSTER")
        print(target_response)

    def verify_clusters(self):
        pass

    def workflow_finish(self):
        if not self.workflow_name:
            raise ValueError("Workflow name is not available, workflow may not have been started")
        # Two paths to a terminal phase:
        #
        # 1. k8s-local (imported_clusters=False): the workflow is waiting at its
        #    second suspend (pause-for-migration-verification). Resume past it,
        #    then wait for the ending phase.
        # 2. imported_clusters=True: workflow_perform_migrations already drove
        #    the functional steps. CDC tests' outer workflow runs to completion
        #    via monitorWorkflow polling the inner migration-workflow until
        #    Succeeded; non-CDC imported-clusters tests are typically already
        #    terminal here (so wait is a fast no-op). The kafka/proxy/replayer
        #    resources spawned by CDC tests keep running independently and are
        #    cleaned up by helm uninstall / workflow reset, not by the outer
        #    workflow's lifecycle.
        if not self.imported_clusters:
            self.argo_service.resume_workflow(workflow_name=self.workflow_name)
        self.argo_service.wait_for_ending_phase(
            workflow_name=self.workflow_name, timeout_seconds=300
        )

    def test_after(self):
        status_result = self.argo_service.get_workflow_status(workflow_name=self.workflow_name)
        phase = status_result.value.get("phase", "")
        assert phase == "Succeeded", f"Expected workflow phase 'Succeeded', got '{phase}'"
