from enum import Enum

import requests

from ..common_utils import wait_for_service_status
from ..cluster_version import ClusterVersion, is_incoming_version_supported
from ..operations_library_factory import get_operations_library_by_version

from console_link.models.backfill_base import Backfill, BackfillStatus
from console_link.environment import Environment
from console_link.models.replayer_base import Replayer, ReplayStatus
from console_link.models.command_result import CommandResult
from console_link.models.snapshot import Snapshot
from console_link.models.metadata import Metadata

MigrationType = Enum("MigrationType", ["METADATA", "BACKFILL", "CAPTURE_AND_REPLAY"])


class ClusterVersionCombinationUnsupported(Exception):
    def __init__(self, source_version, target_version, message="Cluster version combination is unsupported"):
        self.source_version = source_version
        self.target_version = target_version
        self.message = f"{message}: Source version '{source_version}' and Target version '{target_version}'"
        super().__init__(self.message)


def check_ma_system_health():
    print("check_ma_system_health")
    resp = requests.get("http://127.0.0.1:80/api/system/health")
    assert resp.status_code == 200
    data = resp.json()
    assert data["status"] == "ok"
    assert "checks" in data
    assert all(val == "ok" for val in data["checks"].values())
    print("check_ma_system_health complete")


class MATestBase:
    def __init__(self, console_config_path: str, console_link_env: Environment, unique_id: str, description: str,
                 migrations_required=[MigrationType.METADATA, MigrationType.BACKFILL, MigrationType.CAPTURE_AND_REPLAY],
                 allow_source_target_combinations=None, run_isolated=False):
        self.allow_source_target_combinations = allow_source_target_combinations or []
        self.run_isolated = run_isolated
        self.description = description
        self.console_link_env = console_link_env
        self.migrations_required = migrations_required
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
        check_ma_system_health()

    def __repr__(self):
        return f"<{self.__class__.__name__}(source={self.source_version},target={self.target_version})>"

    def test_before(self):
        pass

    def snapshot_before(self):
        pass

    def snapshot_create(self):
        if any(migration in self.migrations_required for migration in (MigrationType.METADATA, MigrationType.BACKFILL)):
            snapshot_result: CommandResult = self.snapshot.create(wait=True)
            assert snapshot_result.success

    def snapshot_after(self):
        pass

    def metadata_before(self):
        pass

    def metadata_migrate(self):
        if MigrationType.METADATA in self.migrations_required:
            metadata_result: CommandResult = self.metadata.migrate()
            assert metadata_result.success

    def metadata_after(self):
        pass

    def backfill_before(self):
        pass

    def backfill_start(self):
        if MigrationType.BACKFILL in self.migrations_required:
            # Flip this bool to only use one worker otherwise use the default worker count (5), useful for debugging
            single_worker_mode = False
            if not single_worker_mode:
                backfill_start_result: CommandResult = self.backfill.start()
                assert backfill_start_result.success
            else:
                backfill_scale_result: CommandResult = self.backfill.scale(units=1)
                assert backfill_scale_result.success
            wait_for_service_status(status_func=lambda: self.backfill.get_status(),
                                    desired_status=BackfillStatus.RUNNING)

    def backfill_during(self):
        pass

    def backfill_wait_for_stop(self):
        if MigrationType.BACKFILL in self.migrations_required:
            backfill_stop_result: CommandResult = self.backfill.stop()
            assert backfill_stop_result.success
            wait_for_service_status(status_func=lambda: self.backfill.get_status(),
                                    desired_status=BackfillStatus.STOPPED)

    def backfill_after(self):
        pass

    def replay_before(self):
        pass

    def replay_start(self):
        if MigrationType.CAPTURE_AND_REPLAY in self.migrations_required:
            replayer_start_result = self.replayer.start()
            assert replayer_start_result.success
            wait_for_service_status(status_func=lambda: self.replayer.get_status(), desired_status=ReplayStatus.RUNNING)

    def replay_during(self):
        pass

    def replay_wait_for_stop(self):
        if MigrationType.CAPTURE_AND_REPLAY in self.migrations_required:
            replayer_stop_result = self.replayer.stop()
            assert replayer_stop_result.success
            wait_for_service_status(status_func=lambda: self.replayer.get_status(), desired_status=ReplayStatus.STOPPED)

    def replay_after(self):
        pass

    def test_after(self):
        pass
