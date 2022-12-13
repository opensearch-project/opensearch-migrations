"""
This class encapsulates an ElasticSearch/OpenSearch Node and its underlying process/container/etc.
"""

import logging
from typing import Dict, List

from docker.models.containers import Container
from docker.models.networks import Network
from docker.models.volumes import Volume
from docker.types import Ulimit

from upgrade_testing_framework.cluster_management.docker_framework_client import DockerFrameworkClient, PortMapping

class NodeConfiguration:
    def __init__(self, node_name: str, cluster_name: str, master_nodes: List[str], seed_hosts: List[str], 
            additional_config: Dict[str, str] = {}):
        self.config = {
            # Core configuration
            "cluster.name": cluster_name,
            "cluster.initial_master_nodes": ",".join(master_nodes),
            "discovery.seed_hosts": ",".join(seed_hosts),
            "node.name": node_name,

            # Stuff we might change later
            "bootstrap.memory_lock": "true"
        }
        self.config.update(additional_config)

class ContainerConfiguration:
    def __init__(self, image: str, network: Network, port_mappings: List[PortMapping], volumes: List[Volume], 
            ulimits: List[Ulimit] = [Ulimit(name='memlock', soft=-1, hard=-1)]):
        self.image = image
        self.network = network
        self.port_mappings = port_mappings
        self.ulimits = ulimits
        self.volumes = volumes

        self.rest_port: int = None
        for host_port, container_port in self.port_mappings:
            if 9200 == container_port:
                self.rest_port = host_port        

class Node:
    def __init__(self, name: str, container_config: ContainerConfiguration, node_config: NodeConfiguration, 
            docker_client: DockerFrameworkClient, container: Container = None):
        self.logger = logging.getLogger(__name__)
        self.name = name
        self.rest_port = container_config.rest_port
        self._container = container
        self._container_config = container_config
        self._docker_client = docker_client
        self._node_config = node_config

    def start(self):
        # TODO: handle when the container is already running

        # run the container
        container = self._docker_client.create_container(
            self._container_config.image,
            self.name,
            self._container_config.network,
            self._container_config.port_mappings,
            self._container_config.volumes,
            self._container_config.ulimits,
            self._node_config.config
        )

        # store container reference
        self._container = container

    def stop(self):
        # TODO: handle when the container is not running

        self.logger.debug(f"Stopping node {self.name}...")
        self._docker_client.stop_container(self._container)
        self.logger.debug(f"Node {self.name} has been stopped")

    def clean_up(self):
        # TODO: handle when the container is not stopped

        self.logger.debug(f"Removing underlying resources of node {self.name}...")

        # delete container and update container reference
        self.logger.debug(f"Deleting container {self._container.name}...")
        self._docker_client.remove_container(self._container)
        self.logger.debug(f"Container {self._container.name} has been deleted")
        self._container = None

    def is_active(self):
        self.logger.debug(f"Checking if node {self.name} is active...")
        if self._container == None:
            return False
        
        exit_code, output = self._docker_client.run(self._container, "curl -X GET \"localhost:9200/\"")
        self.logger.debug(f"Exit Code: {exit_code}, Output: {output}")
        return exit_code == 0
