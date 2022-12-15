import logging

from docker.models.containers import Container

from upgrade_testing_framework.cluster_management.container_configuration import ContainerConfiguration
from upgrade_testing_framework.cluster_management.docker_framework_client import DockerFrameworkClient
from upgrade_testing_framework.cluster_management.node_configuration import NodeConfiguration

class Node:
    """
    This class encapsulates an ElasticSearch/OpenSearch Node and its underlying process/container/etc.
    """

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

        # make sure the engine has permissions on the the volume mount points
        for volume in self._container_config.volumes:
            self._docker_client.set_ownership_of_directory(self._container, self._node_config.user, volume.mount_point)

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

    def get_logs(self):
        # TODO: handle when the container is not running
        return self._container.logs().decode("utf-8")
