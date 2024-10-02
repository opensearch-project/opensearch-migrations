import json
from typing import Dict
import click
import console_link.middleware.clusters as clusters_
from console_link.environment import Environment

import logging

from console_link.models.cluster import HttpMethod

logger = logging.getLogger(__name__)


def split_url(url: str):
    """Split a URL into domain and path components."""
    parts = url.split("/", 1)
    if len(parts) == 1:
        return parts[0], "/"
    else:
        return parts[0], "/" + parts[1]


def get_cluster_from_name(cluster_name: str, env: Environment):
    if cluster_name == 'source_cluster':
        return env.source_cluster
    elif cluster_name == 'target_cluster':
        return env.target_cluster
    else:
        return None


def parse_headers(header: str) -> Dict:
    headers = {}
    for h in header:
        try:
            key, value = h.split(":", 1)
            headers[key.strip()] = value.strip()
        except ValueError:
            raise click.BadParameter(f"Invalid header format: {h}. Expected format: 'Header: Value'.")
    return headers


@click.command()
@click.option(
    "--config-file", default="/etc/migration_services.yaml", help="Path to config file"
)
@click.option('-v', '--verbose', count=True, help="Verbosity level. Default is warn, -v is info, -vv is debug.")
@click.option('-X', '--request', default='GET', help="HTTP method to use",
              type=click.Choice([m.name for m in HttpMethod]))
@click.option('-H', '--header', multiple=True, help='Pass custom header(s) to the server.')
@click.option('-d', '--data', help='Send specified data in a POST request.')
@click.option('--json', 'json_data', help='Send data as JSON.')
@click.option('--params', multiple=True, help='Pass URL query parameters.')
@click.argument('url', required=True)
def cli(url, config_file, verbose, request, header, data, json_data, params):
    """This implements a small subset of curl commands, formatted for use against configured source or target clusters.
    By default the cluster definition is configured to use the `/etc/migration-services.yaml` file that is pre-prepared
    on the migration console, but `--config-file` can point to any YAML file that defines a `source_cluster` or
    target_cluster` based on the schema of the `services.yaml` file.
    
    In specifying the path of the route, use the name of the YAML object as the domain, followed by the path, e.g.
    `source_cluster/_cat/indices`."""
    logging.basicConfig(level=logging.WARN - (10 * verbose))
    logger.info(f"Logging set to {logging.getLevelName(logger.getEffectiveLevel())}")

    # Parse headers
    headers = parse_headers(header)
    
    if json_data:
        try:
            data = json.dumps(json.loads(json_data))
            headers['Content-Type'] = 'application/json'
        except json.JSONDecodeError:
            raise click.BadParameter("Invalid JSON format.")
    
    env = Environment(config_file)
    cluster_name, path = split_url(url)
    cluster = get_cluster_from_name(cluster_name, env)
    if cluster is None:
        raise ValueError(f"Unknown cluster {cluster_name}. Currently only `source_cluster` and `target_cluster`"
                         "are valid and must also be defined in the config file.")

    response = clusters_.call_api(cluster, path, method=HttpMethod[request], headers=headers, data=data)
    if not response.ok:
        click.echo(f"Error: {response.status_code}")
    click.echo(response.text)
