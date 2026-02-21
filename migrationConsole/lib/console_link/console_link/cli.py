# pyright: ignore[reportCallIssue]
from contextlib import contextmanager
import json
from pprint import pprint
import sys
import time
from typing import Dict
import click

import console_link.middleware.clusters as clusters_
import console_link.middleware.metrics as metrics_
import console_link.middleware.backfill as backfill_
import console_link.middleware.snapshot as snapshot_
import console_link.middleware.metadata as metadata_
import console_link.middleware.replay as replay_
import console_link.middleware.kafka as kafka_
import console_link.middleware.tuples as tuples_

from console_link.models.container_utils import get_version_str
from console_link.models.cluster import HttpMethod
from console_link.models.backfill_rfs import RfsWorkersInProgress, WorkingIndexDoesntExist
from console_link.models.utils import DEFAULT_SNAPSHOT_REPO_NAME, ExitCode
from console_link.environment import Environment
from console_link.models.metrics_source import Component, MetricStatistic
from console_link.workflow.models.workflow_config_store import WorkflowConfigStore
from console_link.workflow.models.utils import KubernetesConfigNotFoundError
from click.shell_completion import get_completion_class
from click.core import ParameterSource

import logging
import os

logger = logging.getLogger(__name__)

DISABLE_LEGACY_COMMANDS = os.getenv("MIGRATIONS_CLI_CONSOLE_DISABLE_LEGACY_COMMANDS", "").lower() == "true"

# ################### UNIVERSAL ####################


@contextmanager
def temporarily_disable_logging():
    """
    A context manager that temporarily disables logging.
    """
    logging.disable(logging.CRITICAL)
    try:
        yield
    finally:
        logging.disable(logging.NOTSET)


def can_use_k8s_config_store():
    # disable logging before making this call to prevent the `Invalid kube-config file.`
    # error message from confusing the user.
    with temporarily_disable_logging():
        try:
            WorkflowConfigStore()
            return True
        except KubernetesConfigNotFoundError:
            return False


class Context(object):
    def __init__(self, config_file: str, force_use_config_file: bool = False,
                 allow_empty_workflow_config: bool = False) -> None:
        # Expanding this to include handling `workflow` config objects, for a k8s deployment.
        # Even if we _can_ use the k8s config store, we don't if MIGRATION_USE_SERVICES_YAML_CONFIG is set
        # or if `--force-use-config-file` is passed in.
        if can_use_k8s_config_store() and not force_use_config_file:
            logger.info("Assuming k8s deployment, loading cluster information from workflow config")
            self.env = Environment.from_workflow_config(allow_empty=allow_empty_workflow_config)
            return
        self.config_file = config_file
        try:
            self.env = Environment(config_file=config_file, allow_empty=allow_empty_workflow_config)
        except Exception as e:
            raise click.ClickException(str(e))
        self.json = False


@click.group(invoke_without_command=True)
@click.option("--config-file", default="/config/migration_services.yaml", help="Path to config file")
@click.option("--force-use-config-file", is_flag=True,
              help="Force use of config file, even if k8s deployment is detected.")
