import click
import logging
import console_link.logic as logic
from console_link.logic.instantiation import Environment

logger = logging.getLogger(__name__)


class Context(object):
    def __init__(self, config_file) -> None:
        self.config_file = config_file
        self.env = Environment(config_file)


@click.group()
@click.option('--config-file', default='/etc/migration_services.yaml', help='Path to config file')
@click.pass_context
def cli(ctx, config_file):
    ctx.obj = Context(config_file)


@cli.command(name="osi-create-migration")
@click.option('--pipeline-template-file', default='/root/osiPipelineTemplate.yaml', help='Path to config file')
@click.option("--print-config-only", is_flag=True, show_default=True, default=False,
              help="Flag to only print populated pipeline config when executed")
@click.pass_obj
def create_migration_osi_cmd(ctx, pipeline_template_file, print_config_only):
    """Create OSI migration action"""
    if ctx.env.osi_migration:
        ctx.env.osi_migration.create(pipeline_template_path=pipeline_template_file, print_config_only=print_config_only)
    else:
        logger.error(f"Error: OpenSearch Ingestion has not been configured via the config file: {ctx.config_file}")


@cli.command(name="osi-start-migration")
@click.option('--pipeline-name', default=None, help='Optionally specify a pipeline name')
@click.pass_obj
def start_migration_osi_cmd(ctx, pipeline_name):
    """Start OSI migration action"""
    if ctx.env.osi_migration:
        ctx.env.osi_migration.start(pipeline_name=pipeline_name)
    else:
        logger.error(f"Error: OpenSearch Ingestion has not been configured via the config file: {ctx.config_file}")


@cli.command(name="osi-stop-migration")
@click.option('--pipeline-name', default=None, help='Optionally specify a pipeline name')
@click.pass_obj
def stop_migration_osi_cmd(ctx, pipeline_name):
    """Stop OSI migration action"""
    if ctx.env.osi_migration:
        ctx.env.osi_migration.stop(pipeline_name=pipeline_name)
    else:
        logger.error(f"Error: OpenSearch Ingestion has not been configured via the config file: {ctx.config_file}")


@cli.command(name="cat-indices")
@click.pass_obj
def cat_indices_cmd(ctx):
    """Simple program that calls `_cat/indices` on both a source and target cluster."""
    click.echo("SOURCE CLUSTER")
    click.echo(logic.clusters.cat_indices(ctx.env.source_cluster))
    click.echo("TARGET CLUSTER")
    click.echo(logic.clusters.cat_indices(ctx.env.target_cluster))
    pass


@cli.command(name="start-replayer")
@click.pass_obj
def start_replayer_cmd(ctx):
    logic.services.start_replayer(ctx.env.replayer)


if __name__ == '__main__':
    cli()
