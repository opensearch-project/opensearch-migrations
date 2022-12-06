"""
Class to track the state associated with ElasticSearch/OpenSearch Nodes.  Currently just a struct, as the
responsibility boundary for things like creation/deleting/interogation is unclear.
"""

from typing import List

from docker.models.containers import Container
from docker.models.networks import Network
from docker.models.volumes import Volume

from upgrade_testing_framework.cluster_management.docker_framework_client import DockerFrameworkClient

class NodeConfiguration:
    def __init__(self, cluster_name: str, master_nodes: List[str], seed_hosts: List[str]):
        self.config = {
            # Core configuration
            "cluster.name": cluster_name,
            "cluster.initial_master_nodes": ",".join(master_nodes),
            "discovery.seed_hosts": ",".join(seed_hosts),

            # Stuff we might change later
            "plugins.security.disabled": "true",
            "bootstrap.memory_lock": "true",

            # Stuff we'll absolutely change later
            "OPENSEARCH_JAVA_OPTS": "-Xms512m -Xmx512m", #TODO - tied to a specific engine version
        }

class ContainerConfiguration:
    def __init__(self, image: str, network: Network, port_mappings: dict, volumes: List[Volume]):
        self.image = image
        self.network = network
        self.port_mappings = port_mappings
        self.volumes = volumes

class Node:
    def __init__(self, name: str, container_config: ContainerConfiguration, node_config: NodeConfiguration, 
            docker_client: DockerFrameworkClient = DockerFrameworkClient()):
        self.name = name
        self._container: Container = None
        self._container_config = container_config
        self._node_config = node_config

    def start(self):
        # run the container

        # store container reference

        # return without waiting for success
        pass

    def stop(self):
        # stop the container

        # update container reference (None?)

        # return without waiting for success
        pass

    def is_active(self):
        # check if the Node is alive and part of the cluster
        pass