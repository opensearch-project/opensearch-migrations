"""Configuration commands for the workflow CLI."""

import logging
import os
import subprocess
import termios
import tempfile
from typing import Optional, cast

import click
from kubernetes.client import V1Secret
from kubernetes.client.rest import ApiException

from ...models.command_result import CommandResult
from ..models.config import WorkflowConfig
from ..models.utils import get_current_namespace, get_workflow_config_store, get_credentials_secret_store
from .secret_utils import process_secrets, validate_and_find_secrets

logger = logging.getLogger(__name__)

session_name = 'default'
NO_MANAGED_HTTP_BASIC_CREDENTIALS_MESSAGE = "No managed HTTP Basic credentials found."
STDIN_SECRET_FORMAT_ERROR = (
    "HTTP Basic credentials from stdin were not well-formed. Expected USERNAME:PASSWORD."
)
STDIN_SECRET_HELP = (
    'Read one line from stdin in USERNAME:PASSWORD format. '
    'The password is everything after the first colon. Emits no output on success.'
)
STDIN_SECRET_SILENT_HELP = 'Disable terminal echo while reading credentials from stdin.'
SKIP_CONFIRMATION_HELP = 'Do not prompt for confirmation.'


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
    result = validate_and_find_secrets(raw_yaml)
    new_config = WorkflowConfig(raw_yaml=raw_yaml)
    if not result.get('valid', False):
        _save_config(wf_config_store, new_config, session_name)
        raise click.ClickException(
            f"Configuration saved but has validation errors:\n{result.get('errors', 'Unknown error')}")

    _save_config(wf_config_store, new_config, session_name)
    process_secrets(secret_store, result, interactive=False)


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

        result = validate_and_find_secrets(raw_yaml)
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
    process_secrets(secret_store, result, interactive=True)


@configure_group.command(name="edit")
@click.option(
    '--stdin',
    is_flag=True,
    help='Read configuration from stdin instead of interactively launching an editor',
)
@click.pass_context
def edit_config(ctx, stdin):
    """Edit workflow configuration.
    Opens an interactive editor by default or reads from stdin with --stdin."""
    wf_config_store = get_workflow_config_store(ctx)
    secret_store = get_credentials_secret_store(ctx)

    if stdin:
        _handle_stdin_edit(wf_config_store, secret_store, session_name)
    else:
        _handle_editor_edit(wf_config_store, secret_store, session_name)


def _credentials_secret_store(ctx):
    _ensure_workflow_context(ctx)
    return get_credentials_secret_store(ctx)


def _ensure_workflow_context(ctx):
    ctx.ensure_object(dict)
    ctx.obj.setdefault('config_store', None)
    ctx.obj.setdefault('secret_store', None)
    ctx.obj.setdefault('namespace', get_current_namespace())


def _load_current_config(ctx) -> WorkflowConfig:
    _ensure_workflow_context(ctx)
    store = get_workflow_config_store(ctx)
    config = store.load_config(session_name)
    if config is None or not config:
        raise click.ClickException("No configuration found.")
    return config


def _configured_secret_names(ctx):
    config = _load_current_config(ctx)
    result = validate_and_find_secrets(config.raw_yaml)
    if not result.get('valid', False):
        raise click.ClickException(
            f"Current configuration has validation errors:\n{result.get('errors', 'Unknown error')}")
    invalid = result.get('invalidSecrets')
    if invalid:
        raise click.ClickException(f"Invalidly named secrets found: {invalid}")
    return sorted(set(result.get('validSecrets', []) or []))


def _managed_secret_names(ctx):
    return sorted(_credentials_secret_store(ctx).list_secrets())


def _show_secret_names(names, empty_message):
    if not names:
        click.echo(empty_message)
        return
    for name in names:
        click.echo(name)


def _missing_config_secret_names(ctx):
    names = _configured_secret_names(ctx)
    existing = _credentials_secret_store(ctx).secrets_exist(names)
    return [name for name in names if not existing.get(name)]


def _complete_managed_secret(ctx, _param, incomplete):
    try:
        return [name for name in _managed_secret_names(ctx) if name.startswith(incomplete)]
    except Exception:
        return []


def _complete_missing_secret(ctx, _param, incomplete):
    try:
        return [name for name in _missing_config_secret_names(ctx) if name.startswith(incomplete)]
    except Exception:
        return []


def _prompt_basic_auth_credentials():
    username = click.prompt("Username", type=str)
    password = click.prompt("Password", hide_input=True, confirmation_prompt=True)
    return {"username": username, "password": password}


def _read_secret_stdin(silent: bool) -> str:
    stdin_stream = click.get_text_stream('stdin')
    if not silent or not stdin_stream.isatty():
        return stdin_stream.read()

    file_descriptor = stdin_stream.fileno()
    original_attrs = termios.tcgetattr(file_descriptor)
    silent_attrs = original_attrs[:]
    silent_attrs[3] &= ~termios.ECHO
    try:
        termios.tcsetattr(file_descriptor, termios.TCSANOW, silent_attrs)
        return stdin_stream.read()
    finally:
        termios.tcsetattr(file_descriptor, termios.TCSANOW, original_attrs)


def _parse_basic_auth_credentials_from_stdin(silent: bool = False):
    raw_credentials = _read_secret_stdin(silent).rstrip('\r\n')
    if '\n' in raw_credentials or '\r' in raw_credentials or ':' not in raw_credentials:
        raise click.ClickException(STDIN_SECRET_FORMAT_ERROR)

    username, password = raw_credentials.split(':', 1)
    if not username or not password:
        raise click.ClickException(STDIN_SECRET_FORMAT_ERROR)

    return {"username": username, "password": password}