@click.option("--json", is_flag=True)
@click.option('-v', '--verbose', count=True, help="Verbosity level. Default is warn, -v is info, -vv is debug.")
@click.option("--version", is_flag=True, is_eager=True, help="Show the Migration Assistant version.")
@click.pass_context
def cli(ctx, config_file: str, force_use_config_file: bool, json: bool, verbose: int, version: bool):
    if version:
        click.echo(get_version_str())
        ctx.exit(0)

    # Enforce command required unless --version was passed
    if ctx.invoked_subcommand is None:
        click.echo("Error: Missing command.", err=True)
        click.echo(cli.get_help(ctx))
        ctx.exit(2)

    logging.basicConfig(level=logging.WARN - (10 * verbose))
    logger.info(f"Logging set to {logging.getLevelName(logger.getEffectiveLevel())}")

    # Set the `force_use_config_file` based on the CLI flag OR the env var MIGRATION_USE_SERVICES_YAML_CONFIG
    #  OR if --config-file was passed on the command line
    force_use_config_file = (force_use_config_file or
                             (os.getenv("MIGRATION_USE_SERVICES_YAML_CONFIG", "false") not in ("false", 0)) or
                             (ctx.get_parameter_source("config_file") == ParameterSource.COMMANDLINE))
    logger.info(f"force_use_config_file set to {force_use_config_file}")
    # Disabling all logging for gathering the context for shell completion (run automatically)
    # on container startup
    if ctx.invoked_subcommand == 'completion':
        with temporarily_disable_logging():
            ctx.obj = Context(config_file, force_use_config_file, allow_empty_workflow_config=True)
    else:
        ctx.obj = Context(config_file, force_use_config_file)

    ctx.obj.json = json


# Create a wrapper to handle exceptions for the CLI
def main():
    try:
        cli()
    except Exception as e:
        # Check if verbose mode is enabled by looking at the root logger level
        # Verbose mode sets logging level to INFO (20) or DEBUG (10), default is WARN (30)
        root_logger = logging.getLogger()
        if root_logger.getEffectiveLevel() <= logging.INFO:
            # Verbose mode is enabled, show full traceback
            import traceback
            click.echo("Error occurred with verbose mode enabled, showing full traceback:", err=True)
            click.echo(traceback.format_exc(), err=True)
        else:
            # Normal mode, show clean error message
            click.echo(f"Error: {str(e)}", err=True)
        sys.exit(1)


# ##################### CLUSTERS ###################


@click.group(name="clusters", help="Commands to interact with source and target clusters")
@click.pass_obj
def cluster_group(ctx):
    if ctx.env.source_cluster is None and ctx.env.target_cluster is None:
        raise click.UsageError("Neither source nor target cluster is defined.")


@cluster_group.command(name="cat-indices")
@click.option("--refresh", is_flag=True, default=False)
@click.pass_obj
def cat_indices_cmd(ctx, refresh):
    """Simple program that calls `_cat/indices` on both a source and target cluster."""
    if ctx.json:
        click.echo(
            json.dumps(
                {
                    "source_cluster": clusters_.cat_indices(
                        ctx.env.source_cluster, as_json=True, refresh=refresh
                    ) if ctx.env.source_cluster else None,
                    "target_cluster": clusters_.cat_indices(
                        ctx.env.target_cluster, as_json=True, refresh=refresh
                    ) if ctx.env.target_cluster else None,
                }
            )
        )
        return

    if not refresh:
        click.echo("\nWARNING: Cluster information may be stale. Use --refresh to update.\n")
    click.echo("SOURCE CLUSTER")
    if ctx.env.source_cluster:
        click.echo(clusters_.cat_indices(ctx.env.source_cluster, refresh=refresh))
    else:
        click.echo("No source cluster defined.")
    click.echo("TARGET CLUSTER")
    if ctx.env.target_cluster:
        click.echo(clusters_.cat_indices(ctx.env.target_cluster, refresh=refresh))
    else:
        click.echo("No target cluster defined.")


@cluster_group.command(name="connection-check")
@click.pass_obj
def connection_check_cmd(ctx):
    """Checks if a connection can be established to source and target clusters"""
    click.echo("SOURCE CLUSTER")
    if ctx.env.source_cluster:
        click.echo(clusters_.connection_check(ctx.env.source_cluster))
    else:
        click.echo("No source cluster defined.")
    click.echo("TARGET CLUSTER")
    if ctx.env.target_cluster:
        click.echo(clusters_.connection_check(ctx.env.target_cluster))
    else:
        click.echo("No target cluster defined.")


