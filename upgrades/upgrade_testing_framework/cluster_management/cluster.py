import logging
import time
from typing import Dict, List

from docker.models.networks import Network
from docker.models.volumes import Volume

from upgrade_testing_framework.cluster_management.docker_framework_client import DockerFrameworkClient, PortMapping
from upgrade_testing_framework.cluster_management.node import ContainerConfiguration, Node, NodeConfiguration

class ClusterNotStartedInTimeException(Exception):
    def __init__(self):
        super().__init__(f"The Cluster did not start up within the specified time frame")

"""
The goal of this class's abstraction is to isolate the rest of the codebase from the details of precisely how we
are implementing clusters for our tests.  Ideally, the rest of the code should not know of things like where the
nodes are hosted (e.g. local Docker, other machines, etc).  The current implementation fell a bit short of that, but
we can iterate.

Additionally, Since the initial cut is focused on upgrades via the snapshot/restore mechanism, it feels like we can
assume homogenous clusters.  Along those lines, we will make a number of simplifying decisions to get an initial cut
out the door with the expectation that we will iterate on the design as requirements evolve.

As a result, this abstraction is probably "right for now", but "wrong" in the long run.  
"""
class Cluster:
    def __init__(self, name: str, num_starting_nodes: int, docker_client: DockerFrameworkClient, starting_image: str):
        self.logger = logging.getLogger(__name__)
        self.name = name
        self._docker_client = docker_client
        self._networks: List[Network] = []
        self._nodes: Dict[str, Node] = {}
        self._starting_image = starting_image
        
        if num_starting_nodes < 1:
            raise ValueError("Number of starting nodes should be greater than or equal to 1")
        self._num_starting_nodes = num_starting_nodes

        self._volumes: List[Volume] = []

    def _generate_network_name(self):
        return f"{self.name}-network"

    def _generate_node_name(self, number: int):
        return f"{self.name}-node-{number}"

    def _generate_volume_name(self, suffix: str):
        return f"{self.name}-volume-{suffix}"

    def start(self):
        # TODO: Handle cluster already started

        # Create network
        network = self._docker_client.create_network(self._generate_network_name())
        self._networks.append(network)

        # Create shared volume(s)
        # None for now

        # Create any shared values
        starting_master_nodes = [self._generate_node_name(node_num) for node_num in range(1, self._num_starting_nodes + 1)]
        starting_seed_hosts = [self._generate_node_name(node_num) for node_num in range(1, self._num_starting_nodes + 1)]

        for node_num in range(1, self._num_starting_nodes + 1):
            # Create the node's data volume
            node_volume = self._docker_client.create_volume(self._generate_volume_name(node_num))
            self._volumes.append(node_volume)

            # Create the node name, node config, container config
            node_name = self._generate_node_name(node_num)
            node_config = NodeConfiguration(node_name, self.name, starting_master_nodes, starting_seed_hosts)
            port_mappings = [
                PortMapping(f"920{node_num - 1}", "9200") # TODO: Breaks if more than 10 nodes (unlikely?)
            ]
            container_config = ContainerConfiguration(self._starting_image, network, port_mappings, self._volumes)

            # Instantiate node
            node = Node(node_name, container_config, node_config, self._docker_client)
            self._nodes[node_name] = node

            # Invoke start
            node.start()

    def wait_for_cluster_to_start_up(self, wait_limit_sec: int = None):
        wait_interval_sec = 3 # time between checks, arbitrarily chosen
        min_wait_time_sec = wait_interval_sec

        if wait_limit_sec != None and wait_limit_sec < min_wait_time_sec:
            raise ValueError("If you specify a wait time limit for cluster startup, it must be at least"
                f" {min_wait_time_sec} seconds")

        # Wait for the nodes in the cluster to be active; raise an exception if we exceed our time limit
        nodes_to_wait_for = [node_name for node_name in self._nodes.keys()]
        total_wait_time_sec = 0
        while nodes_to_wait_for:
            self.logger.debug(f"Waiting on the following nodes to be active: {nodes_to_wait_for}")
            for node_name in nodes_to_wait_for:
                if self._nodes[node_name].is_active():
                    self.logger.info(f"Node {node_name} is now active")
                    nodes_to_wait_for.remove(node_name)

            if not nodes_to_wait_for: # all nodes active
                break
            
            # TODO: Check resolution is wait_interval_sec, so doesn't entirely respect wait_limit_sec
            if total_wait_time_sec >= wait_limit_sec:
                raise ClusterNotStartedInTimeException()
            
            total_wait_time_sec += wait_interval_sec
            self.logger.debug(f"Sleeping {wait_interval_sec} seconds; total wait time will be: {total_wait_time_sec}")
            time.sleep(wait_interval_sec)

    def stop(self):
        # TODO: handle when the container is not running

        # call stop() on each of the Nodes
        for node in self._nodes.values():
            node.stop()

        # TODO: confirm all nodes are stopped(?)

    def clean_up(self):
        # TODO: handle when the cluster is not stopped

        # call clean_up() on each node
        for node in self._nodes.values():
            node.clean_up()

        # remove any volumes
        for volume in self._volumes:
            self._docker_client.remove_volume(volume)

        # remove network
        for network in self._networks:
            self._docker_client.remove_network(network)
            

    
        

