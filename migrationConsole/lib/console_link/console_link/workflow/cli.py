"""Main CLI entry point for the workflow tool."""

import signal
import sys
import logging
import click
from click.shell_completion import get_completion_class

# Restore default SIGPIPE handling so piped commands (e.g. `| head`) exit cleanly.
try:
    signal.signal(signal.SIGPIPE, signal.SIG_DFL)
except (AttributeError, ValueError):
    pass

from .models.utils import ExitCode
from .models.utils import get_current_namespace
from .commands.configure import configure_group
from .commands.submit import submit_command
from .commands.approve import approve_group
from .commands.status import status_command
from .commands.log import log_command
from .commands.show import show_command
from .commands.manage import manage_command
from .commands.reset import reset_command

logger = logging.getLogger(__name__)

HELP_CONTEXT = {'help_option_names': ['-h', '--help']}


@click.group(invoke_without_command=True, context_settings=HELP_CONTEXT)
@click.option('-v', '--verbose', count=True, help="Verbosity level. Default is warn, -v is info, -vv is debug.")
@click.pass_context
def workflow_cli(ctx, verbose):
    """Workflow-based migration management CLI"""

    if ctx.invoked_subcommand is None:
        logger.info("Missing command")
        click.echo(workflow_cli.get_help(ctx))
        ctx.exit(ExitCode.INVALID_INPUT.value)

    # Configure logging - only if no handlers exist to avoid issues with Click's CliRunner in tests
    root_logger = logging.getLogger()
    if not root_logger.handlers:
        logging.basicConfig(level=logging.WARN - (10 * verbose))
    else:
        root_logger.setLevel(logging.WARN - (10 * verbose))
    logger.info(f"Logging set to {logging.getLevelName(logger.getEffectiveLevel())}")

    # Initialize ctx.obj as a dictionary before assigning to it
    ctx.ensure_object(dict)

    # Store initialization is deferred - will be created when first accessed
    # This allows utility commands (like completions) to run without K8s access
    # Only set defaults if not already provided (e.g., in tests)
    if 'config_store' not in ctx.obj:
        ctx.obj['config_store'] = None
    if 'secret_store' not in ctx.obj:
        ctx.obj['secret_store'] = None
    if 'namespace' not in ctx.obj:
        ctx.obj['namespace'] = get_current_namespace()  # Detect from pod, fallback to 'ma'


@workflow_cli.group(name="util", context_settings=HELP_CONTEXT)
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


def _enable_short_help(command):
    command.context_settings.setdefault('help_option_names', ['-h', '--help'])
    if isinstance(command, click.Group):
        for subcommand in command.commands.values():
            _enable_short_help(subcommand)


# Add command groups
workflow_cli.add_command(configure_group)
workflow_cli.add_command(submit_command)
workflow_cli.add_command(approve_group)
workflow_cli.add_command(status_command)
workflow_cli.add_command(log_command)
workflow_cli.add_command(show_command)
workflow_cli.add_command(manage_command)
workflow_cli.add_command(reset_command)
workflow_cli.add_command(util_group)
_enable_short_help(workflow_cli)


def main():
    """Main entry point for the workflow CLI."""
    try:
        workflow_cli()
    except Exception as e:
        logger.exception(e)
        sys.exit(ExitCode.FAILURE.value)


if __name__ == "__main__":
    main()