@cluster_group.command(name="run-test-benchmarks")
@click.pass_obj
def run_test_benchmarks_cmd(ctx):
    """Run a series of OpenSearch Benchmark workloads against the source cluster"""
    if not ctx.env.source_cluster:
        raise click.UsageError("Cannot run test benchmarks because no source cluster is defined.")
    click.echo(clusters_.run_test_benchmarks(ctx.env.source_cluster))


@cluster_group.command(name="clear-indices")
@click.option("--acknowledge-risk", is_flag=True, show_default=True, default=False,
              help="Flag to acknowledge risk and skip confirmation")
@click.option('--cluster',
              type=click.Choice(['source', 'target'], case_sensitive=False),
              help="Cluster to perform clear indices action on",
              required=True)
@click.pass_obj
def clear_indices_cmd(ctx, acknowledge_risk, cluster):
    """[Caution] Clear indices on a source or target cluster"""
    cluster_focus = ctx.env.source_cluster if cluster.lower() == 'source' else ctx.env.target_cluster
    if not cluster_focus:
        raise click.UsageError(f"No {cluster.lower()} cluster defined.")
    if acknowledge_risk:
        click.echo("Performing clear indices operation...")
        click.echo(clusters_.clear_indices(cluster_focus))
    else:
        if click.confirm(f'Clearing indices WILL result in the loss of all data on the {cluster.lower()} cluster. '
                         f'Are you sure you want to continue?'):
            click.echo(f"Performing clear indices operation on {cluster.lower()} cluster...")
            click.echo(clusters_.clear_indices(cluster_focus))
        else:
            click.echo("Aborting command.")


def parse_headers(header: str) -> Dict:
    headers = {}
    for h in header:
        try:
            key, value = h.split(":", 1)
            headers[key.strip()] = value.strip()
        except ValueError:
            raise click.BadParameter(f"Invalid header format: {h}. Expected format: 'Header: Value'.")
    return headers


@cluster_group.command(name="curl")
@click.option('-X', '--request', default='GET', help="HTTP method to use",
              type=click.Choice([m.name for m in HttpMethod]))
@click.option('-H', '--header', multiple=True, help='Pass custom header(s) to the server.')
@click.option('-d', '--data', help='Send specified data in a POST request.')
@click.option('--json', 'json_data', help='Send data as JSON.')
@click.option('--timeout', type=int, default=15, show_default=True,
              help='Request timeout in seconds.')
@click.argument('cluster', required=True, type=click.Choice(['target_cluster', 'source_cluster'], case_sensitive=False))
@click.argument('path', required=True)
@click.pass_obj
def cluster_curl_cmd(ctx, cluster, path, request, header, data, json_data, timeout):
    """This implements a small subset of curl commands, formatted for use against configured source or target clusters.
    By default the cluster definition is configured to use the `/config/migration_services.yaml` file that is
    pre-prepared on the migration console, but `--config-file` can point to any YAML file that defines a
    source_cluster` or target_cluster` based on the schema of the `services.yaml` file.

    In specifying the path of the route, use the name of the YAML object as the domain, followed by a space and the
    path, e.g. `source_cluster /_cat/indices`."""

    headers = parse_headers(header)

    if json_data:
        try:
            data = json.dumps(json.loads(json_data))
            headers['Content-Type'] = 'application/json'
        except json.JSONDecodeError:
            raise click.BadParameter("Invalid JSON format.")

    try:
        cluster = ctx.env.__getattribute__(cluster)
        if cluster is None:
            raise AttributeError
    except AttributeError:
        raise click.BadArgumentUsage(f"Unknown cluster {cluster}. Currently only `source_cluster` and "
                                     "`target_cluster` are valid and must also be defined in the config file.")

    if path[0] != '/':
        path = '/' + path

    result: clusters_.CallAPIResult = clusters_.call_api(cluster, path, method=HttpMethod[request],
                                                         headers=headers, data=data, timeout=timeout)
    if result.error_message:
        click.echo(result.error_message)
    else:
        response = result.http_response
        if not response.ok:
            click.echo(f"Error: {response.status_code}")
        click.echo(response.text)


