import json
from pprint import pprint
import click
import console_link.logic.clusters as logic_clusters
import console_link.logic.metrics as logic_metrics
import console_link.logic.backfill as logic_backfill
import console_link.logic.snapshot as logic_snapshot
import console_link.logic.metadata as logic_metadata

from console_link.models.utils import ExitCode
from console_link.environment import Environment
from console_link.models.metrics_source import Component, MetricStatistic
from console_link.models.snapshot import SnapshotStatus

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
    ctx.obj = Context(config_file)
    ctx.obj.json = json
    logging.basicConfig(level=logging.WARN - (10 * verbose))
    logger.info(f"Logging set to {logging.getLevelName(logger.getEffectiveLevel())}")


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
@click.pass_obj
def create_snapshot_cmd(ctx):
    """Create a snapshot of the source cluster"""
    snapshot = ctx.env.snapshot
    status, message = logic_snapshot.create(snapshot)
    if status != SnapshotStatus.COMPLETED:
        raise click.ClickException(message)
    click.echo(message)


@snapshot_group.command(name="status")
@click.pass_obj
def status_snapshot_cmd(ctx):
    """Check the status of the snapshot"""
    snapshot = ctx.env.snapshot
    _, message = logic_snapshot.status(snapshot, source_cluster=ctx.env.source_cluster,
                                       target_cluster=ctx.env.target_cluster)
    click.echo(message)

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

# ##################### METRICS ###################


@cli.group(name="metadata")
@click.pass_obj
def metadata_group(ctx):
    """All actions related to metadata migration"""
    if ctx.env.metadata is None:
        raise click.UsageError("Metadata is not set")


@metadata_group.command(name="migrate")
@click.pass_obj
def migrate_metadata_cmd(ctx):
    exitcode, message = logic_metadata.migrate(ctx.env.metadata)
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


#################################################

if __name__ == "__main__":
    cli()
