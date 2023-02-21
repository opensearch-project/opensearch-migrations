from dataclasses import dataclass
import logging
from typing import Dict, List, NamedTuple, Tuple

import docker.client
from docker.errors import DockerException, ImageNotFound
from docker.models.networks import Network
from docker.models.containers import Container
from docker.models.volumes import Volume
from docker.types import Ulimit

import cluster_migration_core.cluster_management.docker_command_gen as dcg
import cluster_migration_core.core.shell_interactions as shell


class DockerNotInPathException(Exception):
    def __init__(self):
        super().__init__("The 'docker' CLI is not in your system's PATH")


class DockerNotResponsiveException(Exception):
    def __init__(self, original_exception):
        self.original_exception = original_exception
        super().__init__("The Docker server on your system is not responsive")


class DockerImageUnavailableException(Exception):
    def __init__(self, image: str):
        super().__init__(f"The Docker {image} is not available either locally or in your remote repos")


@dataclass
class DockerImage:
    tag: str


class DockerVolume:
    def __init__(self, container_mount_point: str, volume: Volume, host_mount_point: str = None):
        self.container_mount_point = container_mount_point
        self.host_mount_point = host_mount_point
        self.volume = volume

    def to_dict(self) -> dict:
        return {
            "container_mount_point": self.container_mount_point,
            "host_mount_point": self.host_mount_point,
            "volume": self.volume.attrs
        }


class PortMapping(NamedTuple):
    container_port: int
    host_port: int

    def to_dict(self) -> dict:
        return {
            "container_port": self.container_port,
            "host_port": self.host_port
        }


