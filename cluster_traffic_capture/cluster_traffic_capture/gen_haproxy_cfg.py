from dataclasses import dataclass
from typing import List


SPOA_LISTEN_PORT = 12345
SPOA_LISTEN_URL = "127.0.0.1"


@dataclass
class HostAddress:
    host: str
    port: int

    @classmethod
    def from_str(cls, address_str: str):
        try:
            host, port = address_str.split(":")
            return cls(host, int(port))
        except (IndexError, ValueError):
            raise ValueError(f"Invalid host:port combination: {address_str}")


def _gen_global_config() -> str:
    """
    Generates the HAProxy global/default configuration.
    """

    return """
global
    # Logging configuration
    log 127.0.0.1:514 len 65535 local0 # syslogd, facility local0
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

"""


def _get_fe_config_haproxy(haproxy_port: int, mirror: bool) -> str:
    """
    Generates the frontend configuration for the HAProxy server (e.g. the rules for it to accept traffic).  If the
    mirror argument is set to True, the generated configuration will also support mirroring.
    """

    config = f"""
# This section outlines the "frontend" for this instance of HAProxy and specifies the rules by which it receives
# traffic
frontend haproxy
    bind :{haproxy_port}

    # Set up the logging for the req/res stream to the primary cluster
    declare capture request len 80000
    declare capture response len 80000
    http-request capture req.body id 0
    log-format Request-URI:\\ %[capture.req.uri]\\nRequest-Method:\\ %[capture.req.method]\\nRequest-Body:\\ %[capture.req.hdr(0)]\\nResponse-Body:\\ %[capture.res.hdr(0)]
    
    # Associate this frontend with the primary cluster
    default_backend primary_cluster

"""

    mirror_config = """
    # Required for the mirror SPOA to have access to the request
    option http-buffer-request

    # Tell HAProxy to also send traffic to this frontend to the mirror agent too
    filter spoe engine mirror config /usr/local/etc/haproxy/mirror.conf

"""

    return config if not mirror else "\n".join([config, mirror_config])


def _gen_be_config_cluster(cluster_nodes: List[HostAddress]) -> str:
    """
    Generates the backend configuration to route traffic to the cluster's nodes.  Will look something like:

    backend primary_cluster
        http-response capture res.body id 0
        server s9200 1.2.3.4:9200 check
        server s9201 1.2.3.4:9201 check
    """

    # Generate the backend
    backend_server_lines = []
    for node in cluster_nodes:
        backend_server_lines.append(f"    server s{node.port} {node.host}:{node.port} check")
    backend_server_section = "\n".join(backend_server_lines)

    # Construct the URL to send mirrored traffic to
    config = f"""
# These are the primary Cluster's Nodes; traffic will be sent to them synchronously.  The default round robin LB
# pattern is in effect.
backend primary_cluster
    http-response capture res.body id 0
{backend_server_section}
"""
    return config


def gen_haproxy_config_base(haproxy_port: int, cluster_nodes: List[HostAddress]) -> str:
    """
    Generates HAProxy configuration for a host that performs synchronous pass-through of traffic to a cluster while
    capturing request/response traffic.

    Taken from the HAProxy guide here: https://www.haproxy.com/blog/how-to-run-haproxy-with-docker/
    And here: https://www.haproxy.com/blog/haproxy-traffic-mirroring-for-real-world-testing/
    """

    config_sections = [
        _gen_global_config(),
        _get_fe_config_haproxy(haproxy_port, False),
        _gen_be_config_cluster(cluster_nodes)
    ]
    
    return "\n".join(config_sections)


def _get_program_config(mirror_host: HostAddress) -> str:
    """
    Generate the subprocess configuration used to start the mirroring SPOA.
    """

    shadow_url = f"http://{mirror_host.host}:{mirror_host.port}"

    return f"""
# Tells HAProxy to also start the mirroring SPOA as a daemon when it starts up
program mirror
    command spoa-mirror --runtime 0 --address {SPOA_LISTEN_URL} --port {SPOA_LISTEN_PORT} --mirror-url {shadow_url}

"""


def _get_be_config_mirroring() -> str:
    return f"""
# Mirror agents
backend mirroragents
    mode tcp
    balance roundrobin
    timeout connect 5s
    timeout server 5s
    server agent1 {SPOA_LISTEN_URL}:{SPOA_LISTEN_PORT}
"""


def gen_haproxy_config_mirror(haproxy_port: int, cluster_nodes: List[HostAddress], mirror_host: HostAddress) -> str:
    """
    Generates HAProxy configuration for a host that performs synchronous pass-through of traffic to a cluster while
    capturing request/response traffic as well as mirroring that traffic to another specified host.

    Taken from the HAProxy guide here: https://www.haproxy.com/blog/how-to-run-haproxy-with-docker/
    And here: https://www.haproxy.com/blog/haproxy-traffic-mirroring-for-real-world-testing/
    """

    config_sections = [
        _gen_global_config(),
        _get_fe_config_haproxy(haproxy_port, True),
        _gen_be_config_cluster(cluster_nodes),
        _get_program_config(mirror_host),
        _get_be_config_mirroring()
    ]
    
    return "\n".join(config_sections)