@cluster_group.command(name="generate-data")
@click.option('--cluster', type=click.Choice(['source_cluster', 'target_cluster'], case_sensitive=False),
              required=True, help='Target cluster for data generation')
@click.option('--index-name', required=True, help='Name of the index to populate')
@click.option('--doc-size-bytes', type=int, default=150, help='Approximate size of each document in bytes')
@click.option('--num-docs', type=int, help='Total number of documents to generate')
@click.option('--target-size-mb', type=float, help='Target total size in MB (alternative to num-docs)')
@click.option('--batch-size', type=int, default=100, help='Number of documents per batch request')
@click.pass_obj
def generate_data_cmd(ctx, cluster, index_name, doc_size_bytes, num_docs, target_size_mb, batch_size):
    """Generate bulk test data in the specified cluster and index"""
    
    # Validate arguments
    if not num_docs and not target_size_mb:
        raise click.UsageError("Either --num-docs or --target-size-mb must be specified")
    
    if num_docs and target_size_mb:
        raise click.UsageError("Cannot specify both --num-docs and --target-size-mb")
    
    # Calculate num_docs from target size if needed
    if target_size_mb:
        target_bytes = target_size_mb * 1024 * 1024
        num_docs = int(target_bytes / doc_size_bytes)
        click.echo(f"Target size: {target_size_mb}MB = ~{num_docs:,} documents")
    
    # Get the cluster object
    try:
        cluster_obj = ctx.env.__getattribute__(cluster)
        if cluster_obj is None:
            raise AttributeError
    except AttributeError:
        raise click.BadArgumentUsage(f"Unknown cluster {cluster}. Currently only `source_cluster` and "
                                     "`target_cluster` are valid and must also be defined in the config file.")
    
    click.echo(f"Generating {num_docs:,} documents in index '{index_name}' on {cluster}")
    click.echo(f"Document size: ~{doc_size_bytes} bytes, Batch size: {batch_size}")
    
    # Import bulk generation function
    try:
        # Try container path first, then dev path
        import os
        import importlib.util
        
        # Container path (copied during Docker build)
        container_path = "/root/testDocumentGenerator.py"
        # Dev path (relative to current file)
        dev_path = os.path.join(
            os.path.dirname(__file__),
            '../../../TrafficCapture/dockerSolution/src/main/docker/elasticsearchTestConsole/testDocumentGenerator.py')
        
        script_path = container_path if os.path.exists(container_path) else dev_path
        
        spec = importlib.util.spec_from_file_location("testDocumentGenerator", script_path)
        test_module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(test_module)
        bulk_insert_data = test_module.bulk_insert_data
        
        # Execute bulk data generation
        result = bulk_insert_data(cluster_obj, index_name, num_docs, doc_size_bytes, batch_size)
        
        # Display results
        click.echo("\nData generation completed:")
        click.echo(f"  Documents inserted: {result['total_inserted']:,}")
        click.echo(f"  Errors: {result['total_errors']:,}")
        click.echo(f"  Time elapsed: {result['elapsed_time']:.1f}s")
        click.echo(f"  Rate: {result['docs_per_sec']:.1f} docs/sec")
        click.echo(f"  Estimated size: {result['estimated_size_mb']:.1f}MB")
        
        if result['total_errors'] > 0:
            click.echo(f"Warning: {result['total_errors']} documents failed to insert")
            
    except ImportError as e:
        raise click.ClickException(f"Failed to import bulk data generation module: {e}")
    except Exception as e:
        raise click.ClickException(f"Error during data generation: {e}")


# ##################### SNAPSHOT ###################


def _external_snapshots_check(snapshot):
    if snapshot.snapshot_repo_name != DEFAULT_SNAPSHOT_REPO_NAME:
        logger.warning(f"External snapshot detected, normally snapshot commands are not necessary for external "
                       f"snapshots. The snapshot repository: '{snapshot.snapshot_repo_name}' must belong to "
                       f"the source cluster as snapshot commands will perform requests to the source cluster")


