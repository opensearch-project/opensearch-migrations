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
        docker_client: dfc.DockerFrameworkClient = self.state.docker_client
        networks: List[Network] = self.state.networks
        node: Node = self.state.node
        volumes: List[Volume] = self.state.volumes

        # Begin the step body
        node.stop()
        node.clean_up()

        for network in networks:
            docker_client.remove_network(network)

        for volume in volumes:
            docker_client.remove_volume(volume)

        # Update our state
        self.state.networks = []
        self.state.volumes = []
        
        