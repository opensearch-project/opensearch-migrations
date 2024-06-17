import json
from pprint import pprint
import click
import console_link.logic.clusters as logic_clusters
import console_link.logic.metrics as logic_metrics
import console_link.logic.backfill as logic_backfill
from console_link.environment import Environment
from console_link.models.metrics_source import Component, MetricStatistic
import logging

logger = logging.getLogger(__name__)

# ################### UNIVERSAL ####################


class Context(object):
    def __init__(self, config_file) -> None:
        self.config_file = config_file
        self.env = Environment(config_file)
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
        raise ValueError("Source cluster is not set")
    if ctx.env.target_cluster is None:
        raise ValueError("Target cluster is not set")


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
        raise ValueError("Replayer is not set")


@replayer_group.command(name="start")
@click.pass_obj
def start_replayer_cmd(ctx):
    ctx.env.replayer.start()


# ##################### BACKFILL ###################

# As we add other forms of backfill migrations, we should incorporate a way to dynamically allow different sets of
# arguments depending on the type of backfill migration

@cli.group(name="backfill")
@click.pass_obj
def backfill_group(ctx):
    """All actions related to historical/backfill data migrations"""
    if ctx.env.backfill is None:
        raise ValueError("Backfill migration is not set")


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
    """Create migration action"""
    click.echo(logic_backfill.create(ctx.env.backfill, pipeline_template_path=pipeline_template_file,
                                     print_config_only=print_config_only))


@backfill_group.command(name="start")
@click.option('--pipeline-name', default=None, help='Optionally specify a pipeline name')
@click.pass_obj
def start_backfill_cmd(ctx, pipeline_name):
    """Start migration action"""
    ctx.env.backfill.start(pipeline_name=pipeline_name)


@backfill_group.command(name="stop")
@click.option('--pipeline-name', default=None, help='Optionally specify a pipeline name')
@click.pass_obj
def stop_backfill_cmd(ctx, pipeline_name):
    """Stop migration action"""
    ctx.env.backfill.stop(pipeline_name=pipeline_name)


# ##################### METRICS ###################

@cli.group(name="metrics")
@click.pass_obj
def metrics_group(ctx):
    if ctx.env.metrics_source is None:
        raise ValueError("Metrics source is not set")


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
