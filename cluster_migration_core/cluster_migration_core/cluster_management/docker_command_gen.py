from typing import Dict, List

from docker.types import Ulimit

"""
The code in this module can be used to generate Docker CLI commands equivalent to those run by the Python Docker SDK.
This is useful for debugging when things go wrong, as the SDK does not print the full commands at any log level.  The
generation functions will mirror the arguments list of their corresponding SDK command.

Eventually, we may end up using this module to replace the Docker SDK by running these commands directly.  The SDK has
a number of rough edges, like the fact that it doesn't log the actual commands it is running, which makes it tough to
use.
"""


def gen_docker_run(image: str, name: str, network: str, ports: Dict[str, str], volumes: Dict[str, Dict[str, str]],
                   ulimits: List[Ulimit], detach: bool, environment: List[str],
                   extra_hosts: Dict[str, str], entrypoint: List[str]) -> str:
    prefix = "docker run"
    name_section = f"--name {name}"
    network_section = f"--network {network}"
    publish_strs = [f"--publish {host_port}:{container_port}" for container_port, host_port in ports.items()]
    publish_section = " ".join(publish_strs)
    volumes_section = " ".join([f"--volume {k}:{v['bind']}:{v['mode']}" for k, v in volumes.items()])
    ulimits_section = " ".join([f"--ulimit {u.name}={u.soft}:{u.hard}" for u in ulimits])
    environment_section = " ".join([f"--env {entry}" for entry in environment])
    extra_hosts_section = " ".join([f"--add-host {k}:{v}" for k, v in extra_hosts.items()])
    entrypoint_section = " ".join([f"--entrypoint {cmd}" for cmd in entrypoint])
    detach_section = "--detach" if detach else ""
    image_section = image

    command_sections = [
        prefix,
        name_section,
        network_section,
        publish_section,
        volumes_section,
        ulimits_section,
        environment_section,
        extra_hosts_section,
        entrypoint_section,
        detach_section,
        image_section  # Needs to be last
    ]

    return _pretty_join(command_sections, " ")


def _pretty_join(command_sections: List[str], delimiter: str) -> str:
    """
    A quick function to handle empty sections better than the default .join() method.  Avoids inserting spaces when a
    section is blank.
    """
    non_empty_sections = []

    for section in command_sections:
        if section.strip():  # not empty
            non_empty_sections.append(section)

    return delimiter.join(non_empty_sections)
