"""Configuration commands for the workflow CLI."""

import logging
import os
import subprocess
import tempfile
from typing import Optional, cast

import click
from ...models.command_result import CommandResult
from ..models.config import WorkflowConfig
from ..models.utils import get_store


logger = logging.getLogger(__name__)

session_name = 'default'


def _get_empty_config_template() -> str:
    """Return empty configuration template"""
    return """# Workflow Configuration
# Edit this file to configure your migration workflow
# Add any YAML/JSON configuration you need

# Example:
# targets:
#   target-name:
#     endpoint: "https://target:9200"
#     auth:
#       username: "admin"
#       password: "password"
#
# source-migration-configurations:
#   - source:
#       endpoint: "https://source-cluster:9200"
"""


def _launch_editor_for_config(config: Optional[WorkflowConfig] = None) -> CommandResult[WorkflowConfig]:
    """Launch editor to edit workflow configuration"""
    editor = os.environ.get('EDITOR', 'vi')

    # Create temporary file with current config
    with tempfile.NamedTemporaryFile(mode='w+', suffix='.yaml', delete=False) as f:
        if config and config.data:
            f.write(config.to_yaml())
        else:
            # Write empty template
            f.write(_get_empty_config_template())
        temp_file = f.name

    try:
        # Launch editor
        subprocess.run([editor, temp_file], check=True)

        # Read back the edited content
        with open(temp_file, 'r') as f:
            edited_content = f.read()

        # Parse the edited content - no validation, just parse
        try:
            new_config = WorkflowConfig.from_yaml(edited_content)
            return CommandResult(success=True, value=new_config)
        except Exception as e:
            logger.exception(f"Failed to parse edited configuration: {e}")
            return CommandResult(success=False, value=e)

    except subprocess.CalledProcessError as e:
        logger.exception(f"Editor exited with error: {e}")
        return CommandResult(success=False, value=e)
    except Exception as e:
        logger.exception(f"Error launching editor: {e}")
        return CommandResult(success=False, value=e)
    finally:
        # Clean up temp file
        try:
            os.unlink(temp_file)
        except OSError:
            pass


@click.group(name="configure")
@click.pass_context
def configure_group(ctx):
    """Configure workflow settings"""
    pass


@configure_group.command(name="view")
@click.option('--format', type=click.Choice(['yaml', 'json']), default='yaml',
              help='Output format')
@click.pass_context
def view_config(ctx, format):
    """Show workflow configuration"""
    store = get_store(ctx)

    try:
        config = store.load_config(session_name)
        if config is None or not config:
            logger.info("No configuration found")
            click.echo("No configuration found.")
            return

        if format == 'json':
            click.echo(config.to_json())
        else:
            click.echo(config.to_yaml())
    except Exception as e:
        logger.error(f"Failed to load configuration: {e}")
        raise click.ClickException(f"Failed to load configuration: {e}")


def _parse_config_from_stdin(stdin_content: str) -> WorkflowConfig:
    """Parse configuration from stdin content, trying JSON first then YAML"""
    if not stdin_content.strip():
        raise click.ClickException("Configuration was empty, a value is required")

    # Try to parse as JSON first, then YAML
    try:
        return WorkflowConfig.from_json(stdin_content)
    except Exception:
        try:
            return WorkflowConfig.from_yaml(stdin_content)
        except Exception as e:
            raise click.ClickException(f"Failed to parse input as JSON or YAML: {e}")


def _save_config(store, new_config: WorkflowConfig, session_name: str):
    """Save configuration to store"""
    try:
        message = store.save_config(new_config, session_name)
        logger.info(f"Configuration saved: {message}")
        click.echo(message)
    except Exception as e:
        logger.exception(f"Failed to save configuration: {e}")
        raise click.ClickException(f"Failed to save configuration: {e}")


def _handle_stdin_edit(store, session_name: str):
    """Handle configuration edit from stdin"""
    stdin_stream = click.get_text_stream('stdin')
    stdin_content = stdin_stream.read()

    new_config = _parse_config_from_stdin(stdin_content)
    _save_config(store, new_config, session_name)


def _handle_editor_edit(store, session_name: str):
    """Handle configuration edit via editor"""
    try:
        current_config = store.load_config(session_name)
    except Exception as e:
        logger.exception(f"Failed to load configuration: {e}")
        raise click.ClickException(f"Failed to load configuration: {e}")

    edit_result = _launch_editor_for_config(current_config)
    if not edit_result.success:
        raise click.ClickException(str(edit_result.value))

    _save_config(store, cast(WorkflowConfig, edit_result.value), session_name)


@configure_group.command(name="edit")
@click.option('--stdin', is_flag=True, help='Read configuration from stdin instead of launching editor')
@click.pass_context
def edit_config(ctx, stdin):
    """Edit workflow configuration"""
    store = get_store(ctx)

    if stdin:
        _handle_stdin_edit(store, session_name)
    else:
        _handle_editor_edit(store, session_name)


@configure_group.command(name="clear")
@click.option('--confirm', is_flag=True, help='Skip confirmation prompt')
@click.pass_context
def clear_config(ctx, confirm):
    """Reset the pending workflow configuration"""
    store = get_store(ctx)

    if not confirm and not click.confirm(f'Clear workflow configuration for session "{session_name}"?'):
        logger.info("Clear configuration cancelled by user")
        click.echo("Cancelled")
        return

    # Create empty configuration
    empty_config = WorkflowConfig()

    # Save the empty configuration
    try:
        store.save_config(empty_config, session_name)
        logger.info(f"Cleared workflow configuration for session: {session_name}")
        click.echo(f"Cleared workflow configuration for session: {session_name}")
    except Exception as e:
        logger.exception(f"Failed to clear configuration: {e}")
        raise click.ClickException(f"Failed to clear configuration: {e}")
