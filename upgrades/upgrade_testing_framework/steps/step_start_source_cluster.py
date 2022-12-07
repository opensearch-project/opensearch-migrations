from upgrade_testing_framework.core.framework_step import FrameworkStep
import upgrade_testing_framework.cluster_management.docker_framework_client as dfc
from upgrade_testing_framework.cluster_management.node import ContainerConfiguration, NodeConfiguration, Node

import time

class StartSourceCluster(FrameworkStep):
    """
    This step starts the source cluster and ensures it is healthy
    """

    def _run(self):
        # Get the state we need
        source_docker_image = self._get_state_value("test_config")["source_docker_image"]
        docker_client = self.state.docker_client

        # Begin the step body
        network = docker_client.create_network("cluster-net")
        volume1 = docker_client.create_volume("cluster-data1")
        node_config = NodeConfiguration("node-1", "cluster-1", ["node-1"], ["node-1"])
        container_config = ContainerConfiguration(source_docker_image, network, {"9200": "9200"}, [volume1])
        node = Node("node-1", container_config, node_config, docker_client)
        node.start()

        total_wait_time_sec = 0
        while True:
            self.logger.info(f"Check if Node {node.name} is active...")
            if node.is_active():
                self.logger.info(f"Node {node.name} is active")
                break
            else:
                self.logger.info(f"Node {node.name} is not yet active, waiting...")
                time.sleep(2)
                total_wait_time_sec += 2

            if total_wait_time_sec > 10:
                self.logger.info(f"Max wait time reached, aborting")
                break
        
        # Update our state
        self.state.networks = [network]
        self.state.node = node
        self.state.volumes = [volume1]
        