from upgrade_testing_framework.core.framework_step import FrameworkStep
from upgrade_testing_framework.core.constants import UPGRADE_STYLE_SNAPSHOT

class SnapshotRestoreTeardown(FrameworkStep):
    """
    This step tears down any state specifically associated with a snapshot/restore upgrade
    """

    def _run(self):
        # Get the state we need
        docker_client = self.state.docker_client
        snapshot_volume = self.state.shared_volume.volume
        upgrade_style = self.state.test_config.upgrade_def.style

        # Begin the step body
        if UPGRADE_STYLE_SNAPSHOT != upgrade_style:
            self.fail(f"Unsupported upgrade style - {upgrade_style}")

        self.logger.info(f"Removing shared Docker volume {snapshot_volume.name}...")
        docker_client.remove_volume(snapshot_volume)
        self.logger.info(f"Removed shared Docker volume {snapshot_volume.name}")

        # Update our state
        # N/A