@click.group(name="snapshot",
             help="Commands to create and check status of snapshots of the source cluster.")
@click.pass_obj
def snapshot_group(ctx):
    """All actions related to snapshot creation"""
    if ctx.env.snapshot is None:
        raise click.UsageError("Snapshot is not set")
    _external_snapshots_check(ctx.env.snapshot)


@snapshot_group.command(name="create", context_settings={'ignore_unknown_options': True})
@click.option('--wait', is_flag=True, default=False, help='Wait for snapshot completion')
@click.option('--max-snapshot-rate-mb-per-node', type=int, default=None,
              help='Maximum snapshot rate in MB/s per node')
@click.argument('extra_args', nargs=-1, type=click.UNPROCESSED)
@click.pass_obj
def create_snapshot_cmd(ctx, wait, max_snapshot_rate_mb_per_node, extra_args):
    """Create a snapshot of the source cluster"""
    snapshot = ctx.env.snapshot
    result = snapshot_.create(snapshot, wait=wait,
                              max_snapshot_rate_mb_per_node=max_snapshot_rate_mb_per_node,
                              extra_args=extra_args)
    click.echo(result.value)


@snapshot_group.command(name="status")
@click.option('--deep-check', is_flag=True, default=False, help='Perform a deep status check of the snapshot')
@click.pass_obj
def status_snapshot_cmd(ctx, deep_check):
    """Check the status of the snapshot"""
    result = snapshot_.status(ctx.env.snapshot, deep_check=deep_check)
    click.echo(result.value)


@snapshot_group.command(name="delete")
@click.option("--acknowledge-risk", is_flag=True, show_default=True, default=False,
              help="Flag to acknowledge risk and skip confirmation")
@click.pass_obj
def delete_snapshot_cmd(ctx, acknowledge_risk: bool):
    """Delete the snapshot"""
    if not acknowledge_risk:
        confirmed = click.confirm('If you proceed with deleting the snapshot, the cluster will delete underlying local '
                                  'and remote files associated with the snapshot. Are you sure you want to continue?')
        if not confirmed:
            click.echo("Aborting the command to delete snapshot.")
            return
    logger.info("Deleting snapshot")
    result = snapshot_.delete(ctx.env.snapshot)
    click.echo(result.value)


@snapshot_group.command(name="unregister-repo")
@click.option("--acknowledge-risk", is_flag=True, show_default=True, default=False,
              help="Flag to acknowledge risk and skip confirmation")
@click.pass_obj
def unregister_snapshot_repo_cmd(ctx, acknowledge_risk: bool):
    """Remove the snapshot repository"""
    if not acknowledge_risk:
        confirmed = click.confirm('If you proceed with unregistering the snapshot repository, the cluster will '
                                  'deregister the existing snapshot repository but will not perform cleanup of '
                                  'existing snapshot files that may exist. To remove the existing snapshot files '
                                  '"console snapshot delete" must be used while this repository still exists. '
                                  'Are you sure you want to continue?')
        if not confirmed:
            click.echo("Aborting the command to remove snapshot repository.")
            return
    logger.info("Removing snapshot repository")
    result = snapshot_.delete_snapshot_repo(ctx.env.snapshot)
    click.echo(result.value)

# ##################### BACKFILL ###################

# As we add other forms of backfill migrations, we should incorporate a way to dynamically allow different sets of
# arguments depending on the type of backfill migration


@click.group(name="backfill", help="Commands related to controlling the configured backfill mechanism.")
@click.pass_obj
def backfill_group(ctx):
    """All actions related to historical/backfill data migrations"""
    if ctx.env.backfill is None:
        raise click.UsageError("Backfill migration is not set")


