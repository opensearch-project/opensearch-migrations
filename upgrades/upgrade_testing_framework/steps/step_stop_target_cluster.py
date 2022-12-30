from upgrade_testing_framework.core.framework_step import FrameworkStep


class StopTargetCluster(FrameworkStep):
    """
    This step tears down the target cluster and its resources.
    """

    def _run(self):
        # Get the state we need
        cluster = self.state.target_cluster

        # Begin the step body
        self.logger.info(f"Stopping cluster {cluster.name}...")
        cluster.stop()
        self.logger.info(f"Cleaning up underlying resources for cluster {cluster.name}...")
        cluster.clean_up()

        # Update our state
        # N/A
