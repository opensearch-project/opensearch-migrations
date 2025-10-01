"""Configuration commands for the workflow CLI."""

import logging
import os
import subprocess
import tempfile
from typing import Optional

import click
from ...models.command_result import CommandResult
from ..models.config import WorkflowConfig


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
            logger.error(f"Failed to parse edited configuration: {e}")
            return CommandResult(success=False, value=f"Failed to parse configuration: {e}")
            
    except subprocess.CalledProcessError as e:
        logger.error(f"Editor exited with error: {e}")
        return CommandResult(success=False, value=f"Editor failed: {e}")
    except Exception as e:
        logger.error(f"Error launching editor: {e}")
        return CommandResult(success=False, value=f"Failed to launch editor: {e}")
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
    store = ctx.obj['store']

    result = store.load_config(session_name)
    if not result.success:
        raise click.ClickException(f"Failed to load configuration: {result.value}")
    
    config = result.value
    if config is None or not config:
        click.echo(f"No configuration found.")
        return
    
    if format == 'json':
        click.echo(config.to_json())
    else:
        click.echo(config.to_yaml())


@configure_group.command(name="edit")
@click.option('--stdin', is_flag=True, help='Read configuration from stdin instead of launching editor')
@click.pass_context
def edit_config(ctx, stdin):
    """Edit workflow configuration"""
    store = ctx.obj['store']

    if stdin:
        # Read configuration from stdin
        stdin_stream = click.get_text_stream('stdin')
        stdin_content = stdin_stream.read()
        
        if not stdin_content.strip():
            raise click.ClickException("No input provided on stdin")
        
        # Try to parse as JSON first, then YAML
        try:
            new_config = WorkflowConfig.from_json(stdin_content)
        except Exception:
            try:
                new_config = WorkflowConfig.from_yaml(stdin_content)
            except Exception as e:
                raise click.ClickException(f"Failed to parse input as JSON or YAML: {e}")
        
        # Save the new config
        save_result = store.save_config(new_config, session_name)
        if not save_result.success:
            raise click.ClickException(f"Failed to save configuration: {save_result.value}")
        
        click.echo(save_result.value)
    else:
        # Load existing config
        load_result = store.load_config(session_name)
        if not load_result.success:
            raise click.ClickException(f"Failed to load configuration: {load_result.value}")
        
        current_config = load_result.value
        
        # Launch editor
        edit_result = _launch_editor_for_config(current_config)
        if not edit_result.success:
            raise click.ClickException(edit_result.value)
        
        new_config = edit_result.value
        
        # Save updated config
        save_result = store.save_config(new_config, session_name)
        if not save_result.success:
            raise click.ClickException(f"Failed to save configuration: {save_result.value}")
        
        click.echo(save_result.value)


@configure_group.command(name="clear")
@click.option('--confirm', is_flag=True, help='Skip confirmation prompt')
@click.pass_context
def clear_config(ctx, confirm):
    """Reset the pending workflow configuration"""
    store = ctx.obj['store']

    if not confirm:
        if not click.confirm(f'Clear workflow configuration for session "{session_name}"?'):
            click.echo("Cancelled")
            return
    
    # Create empty configuration
    empty_config = WorkflowConfig()
    
    # Save the empty configuration
    save_result = store.save_config(empty_config, session_name)
    if not save_result.success:
        raise click.ClickException(f"Failed to clear configuration: {save_result.value}")
    
    click.echo(f"Cleared workflow configuration for session: {session_name}")
