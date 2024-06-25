import json
from pprint import pprint
import click
import console_link.logic.clusters as logic_clusters
import console_link.logic.metrics as logic_metrics
import console_link.logic.backfill as logic_backfill
import console_link.logic.snapshot as logic_snapshot
import console_link.logic.metadata as logic_metadata
import console_link.logic.replay as logic_replay

from console_link.models.utils import ExitCode
from console_link.environment import Environment
from console_link.models.metrics_source import Component, MetricStatistic
from click.shell_completion import get_completion_class

import logging

logger = logging.getLogger(__name__)

# ################### UNIVERSAL ####################


class Context(object):
    def __init__(self, config_file) -> None:
        self.config_file = config_file
        try:
            self.env = Environment(config_file)
        except Exception as e:
            raise click.ClickException(str(e))
        self.json = False


@click.group()
@click.option(
    "--config-file", default="/etc/migration_services.yaml", help="Path to config file"
)
@click.option("--json", is_flag=True)
@click.option('-v', '--verbose', count=True, help="Verbosity level. Default is warn, -v is info, -vv is debug.")
@click.pass_context
def cli(ctx, config_file, json, verbose):
    logging.basicConfig(level=logging.WARN - (10 * verbose))
    logger.info(f"Logging set to {logging.getLevelName(logger.getEffectiveLevel())}")
    ctx.obj = Context(config_file)
    ctx.obj.json = json


# ##################### CLUSTERS ###################


@cli.group(name="clusters")
@click.pass_obj
def cluster_group(ctx):
    if ctx.env.source_cluster is None:
        raise click.UsageError("Source cluster is not set")
    if ctx.env.target_cluster is None:
        raise click.UsageError("Target cluster is not set")


@cluster_group.command(name="cat-indices")
@click.pass_obj
def cat_indices_cmd(ctx):
    """Simple program that calls `_cat/indices` on both a source and target cluster."""
    if ctx.json:
        click.echo(
            json.dumps(
                {
                    "source_cluster": logic_clusters.cat_indices(
                        ctx.env.source_cluster, as_json=True
                    ),
                    "target_cluster": logic_clusters.cat_indices(
                        ctx.env.target_cluster, as_json=True
                    ),
                }
            )
        )
        return
    click.echo("SOURCE CLUSTER")
    click.echo(logic_clusters.cat_indices(ctx.env.source_cluster))
    click.echo("TARGET CLUSTER")
    click.echo(logic_clusters.cat_indices(ctx.env.target_cluster))


@cluster_group.command(name="connection-check")
@click.pass_obj
def connection_check_cmd(ctx):
    """Checks if a connection can be established to source and target clusters"""
    click.echo("SOURCE CLUSTER")
    click.echo(logic_clusters.connection_check(ctx.env.source_cluster))
    click.echo("TARGET CLUSTER")
    click.echo(logic_clusters.connection_check(ctx.env.target_cluster))


@cluster_group.command(name="run-test-benchmarks")
@click.pass_obj
def run_test_benchmarks_cmd(ctx):
    """Run a series of OpenSearch Benchmark workloads against the source cluster"""
    click.echo(logic_clusters.run_test_benchmarks(ctx.env.source_cluster))


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
    if acknowledge_risk:
        click.echo("Performing clear indices operation...")
        click.echo(logic_clusters.clear_indices(cluster_focus))
    else:
        if click.confirm(f'Clearing indices WILL result in the loss of all data on the {cluster.lower()} cluster. '
                         f'Are you sure you want to continue?'):
            click.echo(f"Performing clear indices operation on {cluster.lower()} cluster...")
            click.echo(logic_clusters.clear_indices(cluster_focus))
        else:
            click.echo("Aborting command.")

# ##################### REPLAYER ###################


@cli.group(name="replayer")
@click.pass_obj
def replayer_group(ctx):
    if ctx.env.replayer is None:
        raise click.UsageError("Replayer is not set")


@replayer_group.command(name="start")
@click.pass_obj
def start_replayer_cmd(ctx):
    ctx.env.replayer.start()

# ##################### SNAPSHOT ###################


@cli.group(name="snapshot")
@click.pass_obj
def snapshot_group(ctx):
    """All actions related to snapshot creation"""
    if ctx.env.snapshot is None:
        raise click.UsageError("Snapshot is not set")


@snapshot_group.command(name="create")
@click.option('--wait', is_flag=True, default=False, help='Wait for snapshot completion')
@click.option('--max-snapshot-rate-mb-per-node', type=int, default=None,
              help='Maximum snapshot rate in MB/s per node')
@click.pass_obj
def create_snapshot_cmd(ctx, wait, max_snapshot_rate_mb_per_node):
    """Create a snapshot of the source cluster"""
    snapshot = ctx.env.snapshot
    result = logic_snapshot.create(snapshot, wait=wait,
                                   max_snapshot_rate_mb_per_node=max_snapshot_rate_mb_per_node)
    click.echo(result.value)


@snapshot_group.command(name="status")
@click.option('--deep-check', is_flag=True, default=False, help='Perform a deep status check of the snapshot')
@click.pass_obj
def status_snapshot_cmd(ctx, deep_check):
    """Check the status of the snapshot"""
    result = logic_snapshot.status(ctx.env.snapshot, deep_check=deep_check)
    click.echo(result.value)

