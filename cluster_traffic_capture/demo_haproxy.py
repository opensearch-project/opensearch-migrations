#!/usr/bin/env python3
import argparse
import logging
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


def get_command_line_args():
    parser = argparse.ArgumentParser(
        description="Script to build the Primary/Shadow HAProxy Docker images (see README)"
    )

    parser.add_argument('-v', '--verbose',
                        action='store_true',
                        help="Turns on DEBUG-level logging",
                        dest="verbose",
                        default=False
                        )

    return parser.parse_args()


def main():
    # =================================================================================================================
    # Parse/validate args
    # =================================================================================================================
    args = get_command_line_args()
    verbose = args.verbose

    # =================================================================================================================
    # Configure Logging
    # =================================================================================================================
    logging.basicConfig()
    if verbose:
        logging.root.setLevel(logging.DEBUG)

    # =================================================================================================================
    # Pull Necessary State
    # =================================================================================================================
    print("Pulling AWS credentials from ENV variables...")

    print("Pulling ENV variable: AWS_ACCESS_KEY_ID")
    aws_access_key_id = os.environ.get("AWS_ACCESS_KEY_ID")
    if not aws_access_key_id:
        print("ENV variable 'AWS_ACCESS_KEY_ID' not available; please make sure it is exported.")

    print("Pulling ENV variable: AWS_SECRET_ACCESS_KEY")
    aws_secret_access_key = os.environ.get("AWS_SECRET_ACCESS_KEY")
    if not aws_secret_access_key:
        print("ENV variable 'AWS_SECRET_ACCESS_KEY' not available; please make sure it is exported.")

    print("Pulling ENV variable: AWS_SESSION_TOKEN")
    aws_session_token = os.environ.get("AWS_SESSION_TOKEN")
    if not aws_session_token:
        print("ENV variable 'AWS_SESSION_TOKEN' not available; this will cause problems if using temporary creds")

    if not aws_access_key_id or not aws_secret_access_key:
        message = ("The AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY are required for the demo containers to function"
                   " properly.  Please ensure they are correct and exported in your current shell evnironment.")
        raise RuntimeError(message)

    print("Pulling the AWS Region from the ENV variable AWS_REGION...")
    aws_region = os.environ.get("AWS_REGION")
    if not aws_region:
        message = ("The AWS_REGION ENV variable is required for the demo containers to function properly.  Please"
                   " ensure it is correct and exported in your current shell evnironment.")
        raise RuntimeError(message)

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

    # We need to construct an AWS Config file in order to execute the demo.  This is required because the CloudWatch
    # Agent checks to see whether it is running in ECS or On-Prem when it starts up.  The On-Prem path requires
    # this file to supply the AWS Creds and AWS Region to post to.  Spoofing the check and getting it to think it's
    # running in ECS is possible but requires faking the local metadata service.  For our purposes, making and
    # installing the configuration file is easier to get the demo working on a laptop than faking the metadata service
    # and can be done in a way to keep the Docker image(s) we're generating generic to On-Prem vs. ECS.
    #
    # There are a number of approaches we can take to make this configuration file available inside the container.
    # Unfortunately, the easy routes either require writing a real file to disk and mounting it, which presents risks,
    # or embedding it into the Dockerfile, which violates the boundary between the demo and "production" code.
    # Therefore, we instead do it in a bit of a janky way by passing the credentials into the container using ENV
    # variables and using an ENTRYPOINT script override to read them in-container and write the AWS Config file during
    # launch.
    #
    # Why aren't we writing the Config to a Python temporary file we mount into the container?  Good question!  Two
    # problems with them.  First, reading from them is wonky and their behavior is platform-specific.  Second, we still
    # have to handle cleanup and certain situations will still result in the file not getting cleaned up (SIGKILL).
    demo_config_dir_host = os.path.join(demo_dir, "docker_config_demo")
    demo_config_dir_container = "/docker_config_demo"
    demo_entrypoint = os.path.join(demo_config_dir_container, "demo_entrypoint.sh")
    
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

    # Create/Run the Primary HAProxy Container
    haproxy_network_primary = docker_client.create_network("haproxy-network-primary")  # unnecessary but required by API
    print("Starting HAProxy container for Primary Cluster...")
    haproxy_container_primary = docker_client.create_container(
        image=primary_image,
        container_name=primary_image,
        network=haproxy_network_primary,
        ports=[dfc.PortMapping(HAPROXY_INTERNAL_PORT, HAPROXY_PRIMARY_PORT)],
        volumes=[dfc.DockerVolume(demo_config_dir_container, None, demo_config_dir_host)],
        ulimits=[Ulimit(name='memlock', soft=-1, hard=-1)],
        env_kv={
            "AWS_REGION": aws_region,
            "AWS_ACCESS_KEY_ID": aws_access_key_id,
            "AWS_SECRET_ACCESS_KEY": aws_secret_access_key,
            "AWS_SESSION_TOKEN": aws_session_token
        },
        extra_hosts={TAG_DOCKER_HOST: "host-gateway"},
        entrypoint=[demo_entrypoint]
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
