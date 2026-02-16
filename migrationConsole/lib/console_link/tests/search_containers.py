from collections import namedtuple

import requests
from requests.exceptions import ConnectionError as RequestsConnectionError

from testcontainers.core.container import DockerContainer
from testcontainers.core.utils import raise_for_deprecated_parameter
from testcontainers.core.waiting_utils import wait_container_is_ready, wait_for_logs

# This code is modified from testcontainers.elasticsearch and testcontainers.opensearch to create a unified version
# of the Elasticsearch and OpenSearch test containers, with support for a wider range of versions than the original.

elastic = 'ELASTICSEARCH'
opensearch = 'OPENSEARCH'
CLUSTER_SNAPSHOT_DIR = "/tmp/snapshots"
STARTUP_LOGS_PREDICATE = ".*recovered .* indices into cluster_state.*"

Version = namedtuple("version", ["flavor", "major_version", "minor_version", "patch_version"])
VersionAndImage = namedtuple("version_and_image", ["version", "image"])

IMAGE_MAPPINGS = {
    Version(elastic, 5, 6, 16): "custom-elasticsearch:5.6.16",
    Version(elastic, 6, 8, 23): "custom-elasticsearch:6.8.23",
    Version(elastic, 7, 10, 2): "custom-elasticsearch:7.10.2",
    Version(opensearch, 1, 3, 16): "opensearchproject/opensearch:1.3.16",
    Version(opensearch, 2, 19, 1): "opensearchproject/opensearch:2.19.1"
}


class UnknownSearchContainerVersionException(Exception):
    pass


class Non200StatusCodeException(Exception):
    pass


def _environment_by_version(version: Version) -> dict[str, str]:
    """Returns environment variables required for each major version to work."""
    env = {}
    if version.flavor == elastic:
        if version.major_version == 5:
            env["xpack.security.enabled"] = "false"
        if version.major_version == 6:
            # This setting is needed to avoid the check for the kernel parameter
            # vm.max_map_count in the BootstrapChecks
            env["discovery.zen.minimum_master_nodes"] = "1"
    elif version.flavor == opensearch:
        env["OPENSEARCH_INITIAL_ADMIN_PASSWORD"] = "SecurityIsDisabled123$%^"
        env["plugins.security.disabled"] = "true"
    return env


class SearchContainer(DockerContainer):
    """
    ElasticSearch container.

    Example:
        .. doctest::
            >>> from tests.search_containers import SearchContainer, Version
            >>> with SearchContainer(Version('ELASTICSEARCH', 7, 10, 2), mem_limit='3G') as es:
            ...    resp = requests.get(es.get_url())
            ...    resp.raise_for_status()
            ...    return resp.json()['version']['number']
            '7.10.2'
    """

    def __init__(self, version: Version, port: int = 9200, **kwargs) -> None:
        raise_for_deprecated_parameter(kwargs, "port_to_expose", "port")
        if version not in IMAGE_MAPPINGS:
            raise UnknownSearchContainerVersionException(f"Unknown version provided: {version}")
        self.version = version
        image = IMAGE_MAPPINGS[version]
        super().__init__(image, **kwargs)
        self.port = port
        self.with_exposed_ports(self.port)
        self.with_env("transport.host", "127.0.0.1")
        self.with_env("http.host", "0.0.0.0")
        self.with_env("discovery.type", "single-node")
        self.with_env("path.repo", CLUSTER_SNAPSHOT_DIR)
        for key, value in _environment_by_version(version).items():
            self.with_env(key, value)

    def get_url(self) -> str:
        host = self.get_container_host_ip()
        port = self.get_exposed_port(self.port)
        return f"http://{host}:{port}"

    @wait_container_is_ready(ConnectionError, RequestsConnectionError, Non200StatusCodeException, TimeoutError)
    def _healthcheck(self) -> None:
        r = requests.get(self.get_url(), timeout=10)
        if r.status_code != 200:
            print(f"Health check failed with status code: {r.status_code}")
            raise Non200StatusCodeException()

    def start(self) -> "SearchContainer":
        """This method starts the OpenSearch container and runs the healthcheck
        to verify that the container is ready to use."""
        super().start()
        wait_for_logs(self, predicate=STARTUP_LOGS_PREDICATE, timeout=60)
        self._healthcheck()
        return self
