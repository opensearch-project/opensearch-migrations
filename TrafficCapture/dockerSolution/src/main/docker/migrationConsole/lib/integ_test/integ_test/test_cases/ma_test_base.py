from ..common_utils import wait_for_running_replayer
from ..cluster_version import ClusterVersion, is_incoming_version_supported
from ..operations_library_factory import get_operations_library_by_version

from console_link.models.backfill_base import Backfill
from console_link.environment import Environment
from console_link.models.replayer_base import Replayer
from console_link.models.command_result import CommandResult
from console_link.models.snapshot import Snapshot
from console_link.models.metadata import Metadata


class ClusterVersionCombinationUnsupported(Exception):
    def __init__(self, source_version, target_version, message="Cluster version combination is unsupported"):
        self.source_version = source_version
        self.target_version = target_version
        self.message = f"{message}: Source version '{source_version}' and Target version '{target_version}'"
        super().__init__(self.message)


class MATestBase:
    def __init__(self, console_config_path: str, console_link_env: Environment, unique_id: str,
                 allow_source_target_combinations=None, run_isolated=False, short_description="MA base test case"):
        self.allow_source_target_combinations = allow_source_target_combinations or []
        self.run_isolated = run_isolated
        self.short_description = short_description
        self.console_link_env = console_link_env
        if ((not console_link_env.source_cluster or not console_link_env.target_cluster) or
                (not console_link_env.source_cluster.version or not console_link_env.target_cluster.version)):
            raise RuntimeError("Both a source cluster and target cluster must be defined for the console library and "
                               "include the version field")
        self.source_cluster = console_link_env.source_cluster
        self.target_cluster = console_link_env.target_cluster
        self.source_version = ClusterVersion(version_str=self.source_cluster.version)
        self.target_version = ClusterVersion(version_str=self.target_cluster.version)

        supported_combo = False
        for (allowed_source, allowed_target) in allow_source_target_combinations:
            if (is_incoming_version_supported(allowed_source, self.source_version) and
                    is_incoming_version_supported(allowed_target, self.target_version)):
                supported_combo = True
                break
        if not supported_combo:
            raise ClusterVersionCombinationUnsupported(self.source_version, self.target_version)

        self.source_operations = get_operations_library_by_version(self.source_version)
        self.target_operations = get_operations_library_by_version(self.target_version)
        self.console_config_path = console_config_path
        self.unique_id = unique_id
        self.snapshot: Snapshot = console_link_env.snapshot
        self.backfill: Backfill = console_link_env.backfill
        self.metadata: Metadata = console_link_env.metadata
        self.replayer: Replayer = console_link_env.replay

    def __repr__(self):
        return f"<{self.__class__.__name__}(source={self.source_version},target={self.target_version})>"

    def perform_initial_operations(self):
        pass

    def perform_snapshot_create(self):
        snapshot_result: CommandResult = self.snapshot.create(wait=True)
        assert snapshot_result.success

    def perform_operations_after_snapshot(self):
        pass

    def perform_metadata_migration(self):
        metadata_result: CommandResult = self.metadata.migrate()
        assert metadata_result.success

    def perform_operations_after_metadata_migration(self):
        pass

    def start_backfill_migration(self):
        backfill_start_result: CommandResult = self.backfill.start()
        assert backfill_start_result.success
        # small enough to allow containers to be reused, big enough to test scaling out
        backfill_scale_result: CommandResult = self.backfill.scale(units=2)
        assert backfill_scale_result.success

    def perform_operations_during_backfill_migration(self):
        pass

    def stop_backfill_migration(self):
        backfill_stop_result: CommandResult = self.backfill.stop()
        assert backfill_stop_result.success

    def perform_operations_after_backfill_migration(self):
        pass

    def start_live_capture_migration(self):
        replayer_start_result = self.replayer.start()
        assert replayer_start_result.success
        wait_for_running_replayer(replayer=self.replayer)

    def perform_operations_during_live_capture_migration(self):
        pass

    def stop_live_capture_migration(self):
        replayer_stop_result = self.replayer.stop()
        assert replayer_stop_result.success

    def perform_operations_after_live_capture_migration(self):
        pass

    def perform_final_operations(self):
        pass