@backfill_group.command(name="describe")
@click.pass_obj
def describe_backfill_cmd(ctx):
    click.echo(backfill_.describe(ctx.env.backfill, as_json=ctx.json))


@backfill_group.command(name="start")
@click.option('--pipeline-name', default=None, help='Optionally specify a pipeline name')
@click.pass_obj
def start_backfill_cmd(ctx, pipeline_name):
    exitcode, message = backfill_.start(ctx.env.backfill, pipeline_name=pipeline_name)
    if exitcode != ExitCode.SUCCESS:
        raise click.ClickException(message)
    click.echo(message)


@backfill_group.command(name="pause")
@click.option('--pipeline-name', default=None, help='Optionally specify a pipeline name')
@click.pass_obj
def pause_backfill_cmd(ctx, pipeline_name):
    exitcode, message = backfill_.pause(ctx.env.backfill, pipeline_name=pipeline_name)
    if exitcode != ExitCode.SUCCESS:
        raise click.ClickException(message)
    click.echo(message)


@backfill_group.command(name="stop")
@click.option('--pipeline-name', default=None, help='Optionally specify a pipeline name')
@click.pass_obj
def stop_backfill_cmd(ctx, pipeline_name):
    exitcode, message = backfill_.stop(ctx.env.backfill, pipeline_name=pipeline_name)
    if exitcode != ExitCode.SUCCESS:
        raise click.ClickException(message)
    click.echo(message)

    click.echo("Archiving the working state of the backfill operation...")
    exitcode, message = backfill_.archive(ctx.env.backfill)

    if isinstance(message, WorkingIndexDoesntExist):
        click.echo("Working state index doesn't exist, skipping archive operation.")
        return

    while isinstance(message, RfsWorkersInProgress):
        click.echo("RFS Workers are still running, waiting for them to complete...")
        time.sleep(5)
        exitcode, message = backfill_.archive(ctx.env.backfill)

    if exitcode != ExitCode.SUCCESS:
        raise click.ClickException(message)
    click.echo(f"Backfill working state archived to: {message}")


@backfill_group.command(name="scale")
@click.argument("units", type=int, required=True)
@click.pass_obj
def scale_backfill_cmd(ctx, units: int):
    exitcode, message = backfill_.scale(ctx.env.backfill, units)
    if exitcode != ExitCode.SUCCESS:
        raise click.ClickException(message)
    click.echo(message)


@backfill_group.command(name="status")
@click.option('--deep-check', is_flag=True, help='Perform a deep status check of the backfill')
@click.pass_obj
def status_backfill_cmd(ctx, deep_check):
    logger.info(f"Called `console backfill status`, with {deep_check=}")
    exitcode, message = backfill_.status(ctx.env.backfill, deep_check=deep_check)
    if exitcode != ExitCode.SUCCESS:
        raise click.ClickException(message)
    click.echo(message)


# ##################### REPLAY ###################

@click.group(name="replay", help="Commands related to controlling the replayer.")
@click.pass_obj
def replay_group(ctx):
    """All actions related to replaying data"""
    if ctx.env.replay is None:
        raise click.UsageError("Replay is not set")


@replay_group.command(name="describe")
@click.pass_obj
def describe_replay_cmd(ctx):
    click.echo(replay_.describe(ctx.env.replay, as_json=ctx.json))


@replay_group.command(name="start")
@click.pass_obj
def start_replay_cmd(ctx):
    exitcode, message = replay_.start(ctx.env.replay)
    if exitcode != ExitCode.SUCCESS:
        raise click.ClickException(message)
    click.echo(message)


@replay_group.command(name="stop")
@click.pass_obj
def stop_replay_cmd(ctx):
    exitcode, message = replay_.stop(ctx.env.replay)
    if exitcode != ExitCode.SUCCESS:
        raise click.ClickException(message)
    click.echo(message)


