import logging
import sys
from docker.types import Ulimit
from upgrade_testing_framework.core.docker_framework_client import DockerFrameworkClient


'''
Sample demo file to start a three node cluster with attached volumes, network, and default environment settings. To be deleted once docker 
library is in good state

'''
def gen_default_opensearch_env(manager_nodes: str, node_name: str, discovery_seed_hosts: str,
                               security_disabled='false', cluster_name='opensearch-cluster', additional_settings=None) -> dict:
    env_settings = {'cluster.initial_cluster_manager_nodes': manager_nodes,
                    'plugins.security.disabled': security_disabled,
                    'cluster.name': cluster_name,
                    'node.name': node_name,
                    'discovery.seed_hosts': discovery_seed_hosts,
                    'bootstrap.memory_lock': 'true',
                    'OPENSEARCH_JAVA_OPTS': '-Xms512m -Xmx512m'}
    if type(additional_settings) is dict:
        env_settings.update(additional_settings)
    return env_settings


def gen_default_opensearch_ulimits() -> list:
    ulimits = [Ulimit(name='memlock', soft=-1, hard=-1),
               Ulimit(name='nofile', soft=65536, hard=65536)]
    return ulimits



def main():
    # Will use custom logger in future, shortcut for log visibility now
    logger = logging.getLogger()
    logger.setLevel(logging.DEBUG)
    handler = logging.StreamHandler(sys.stdout)
    handler.setLevel(logging.DEBUG)
    formatter = logging.Formatter('%(asctime)s - %(name)s - %(levelname)s - %(message)s')
    handler.setFormatter(formatter)
    logger.addHandler(handler)

    docker_client = DockerFrameworkClient(logger=logger)

    network = docker_client.create_network("opensearch-net")
    volume1 = docker_client.create_volume("os-data1")
    volume2 = docker_client.create_volume("os-data2")
    volume3 = docker_client.create_volume("os-data3")
    master_nodes = "opensearch-node1, opensearch-node2, opensearch-node3"
    container1 = docker_client.create_container('opensearchproject/opensearch:2.3.0', 'opensearch-node1', 'opensearch-net',
                                                {'9200': '9200', '9600': '9600'},
                                                {'os-data1': {'bind': '/usr/share/opensearch/data', 'mode': 'rw'}},
                                                gen_default_opensearch_ulimits(),
                                                gen_default_opensearch_env(master_nodes, "opensearch-node1", master_nodes,
                                                                           security_disabled='true'))
    container2 = docker_client.create_container('opensearchproject/opensearch:2.3.0', 'opensearch-node2', 'opensearch-net',
                                                None, {'os-data2': {'bind': '/usr/share/opensearch/data', 'mode': 'rw'}},
                                                gen_default_opensearch_ulimits(),
                                                gen_default_opensearch_env(master_nodes, "opensearch-node2", master_nodes,
                                                                           security_disabled='true'))
    container3 = docker_client.create_container('opensearchproject/opensearch:2.3.0', 'opensearch-node3', 'opensearch-net',
                                                None, {'os-data3': {'bind': '/usr/share/opensearch/data', 'mode': 'rw'}},
                                                gen_default_opensearch_ulimits(),
                                                gen_default_opensearch_env(master_nodes, "opensearch-node3", master_nodes,
                                                                           security_disabled='true'))

    # Additional demo code to wait and clean up resources as containers are manually stopped
    container1.wait()
    docker_client.remove_container(container1)
    docker_client.remove_volume(volume1)
    container2.wait()
    docker_client.remove_container(container2)
    docker_client.remove_volume(volume2)
    container3.wait()
    docker_client.remove_container(container3)
    docker_client.remove_volume(volume3)
    docker_client.remove_network(network)


if __name__ == "__main__":
    main()
