from typing import Dict, List

from docker.models.networks import Network
from docker.models.volumes import Volume

from upgrade_testing_framework.cluster_management.node import Node

"""
The goal of this class's abstraction is to isolate the rest of the codebase from the details of precisely how we
are implementing clusters for our tests.  Ideally, the rest of the code should not knowl of things like where the
nodes are hosted (e.g. local Docker, other machines, etc).  We'll see how possible that is in practice.

This abstraction is probably "right for now", but "wrong" in the long run.  Since the initial cut is focused on
upgrades via the snapshot/restore mechanism, it feels like we can assume homogenous clusters.  Along those lines,
we will make a number of simplifying decisions to get an initial cut out the door with the expectation that we will
iterate on the design as requirements evolve.
"""
class Cluster:
    def __init__(self, name: str, num_starting_nodes: int):
        self.name = name
        self._networks: List[Network] = []
        self._nodes: Dict[str, Node] = {}
        self._started = False
        
        if num_starting_nodes < 1:
            raise ValueError("Number of starting nodes should be greater than or equal to 1")
        self._num_starting_nodes = num_starting_nodes

        self._volumes: List[Volume] = []

    def _generate_network_name(self):
        return f"{self.name}-network"

    def _generate_node_name(self, number: int):
        return f"{self.name}-node-{number}"

    def _generate_volume_name(self, number: int):
        return f"{self.name}-volume-{number}"

    def start(self):
        self._started = True

        # Create network

        # Create any shared values
        starting_master_nodes = [self._generate_node_name(node_num) for node_num in range(1, self.starting_nodes)]
        starting_seed_hosts = [self._generate_node_name(node_num) for node_num in range(1, self.starting_nodes)]

        for node_num in range(1, self.starting_nodes):
            # Generate node name, node config, container config

            # Instantiate node

            # Invoke start
            pass

        # While nodes are not all running
            # Abort if waiting too long

            # Ask each node if it is ready

            # Remove ready nodes from list

            # Sleep
            

    
        

