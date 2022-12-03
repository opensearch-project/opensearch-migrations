import logging
import docker.client
from docker.errors import ImageNotFound, NotFound
from docker.models.networks import Network
from docker.models.containers import Container
from docker.models.volumes import Volume


class RemoveRunningContainerException(Exception):
    def __init__(self, container: Container):
        self.container = container
        super().__init__("A running container should be stopped before attempting to remove: {}".format(container.id))


class ExistingContainerException(Exception):
    def __init__(self, container_name: str):
        self.container_name = container_name
        super().__init__("There already exists a container with the name: {}".format(container_name))


class DockerFrameworkClient:

    def __init__(self, logger=logging.getLogger(__name__), docker_client=docker.from_env()):
        # TODO: Use a custom logger
        self.logger = logger
        self.docker_client = docker_client

    def is_container_running(self, container: Container) -> bool:
        # TODO: Make more robust, case where this attr is not valid
        if container is None:
            raise TypeError("Provided argument is not of type docker.models.containers.Container")
        container_state = container.attrs['State']
        return container_state == 'running'

    def does_container_exist(self, container_name: str) -> bool:
        try:
            self.docker_client.containers.get(container_id=container_name)
        except NotFound:
            return False
        else:
            return True

    def does_image_exist_locally(self, image: str) -> bool:
        try:
            self.docker_client.images.get(image)
        except ImageNotFound:
            return False
        else:
            return True

    def create_container(self, image: str, container_name: str, network_name: str, ports: dict, volumes: dict, ulimits: list,
                         environment: dict) -> Container:
        """
        After performing some basic sanity checks, this function will create and start a Docker container with the supplied parameters

        :param image: The docker image to run, i.e. opensearchproject/opensearch:2.4.0
        :param container_name: The docker container name
        :param network_name: The existing network this container will connect to at creation time, i.e. opensearch-net
        :param ports: A dictionary of ports to bind inside container, i.e. {'9200': '9200', '9600': '9600'}
        :param volumes: A dictionary of volumes to mount, i.e. {'vol1': {'bind': '/usr/share/opensearch/data', 'mode': 'rw'}}
        :param ulimits: A list of docker.types.Ulimit instances to limit resource utilization, i.e. [Ulimit(name='memlock', soft=-1, hard=-1)]
        :param environment: A dictionary of environment fields to be used by the container process, i.e. {'node.name': 'opensearch-node1'}
        :return: Container object for the running container

        For Docker specific details on parameters see: https://docker-py.readthedocs.io/en/stable/containers.html
        """

        # Further checks to add:
        # Check if volume exists
        # Sanity on image str
        # Check that docker service is up
        # Check docker sanity
        # Check for adequate disk space
        # Check for restricted ports
        # Check for necessary environment fields

        if self.does_container_exist(container_name):
            raise ExistingContainerException(container_name)

        if not self.does_image_exist_locally(image):
            self.logger.info("Docker image {} not found locally. Attempting to fetch from remote repository...".format(image))
            self.docker_client.images.pull(image)

        self.logger.debug("Creating container named: {}".format(container_name))
        container = self.docker_client.containers.run(image, name=container_name, network=network_name, ports=ports, volumes=volumes,
                                                      ulimits=ulimits,
                                                      detach=True,
                                                      environment=environment)
        return container

    def remove_container(self, container: Container):
        if container is None:
            raise TypeError("Provided argument is not of type docker.models.containers.Container")
        if self.is_container_running(container):
            raise RemoveRunningContainerException(container)
        self.logger.debug("Removing container: {}".format(container.id))
        container.remove()

    def create_network(self, name: str, driver="bridge") -> Network:
        # TODO: Additional checks and testing needed
        network = self.docker_client.networks.create(name, driver=driver)
        return network

    def remove_network(self, network: Network):
        # TODO: Additional checks and testing needed
        network.remove()

    def create_volume(self, name: str) -> Volume:
        # TODO: Additional checks and testing needed
        volume = self.docker_client.volumes.create(name)
        return volume

    def remove_volume(self, volume: Volume):
        # TODO: Additional checks and testing needed
        volume.remove()
