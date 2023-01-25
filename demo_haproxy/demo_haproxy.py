#!/usr/bin/env python3
import os
from pathlib import Path
import shutil
from typing import List

from docker.types import Ulimit

import cluster_migration_core.cluster_management.docker_framework_client as dfc
import cluster_migration_core.core.shell_interactions as shell
from cluster_migration_core.core.test_config_wrangling import ClusterConfig
from cluster_migration_core.cluster_management.cluster import Cluster, ClusterNotStartedInTimeException


# Standard tag used to specify the host running a container from inside that container
TAG_DOCKER_HOST = "host.docker.internal"


def generate_haproxy_config(primary_ports: List[int]) -> str:
    # Taken from the HAProxy guide here: https://www.haproxy.com/blog/how-to-run-haproxy-with-docker/

    backend_server_lines = []
    for port in primary_ports:
        backend_server_lines.append(f"    server s{port} {TAG_DOCKER_HOST}:{port} check")
    backend_server_section = "\n".join(backend_server_lines)

    return f"""
global
    stats socket /var/run/api.sock user haproxy group haproxy mode 660 level admin expose-fd listeners
    log 127.0.0.1:514 local0
    log stdout format raw local0 debug

defaults
    mode http
    timeout client 10s
    timeout connect 5s
    timeout server 10s
    timeout http-request 10s
    log global

frontend stats
    bind *:8404
    stats enable
    stats uri /
    stats refresh 10s

frontend myfrontend
    bind :80
    declare capture request len 80000
    declare capture response len 80000
    http-request capture req.body id 0
    log-format Request-URI:\\ %[capture.req.uri]\\nRequest-Method:\\ %[capture.req.method]\\nRequest-Body:\\ %[capture.req.hdr(0)]\\nResponse-Body:\\ %[capture.res.hdr(0)]
    default_backend webservers

backend webservers
    http-response capture res.body id 0
{backend_server_section}

"""


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

    # # Set up the Shadow Cluster
    # shadow_cluster_config = ClusterConfig(raw_config={
    #     "engine_version": "ES_7_10_2",
    #     "image": "docker.elastic.co/elasticsearch/elasticsearch-oss:7.10.2",
    #     "node_count": 2,
    #     "additional_node_config": {
    #         "ES_JAVA_OPTS": "-Xms512m -Xmx512m"
    #     }
    # })

    # print("Creating shadow cluster...")
    # starting_port = max(primary_cluster.rest_ports) + 1
    # shadow_cluster = Cluster("shadow-cluster", shadow_cluster_config, docker_client, starting_port=starting_port)
    # shadow_cluster.start()

    # try:
    #     max_wait_sec = 30
    #     print(f"Waiting up to {max_wait_sec} sec for cluster to be active...")
    #     shadow_cluster.wait_for_cluster_to_start_up(max_wait_time_sec=30)
    # except ClusterNotStartedInTimeException as exception:
    #     print("Cluster did not start within time limit")
    #     raise exception
    # print(f"Cluster {shadow_cluster.name} is active")

    # =================================================================================================================
    # Set up HAProxy
    # =================================================================================================================
    
    # Set up our local workspace
    workspace = Path("/tmp/haproxy_demo/")
    workspace.mkdir(exist_ok=True)

    # Set Python's working directory to something predictable (this file's directory) so we can copy Docker-related
    # files into the workspace, then perform the copy
    demo_dir = os.path.dirname(__file__)
    os.chdir(demo_dir)

    docker_files_dir = "./docker_stuff"
    files = os.listdir(docker_files_dir)
    print(f"Copying Docker-related files to: {workspace}")
    for file_name in files:
        shutil.copy2(os.path.join(docker_files_dir, file_name), workspace)
    
    # Write our HAProxy Config File to our workspace
    haproxy_config_str = generate_haproxy_config(primary_cluster.rest_ports)
    haproxy_config_path = os.path.join(workspace, "haproxy.cfg")
    with open(haproxy_config_path, "w") as haproxy_config_file:
        print(f"Writing HAProxy Config to: {haproxy_config_path}")
        haproxy_config_file.write(haproxy_config_str)    

    # Change directory again to the workspace then build our Docker image
    os.chdir(workspace)

    image_tag = "haproxy-demo"
    print("Building HAProxy Docker image...")
    haproxy_image = docker_client.build_image(str(workspace), image_tag)

    # Start the HAProxy Container
    haproxy_network = docker_client.create_network("haproxy-network")  # unnecessary but required by current Cluster API
    haproxy_volume = dfc.DockerVolume(
        container_mount_point="/usr/local/etc/haproxy",
        host_mount_point=workspace,
        volume=docker_client.create_volume("haproxy-volume")
    )
    print("Starting HAProxy container...")
    haproxy_container = docker_client.create_container(
        image=haproxy_image.tag,
        container_name="haproxy",
        network=haproxy_network,
        ports=[dfc.PortMapping(80, 80), dfc.PortMapping(8404, 8404)],
        volumes=[haproxy_volume],
        ulimits=[Ulimit(name='memlock', soft=-1, hard=-1)],
        extra_hosts={TAG_DOCKER_HOST: "host-gateway"}
    )

    # =================================================================================================================
    # Run the demo here
    # =================================================================================================================
    prompt = """
HAProxy is currently running in a Docker container, available at 127.0.0.1:80, and configured to pass traffic to an
ES 7.10.2 cluster of two nodes (each running in their own Docker containers).  The requests/responses passed to the
cluster via the HAProxy container will be logged to the container at /var/log/haproxy-traffic.log.

Some example commands you can run to demonstrate the behavior are:
curl -X GET 'localhost:80'
curl -X GET 'localhost:80/_cat/nodes?v=true&pretty'

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

    # # Clean up the shadow cluster
    # print(f"Stopping cluster {shadow_cluster.name}...")
    # shadow_cluster.stop()
    # print(f"Cleaning up underlying resources for cluster {shadow_cluster.name}...")
    # shadow_cluster.clean_up()

    # Clean up HAProxy stuff
    print("Cleaning up underlying resources for the HAProxy container...")
    docker_client.stop_container(haproxy_container)
    docker_client.remove_container(haproxy_container)
    docker_client.remove_network(haproxy_network)
    docker_client.remove_volume(haproxy_volume.volume)


if __name__ == "__main__":
    main()
