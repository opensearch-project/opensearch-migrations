"""Shared helpers for validating workflow configs and checking referenced secrets.

These functions are used by both `workflow configure edit` (which creates/updates
secrets interactively) and `workflow submit` (which verifies secrets exist before
submitting the workflow).
"""

import logging

import click

from ..models.secret_store import SecretStore
from ..services.script_runner import ScriptRunner

logger = logging.getLogger(__name__)

# Label value used to identify HTTP-Basic credential Kubernetes Secrets managed
# by the workflow CLI. Kept in sync with ``get_credentials_secret_store`` in
# ``workflow.models.utils`` so that secrets created via ``configure edit`` are
# the same ones verified by ``submit``.
CREDENTIALS_USE_CASE_LABEL = "http-basic-credentials"


def get_credentials_secret_store_for_namespace(namespace: str) -> SecretStore:
    """Construct a ``SecretStore`` for HTTP-Basic credentials in the given namespace.

    Mirrors the context-based ``get_credentials_secret_store`` helper in
    ``workflow.models.utils``; this variant is used by commands (like ``submit``)
    where the namespace is a command-level option rather than a click-context
    attribute.
    """
    return SecretStore(
        namespace=namespace,
        default_labels={"use-case": CREDENTIALS_USE_CASE_LABEL},
    )


def validate_and_find_secrets(raw_yaml: str) -> dict:
    """Validate config via TS Zod schema and scrape secrets in one call.

    Returns a dict with:
      - ``valid``: bool
      - ``errors``: optional validation error string
      - ``validSecrets``: optional list of basic-auth secret names referenced in
        the config that pass naming rules
      - ``invalidSecrets``: optional list of basic-auth secret names that fail
        naming rules
    """
    runner = ScriptRunner()
    result = runner.get_basic_creds_secrets_in_config(raw_yaml)
    logger.info(f"got back script result for validate_and_find_secrets: {result}")
    return result


def process_secrets(secret_store: SecretStore, result: dict, interactive: bool = False) -> None:
    """Process secrets from a ``validate_and_find_secrets`` result.

    In interactive mode, missing secrets trigger a prompt to create them.
    In non-interactive mode, missing secrets cause a :class:`click.ClickException`.
    """
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


def verify_configured_secrets_exist(secret_store: SecretStore, raw_yaml: str) -> None:
    """Non-interactive guard for ``workflow submit``.

    Runs the same validation + secret scrape as `configure edit`, but only
    ever verifies existence — it never prompts or mutates secrets. Raises a
    :class:`click.ClickException` if any referenced secrets are missing or if
    the config references invalidly named secrets.
    """
    result = validate_and_find_secrets(raw_yaml)
    process_secrets(secret_store, result, interactive=False)


def _notify_existing_secrets(existing, interactive: bool) -> None:
    if not existing:
        return
    msg = (f"Found {len(existing)} existing secret{'s' if len(existing) > 1 else ''} "
           f"that will be used for HTTP-Basic authentication "
           f"of requests to clusters:\n  " + "\n  ".join(existing))
    if interactive:
        click.echo(msg)
    else:
        logger.info(msg)


def _handle_missing_config_secrets(secret_store: SecretStore, missing, interactive: bool) -> None:
    if not missing:
        return
    if interactive:
        _handle_add_basic_creds_secrets(secret_store, missing)
    else:
        raise click.ClickException(
            f"Found {len(missing)} missing secret{'s' if len(missing) > 1 else ''} "
            f"that must be created to make well-formed HTTP-Basic requests to clusters:\n  " +
            "\n  ".join(missing) +
            "\n\nRun `workflow configure credentials create` before `workflow submit`."
        )


def _handle_add_basic_creds_secrets(secret_store: SecretStore, missing_names) -> None:
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
