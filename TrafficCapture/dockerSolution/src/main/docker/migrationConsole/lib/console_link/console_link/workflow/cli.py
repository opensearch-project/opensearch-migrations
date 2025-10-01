"""Main CLI entry point for the workflow tool."""

import sys
import logging
import click
import traceback
from click.shell_completion import get_completion_class

from .models.store import WorkflowConfigStore
from .commands.configure import configure_group

logger = logging.getLogger(__name__)


@click.group(invoke_without_command=True)
@click.option('-v', '--verbose', count=True, help="Verbosity level. Default is warn, -v is info, -vv is debug.")
@click.pass_context
def workflow_cli(ctx, verbose):
    """Workflow-based migration management CLI"""

    if ctx.invoked_subcommand is None:
        click.echo("Error: Missing command.", err=True)
        click.echo(workflow_cli.get_help(ctx))
        ctx.exit(2)

    # Configure logging
    logging.basicConfig(level=logging.WARN - (10 * verbose))
    logger.info(f"Logging set to {logging.getLevelName(logger.getEffectiveLevel())}")

    # Initialize ctx.obj as a dictionary before assigning to it
    ctx.ensure_object(dict)

    # Store initialization is deferred - will be created when first accessed
    # This allows utility commands (like completions) to run without K8s access
    ctx.obj['store'] = None
    ctx.obj['namespace'] = "ma"  # Use 'ma' namespace where the migration console is deployed


def get_store(ctx) -> WorkflowConfigStore:
    """Lazy initialization of WorkflowConfigStore"""
    if ctx.obj['store'] is None:
        ctx.obj['store'] = WorkflowConfigStore(namespace=ctx.obj['namespace'])
    return ctx.obj['store']


@workflow_cli.group(name="util")
@click.pass_context
def util_group(ctx):
    """Utility commands"""


@util_group.command(name="completions")
@click.argument('shell', type=click.Choice(['bash', 'zsh', 'fish']))
@click.pass_context
def completion(ctx, shell):
    """Generate shell completion script and instructions for setup.

    Supported shells: bash, zsh, fish

    To enable completion:

    Bash:
      workflow completion bash > /etc/bash_completion.d/workflow
      # Then restart your shell

    Zsh:
      # If shell completion is not already enabled in your environment,
      # you will need to enable it. You can execute the following once:
      echo "autoload -U compinit; compinit" >> ~/.zshrc

      workflow completion zsh > "${fpath[1]}/_workflow"
      # Then restart your shell

    Fish:
      workflow completion fish > ~/.config/fish/completions/workflow.fish
      # Then restart your shell
    """
    completion_class = get_completion_class(shell)
    if completion_class is None:
        click.echo(f"Error: {shell} shell is currently not supported", err=True)
        ctx.exit(1)

    try:
        completion_script = completion_class(lambda: workflow_cli(ctx),
                                             {},
                                             "workflow",
                                             "_WORKFLOW_COMPLETE").source()
        click.echo(completion_script)
    except RuntimeError as exc:
        click.echo(f"Error: {exc}", err=True)
        ctx.exit(1)


# Add command groups
workflow_cli.add_command(configure_group)
workflow_cli.add_command(util_group)


def main():
    """Main entry point for the workflow CLI."""
    try:
        workflow_cli()
    except Exception as e:
        # Check if verbose mode is enabled by looking at the root logger level
        # Verbose mode sets logging level to INFO (20) or DEBUG (10), default is WARN (30)
        root_logger = logging.getLogger()
        if root_logger.getEffectiveLevel() <= logging.INFO:
            # Verbose mode is enabled, show full traceback
            click.echo("Error occurred with verbose mode enabled, showing full traceback:", err=True)
            click.echo(traceback.format_exc(), err=True)
        else:
            # Normal mode, show clean error message
            click.echo(f"Error: {str(e)}", err=True)
        sys.exit(1)


if __name__ == "__main__":
    main()
