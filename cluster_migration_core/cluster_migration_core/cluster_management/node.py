import logging

from docker.models.containers import Container

from cluster_migration_core.cluster_management.container_configuration import ContainerConfiguration
from cluster_migration_core.cluster_management.docker_framework_client import DockerFrameworkClient
from cluster_migration_core.cluster_management.node_configuration import NodeConfiguration

from cluster_migration_core.core.versions_engine import EngineVersion

STATE_NOT_STARTED = "NOT_STARTED"
STATE_RUNNING = "RUNNING"
STATE_STOPPED = "STOPPED"
STATE_CLEANED = "CLEANED_UP"


class NodeNotRunningException(Exception):
    def __init__(self):
        super().__init__("The node is not currently running")


class NodeNotStoppedException(Exception):
    def __init__(self):
        super().__init__("The node is not stopped")


class NodeRestartNotAllowedException(Exception):
    def __init__(self):
        super().__init__("Restarting stopped nodes is not yet allowed")


class Node:
    """
    This class encapsulates an ElasticSearch/OpenSearch Node and its underlying process/container/etc.
    """

    def __init__(self, name: str, container_config: ContainerConfiguration, node_config: NodeConfiguration,
                 docker_client: DockerFrameworkClient, container: Container = None):
        self.logger = logging.getLogger(__name__)
        self.name = name
        self._container = container
        self._container_config = container_config
        self._docker_client = docker_client
        self._node_config = node_config

        self._node_state = STATE_NOT_STARTED

    @property
    def engine_version(self) -> EngineVersion:
        return self._node_config.engine_version

    @property
    def rest_port(self) -> int:
        return self._container_config.rest_port

    def to_dict(self) -> dict:
        return {
            "container": self._container.attrs if self._container else None,
            "container_config": self._container_config.to_dict(),
            "name": self.name,
            "node_config": self._node_config.to_dict(),
            "node_state": self._node_state,
        }

    def start(self):
        if self._node_state == STATE_RUNNING:
            self.logger.debug(f"Node {self.name} is already running")
            return  # no-op

        if self._node_state in [STATE_STOPPED, STATE_CLEANED]:
            raise NodeRestartNotAllowedException()

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

        self._node_state = STATE_RUNNING

    def stop(self):
        if self._node_state != STATE_RUNNING:
            self.logger.debug(f"Node {self.name} is not running")
            return  # no-op

        self.logger.debug(f"Stopping node {self.name}...")
        self._docker_client.stop_container(self._container)
        self.logger.debug(f"Node {self.name} has been stopped")

        self._node_state = STATE_STOPPED

    def clean_up(self):
        if self._node_state != STATE_STOPPED:
            self.logger.debug(f"Node {self.name} is not stopped")
            raise NodeNotStoppedException()

        self.logger.debug(f"Removing underlying resources of node {self.name}...")

        # delete container and update container reference
        self.logger.debug(f"Deleting container {self._container.name}...")
        self._docker_client.remove_container(self._container)
        self.logger.debug(f"Container {self._container.name} has been deleted")
        self._container = None

        self._node_state = STATE_CLEANED

    def is_active(self) -> bool:
        if self._node_state != STATE_RUNNING:
            self.logger.debug(f"Node {self.name} is not running")
            return False

        self.logger.debug(f"Checking if node {self.name} is active...")
        if self._container is None:
            return False

        exit_code, output = self._docker_client.run_command(self._container, "curl -X GET \"localhost:9200/\"")
        self.logger.debug(f"Exit Code: {exit_code}, Output: {output}")
        return exit_code == 0

    def get_logs(self) -> str:
        if self._node_state != STATE_RUNNING:
            self.logger.debug(f"Node {self.name} is not running")
            raise NodeNotRunningException()

        return self._container.logs().decode("utf-8")
