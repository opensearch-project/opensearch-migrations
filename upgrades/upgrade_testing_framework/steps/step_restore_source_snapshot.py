import upgrade_testing_clients as clients
from upgrade_testing_framework.core.framework_step import FrameworkStep

class RestoreSourceSnapshot(FrameworkStep):
    """
    This step restores the source snapshot on the target cluster and confirms it worked.
    """

    def _run(self):
        # Get the state we need
        shared_volume = self.state.shared_volume
        snapshot = self.state.snapshot
        target_cluster = self.state.target_cluster

        # Begin the step body
        self.logger.info("Checking if source snapshots are visible on target...")
        node = target_cluster.nodes[0] # shouldn't matter which node we pick
        port = node.rest_port
        engine_version = node.engine_version
        rest_client = clients.get_rest_client(engine_version)

        rest_client.register_snapshot_dir(port, snapshot.repo_name, shared_volume.mount_point)
        response_all_snapshots = rest_client.get_snapshots_all(port, snapshot.repo_name)

        if snapshot.snapshot_id in response_all_snapshots.response_text:
            self.logger.info("Source snapshot visible to target cluster")
        else:
            self.fail("Snapshot restoration failed; source snapshots not visible to target cluster")

        self.logger.info("Restoring source snapshot onto target...")
        response_restore = rest_client.restore_snapshot(port, snapshot.repo_name, snapshot.snapshot_id)

        if response_restore.succeeded:
            self.logger.info("Snapshot restored successfully")
        else:
            self.fail("Snapshot restoration failed")
        
        # Update our state
        # N/A
        
