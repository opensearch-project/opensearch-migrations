import click
import console_link.logic as logic
from console_link.logic.instantiation import Environment


class Context(object):
    def __init__(self, config_file) -> None:
        self.config_file = config_file
        self.env = Environment(config_file)


@click.group()
@click.option('--config-file', default='/etc/migration_services.yaml', help='Path to config file')
@click.pass_context
def cli(ctx, config_file):
    ctx.obj = Context(config_file)


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