# ##################### BACKFILL ###################

# As we add other forms of backfill migrations, we should incorporate a way to dynamically allow different sets of
# arguments depending on the type of backfill migration


@cli.group(name="backfill")
@click.pass_obj
def backfill_group(ctx):
    """All actions related to historical/backfill data migrations"""
    if ctx.env.backfill is None:
        raise click.UsageError("Backfill migration is not set")


@backfill_group.command(name="describe")
@click.pass_obj
def describe_backfill_cmd(ctx):
    click.echo(logic_backfill.describe(ctx.env.backfill, as_json=ctx.json))


@backfill_group.command(name="create")
@click.option('--pipeline-template-file', default='/root/osiPipelineTemplate.yaml', help='Path to config file')
@click.option("--print-config-only", is_flag=True, show_default=True, default=False,
              help="Flag to only print populated pipeline config when executed")
@click.pass_obj
def create_backfill_cmd(ctx, pipeline_template_file, print_config_only):
    exitcode, message = logic_backfill.create(ctx.env.backfill,
                                              pipeline_template_path=pipeline_template_file,
                                              print_config_only=print_config_only)
    if exitcode != ExitCode.SUCCESS:
        raise click.ClickException(message)
    click.echo(message)


@backfill_group.command(name="start")
@click.option('--pipeline-name', default=None, help='Optionally specify a pipeline name')
@click.pass_obj
def start_backfill_cmd(ctx, pipeline_name):
    exitcode, message = logic_backfill.start(ctx.env.backfill, pipeline_name=pipeline_name)
    if exitcode != ExitCode.SUCCESS:
        raise click.ClickException(message)
    click.echo(message)


@backfill_group.command(name="stop")
@click.option('--pipeline-name', default=None, help='Optionally specify a pipeline name')
@click.pass_obj
def stop_backfill_cmd(ctx, pipeline_name):
    exitcode, message = logic_backfill.stop(ctx.env.backfill, pipeline_name=pipeline_name)
    if exitcode != ExitCode.SUCCESS:
        raise click.ClickException(message)
    click.echo(message)


@backfill_group.command(name="scale")
@click.argument("units", type=int, required=True)
@click.pass_obj
def scale_backfill_cmd(ctx, units: int):
    exitcode, message = logic_backfill.scale(ctx.env.backfill, units)
    if exitcode != ExitCode.SUCCESS:
        raise click.ClickException(message)
    click.echo(message)


@backfill_group.command(name="status")
@click.pass_obj
def status_backfill_cmd(ctx):
    exitcode, message = logic_backfill.status(ctx.env.backfill)
    if exitcode != ExitCode.SUCCESS:
        raise click.ClickException(message)
    click.echo(message)


# ##################### REPLAY ###################

@cli.group(name="replay")
@click.pass_obj
def replay_group(ctx):
    """All actions related to replaying data"""
    if ctx.env.replay is None:
        raise click.UsageError("Replay is not set")


@replay_group.command(name="describe")
@click.pass_obj
def describe_replay_cmd(ctx):
    click.echo(logic_replay.describe(ctx.env.replay, as_json=ctx.json))


@replay_group.command(name="start")
@click.pass_obj
def start_replay_cmd(ctx):
    exitcode, message = logic_replay.start(ctx.env.replay)
    if exitcode != ExitCode.SUCCESS:
        raise click.ClickException(message)
    click.echo(message)


@replay_group.command(name="stop")
@click.pass_obj
def stop_replay_cmd(ctx):
    exitcode, message = logic_replay.stop(ctx.env.replay)
    if exitcode != ExitCode.SUCCESS:
        raise click.ClickException(message)
    click.echo(message)


@replay_group.command(name="scale")
@click.argument("units", type=int, required=True)
@click.pass_obj
def scale_replay_cmd(ctx, units: int):
    exitcode, message = logic_replay.scale(ctx.env.replay, units)
    if exitcode != ExitCode.SUCCESS:
        raise click.ClickException(message)
    click.echo(message)


@replay_group.command(name="status")
@click.pass_obj
def status_replay_cmd(ctx):
    exitcode, message = logic_replay.status(ctx.env.replay)
    if exitcode != ExitCode.SUCCESS:
        raise click.ClickException(message)
    click.echo(message)


# ##################### METADATA ###################


@cli.group(name="metadata")
@click.pass_obj
def metadata_group(ctx):
    """All actions related to metadata migration"""
    if ctx.env.metadata is None:
        raise click.UsageError("Metadata is not set")


@metadata_group.command(name="migrate")
@click.option("--detach", is_flag=True, help="Run metadata migration in detached mode")
@click.pass_obj
def migrate_metadata_cmd(ctx, detach):
    exitcode, message = logic_metadata.migrate(ctx.env.metadata, detach)
    if exitcode != ExitCode.SUCCESS:
        raise click.ClickException(message)
    click.echo(message)

# ##################### METRICS ###################


@cli.group(name="metrics")
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
    metric_data = logic_metrics.get_metric_data(
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

# ##################### UTILITIES ###################


@cli.command()
@click.option(
    "--config-file", default="/etc/migration_services.yaml", help="Path to config file"
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


#################################################

if __name__ == "__main__":
    cli()
