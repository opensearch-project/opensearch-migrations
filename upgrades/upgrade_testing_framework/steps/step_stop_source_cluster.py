from typing import List

from docker.models.networks import Network
from docker.models.volumes import Volume

from upgrade_testing_framework.core.framework_step import FrameworkStep
import upgrade_testing_framework.cluster_management.docker_framework_client as dfc
from upgrade_testing_framework.cluster_management.node import Node

class StopSourceCluster(FrameworkStep):
    """
    This step tears down the source cluster and its resources.
    """

    def _run(self):
        # Get the state we need
        cluster = self.state.source_cluster

        # Begin the step body
        self.logger.info(f"Stopping cluster {cluster.name}...")
        cluster.stop()
        self.logger.info(f"Cleaning up underlying resources for cluster {cluster.name}...")
        cluster.clean_up()

        # Update our state
        self.state.source_cluster = None
        
        