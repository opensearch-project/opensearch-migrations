from typing import List

from docker.models.networks import Network
from docker.types import Ulimit

from upgrade_testing_framework.cluster_management.docker_framework_client import DockerVolume, PortMapping


class ContainerConfiguration:
    def __init__(self, image: str, network: Network, port_mappings: List[PortMapping], volumes: List[DockerVolume],
                 ulimits: List[Ulimit] = [Ulimit(name='memlock', soft=-1, hard=-1)]):
        self.image = image
        self.network = network
        self.port_mappings = port_mappings
        self.volumes = volumes
        self.ulimits = ulimits

        self.rest_port: int = None
        for container_port, host_port in self.port_mappings:
            if 9200 == container_port:
                self.rest_port = host_port

    def to_dict(self) -> dict:
        return {
            "image": self.image,
            "network": self.network.attrs,
            "port_mappings": [mapping.to_dict() for mapping in self.port_mappings],
            "rest_port": self.rest_port,
            "volumes": [volume.to_dict() for volume in self.volumes],
            "ulimits": [{"name": u.name, "soft": u.soft, "hard": u.hard} for u in self.ulimits]
        }
