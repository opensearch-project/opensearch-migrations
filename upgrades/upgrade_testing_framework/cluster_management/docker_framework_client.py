from collections import namedtuple
import logging
from typing import Dict, List, Tuple

import docker.client
from docker.errors import DockerException, ImageNotFound
from docker.models.networks import Network
from docker.models.containers import Container
from docker.models.volumes import Volume
from docker.types import Ulimit

import upgrade_testing_framework.core.shell_interactions as shell

class DockerNotInPathException(Exception):
    def __init__(self):
        super().__init__(f"The 'docker' CLI is not in your system's PATH")

class DockerNotResponsiveException(Exception):
    def __init__(self, original_exception):
        self.original_exception = original_exception
        super().__init__(f"The Docker server on your system is not responsive")

class DockerImageUnavailableException(Exception):
    def __init__(self, image: str):
        super().__init__(f"The Docker {image} is not available either locally or in your remote repos")

PortMapping = namedtuple("PortMapping", "host_port container_port")

class DockerFrameworkClient:
    def _try_create_docker_client() -> docker.client.DockerClient:
        # First check to see if Docker is available in the user's PATH.  The Docker SDK doesn't provide a good
        # way to distinguish between this case and Docker just not running.
        exit_code, _ = shell.call_shell_command("which docker") # TODO not platform agnostic, and should wrap this
        if exit_code != 0:
            raise DockerNotInPathException

        # Now let's check to see if Docker is running and available
        try:
            docker_client = docker.client.from_env()
        except DockerException as exception:
            raise DockerNotResponsiveException(exception)

        return docker_client


    def __init__(self, logger=logging.getLogger(__name__), docker_client=None):
        self.logger = logger

        if docker_client == None:
            self._docker_client = DockerFrameworkClient._try_create_docker_client()
        else:
            self._docker_client = docker_client

    def _is_image_available_locally(self, image: str) -> bool:
        try:
            self._docker_client.images.get(image)
        except ImageNotFound:
            self.logger.debug(f"Image {image} not available locally")
            return False
        self.logger.debug(f"Image {image} is available locally")
        return True
    
    def ensure_image_available(self, image: str):
        """
        Check if the supplied image is available locally; try to pull it from remote repos if it isn't.
        """
        if not self._is_image_available_locally(image):
            self.logger.debug(f"Attempting to pull image from remote repositories...")
            try:
                self._docker_client.images.pull(image)
                self.logger.debug(f"Pulled image {image} successfully")
            except ImageNotFound:
                raise DockerImageUnavailableException(image)

    def create_network(self, name: str, driver="bridge") -> Network:
        self.logger.debug(f"Creating network {name}...")
        network = self._docker_client.networks.create(name, driver=driver)
        self.logger.debug(f"Created network {name} with ID {network.id}")
        return network

    def remove_network(self, network: Network):
        self.logger.debug(f"Removing network {network.name} with ID {network.id}...")
        network.remove()
        self.logger.debug(f"Removed network {network.name}")

    def create_volume(self, name: str) -> Volume:
        self.logger.debug(f"Creating volume {name}...")
        volume = self._docker_client.volumes.create(name)
        self.logger.debug(f"Created volume {name}")
        return volume

    def remove_volume(self, volume: Volume):
        self.logger.debug(f"Removing volume {volume.name}...")
        volume.remove()
        self.logger.debug(f"Removed volume {volume.name}")

    def create_container(self, image: str, container_name: str, network: Network, ports: List[PortMapping], volumes: List[Volume], ulimits: List[Ulimit],
                         env_variables: Dict[str, str]) -> Container:

        # TODO - need handle if container already exists (name collision)
        # TODO - need handle if we exceed the resource allocation for Docker
        # TODO - need handle port contention

        # It doesn't appear you can just pass in a list of Volumes to the client, so we have to make this wonky mapping
        port_mapping = {pair.container_port: pair.host_port for pair in ports}
        volume_mapping = {volume.attrs["Name"]: {"bind": volume.attrs["Mountpoint"], "mode": "rw"} for volume in volumes}
        
        self.logger.debug(f"Creating container named {container_name}...")
        container = self._docker_client.containers.run(
            image,
            name=container_name,
            network=network.name,
            ports=port_mapping,
            volumes=volume_mapping,
            ulimits=ulimits,
            detach=True,
            environment=env_variables
        )
        self.logger.debug("Created container")
        return container

    def stop_container(self, container: Container):
        self.logger.debug(f"Stopping container {container.name}...")
        container.stop()
        self.logger.debug(f"Stopped container {container.name}")

    def remove_container(self, container: Container):
        # TODO - Need to handle when container isn't stopped
        self.logger.debug(f"Removing container {container.name}...")
        container.remove()
        self.logger.debug(f"Removed container {container.name}")

    def run(self, container: Container, command: str) -> Tuple[int, str]:
        # TODO - Need to handle when container isn't stopped
        self.logger.debug(f"Running command {command} in container {container.name}...")
        return container.exec_run(command)


    

    
