#!/usr/bin/env python3
import argparse

import os
from pathlib import Path
import shutil

import cluster_traffic_capture.gen_haproxy_cfg as ghc
import cluster_migration_core.cluster_management.docker_framework_client as dfc


DEFAULT_IMAGE_PRIMARY = "haproxy-primary"
DEFAULT_IMAGE_SHADOW = "haproxy-shadow"
DEFAULT_INTERNAL_PORT = 9200
DEFAULT_WORKSPACE = "/tmp/cluster_traffic_capture/"
DOCKERFILES_DIR = "./docker_config_capture"
FILENAME_NO_MIRROR = "haproxy_no_mirror.cfg"
FILENAME_W_MIRROR = "haproxy_w_mirror.cfg"


def get_command_line_args():
    parser = argparse.ArgumentParser(
        description="Script to build the Primary/Shadow HAProxy Docker images (see README)"
    )

    primary_image_help = ("The name (tag) that will be assigned to the built Docker image for the Primary HAProxy"
                          f" instance.  Default: {DEFAULT_IMAGE_PRIMARY}"
                          )
    parser.add_argument("--primary-image",
                        help=primary_image_help,
                        dest="primary_image",
                        default=DEFAULT_IMAGE_PRIMARY
                        )

    primary_nodes_help = ("Space-separated list of hostname:port pairs for the Primary Cluster's Nodes.  The hostnames"
                          " can either be IP addresses or DNS names, as long as it's addressible from the Primary"
                          " HAProxy instance.  Example: '--primary-nodes 2.3.4.5:9200 primary.node:9200'"
                          )
    parser.add_argument("--primary-nodes",
                        help=primary_nodes_help,
                        dest="primary_nodes",
                        required=True,
                        nargs='+'
                        )

    shadow_haproxy_help = ("The hostname:port pair for the Shadow HAProxy instance.  This is used by the Primary"
                           " HAProxy instance as the location to mirror traffic to, and should be addressible by the"
                           " Primary HAProxy instance.  Example: '--shadow-haproxy 1.2.3.4:9200'"
                           )
    parser.add_argument("--shadow-haproxy",
                        help=shadow_haproxy_help,
                        dest="shadow_haproxy",
                        required=True
                        )

    shadow_image_help = ("The name (tag) that will be assigned to the built Docker image for the Shadow HAProxy"
                         f" instance.  Default: {DEFAULT_IMAGE_SHADOW}"
                         )
    parser.add_argument("--shadow-image",
                        help=shadow_image_help,
                        dest="shadow_image",
                        default=DEFAULT_IMAGE_SHADOW
                        )

    shadow_nodes_help = ("Space-separated list of hostname:port pairs for the Shadow Cluster's Nodes.  The hostnames"
                         " can either be IP addresses or DNS names, as long as it's addressible from the Shadow"
                         " HAProxy instance.  Example: '--shadow-nodes 1.2.3.4:9200 my.node:9200'"
                         )
    parser.add_argument("--shadow-nodes",
                        help=shadow_nodes_help,
                        dest="shadow_nodes",
                        required=True,
                        nargs='+'
                        )

    workspace_help = ("Path to the working directory where the Docker-related files will be copied and the Docker"
                      f" images built out of.  Will be created if it doesn't exist.  Default: {DEFAULT_WORKSPACE}"
                      )
    parser.add_argument("--workspace",
                        help=workspace_help,
                        dest="workspace",
                        default=DEFAULT_WORKSPACE
                        )

    internal_port_help = ("Port number that the HAProxy Servers will listen for traffic on inside of their containers."
                          "  This is the port you will need to provide an external mapping for to pipe traffic from"
                          f" the host's network to the container's network.  Default: {DEFAULT_INTERNAL_PORT}"
                          )
    parser.add_argument("--internal-port",
                        help=internal_port_help,
                        dest="internal_port",
                        default=DEFAULT_INTERNAL_PORT
                        )

    return parser.parse_args()


def main():
    # =================================================================================================================
    # Parse/validate args
    # =================================================================================================================
    args = get_command_line_args()
    primary_image = args.primary_image
    primary_nodes = [ghc.HostAddress.from_str(address) for address in args.primary_nodes]
    shadow_haproxy = ghc.HostAddress.from_str(args.shadow_haproxy)
    shadow_image = args.shadow_image
    shadow_nodes = [ghc.HostAddress.from_str(address) for address in args.shadow_nodes]

    workspace_path = args.workspace
    haproxy_internal_port = args.internal_port

    # =================================================================================================================
    # Build the Docker Images
    # =================================================================================================================
    docker_client = dfc.DockerFrameworkClient()
    
    # Set up our local workspace
    workspace = Path(workspace_path)
    workspace.mkdir(exist_ok=True)

    # Set Python's working directory to something predictable (this file's directory) so we can copy Docker-related
    # files into the workspace, then perform the copy
    demo_dir = os.path.dirname(__file__)
    os.chdir(demo_dir)

    docker_files_dir = DOCKERFILES_DIR
    files = os.listdir(docker_files_dir)
    print(f"Copying Docker-related files to: {workspace}")
    for file_name in files:
        shutil.copy2(os.path.join(docker_files_dir, file_name), workspace)
    
    # Write our the base HAProxy Config File (no mirroring) to our workspace
    haproxy_config_str = ghc.gen_haproxy_config_base(haproxy_internal_port, shadow_nodes)
    haproxy_config_path = os.path.join(workspace, FILENAME_NO_MIRROR)
    with open(haproxy_config_path, "w") as haproxy_config_file:
        print(f"Writing HAProxy Config to: {haproxy_config_path}")
        haproxy_config_file.write(haproxy_config_str)

    # Write our the t-split HAProxy Config File (with mirroring) to our workspace
    haproxy_mirror_config_str = ghc.gen_haproxy_config_mirror(
        haproxy_internal_port,
        primary_nodes,
        shadow_haproxy
    )
    haproxy_mirror_config_path = os.path.join(workspace, FILENAME_W_MIRROR)
    with open(haproxy_mirror_config_path, "w") as haproxy_config_file:
        print(f"Writing HAProxy Config to: {haproxy_mirror_config_path}")
        haproxy_config_file.write(haproxy_mirror_config_str)

    # Change directory again to the workspace then build our Docker image
    os.chdir(workspace)

    # Build the Docker images for the Primary HAProxy
    print("Building HAProxy Docker image for Primary Cluster...")
    haproxy_image_primary = docker_client.build_image(str(workspace), primary_image, "haproxy-w-mirror")
    print(f"Primary HAProxy image available locally w/ tag: {haproxy_image_primary.tag}")

    # Build the Docker images for the Shadow HAProxy
    print("Building HAProxy Docker image for Shadow Cluster...")
    haproxy_image_shadow = docker_client.build_image(str(workspace), shadow_image, "haproxy-no-mirror")
    print(f"Shadow HAProxy image available locally w/ tag: {haproxy_image_shadow.tag}")


if __name__ == "__main__":
    main()
