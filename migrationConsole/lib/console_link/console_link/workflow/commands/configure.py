"""Configuration commands for the workflow CLI."""

import logging
import os
import subprocess
import tempfile
from typing import Optional, cast

import click

from ..models.secret_store import SecretStore
from ...models.command_result import CommandResult
from ..models.config import WorkflowConfig
from ..models.utils import get_workflow_config_store, get_credentials_secret_store

logger = logging.getLogger(__name__)

session_name = 'default'


def _get_empty_config_template() -> str:
    """Return empty configuration template.

    Returns sample configuration from CONFIG_PROCESSOR_DIR if available,
    otherwise returns a blank starter configuration template.
    """
    from ..services.script_runner import ScriptRunner
    runner = ScriptRunner()
    return runner.get_sample_config()


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


@configure_group.command(name="view")
@click.pass_context
def view_config(ctx):
    """Show workflow configuration"""
    store = get_workflow_config_store(ctx)

    try:
        config = store.load_config(session_name)
        if config is None or not config:
            logger.info("No configuration found")
            click.echo("No configuration found.")
            return

        click.echo(config.to_yaml())
    except Exception as e:
        logger.error(f"Failed to load configuration: {e}")
        raise click.ClickException(f"Failed to load configuration: {e}")


def _parse_config_from_stdin(stdin_content: str) -> WorkflowConfig:
    """Parse configuration from stdin content as YAML (JSON is valid YAML 1.2)"""
    if not stdin_content.strip():
        raise click.ClickException("Configuration was empty, a value is required")

    try:
        return WorkflowConfig.from_yaml(stdin_content)
    except Exception as e:
        raise click.ClickException(f"Failed to parse input as YAML: {e}")


def _get_basic_creds_secrets_in_config(secret_store: SecretStore, new_config: WorkflowConfig):
    """Scrape any auth credentials secrets that need to be created."""

    from ..services.script_runner import ScriptRunner
    runner = ScriptRunner()
    result = runner.get_basic_creds_secrets_in_config(new_config.to_yaml())
    logger.info(f"got back script result for get_secrets_in_config: {result}")

    if 'invalidSecrets' in result and (invalid_secrets := result['invalidSecrets']):
        raise click.ClickException(f"Invalidly named secret{'s' if len(invalid_secrets) > 0 else ''} found:"
                                   f" {invalid_secrets}")
    elif 'validSecrets' in result and (valid_secrets := result.get('validSecrets', [])):
        valid_existing_resource_names = list(filter(secret_store.secret_exists, valid_secrets))
        return set(valid_existing_resource_names), list(set(valid_secrets) - set(valid_existing_resource_names))
    else:
        return [], []


def _handle_add_basic_creds_secrets(secret_store, missing_secrets):
    num_missing = len(missing_secrets)
    click.echo(f"{num_missing} secret{'s' if num_missing > 1 else ''} used in the cluster definitions must be created.")

    i = 0
    while i < len(missing_secrets):
        s = missing_secrets[i]

        if not click.confirm(f"Would you like to create secret '{s}' now?", default=True):
            click.echo(f"Skipped creating {s}")
            i += 1
            continue

        try:
            username = click.prompt("Username", type=str)
            password = click.prompt("Password", hide_input=True, confirmation_prompt=True)

            secret_store.save_secret(s, {"username": username, "password": password})
            click.echo(f"Secret {s} saved successfully")
            i += 1  # Only advance on success
        except click.Abort:
            click.echo(f"\nCancelled {s}")
            if click.confirm("Retry this secret?", default=True):
                continue  # Stay on same secret to give the user another chance (they can skip too)
            else:
                i += 1  # Move to next secret


def _save_config(store, new_config: WorkflowConfig, session_name: str):
    """Save configuration to store"""
    try:
        message = store.save_config(new_config, session_name)
        logger.info(f"Configuration saved: {message}")
        click.echo(message)
    except Exception as e:
        logger.exception(f"Failed to save configuration: {e}")
        raise click.ClickException(f"Failed to save configuration: {e}")


def _handle_stdin_edit(wf_config_store, secret_store, session_name: str):
    """Handle configuration edit from stdin"""
    stdin_stream = click.get_text_stream('stdin')
    stdin_content = stdin_stream.read()

    new_config = _parse_config_from_stdin(stdin_content)
    _save_config(wf_config_store, new_config, session_name)

    existing_items, missing_items = _get_basic_creds_secrets_in_config(secret_store, new_config)
    if existing_items:
        logger.info(f"Found {len(existing_items)} existing secret{'s' if len(existing_items) > 0 else ''} "
                    f"that will be used for HTTP-Basic authentication "
                    f"of requests to clusters:\n  " + "\n  ".join(existing_items))
    if missing_items:
        raise click.ClickException(
            f"Found {len(missing_items)} missing secret{'s' if len(missing_items) > 0 else ''} "
            f"that must be created to make well-formed HTTP-Basic requests to clusters:\n  " +
            "\n  ".join(missing_items))


def _handle_editor_edit(store, secret_store, session_name: str):
    """Handle configuration edit via editor"""
    try:
        current_config = store.load_config(session_name)
    except Exception as e:
        logger.exception(f"Failed to load configuration: {e}")
        raise click.ClickException(f"Failed to load configuration: {e}")

    edit_result = _launch_editor_for_config(current_config)
    if not edit_result.success:
        raise click.ClickException(str(edit_result.value))

    new_config = cast(WorkflowConfig, edit_result.value)
    _save_config(store, new_config, session_name)

    existing_items, missing_items = _get_basic_creds_secrets_in_config(secret_store, new_config)
    if existing_items:
        click.echo(f"Found {len(existing_items)} existing secrets that will be used for HTTP-Basic authentication"
                   f" of requests to clusters:\n  " + "\n  ".join(existing_items))
    if missing_items:
        _handle_add_basic_creds_secrets(secret_store, missing_items)


@configure_group.command(name="edit")
@click.option('--stdin', is_flag=True, help='Read configuration from stdin instead of launching editor')
@click.pass_context
def edit_config(ctx, stdin):
    """Edit workflow configuration"""
    wf_config_store = get_workflow_config_store(ctx)
    secret_store = get_credentials_secret_store(ctx)

    if stdin:
        _handle_stdin_edit(wf_config_store, secret_store, session_name)
    else:
        _handle_editor_edit(wf_config_store, secret_store, session_name)


@configure_group.command(name="clear")
@click.option('--confirm', is_flag=True, help='Skip confirmation prompt')
@click.pass_context
def clear_config(ctx, confirm):
    """Reset the pending workflow configuration"""
    store = get_workflow_config_store(ctx)

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


@configure_group.command(name="sample")
@click.option('--load', is_flag=True, help='Load sample into current session')
@click.pass_context
def sample_config(ctx, load):
    """Show or load sample configuration.

    Displays sample configuration from CONFIG_PROCESSOR_DIR if available,
    otherwise displays a blank starter configuration template.
    """
    try:
        from ..services.script_runner import ScriptRunner
        runner = ScriptRunner()
        sample_content = runner.get_sample_config()

        if load:
            # Load sample into session
            store = get_workflow_config_store(ctx)
            config = WorkflowConfig.from_yaml(sample_content)
            _save_config(store, config, session_name)
            click.echo("Sample configuration loaded successfully")
            click.echo("\nUse 'workflow configure view' to see it")
            click.echo("Use 'workflow configure edit' to modify it")
        else:
            # Just display the sample
            click.echo(sample_content)

    except Exception as e:
        logger.exception(f"Failed to get sample configuration: {e}")
        raise click.ClickException(f"Failed to get sample: {e}")
