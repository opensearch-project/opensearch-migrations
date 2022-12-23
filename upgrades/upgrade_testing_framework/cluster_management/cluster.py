import logging
import time
from typing import Dict, List, NamedTuple

from docker.models.networks import Network
from docker.models.volumes import Volume

from upgrade_testing_framework.cluster_management.docker_framework_client import DockerFrameworkClient, DockerVolume, PortMapping
from upgrade_testing_framework.cluster_management.node import ContainerConfiguration, Node, NodeConfiguration
from upgrade_testing_framework.cluster_management.node_configuration import NodeConfiguration
from upgrade_testing_framework.core.test_config_wrangling import ClusterConfig
import upgrade_testing_clients.versions_engine as ev

class ClusterNotRunningException(Exception):
    def __init__(self):
        super().__init__(f"The cluster is not currently running")

class ClusterNotStartedInTimeException(Exception):
    def __init__(self):
        super().__init__(f"The cluster did not start up within the specified time frame")

class ClusterNotStoppedException(Exception):
    def __init__(self):
        super().__init__(f"The cluster is not currently stopped")

class ClusterRestartNotAllowedException(Exception):
    def __init__(self):
        super().__init__(f"Restarting stopped clusters is not yet allowed")

STATE_NOT_STARTED = "NOT_STARTED"
STATE_RUNNING = "RUNNING"
STATE_STOPPED = "STOPPED"
STATE_CLEANED = "CLEANED_UP"

