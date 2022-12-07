import logging
from typing import List

import docker.client
from docker.errors import APIError, DockerException, ImageNotFound, NotFound
from docker.models.networks import Network
from docker.models.containers import Container
from docker.models.volumes import Volume

import upgrade_testing_framework.core.shell_interactions as shell

# class RemoveRunningContainerException(Exception):
#     def __init__(self, container: Container):
#         self.container = container
#         super().__init__("A running container should be stopped before attempting to remove: {}".format(container.id))

# class ExistingContainerException(Exception):
#     def __init__(self, container_name: str):
#         self.container_name = container_name
#         super().__init__("There already exists a container with the name: {}".format(container_name))

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

    def create_container(self, image: str, container_name: str, network: Network, ports: dict, volumes: List[Volume], ulimits: list,
                         env_variables: dict) -> Container:

        # It doesn't appear you can just pass in a list of Volumes to the client, so we have to make this wonky mapping
        volume_mapping = {volume.attrs["Name"]: {"bind": volume.attrs["Mountpoint"], "mode": "rw"} for volume in volumes}
        
        self.logger.debug("Creating container named: {}".format(container_name))
        container = self._docker_client.containers.run(image, name=container_name, network=network.name, ports=ports, volumes=volume_mapping,
                                                      ulimits=ulimits,
                                                      detach=True,
                                                      environment=env_variables)
        return container

    # def is_container_running(self, container: Container) -> bool:
    #     # TODO: Make more robust, case where this attr is not valid
    #     if container is None:
    #         raise TypeError("Provided argument is not of type docker.models.containers.Container")
    #     container_state = container.attrs['State']
    #     return container_state == 'running'

    # def does_container_exist(self, container_name: str) -> bool:
    #     try:
    #         self._docker_client.containers.get(container_id=container_name)
    #     except NotFound:
    #         return False
    #     else:
    #         return True

    

    # def create_container(self, image: str, container_name: str, network_name: str, ports: dict, volumes: dict, ulimits: list,
    #                      environment: dict) -> Container:
    #     """
    #     After performing some basic sanity checks, this function will create and start a Docker container with the supplied parameters

    #     :param image: The docker image to run, i.e. opensearchproject/opensearch:2.4.0
    #     :param container_name: The docker container name
    #     :param network_name: The existing network this container will connect to at creation time, i.e. opensearch-net
    #     :param ports: A dictionary of ports to bind inside container, i.e. {'9200': '9200', '9600': '9600'}
    #     :param volumes: A dictionary of volumes to mount, i.e. {'vol1': {'bind': '/usr/share/opensearch/data', 'mode': 'rw'}}
    #     :param ulimits: A list of docker.types.Ulimit instances to limit resource utilization, i.e. [Ulimit(name='memlock', soft=-1, hard=-1)]
    #     :param environment: A dictionary of environment fields to be used by the container process, i.e. {'node.name': 'opensearch-node1'}
    #     :return: Container object for the running container

    #     For Docker specific details on parameters see: https://docker-py.readthedocs.io/en/stable/containers.html
    #     """

    #     # Further checks to add:
    #     # Check if volume exists
    #     # Check for adequate disk space
    #     # Check for restricted ports
    #     # Check for necessary environment fields

    #     if self.does_container_exist(container_name):
    #         raise ExistingContainerException(container_name)

    #     if not self.does_image_exist_locally(image):
    #         self.logger.info("Docker image {} not found locally. Attempting to fetch from remote repository...".format(image))
    #         self._docker_client.images.pull(image)

    #     self.logger.debug("Creating container named: {}".format(container_name))
    #     container = self._docker_client.containers.run(image, name=container_name, network=network_name, ports=ports, volumes=volumes,
    #                                                   ulimits=ulimits,
    #                                                   detach=True,
    #                                                   environment=environment)
    #     return container

    # def remove_container(self, container: Container):
    #     if container is None:
    #         raise TypeError("Provided argument is not of type docker.models.containers.Container")
    #     if self.is_container_running(container):
    #         raise RemoveRunningContainerException(container)
    #     self.logger.debug("Removing container: {}".format(container.id))
    #     container.remove()

    
