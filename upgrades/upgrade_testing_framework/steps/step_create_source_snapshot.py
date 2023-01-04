import cluster_migration_core.clients as clients
from cluster_migration_core.cluster_management.cluster_objects import ClusterSnapshot
from cluster_migration_core.core.framework_step import FrameworkStep


class CreateSourceSnapshot(FrameworkStep):
    """
    This step creates the snapshot on the source cluster.
    """

    def _run(self):
        # Get the state we need
        shared_volume = self.state.shared_volume
        source_cluster = self.state.source_cluster

        # Begin the step body
        self.logger.info("Creating snapshot of source cluster...")
        node = source_cluster.nodes[0]  # shouldn't matter which node we pick
        port = node.rest_port
        engine_version = node.engine_version
        rest_client = clients.get_rest_client(engine_version)
        repo_name, snapshot_id = ("noldor_repo", "test_snapshot")

        rest_client.register_snapshot_dir(port, repo_name, shared_volume.mount_point)
        rest_client.create_snapshot(port, repo_name, snapshot_id)

        self.logger.info("Confirming snapshot of source cluster exists...")
        response_confirm = rest_client.get_snapshot_by_id(port, repo_name, snapshot_id)
        if response_confirm.succeeded:
            self.logger.info("Source snapshot created successfully")
        else:
            self.fail("Snapshot creation failed")

        # Update our state
        self.state.snapshot = ClusterSnapshot(repo_name, snapshot_id)
