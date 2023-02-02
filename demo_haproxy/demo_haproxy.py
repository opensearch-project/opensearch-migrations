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


HAPROXY_PRIMARY_PORT = 80
HAPROXY_SHADOW_PORT = 81
SPOA_LISTEN_PORT = 12345
SPOA_LISTEN_URL = "127.0.0.1"


# Standard tag used to specify the host running a container from inside that container
TAG_DOCKER_HOST = "host.docker.internal"


def generate_haproxy_config(primary_ports: List[int], shadow_ports: List[int], primary: bool) -> str:
    """
    This jumble of code generates two different HAProxy config files.  If "primary" is True, it generates a config
    file for the HAProxy instance between the client and the primary cluster and turn on traffic mirroring to the
    shadow cluster.  If "primary" is False, it generates a config file for the HAProxy instance between the Primary
    HAProxy instance and the shadow cluster without any mirroring configured.  The logging and other configuration are
    the same for either config.

    Taken from the HAProxy guide here: https://www.haproxy.com/blog/how-to-run-haproxy-with-docker/
    And here: https://www.haproxy.com/blog/haproxy-traffic-mirroring-for-real-world-testing/
    """

    # Configure the "backend" section for our primary cluster
    backend_server_lines = []
    backend_ports = primary_ports if primary else shadow_ports
    for port in backend_ports:
        backend_server_lines.append(f"    server s{port} {TAG_DOCKER_HOST}:{port} check")
    backend_server_section = "\n".join(backend_server_lines)

    # Construct the URL to send mirrored traffic to
    shadow_url = f"http://{TAG_DOCKER_HOST}:{HAPROXY_SHADOW_PORT}"

    base_config = f"""
global
    # Logging configuration
    log 127.0.0.1:514 local0 # syslogd, facility local0
    log stdout format raw local0 debug # stdout too

    # Turn on Master/Worker mode.  Required to use SPOE Mirroring.  While hardly an expert, I've attempted to configure
    # this so that the HAProxy container will exit if a process associated w/ the proxy mechanism runs into trouble.
    # This will ensure problems are surfaced quickly/obviously, possibly at the expense of resilience at the container
    # level.
    #
    # The premise of master/worker is that it sets up a main process which "is" HAProxy from the perspective of the
    # rest of the system, and the actual work of HAProxy is done by subprocesses that can be killed/restarted at-will
    # without changing the process id.  This provides better integration w/ systemd's process management system, and
    # more logically bundles the various aspects of HAProxy under one process umbrella (the main process).
    master-worker # not using no-exit-on-failure so container dies if any subprocess dies
    mworker-max-reloads 3 # Limit subprocess reloads to 3 before SIGTERM is sent
    hard-stop-after 15s # If a subprocess is still running 15s after reload signal, send SIGTERM

defaults
    mode http
    timeout client 10s
    timeout connect 5s
    timeout server 10s
    timeout http-request 10s
    log global

# These are the primary Cluster's Nodes; traffic will be sent to them synchronously.  The default round robin LB
# pattern is in effect.
backend primary_cluster
    http-response capture res.body id 0
{backend_server_section}

# This section outlines the "frontend" for this instance of HAProxy and specifies the rules by which it receives
# traffic
frontend myfrontend
    bind :{HAPROXY_PRIMARY_PORT if primary else HAPROXY_SHADOW_PORT}

    # Set up the logging for the req/res stream to the primary cluster
    declare capture request len 80000
    declare capture response len 80000
    http-request capture req.body id 0
    log-format Request-URI:\\ %[capture.req.uri]\\nRequest-Method:\\ %[capture.req.method]\\nRequest-Body:\\ %[capture.req.hdr(0)]\\nResponse-Body:\\ %[capture.res.hdr(0)]
    
    # Associate this frontend with the primary cluster
    default_backend primary_cluster

"""

    mirror_config = f"""
    # Required for the mirror SPOA to have access to the request
    option http-buffer-request

    # Tell HAProxy to also send traffic to this frontend to the mirror agent too
    filter spoe engine mirror config /usr/local/etc/haproxy/mirror.conf

# Tells HAProxy to also start the mirroring SPOA as a daemon when it starts up
program mirror
    command spoa-mirror --runtime 0 --address {SPOA_LISTEN_URL} --port {SPOA_LISTEN_PORT} --mirror-url {shadow_url}

# Mirror agents
backend mirroragents
    mode tcp
    balance roundrobin
    timeout connect 5s
    timeout server 5s
    server agent1 {SPOA_LISTEN_URL}:{SPOA_LISTEN_PORT}

"""

    final_config = base_config if not primary else base_config + mirror_config
    return final_config


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
    
    # Write our the base HAProxy Config File (no mirroring) to our workspace
    haproxy_config_str = generate_haproxy_config(primary_cluster.rest_ports, shadow_cluster.rest_ports, False)
    haproxy_config_path = os.path.join(workspace, "haproxy.cfg")
    with open(haproxy_config_path, "w") as haproxy_config_file:
        print(f"Writing HAProxy Config to: {haproxy_config_path}")
        haproxy_config_file.write(haproxy_config_str)

    # Write our the t-split HAProxy Config File (with mirroring) to our workspace
    haproxy_mirror_config_str = generate_haproxy_config(primary_cluster.rest_ports, shadow_cluster.rest_ports, True)
    haproxy_mirror_config_path = os.path.join(workspace, "haproxy_w_mirror.cfg")
    with open(haproxy_mirror_config_path, "w") as haproxy_config_file:
        print(f"Writing HAProxy Config to: {haproxy_mirror_config_path}")
        haproxy_config_file.write(haproxy_mirror_config_str)

    # Change directory again to the workspace then build our Docker image
    os.chdir(workspace)

    # Build the Docker images for the Shadow HAProxy and start the container
    print("Building HAProxy Docker image for Shadow Cluster...")
    haproxy_image_shadow = docker_client.build_image(str(workspace), "haproxy-demo-shadow", "haproxy-base")

    haproxy_network_shadow = docker_client.create_network("haproxy-network-shadow")  # unnecessary but required by API
    haproxy_volume_shadow = dfc.DockerVolume(
        container_mount_point="/usr/local/etc/haproxy",
        host_mount_point=workspace,
        volume=docker_client.create_volume("haproxy-volume-shadow")
    )
    print("Starting HAProxy container for Shadow Cluster...")
    haproxy_container_shadow = docker_client.create_container(
        image=haproxy_image_shadow.tag,
        container_name="haproxy-shadow",
        network=haproxy_network_shadow,
        ports=[dfc.PortMapping(81, 81)],
        volumes=[haproxy_volume_shadow],
        ulimits=[Ulimit(name='memlock', soft=-1, hard=-1)],
        extra_hosts={TAG_DOCKER_HOST: "host-gateway"}
    )

    # Build the Docker images for the Primary HAProxy
    print("Building HAProxy Docker image for Primary Cluster...")
    haproxy_image_primary = docker_client.build_image(str(workspace), "haproxy-demo-primary", "haproxy-mirror")

    # Start the HAProxy Container
    haproxy_network_primary = docker_client.create_network("haproxy-network-primary")  # unnecessary but required by API
    haproxy_volume_primary = dfc.DockerVolume(
        container_mount_point="/usr/local/etc/haproxy",
        host_mount_point=workspace,
        volume=docker_client.create_volume("haproxy-volume-primary")
    )
    print("Starting HAProxy container for Primary Cluster...")
    haproxy_container_primary = docker_client.create_container(
        image=haproxy_image_primary.tag,
        container_name="haproxy-primary",
        network=haproxy_network_primary,
        ports=[dfc.PortMapping(80, 80)],
        volumes=[haproxy_volume_primary],
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
    docker_client.remove_volume(haproxy_volume_primary.volume)

    print("Cleaning up underlying resources for the Shadow HAProxy container...")
    docker_client.stop_container(haproxy_container_shadow)
    docker_client.remove_container(haproxy_container_shadow)
    docker_client.remove_network(haproxy_network_shadow)
    docker_client.remove_volume(haproxy_volume_shadow.volume)


if __name__ == "__main__":
    main()
