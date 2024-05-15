import click
from console_link.logic.clusters import cat_indices
from console_link.logic.instantiation import Environment
from pprint import pprint


class Context(object):
    def __init__(self, config_file) -> None:
        self.config_file = config_file
        self.env = Environment(config_file)


@click.group()
@click.option('--config-file', default='services.yaml', help='Path to config file')
@click.pass_context
def cli(ctx, config_file):
    ctx.obj = Context(config_file)


@cli.command(name="cat-indices")
@click.pass_obj
def cat_indices_cmd(ctx):
    """Simple program that calls `_cat/indices` on both a source and target cluster."""
    pprint(cat_indices(ctx.env))
    pass


if __name__ == '__main__':
    cli()