"""
The goal of this class's abstraction is to isolate the rest of the codebase from the details of precisely how we
are implementing clusters for our tests.  Ideally, the rest of the code should not know of things like where the
nodes are hosted (e.g. local Docker, local installs, etc).  The current implementation fell a bit short of that, but
we can iterate.

Additionally, since the initial cut is focused on upgrades via the snapshot/restore mechanism, it feels like we can
assume homogenous clusters.  Along those lines, we will make a number of simplifying decisions to get an initial cut
out the door with the expectation that we will iterate on the design as requirements evolve.

As a result, this abstraction is probably "right for now", but "wrong" in the long run.  
"""
class Cluster:
    def __init__(self, name: str, cluster_config: ClusterConfig, docker_client: DockerFrameworkClient, 
            shared_volume: DockerVolume = None, starting_port: int = 9200):
        self.logger = logging.getLogger(__name__)
        self.name = name
        self._cluster_config = cluster_config
        self._docker_client = docker_client
        self._networks: List[Network] = []
        self._nodes: Dict[str, Node] = {}
        self._shared_volume = shared_volume
        self._starting_port = starting_port
        
        if self._cluster_config.node_count < 1:
            raise ValueError("Number of starting nodes should be greater than or equal to 1")

        self._volumes: List[Volume] = []

        self._cluster_state = STATE_NOT_STARTED

    @property
    def nodes(self) -> List[Node]:
        return list(self._nodes.values())

    @property
    def rest_ports(self) -> List[int]:
        return [node.rest_port for node in self._nodes.values()]

    def to_dict(self) -> dict:
        return {
            "cluster_config": self._cluster_config.to_dict(),
            "cluster_state": self._cluster_state,
            "name": self.name,
            "nodes": {node_name: node.to_dict() for node_name, node in self._nodes.items()},
        }

    def _generate_network_name(self) -> str:
        return f"{self.name}-network"

    def _generate_node_name(self, number: int) -> str:
        return f"{self.name}-node-{number}"

    def _generate_volume_name(self, suffix: str) -> str:
        return f"{self.name}-volume-{suffix}"

    def _generate_port_number(self, number: int) -> int:
        return self._starting_port + number

    def start(self):
        if self._cluster_state == STATE_RUNNING:
            self.logger.debug(f"Cluster {self.name} is already running")
            return # no-op

        if self._cluster_state in [STATE_STOPPED, STATE_CLEANED]:
            raise ClusterRestartNotAllowedException()

        # Create network
        network = self._docker_client.create_network(self._generate_network_name())
        self._networks.append(network)

        # Create any shared values
        starting_master_nodes = [self._generate_node_name(node_num) for node_num in range(1, self._cluster_config.node_count + 1)]
        starting_seed_hosts = [self._generate_node_name(node_num) for node_num in range(1, self._cluster_config.node_count + 1)]

        for node_num in range(1, self._cluster_config.node_count + 1):
            # Create the node configuration first, as other things depend on it
            engine_version = ev.get_version(self._cluster_config.engine_version)
            node_name = self._generate_node_name(node_num)
            node_env_config = self._cluster_config.additional_node_config
            if self._shared_volume is not None: # enables snapshot sharing between clusters
                node_env_config["path.repo"] = self._shared_volume.mount_point
            node_config = NodeConfiguration(engine_version, node_name, self.name, starting_master_nodes, starting_seed_hosts, node_env_config)

            # Create the node's data volume
            data_volume = self._docker_client.create_volume(self._generate_volume_name(node_num))
            self._volumes.append(data_volume) # track to clean up on teardown
            
            # Put together the list of volumes to mount in the container
            node_volumes = []            
            node_volumes.append(DockerVolume(node_config.data_dir, data_volume)) # make data persist after stop/start
            if self._shared_volume is not None:
                node_volumes.append(self._shared_volume) # enables snapshot sharing between clusters

            # Put together the list of ports to channel between the host and container
            port_mappings = [
                PortMapping(9200, self._generate_port_number(node_num - 1))
            ]

            # Assemble the container configuration based on the preceding work
            container_config = ContainerConfiguration(self._cluster_config.image, network, port_mappings, node_volumes)            

            # Instantiate node
            node = Node(node_name, container_config, node_config, self._docker_client)
            self._nodes[node_name] = node

            # Invoke start
            node.start()

        self._cluster_state = STATE_RUNNING

    def wait_for_cluster_to_start_up(self, max_wait_time_sec: int = None):
        if self._cluster_state != STATE_RUNNING:
            self.logger.debug(f"Cluster {self.name} is not running")
            raise ClusterNotRunningException()

        wait_interval_sec = 1 # time between checks, arbitrarily chosen
        min_wait_time_sec = wait_interval_sec

        if max_wait_time_sec != None and max_wait_time_sec < min_wait_time_sec:
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
            
            # TODO: Check resolution is wait_interval_sec, so might not respect wait_limit_sec if > 1
            if max_wait_time_sec is not None and total_wait_time_sec >= max_wait_time_sec:
                raise ClusterNotStartedInTimeException()
            
            total_wait_time_sec += wait_interval_sec
            self.logger.debug(f"Sleeping {wait_interval_sec} seconds; total wait time will be: {total_wait_time_sec}")
            time.sleep(wait_interval_sec)

    def stop(self):
        if self._cluster_state != STATE_RUNNING:
            self.logger.debug(f"Cluster {self.name} is not running")
            return # no-op
        
        self.logger.debug(f"Stopping cluster {self.name}...")

        # call stop() on each of the Nodes
        for node in self._nodes.values():
            node.stop()
        self.logger.debug(f"Stopped cluster {self.name}")

        self._cluster_state = STATE_STOPPED

    def clean_up(self):
        if self._cluster_state != STATE_STOPPED:
            self.logger.debug(f"Cluster {self.name} is not stopped")
            raise ClusterNotStoppedException()

        self.logger.debug(f"Cleaning up cluster {self.name}...")

        # call clean_up() on each node
        for node in self._nodes.values():
            node.clean_up()

        # remove any volumes
        for volume in self._volumes:
            self._docker_client.remove_volume(volume)
        self._volumes = []

        # remove network
        for network in self._networks:
            self._docker_client.remove_network(network)
        self._networks = []
        
        self.logger.debug(f"Cleaned up cluster {self.name}")

        self._cluster_state = STATE_CLEANED