@replay_group.command(name="scale")
@click.argument("units", type=int, required=True)
@click.pass_obj
def scale_replay_cmd(ctx, units: int):
    exitcode, message = replay_.scale(ctx.env.replay, units)
    if exitcode != ExitCode.SUCCESS:
        raise click.ClickException(message)
    click.echo(message)


@replay_group.command(name="status")
@click.pass_obj
def status_replay_cmd(ctx):
    exitcode, message = replay_.status(ctx.env.replay)
    if exitcode != ExitCode.SUCCESS:
        raise click.ClickException(message)
    click.echo(message)


# ##################### METADATA ###################


@click.group(name="metadata", help="Commands related to migrating metadata to the target cluster.")
@click.pass_obj
def metadata_group(ctx):
    """All actions related to metadata migration"""
    if ctx.env.metadata is None:
        raise click.UsageError("Metadata is not set")


@metadata_group.command(name="migrate", context_settings={
    'ignore_unknown_options': True,
    'help_option_names': []
})
@click.argument('extra_args', nargs=-1, type=click.UNPROCESSED)
@click.pass_obj
def migrate_metadata_cmd(ctx, extra_args):
    exitcode, message = metadata_.migrate(ctx.env.metadata, extra_args)
    if exitcode != ExitCode.SUCCESS:
        raise click.ClickException(message)
    click.echo(message)


@metadata_group.command(name="evaluate", context_settings={
    'ignore_unknown_options': True,
    'help_option_names': []
})
@click.argument('extra_args', nargs=-1, type=click.UNPROCESSED)
@click.pass_obj
def evaluate_metadata_cmd(ctx, extra_args):
    exitcode, message = metadata_.evaluate(ctx.env.metadata, extra_args)
    if exitcode != ExitCode.SUCCESS:
        raise click.ClickException(message)
    click.echo(message)

# ##################### METRICS ###################


@click.group(name="metrics", help="Commands related to checking metrics emitted by the capture proxy and replayer.")
@click.pass_obj
def metrics_group(ctx):
    if ctx.env.metrics_source is None:
        raise click.UsageError("Metrics source is not set")


@metrics_group.command(name="list")
@click.pass_obj
def list_metrics_cmd(ctx):
    if ctx.json:
        click.echo(json.dumps(ctx.env.metrics_source.get_metrics()))
        return
    pprint(ctx.env.metrics_source.get_metrics())


@metrics_group.command(name="get-data")
@click.argument("component", type=click.Choice([c.value for c in Component]))
@click.argument("metric_name")
@click.option(
    "--statistic",
    type=click.Choice([s.name for s in MetricStatistic]),
    default="Average",
)
@click.option("--lookback", type=int, default=60, help="Lookback in minutes")
@click.pass_obj
def get_metrics_data_cmd(ctx, component, metric_name, statistic, lookback):
    metric_data = metrics_.get_metric_data(
        ctx.env.metrics_source,
        component,
        metric_name,
        statistic,
        lookback
    )
    if ctx.json:
        click.echo(json.dumps(metric_data))
        return

    click.echo(f"Component: {component}")
    click.echo(f"Metric Name: {metric_name}")
    click.echo(f"Statistic: {statistic}")
    click.echo(f"Lookback: {lookback} minutes")
    click.echo(f"Metrics Source Type: {type(ctx.env.metrics_source)}")
    pprint(
        metric_data
    )

# ##################### KAFKA ###################


@click.group(name="kafka")
@click.pass_obj
def kafka_group(ctx):
    """All actions related to Kafka operations"""
    if ctx.env.kafka is None:
        raise click.UsageError("Kafka is not set")


@kafka_group.command(name="create-topic")
@click.option('--topic-name', default="logging-traffic-topic", help='Specify a topic name to create')
@click.pass_obj
def create_topic_cmd(ctx, topic_name):
    result = kafka_.create_topic(ctx.env.kafka, topic_name=topic_name)
    click.echo(result.value)


