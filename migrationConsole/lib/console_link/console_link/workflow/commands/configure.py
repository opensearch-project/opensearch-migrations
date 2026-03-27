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


def _launch_editor_for_config(config: Optional[WorkflowConfig] = None) -> CommandResult[str]:
    """Launch editor to edit workflow configuration. Returns raw YAML string."""
    editor = os.environ.get('EDITOR', 'vi')

    # Create temporary file with current config
    with tempfile.NamedTemporaryFile(mode='w+', suffix='.yaml', delete=False) as f:
        if config:
            f.write(config.raw_yaml)
        else:
            # Write empty template
            f.write(_get_empty_config_template())
        temp_file = f.name

    try:
        # Launch editor
        subprocess.run([editor, temp_file], check=True)
    except subprocess.CalledProcessError as e:
        logger.warning(f"Editor exited with non-zero status: {e.returncode}")
        return CommandResult(success=False, value=e)

    try:
        # Read back the edited content
        with open(temp_file, 'r') as f:
            edited_content = f.read()

        return CommandResult(success=True, value=edited_content)

    except Exception as e:
        logger.exception(f"Error reading edited file: {e}")
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

        click.echo(config.raw_yaml, nl=False)
    except Exception as e:
        logger.error(f"Failed to load configuration: {e}")
        raise click.ClickException(f"Failed to load configuration: {e}")


def _parse_config_from_stdin() -> str:
    """Read configuration from stdin and return raw content."""
    stdin_stream = click.get_text_stream('stdin')
    stdin_content = stdin_stream.read()
    if not stdin_content.strip():
        raise click.ClickException("Configuration was empty, a value is required")
    return stdin_content


def _validate_and_find_secrets(raw_yaml: str):
    """Validate config via TS Zod schema and scrape secrets in one call.

    Returns dict with 'valid' bool, optional 'errors', and optional 'validSecrets'/'invalidSecrets'.
    """
    from ..services.script_runner import ScriptRunner
    runner = ScriptRunner()
    result = runner.get_basic_creds_secrets_in_config(raw_yaml)
    logger.info(f"got back script result for validate_and_find_secrets: {result}")
    return result


def _process_secrets(secret_store: SecretStore, result: dict, interactive: bool = False):
    """Process secrets from a validate+findSecrets result."""
    invalid_secrets = result.get('invalidSecrets')
    if invalid_secrets:
        raise click.ClickException(f"Invalidly named secret{'s' if len(invalid_secrets) > 1 else ''} found:"
                                   f" {invalid_secrets}")

    valid_secrets = result.get('validSecrets', [])
    if not valid_secrets:
        return

    existing = list(filter(secret_store.secret_exists, valid_secrets))
    missing = list(set(valid_secrets) - set(existing))

    _notify_existing_secrets(existing, interactive)
    _handle_missing_config_secrets(secret_store, missing, interactive)


def _notify_existing_secrets(existing, interactive):
    if not existing:
        return
    msg = (f"Found {len(existing)} existing secret{'s' if len(existing) > 1 else ''} "
           f"that will be used for HTTP-Basic authentication "
           f"of requests to clusters:\n  " + "\n  ".join(existing))
    if interactive:
        click.echo(msg)
    else:
        logger.info(msg)


def _handle_missing_config_secrets(secret_store, missing, interactive):
    if not missing:
        return
    if interactive:
        _handle_add_basic_creds_secrets(secret_store, missing)
    else:
        raise click.ClickException(
            f"Found {len(missing)} missing secret{'s' if len(missing) > 1 else ''} "
            f"that must be created to make well-formed HTTP-Basic requests to clusters:\n  " +
            "\n  ".join(missing))


def _handle_add_basic_creds_secrets(secret_store, missing_names):
    num_missing = len(missing_names)
    click.echo(f"{num_missing} secret{'s' if num_missing > 1 else ''} used in the cluster definitions must be created.")

    i = 0
    while i < len(missing_names):
        s = missing_names[i]

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
    raw_yaml = _parse_config_from_stdin()

    # Validate via TS and get secrets in one call — save regardless of validation result
    result = _validate_and_find_secrets(raw_yaml)
    new_config = WorkflowConfig(raw_yaml=raw_yaml)
    if not result.get('valid', False):
        _save_config(wf_config_store, new_config, session_name)
        raise click.ClickException(
            f"Configuration saved but has validation errors:\n{result.get('errors', 'Unknown error')}")

    _save_config(wf_config_store, new_config, session_name)
    _process_secrets(secret_store, result, interactive=False)


def _handle_editor_edit(store, secret_store, session_name: str):
    """Handle configuration edit via editor"""
    try:
        current_config = store.load_config(session_name)
    except Exception as e:
        logger.exception(f"Failed to load configuration: {e}")
        raise click.ClickException(f"Failed to load configuration: {e}")

    while True:
        edit_result = _launch_editor_for_config(current_config)
        if not edit_result.success:
            raise click.ClickException(str(edit_result.value))

        raw_yaml = cast(str, edit_result.value)

        result = _validate_and_find_secrets(raw_yaml)
        if result.get('valid', False):
            break

        # Validation failed — let user choose
        click.echo(f"\nValidation errors:\n{result.get('errors', 'Unknown error')}\n")
        choice = click.prompt(
            "Would you like to (s)ave anyway, (e)dit again, or (d)iscard?",
            type=click.Choice(['s', 'e', 'd'], case_sensitive=False))

        if choice == 's':
            _save_config(store, WorkflowConfig(raw_yaml=raw_yaml), session_name)
            return
        elif choice == 'd':
            click.echo("Changes discarded.")
            return
        # choice == 'e': clear old errors before re-opening editor
        click.clear()
        current_config = WorkflowConfig(raw_yaml=raw_yaml)

    _save_config(store, WorkflowConfig(raw_yaml=raw_yaml), session_name)
    _process_secrets(secret_store, result, interactive=True)


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
