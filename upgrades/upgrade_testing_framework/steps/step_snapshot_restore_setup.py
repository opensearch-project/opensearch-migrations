from upgrade_testing_framework.cluster_management.docker_framework_client import DockerVolume
from upgrade_testing_framework.core.framework_step import FrameworkStep
from upgrade_testing_framework.core.constants import UPGRADE_STYLE_SNAPSHOT


class SnapshotRestoreSetup(FrameworkStep):
    """
    Setup work for a snapshot/restore upgrade
    """

    def _run(self):
        # Get the state we need
        docker_client = self.state.docker_client
        upgrade_style = self.state.test_config.upgrade_def.style

        # Begin the step body
        if UPGRADE_STYLE_SNAPSHOT != upgrade_style:
            self.fail(f"Unsupported upgrade style - {upgrade_style}")

        self.logger.info("Creating shared Docker volume to share snapshots...")
        snapshot_volume = docker_client.create_volume("cluster-snapshots-volume")
        snapshot_mount_point = "/snapshots"
        self.logger.info(f"Created shared Docker volume {snapshot_volume.name}")

        # Update our state
        self.state.shared_volume = DockerVolume(snapshot_mount_point, snapshot_volume)