@kafka_group.command(name="delete-topic")
@click.option("--acknowledge-risk", is_flag=True, show_default=True, default=False,
              help="Flag to acknowledge risk and skip confirmation")
@click.option('--topic-name', default="logging-traffic-topic", help='Specify a topic name to delete')
@click.pass_obj
def delete_topic_cmd(ctx, acknowledge_risk, topic_name):
    if acknowledge_risk:
        result = kafka_.delete_topic(ctx.env.kafka, topic_name=topic_name)
        click.echo(result.value)
    else:
        if click.confirm('Deleting a topic will irreversibly delete all captured traffic records stored in that '
                         'topic. Are you sure you want to continue?'):
            click.echo(f"Performing delete topic operation on {topic_name} topic...")
            result = kafka_.delete_topic(ctx.env.kafka, topic_name=topic_name)
            click.echo(result.value)
        else:
            click.echo("Aborting command.")


@kafka_group.command(name="describe-consumer-group")
@click.option('--group-name', default="logging-group-default", help='Specify a group name to describe')
@click.pass_obj
def describe_group_command(ctx, group_name):
    result = kafka_.describe_consumer_group(ctx.env.kafka, group_name=group_name)
    click.echo(result.value)


@kafka_group.command(name="describe-topic-records")
@click.option('--topic-name', default="logging-traffic-topic", help='Specify a topic name to describe')
@click.pass_obj
def describe_topic_records_cmd(ctx, topic_name):
    result = kafka_.describe_topic_records(ctx.env.kafka, topic_name=topic_name)
    click.echo(result.value)

# ##################### UTILITIES ###################


@click.command()
@click.option(
    "--config-file", default="/config/migration_services.yaml", help="Path to config file"
)
@click.option("--json", is_flag=True)
@click.argument('shell', type=click.Choice(['bash', 'zsh', 'fish']))
@click.pass_obj
def completion(ctx, config_file, json, shell):
    """Generate shell completion script and instructions for setup.

    Supported shells: bash, zsh, fish

    To enable completion:

    Bash:
      console completion bash > /etc/bash_completion.d/console
      # Then restart your shell

    Zsh:
      # If shell completion is not already enabled in your environment,
      # you will need to enable it. You can execute the following once:
      echo "autoload -U compinit; compinit" >> ~/.zshrc

      console completion zsh > "${fpath[1]}/_console"
      # Then restart your shell

    Fish:
      console completion fish > ~/.config/fish/completions/console.fish
      # Then restart your shell
    """
    completion_class = get_completion_class(shell)
    if completion_class is None:
        click.echo(f"Error: {shell} shell is currently not supported", err=True)
        ctx.exit(1)

    try:
        completion_script = completion_class(lambda: cli(ctx, config_file, json),
                                             {},
                                             "console",
                                             "_CONSOLE_COMPLETE").source()
        click.echo(completion_script)
    except RuntimeError as exc:
        click.echo(f"Error: {exc}", err=True)
        ctx.exit(1)


@click.group(name="tuples")
@click.pass_obj
def tuples_group(ctx):
    """ All commands related to tuples. """
    pass


@tuples_group.command()
@click.option('--in', 'inputfile',
              type=click.File('r'),
              default=sys.stdin)
@click.option('--out', 'outputfile',
              type=click.File('a'),
              default=sys.stdout)
def show(inputfile, outputfile):
    tuples_.convert(inputfile, outputfile)
    if outputfile != sys.stdout:
        click.echo(f"Converted tuples output to {outputfile.name}")


#################################################

cli.add_command(cluster_group)
cli.add_command(completion)

if not DISABLE_LEGACY_COMMANDS:
    cli.add_command(snapshot_group)
    cli.add_command(backfill_group)
    cli.add_command(replay_group)
    cli.add_command(metadata_group)
    cli.add_command(metrics_group)
    cli.add_command(kafka_group)
    cli.add_command(tuples_group)

if __name__ == "__main__":
    main()
