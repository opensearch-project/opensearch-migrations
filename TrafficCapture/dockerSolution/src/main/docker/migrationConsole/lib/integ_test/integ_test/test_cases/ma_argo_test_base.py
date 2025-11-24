from enum import Enum
import logging
import json

from ..cluster_version import ClusterVersion, is_incoming_version_supported
from ..operations_library_factory import get_operations_library_by_version

from console_link.models.argo_service import ArgoService
from console_link.middleware.clusters import cat_indices, connection_check, clear_indices, ConnectionResult

logger = logging.getLogger(__name__)

MigrationType = Enum("MigrationType", ["METADATA", "BACKFILL", "CAPTURE_AND_REPLAY"])
OTEL_COLLECTOR_ENDPOINT = "http://otel-collector:4317"


class ClusterVersionCombinationUnsupported(Exception):
    def __init__(self, source_version, target_version, message="Cluster version combination is unsupported"):
        self.source_version = source_version
        self.target_version = target_version
        self.message = f"{message}: Source version '{source_version}' and Target version '{target_version}'"
        super().__init__(self.message)


class MATestUserArguments:
    def __init__(self, source_version: str, target_version: str, unique_id: str, reuse_clusters: bool):
        self.source_version = source_version
        self.target_version = target_version
        self.unique_id = unique_id
        self.reuse_clusters = reuse_clusters


class MATestBase:
    def __init__(self, user_args: MATestUserArguments, description: str, migrations_required=None,
                 allow_source_target_combinations=None):
        self.allow_source_target_combinations = allow_source_target_combinations or []
        self.description = description
        self.reuse_clusters = user_args.reuse_clusters
        self.migrations_required = migrations_required if migrations_required else [MigrationType.METADATA,
                                                                                    MigrationType.BACKFILL]
        self.source_version = ClusterVersion(version_str=user_args.source_version)
        self.target_version = ClusterVersion(version_str=user_args.target_version)
        self.argo_service = ArgoService()
        self.workflow_name = None
        self.source_cluster = None
        self.target_cluster = None
        self.imported_clusters = False

        supported_combo = False
        for (allowed_source, allowed_target) in allow_source_target_combinations:
            if (is_incoming_version_supported(allowed_source, self.source_version) and
                    is_incoming_version_supported(allowed_target, self.target_version)):
                supported_combo = True
                break
        if not supported_combo:
            raise ClusterVersionCombinationUnsupported(self.source_version, self.target_version)

        self.source_argo_cluster_template = (f"{self.source_version.full_cluster_type}-"
                                             f"{self.source_version.major_version}-"
                                             f"{self.source_version.minor_version}-single-node")
        self.target_argo_cluster_template = (f"{self.target_version.full_cluster_type}-"
                                             f"{self.target_version.major_version}-"
                                             f"{self.target_version.minor_version}-single-node")

        self.parameters = {}
        self.workflow_template = "full-migration-with-clusters"
        self.workflow_snapshot_and_migration_config = None
        self.source_operations = get_operations_library_by_version(self.source_version)
        self.target_operations = get_operations_library_by_version(self.target_version)
        self.unique_id = user_args.unique_id

    def __repr__(self):
        return f"<{self.__class__.__name__}(source={self.source_version},target={self.target_version})>"

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
        snapshot_and_migration_configs = [{
            "migrations": [{
                "metadata": {
                    "from_snapshot": None,
                    "otel_endpoint": OTEL_COLLECTOR_ENDPOINT
                },
                "documentBackfillConfigs": [{
                    "test": None
                }]
            }]
        }]
        self.workflow_snapshot_and_migration_config = snapshot_and_migration_configs

    def prepare_workflow_parameters(self):
        # For existing clusters
        if self.imported_clusters:
            self.workflow_template = "full-migration"
            source_migration_configs = [
                {
                    "source": self.source_cluster.config,
                    "snapshot-and-migration-configs": self.workflow_snapshot_and_migration_config
                }
            ]
            self.parameters["source-migration-configs"] = source_migration_configs
            self.parameters["targets"] = json.dumps([self.target_cluster.config], separators=(',', ':'))
        else:
            self.parameters["snapshot-and-migration-configs"] = self.workflow_snapshot_and_migration_config
            self.parameters["source-cluster-template"] = self.source_argo_cluster_template
            self.parameters["target-cluster-template"] = self.target_argo_cluster_template
            self.parameters["skip-cleanup"] = "true" if self.reuse_clusters else "false"

    def workflow_start(self):
        start_result = self.argo_service.start_workflow(workflow_template_name=self.workflow_template,
                                                        parameters=self.parameters)
        assert start_result.success is True
        self.workflow_name = start_result.value

    def workflow_setup_clusters(self):
        if not self.workflow_name:
            raise ValueError("Workflow name is not available, workflow may not have been started")
        if not self.imported_clusters:
            self.argo_service.wait_for_suspend(workflow_name=self.workflow_name, timeout_seconds=300)
            self.source_cluster = self.argo_service.get_source_cluster_from_workflow(workflow_name=self.workflow_name)
            self.target_cluster = self.argo_service.get_target_cluster_from_workflow(workflow_name=self.workflow_name)

    def prepare_clusters(self):
        pass

    def workflow_perform_migrations(self, timeout_seconds: int = 240):
        if not self.workflow_name:
            raise ValueError("Workflow name is not available, workflow may not have been started")
        if self.imported_clusters:
            self.argo_service.wait_for_ending_phase(workflow_name=self.workflow_name, timeout_seconds=timeout_seconds)
        else:
            self.argo_service.resume_workflow(workflow_name=self.workflow_name)
            self.argo_service.wait_for_suspend(workflow_name=self.workflow_name, timeout_seconds=timeout_seconds)

    def display_final_cluster_state(self):
        source_response = cat_indices(cluster=self.source_cluster).decode("utf-8")
        target_response = cat_indices(cluster=self.target_cluster).decode("utf-8")
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
        if not self.imported_clusters:
            self.argo_service.resume_workflow(workflow_name=self.workflow_name)
            self.argo_service.wait_for_ending_phase(workflow_name=self.workflow_name)

    def test_after(self):
        status_result = self.argo_service.get_workflow_status(workflow_name=self.workflow_name)
        phase = status_result.value.get("phase", "")
        assert phase == "Succeeded"
