#!/usr/bin/env python3
import os

from docker.types import Ulimit

import cluster_migration_core.cluster_management.docker_framework_client as dfc
import cluster_migration_core.core.shell_interactions as shell
from cluster_migration_core.core.test_config_wrangling import ClusterConfig
from cluster_migration_core.cluster_management.cluster import Cluster, ClusterNotStartedInTimeException


HAPROXY_INTERNAL_PORT = 9200
HAPROXY_PRIMARY_PORT = 80
HAPROXY_SHADOW_PORT = 81
# Standard tag used to specify the host running a container from inside that container
TAG_DOCKER_HOST = "host.docker.internal"


def main():
    # =================================================================================================================
    # Setup Clusters
    # =================================================================================================================
    docker_client = dfc.DockerFrameworkClient()

    # Set up the Primary Cluster
    primary_cluster_config = ClusterConfig(raw_config={
        "engine_version": "ES_7_10_2",
        "image": "docker.elastic.co/elasticsearch/elasticsearch-oss:7.10.2",
        "node_count": 2,
        "additional_node_config": {
            "ES_JAVA_OPTS": "-Xms512m -Xmx512m"
        }
    })

    print("Creating primary cluster...")
    primary_cluster = Cluster("primary-cluster", primary_cluster_config, docker_client)
    primary_cluster.start()

    try:
        max_wait_sec = 30
        print(f"Waiting up to {max_wait_sec} sec for cluster to be active...")
        primary_cluster.wait_for_cluster_to_start_up(max_wait_time_sec=30)
    except ClusterNotStartedInTimeException as exception:
        print("Cluster did not start within time limit")
        raise exception
    print(f"Cluster {primary_cluster.name} is active")

    # Set up the Shadow Cluster
    shadow_cluster_config = ClusterConfig(raw_config={
        "engine_version": "ES_7_10_2",
        "image": "docker.elastic.co/elasticsearch/elasticsearch-oss:7.10.2",
        "node_count": 2,
        "additional_node_config": {
            "ES_JAVA_OPTS": "-Xms512m -Xmx512m"
        }
    })

    print("Creating shadow cluster...")
    starting_port = max(primary_cluster.rest_ports) + 1
    shadow_cluster = Cluster("shadow-cluster", shadow_cluster_config, docker_client, starting_port=starting_port)
    shadow_cluster.start()

    try:
        max_wait_sec = 30
        print(f"Waiting up to {max_wait_sec} sec for cluster to be active...")
        shadow_cluster.wait_for_cluster_to_start_up(max_wait_time_sec=30)
    except ClusterNotStartedInTimeException as exception:
        print("Cluster did not start within time limit")
        raise exception
    print(f"Cluster {shadow_cluster.name} is active")

    # =================================================================================================================
    # Set up HAProxy
    # =================================================================================================================
    # Set Python's working directory to something predictable (this file's directory) 
    demo_dir = os.path.dirname(__file__)
    os.chdir(demo_dir)
    
    # Build the Docker images for the Primary and Shadow HAProxy containers
    print("Building HAProxy Docker images for Primary and Shadow HAProxy containers...")
    primary_image = "haproxy-primary"
    primary_nodes = " ".join([f"{TAG_DOCKER_HOST}:{port}" for port in primary_cluster.rest_ports])
    shadow_haproxy = f"{TAG_DOCKER_HOST}:{HAPROXY_SHADOW_PORT}"
    shadow_image = "haproxy-shadow"
    shadow_nodes = " ".join([f"{TAG_DOCKER_HOST}:{port}" for port in shadow_cluster.rest_ports])

    build_command = " ".join([
        "./build_docker_images.py",
        "--primary-image",
        primary_image,
        "--primary-nodes",
        primary_nodes,
        "--shadow-haproxy",
        shadow_haproxy,
        "--shadow-image",
        shadow_image,
        "--shadow-nodes",
        shadow_nodes,
        "--internal-port",
        str(HAPROXY_INTERNAL_PORT)
    ])
    print(f"Executing command to build Docker images: {build_command}")

    def subshell_print(message: str):
        print(f"Subshell> {message}")
    
    shell.call_shell_command(build_command, output_logger=subshell_print)

    # Create/Run the Shadow HAProxy Container
    haproxy_network_shadow = docker_client.create_network("haproxy-network-shadow")  # unnecessary but required by API
    print("Starting HAProxy container for Shadow Cluster...")
    haproxy_container_shadow = docker_client.create_container(
        image=shadow_image,
        container_name=shadow_image,
        network=haproxy_network_shadow,
        ports=[dfc.PortMapping(HAPROXY_INTERNAL_PORT, HAPROXY_SHADOW_PORT)],
        volumes=[],
        ulimits=[Ulimit(name='memlock', soft=-1, hard=-1)],
        extra_hosts={TAG_DOCKER_HOST: "host-gateway"}
    )

    # Create/Run the Shadow HAProxy Container
    haproxy_network_primary = docker_client.create_network("haproxy-network-primary")  # unnecessary but required by API
    print("Starting HAProxy container for Primary Cluster...")
    haproxy_container_primary = docker_client.create_container(
        image=primary_image,
        container_name=primary_image,
        network=haproxy_network_primary,
        ports=[dfc.PortMapping(HAPROXY_INTERNAL_PORT, HAPROXY_PRIMARY_PORT)],
        volumes=[],
        ulimits=[Ulimit(name='memlock', soft=-1, hard=-1)],
        extra_hosts={TAG_DOCKER_HOST: "host-gateway"}
    )

    # =================================================================================================================
    # Run the demo here
    # =================================================================================================================
    prompt = """
HAProxy is currently running in a Docker container, available at 127.0.0.1:80, and configured to pass traffic to an
ES 7.10.2 cluster of two nodes (each running in their own Docker containers).  The requests/responses passed to the
cluster via the HAProxy container will be logged to the container at /var/log/haproxy-traffic.log.  The requests are
mirrored to an identical shadow cluster.

Some example commands you can run to demonstrate the behavior are:
curl -X GET 'localhost:80'
curl -X GET 'localhost:80/_cat/nodes?v=true&pretty'
curl -X PUT 'localhost:80/noldor/_doc/1' -H 'Content-Type: application/json' -d'{"name": "Finwe"}'
curl -X GET 'localhost:80/noldor/_doc/1'

When you are done playing with the setup, hit the RETURN key in this terminal window to shut down and clean up the
demo containers.
    """
    shell.louder_input(prompt)

    # =================================================================================================================
    # Clean Up
    # =================================================================================================================
    # Clean up the primary cluster
    print(f"Stopping cluster {primary_cluster.name}...")
    primary_cluster.stop()
    print(f"Cleaning up underlying resources for cluster {primary_cluster.name}...")
    primary_cluster.clean_up()

    # Clean up the shadow cluster
    print(f"Stopping cluster {shadow_cluster.name}...")
    shadow_cluster.stop()
    print(f"Cleaning up underlying resources for cluster {shadow_cluster.name}...")
    shadow_cluster.clean_up()

    # Clean up HAProxy stuff
    print("Cleaning up underlying resources for the Primary HAProxy container...")
    docker_client.stop_container(haproxy_container_primary)
    docker_client.remove_container(haproxy_container_primary)
    docker_client.remove_network(haproxy_network_primary)

    print("Cleaning up underlying resources for the Shadow HAProxy container...")
    docker_client.stop_container(haproxy_container_shadow)
    docker_client.remove_container(haproxy_container_shadow)
    docker_client.remove_network(haproxy_network_shadow)


if __name__ == "__main__":
    main()
