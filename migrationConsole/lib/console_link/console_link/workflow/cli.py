"""Main CLI entry point for the workflow tool."""

import sys
import logging
import click
from click.shell_completion import get_completion_class

from .models.utils import ExitCode
from .commands.configure import configure_group
from .commands.submit import submit_command
from .commands.stop import stop_command
from .commands.approve import approve_command
from .commands.status import status_command
from .commands.output import output_command

logger = logging.getLogger(__name__)


@click.group(invoke_without_command=True)
@click.option('-v', '--verbose', count=True, help="Verbosity level. Default is warn, -v is info, -vv is debug.")
@click.pass_context
def workflow_cli(ctx, verbose):
    """Workflow-based migration management CLI"""

    if ctx.invoked_subcommand is None:
        logger.info("Missing command")
        click.echo(workflow_cli.get_help(ctx))
        ctx.exit(ExitCode.INVALID_INPUT.value)

    # Configure logging
    logging.basicConfig(level=logging.WARN - (10 * verbose))
    logger.info(f"Logging set to {logging.getLevelName(logger.getEffectiveLevel())}")

    # Initialize ctx.obj as a dictionary before assigning to it
    ctx.ensure_object(dict)

    # Store initialization is deferred - will be created when first accessed
    # This allows utility commands (like completions) to run without K8s access
    # Only set defaults if not already provided (e.g., in tests)
    if 'store' not in ctx.obj:
        ctx.obj['store'] = None
    if 'namespace' not in ctx.obj:
        ctx.obj['namespace'] = "ma"  # Use 'ma' namespace where the migration console is deployed


@workflow_cli.group(name="util")
@click.pass_context
def util_group(ctx):
    """Utility commands"""


@util_group.command(name="completions")
@click.argument('shell', type=click.Choice(['bash', 'zsh', 'fish']))
@click.pass_context
def completion(ctx, shell):
    """Generate shell completion script for bash, zsh, or fish.

    Example setup:
      Bash: workflow completion bash > /etc/bash_completion.d/workflow
      Zsh:  workflow completion zsh > "${fpath[1]}/_workflow"
      Fish: workflow completion fish > ~/.config/fish/completions/workflow.fish

    Restart your shell after installation.
    """
    completion_class = get_completion_class(shell)
    if completion_class is None:
        logger.error(f"{shell} shell is currently not supported")
        ctx.exit(ExitCode.INVALID_INPUT.value)

    try:
        completion_script = completion_class(lambda: workflow_cli(ctx),
                                             {},
                                             "workflow",
                                             "_WORKFLOW_COMPLETE").source()
        click.echo(completion_script)
    except RuntimeError as exc:
        logger.error(f"Failed to generate completion script: {exc}")
        ctx.exit(ExitCode.FAILURE.value)


# Add command groups
workflow_cli.add_command(configure_group)
workflow_cli.add_command(submit_command)
workflow_cli.add_command(stop_command)
workflow_cli.add_command(approve_command)
workflow_cli.add_command(status_command)
workflow_cli.add_command(output_command)
workflow_cli.add_command(util_group)


def main():
    """Main entry point for the workflow CLI."""
    try:
        workflow_cli()
    except Exception as e:
        logger.exception(e)
        sys.exit(ExitCode.FAILURE.value)


if __name__ == "__main__":
    main()