def _get_basic_auth_credentials(stdin: bool, silent: bool = False):
    if silent and not stdin:
        raise click.ClickException("--silent can only be used with --stdin.")
    if stdin:
        return _parse_basic_auth_credentials_from_stdin(silent)
    return _prompt_basic_auth_credentials()


def _read_raw_secret(secret_store, name):
    try:
        return secret_store.v1.read_namespaced_secret(name=name, namespace=secret_store.namespace)
    except ApiException as e:
        if e.status == 404:
            return None
        raise


def _secret_has_managed_labels(secret_store, secret: V1Secret) -> bool:
    labels = secret.metadata.labels if secret.metadata and secret.metadata.labels else {}
    return all(labels.get(key) == value for key, value in secret_store.default_labels.items())


def _require_managed_secret(secret_store, name):
    if not secret_store.secret_exists(name):
        raise click.ClickException(f"No managed HTTP Basic credentials found: {name}")


def _credentials_save_message(message):
    return (message
            .replace("Secret created:", "Credentials created:")
            .replace("Secret updated:", "Credentials updated:")
            .replace("Secret deleted:", "Credentials deleted:"))


@configure_group.group(name="credentials")
def credentials_group():
    """Configure HTTP Basic credentials for web servers the migration assistant communicates with."""


@credentials_group.command(name="list")
@click.pass_context
def list_secrets(ctx):
    """List managed HTTP Basic credentials."""
    _show_secret_names(_managed_secret_names(ctx), NO_MANAGED_HTTP_BASIC_CREDENTIALS_MESSAGE)


@credentials_group.command(name="create")
@click.option('--show-missing', '--list', is_flag=True, default=False,
              help='List configured HTTP Basic credentials that have not been created')
@click.option(
    '--stdin',
    is_flag=True,
    default=False,
    help=STDIN_SECRET_HELP,
)
@click.option('--silent', is_flag=True, default=False, help=STDIN_SECRET_SILENT_HELP)
@click.argument('name', required=False, shell_complete=_complete_missing_secret)
@click.pass_context
def create_secret(ctx, show_missing, stdin, silent, name):
    """Create HTTP Basic credentials.
    Interactively prompts for username and password by default.
    Use --stdin to read USERNAME:PASSWORD non-interactively."""
    if show_missing:
        _show_secret_names(_missing_config_secret_names(ctx), "No missing HTTP Basic credentials found.")
        return

    if not name:
        click.echo("Error: specify a credentials name or --show-missing.\n", err=True)
        click.echo(ctx.get_help())
        ctx.exit(1)

    secret_store = _credentials_secret_store(ctx)
    existing = _read_raw_secret(secret_store, name)
    if existing is not None:
        if _secret_has_managed_labels(secret_store, existing):
            raise click.ClickException(f"Managed HTTP Basic credentials already exist: {name}")
        raise click.ClickException(f"Kubernetes Secret {name} already exists but is not managed by workflow configure.")

    message = secret_store.save_secret(name, _get_basic_auth_credentials(stdin, silent))
    if not stdin:
        click.echo(_credentials_save_message(message))


@credentials_group.command(name="update")
@click.option('--list', 'list_names', is_flag=True, default=False, help='List managed HTTP Basic credentials')
@click.option(
    '--stdin',
    is_flag=True,
    default=False,
    help=STDIN_SECRET_HELP,
)
@click.option('--silent', is_flag=True, default=False, help=STDIN_SECRET_SILENT_HELP)
@click.argument('name', required=False, shell_complete=_complete_managed_secret)
@click.pass_context
def update_secret(ctx, list_names, stdin, silent, name):
    """Update HTTP Basic credentials.
    Interactively prompts for username and password by default.
    Use --stdin to read USERNAME:PASSWORD non-interactively."""
    if list_names:
        _show_secret_names(_managed_secret_names(ctx), NO_MANAGED_HTTP_BASIC_CREDENTIALS_MESSAGE)
        return
    if not name:
        click.echo("Error: specify a credentials name or --list.\n", err=True)
        click.echo(ctx.get_help())
        ctx.exit(1)

    secret_store = _credentials_secret_store(ctx)
    _require_managed_secret(secret_store, name)
    message = secret_store.save_secret(name, _get_basic_auth_credentials(stdin, silent))
    if not stdin:
        click.echo(_credentials_save_message(message))


@credentials_group.command(name="delete")
@click.option('--list', 'list_names', is_flag=True, default=False, help='List managed HTTP Basic credentials')
@click.argument('name', required=False, shell_complete=_complete_managed_secret)
@click.option('--confirm', 'yes', is_flag=True, hidden=True)
@click.option('-y', '--yes', is_flag=True, default=False, help=SKIP_CONFIRMATION_HELP)
@click.pass_context
def delete_secret(ctx, list_names, name, yes):
    """Delete managed HTTP Basic credentials.
    Interactively prompts for confirmation by default. Use -y/--yes to skip the prompt."""
    if list_names:
        _show_secret_names(_managed_secret_names(ctx), NO_MANAGED_HTTP_BASIC_CREDENTIALS_MESSAGE)
        return
    if not name:
        click.echo("Error: specify a credentials name or --list.\n", err=True)
        click.echo(ctx.get_help())
        ctx.exit(1)

    secret_store = _credentials_secret_store(ctx)
    _require_managed_secret(secret_store, name)
    if not yes and not click.confirm(f"Delete managed HTTP Basic credentials '{name}'?"):
        click.echo("Cancelled")
        return
    click.echo(_credentials_save_message(secret_store.delete_secret(name)))


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
