from enum import Enum
import logging
import json

#from ..common_utils import check_ma_system_health
from ..cluster_version import ClusterVersion, is_incoming_version_supported
from ..operations_library_factory import get_operations_library_by_version

from console_link.models.argo_service import ArgoService
from console_link.middleware.clusters import cat_indices

logger = logging.getLogger(__name__)

MigrationType = Enum("MigrationType", ["METADATA", "BACKFILL", "CAPTURE_AND_REPLAY"])

ARGO_CLUSTER_TEMPLATES = [
    "elasticsearch-5-6-single-node",
    "opensearch-2-19-single-node"
]


class ClusterVersionCombinationUnsupported(Exception):
    def __init__(self, source_version, target_version, message="Cluster version combination is unsupported"):
        self.source_version = source_version
        self.target_version = target_version
        self.message = f"{message}: Source version '{source_version}' and Target version '{target_version}'"
        super().__init__(self.message)


class MATestBase:
    def __init__(self, source_version: str, target_version: str, unique_id: str, description: str,
                 migrations_required=None, allow_source_target_combinations=None):
        self.allow_source_target_combinations = allow_source_target_combinations or []
        self.description = description
        self.migrations_required = migrations_required if migrations_required else [MigrationType.METADATA,
                                                                                    MigrationType.BACKFILL]
        self.source_version = ClusterVersion(version_str=source_version)
        self.target_version = ClusterVersion(version_str=target_version)
        self.argo_service = ArgoService()
        self.workflow_name = None
        self.source_cluster = None
        self.target_cluster = None

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
        if self.source_argo_cluster_template not in ARGO_CLUSTER_TEMPLATES:
            raise ValueError(f"The provided source version: {source_version} does not match any of the "
                             f"currently allowed Argo cluster templates: {ARGO_CLUSTER_TEMPLATES}")
        if self.target_argo_cluster_template not in ARGO_CLUSTER_TEMPLATES:
            raise ValueError(f"The provided target version: {target_version} does not match any of the "
                             f"currently allowed Argo cluster templates: {ARGO_CLUSTER_TEMPLATES}")

        self.parameters = {
            "source-cluster-template": self.source_argo_cluster_template,
            "target-cluster-template": self.target_argo_cluster_template
        }
        self.source_operations = get_operations_library_by_version(self.source_version)
        self.target_operations = get_operations_library_by_version(self.target_version)
        self.unique_id = unique_id

    def __repr__(self):
        return f"<{self.__class__.__name__}(source={self.source_version},target={self.target_version})>"

    def test_before(self):
        # TODO This should be enabled once the console API is enabled for this chart
        #check_ma_system_health()
        pass

    def prepare_workflow_parameters(self):
        snapshot_and_migration_configs = [{
            "migrations": [{
                "metadata": {
                    "from_snapshot": None
                },
                "documentBackfillConfigs": [{
                    "test": None
                }]
            }]
        }]
        snapshot_and_migration_configs_str = json.dumps(
            snapshot_and_migration_configs,
            separators=(',', ':')
        )
        self.parameters["snapshot-and-migration-configs"] = snapshot_and_migration_configs_str

    def workflow_start(self):
        start_result = self.argo_service.start_workflow(workflow_template_name="full-migration-with-clusters",
                                                        parameters=self.parameters)
        assert start_result.success is True
        self.workflow_name = start_result.value

    def workflow_setup_clusters(self):
        if not self.workflow_name:
            raise ValueError("Workflow name is not available, workflow may not have been started")
        self.argo_service.wait_for_suspend(workflow_name=self.workflow_name, timeout_seconds=180)
        self.source_cluster = self.argo_service.get_source_cluster_from_workflow(workflow_name=self.workflow_name)
        self.target_cluster = self.argo_service.get_target_cluster_from_workflow(workflow_name=self.workflow_name)

    def prepare_clusters(self):
        pass

    def workflow_perform_migrations(self, timeout_seconds: int = 180):
        if not self.workflow_name:
            raise ValueError("Workflow name is not available, workflow may not have been started")
        self.argo_service.resume_workflow(workflow_name=self.workflow_name)
        self.argo_service.wait_for_suspend(workflow_name=self.workflow_name, timeout_seconds=timeout_seconds)

    def display_final_cluster_state(self):
        source_response = cat_indices(cluster=self.source_cluster).decode("utf-8")
        target_response = cat_indices(cluster=self.target_cluster).decode("utf-8")
        print("SOURCE CLUSTER")
        print(source_response)
        print("TARGET CLUSTER")
        print(target_response)

    def verify_clusters(self):
        pass

    def workflow_finish(self):
        if not self.workflow_name:
            raise ValueError("Workflow name is not available, workflow may not have been started")
        self.argo_service.resume_workflow(workflow_name=self.workflow_name)
        self.argo_service.wait_for_ending_phase(workflow_name=self.workflow_name)

    def test_after(self):
        status_result = self.argo_service.get_workflow_status(workflow_name=self.workflow_name)
        phase = status_result.value.get("phase", "")
        assert phase == "Succeeded"