class DockerFrameworkClient:
    def _try_create_docker_client() -> docker.client.DockerClient:
        # First check to see if Docker is available in the user's PATH.  The Docker SDK doesn't provide a good
        # way to distinguish between this case and Docker just not running.
        exit_code, _ = shell.call_shell_command("which docker")  # TODO not platform agnostic, and should wrap this
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

        if docker_client is None:
            self._docker_client = DockerFrameworkClient._try_create_docker_client()
        else:
            self._docker_client = docker_client

    def build_image(self, dir_path: str, tag: str, target: str = None) -> DockerImage:
        """
        dir_path: The path to the directory containing the Dockerfile
        tag: The tag to set to the image after it's built
        target: The build target/stage in a multi-stage Dockerfile to generate the image for
        """
        kwargs = {
            "path": dir_path,
            "tag": tag
        }

        if target:
            kwargs["target"] = target

        self._docker_client.images.build(
            **kwargs
        )
        return DockerImage(tag)

    def is_image_available_locally(self, image: str) -> bool:
        try:
            self._docker_client.images.get(image)
        except ImageNotFound:
            self.logger.debug(f"Image {image} not available locally")
            return False
        self.logger.debug(f"Image {image} is available locally")
        return True

    def pull_image(self, image: str):
        self.logger.debug(f"Attempting to pull image {image} from remote repository...")
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

    def create_container(self, image: str, container_name: str, network: Network, ports: List[PortMapping],
                         volumes: List[DockerVolume], ulimits: List[Ulimit], env_kv: Dict[str, str] = None,
                         env_passthrough: List[str] = None, extra_hosts: Dict[str, str] = None, detach: bool = True,
                         entrypoint: List[str] = None) -> Container:
        """
        image: the name of the Docker image to spin up
        container_name: the name/tag you want assigned to the created container
        network: the Docker network to connect the container to
        ports: list of port mappings you want configured between the container and the local host (e.g. --publish)
        volumes: list of Docker volumes you want mounted into the container
        ulimits: list of resource constraints to apply to the container
        env_kv: dict of key/value pairs of ENV variables that should be present in the container
        env_passthrough: list of export'd ENV variable names to pipe through from the invoking context to the container
        extra_hosts: dict of hostname mappings to add to the container (e.g. --add-host)
        detach: whether to detach the container from the current process
        entrypoints: list of strings to supply during the run as entrypoint commands; overrides default in Dockerfile
        """

        # TODO - need handle if container already exists (name collision)
        # TODO - need handle if we exceed the resource allocation for Docker
        # TODO - need handle port contention

        # Initialize optional, mutable containers
        if not entrypoint:
            entrypoint = []
        if not env_kv:
            env_kv = {}
        if not env_passthrough:
            env_passthrough = []
        if not extra_hosts:
            extra_hosts = {}

        # Environment variables can be specified to a Docker container in few ways.  One is to provide explicit key/val
        # pairs as part of the "run" command; another is to provide just the key name of an existing environment
        # variable in the user's shell context (e.g. passthrough).  Assuming the passthrough variable has been export'd
        # then Docker will pipe it through to the container.  This is useful for things like security credentials.
        # However, we need to assemble a single list of variables to pass to the Docker SDK, which is what this code
        # is doing.
        environment_combined = []
        environment_combined.extend([f"{k}={v}" for k, v in env_kv.items()])
        environment_combined.extend(env_passthrough)

        # It doesn't appear you can just pass in a list of Volumes to the client, so we have to make this wonky mapping
        port_mapping = {str(pair.container_port): str(pair.host_port) for pair in ports}
        volume_mapping = {}
        for dv in volumes:
            # In this case, we have a directory on the host machine we want to make available to the container so the
            # key is the path to that directory.  We make the mount type "Read Only" for safety, but this should
            # ideally be configurable.
            if dv.host_mount_point:
                volume_mapping[dv.host_mount_point] = {"bind": dv.container_mount_point, "mode": "ro"}
            
            # In this case, the volume is entirely within Docker temporary filespace, so the key is the name of our
            # volume.  We set this to be "Read-Write" so multiple containers can share the volume's files, but this
            # should probably be configurable as well.
            else:
                volume_mapping[dv.volume.attrs["Name"]] = {"bind": dv.container_mount_point, "mode": "rw"}

        self.logger.debug(f"Creating container {container_name}...")
        container = self._log_and_execute_command_run(
            image=image,
            name=container_name,
            network=network.name,
            ports=port_mapping,
            volumes=volume_mapping,
            ulimits=ulimits,
            detach=detach,
            environment=environment_combined,
            extra_hosts=extra_hosts,
            entrypoint=entrypoint
        )
        self.logger.debug(f"Created container {container_name}")
        return container

    def _log_and_execute_command_run(self, image: str, name: str, network: str, ports: Dict[str, str],
                                     volumes: Dict[str, Dict[str, str]], ulimits: List[Ulimit], detach: bool,
                                     environment: List[str], extra_hosts: Dict[str, str],
                                     entrypoint: List[str]) -> Container:

        args = {"image": image, "name": name, "network": network, "ports": ports, "volumes": volumes,
                "ulimits": ulimits, "detach": detach, "environment": environment, "extra_hosts": extra_hosts,
                "entrypoint": entrypoint}

        run_command = dcg.gen_docker_run(**args)
        self.logger.debug(f"Predicted command being run by the Docker SDK: {run_command}")
        
        # Annoyingly, the Docker SDK breaks if you pass in an empty list, so we have to manually look for and handle
        # this case.
        if not ("entrypoint" in args.keys() and args.get("entrypoint")):  # We don't actually have entrypoint cmds
            args.pop("entrypoint", None)
        
        container = self._docker_client.containers.run(**args)
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

    def run_command(self, container: Container, command: str) -> Tuple[int, str]:
        # TODO - Need to handle when container isn't running
        self.logger.debug(f"Running command {command} in container {container.name}...")
        return container.exec_run(command)

    def set_ownership_of_directory(self, container: Container, new_owner: str, dir: str):
        # TODO - Need to handle when container isn't running
        self.logger.debug(f"Setting ownership of {dir} to {new_owner} in container {container.name}...")
        chown_command = f"chown -R {new_owner} {dir}"
        self.run_command(container, chown_command)
