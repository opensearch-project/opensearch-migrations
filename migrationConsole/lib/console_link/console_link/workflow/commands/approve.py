"""Approve command for workflow CLI - resumes suspended workflows in Argo Workflows."""

import json
import logging
import os
import tempfile
import time
from pathlib import Path
from typing import Any

import click
from click.shell_completion import CompletionItem

from ..models.utils import ExitCode
from .autocomplete_workflows import DEFAULT_WORKFLOW_NAME, get_workflow_completions
from .suspend_steps import (
    RESET_PREFIX,
    fetch_workflow_nodes,
    find_suspend_steps,
    match_steps,
    approve_steps,
)

logger = logging.getLogger(__name__)

_AUTOCOMPLETE_APPROVAL_CACHE_TTL_SECONDS = 10


def _get_cache_file(workflow_name: str) -> Path:
    cache_dir = Path(tempfile.gettempdir()) / "workflow_completions"
    cache_dir.mkdir(exist_ok=True)
    return cache_dir / f"approval_{workflow_name}.json"


def _fetch_approvable_steps(workflow_name, namespace, argo_server, token, insecure):
    """Fetch suspended steps eligible for approval (excludes reset: steps)."""
    nodes = fetch_workflow_nodes(workflow_name, namespace, argo_server, token, insecure)
    if nodes is None:
        return []
    return find_suspend_steps(nodes, exclude_prefix=RESET_PREFIX, phase_filter='Running')


def _get_cached_suspended_names(ctx) -> list[tuple[Any]] | list[tuple[str, str, str]] | list[Any]:
    """Fetch and cache suspended step names."""
    workflow_name = ctx.params.get('workflow_name') or DEFAULT_WORKFLOW_NAME
    cache_file = _get_cache_file(workflow_name)

    if cache_file.exists() and (time.time() - cache_file.stat().st_mtime) < _AUTOCOMPLETE_APPROVAL_CACHE_TTL_SECONDS:
        try:
            data = json.loads(cache_file.read_text())
            return [tuple(x) for x in data['suspended']]
        except Exception:
            pass

    try:
        namespace = ctx.params.get('namespace', 'ma')
        argo_server = ctx.params.get('argo_server') or os.environ.get('ARGO_SERVER') or (
            f"http://{os.environ.get('ARGO_SERVER_SERVICE_HOST', 'localhost')}"
            f":{os.environ.get('ARGO_SERVER_SERVICE_PORT', '2746')}"
        )
        token = ctx.params.get('token')
        insecure = ctx.params.get('insecure', False)

        suspended = _fetch_approvable_steps(workflow_name, namespace, argo_server, token, insecure)
        cache_file.write_text(json.dumps({'suspended': suspended}))
        return suspended
    except Exception:
        return []


def get_approval_task_name_completions(ctx, _, incomplete):
    """Shell completion for approval step names."""
    suspended = _get_cached_suspended_names(ctx)
    return [
        CompletionItem(name_param, help=display_name)
        for _, name_param, display_name, _ in suspended
        if name_param.startswith(incomplete)
    ]


@click.command(name="approve")
@click.argument('task-names', nargs=-1, required=True, shell_complete=get_approval_task_name_completions)
@click.option('--workflow-name', default=DEFAULT_WORKFLOW_NAME, shell_complete=get_workflow_completions)
@click.option(
    '--argo-server',
    default=lambda: os.environ.get(
        'ARGO_SERVER',
        f"http://{os.environ.get('ARGO_SERVER_SERVICE_HOST', 'localhost')}:"
        f"{os.environ.get('ARGO_SERVER_SERVICE_PORT', '2746')}"
    ),
    help='Argo Server URL'
)
@click.option('--namespace', default='ma')
@click.option('--insecure', is_flag=True, default=False)
@click.option('--token', help='Bearer token for authentication')
@click.pass_context
def approve_command(ctx, task_names, workflow_name, argo_server, namespace, insecure, token):
    """Approve suspended workflow steps matching TASK_NAMES.

    Each TASK_NAME can be an exact name or glob pattern (e.g., *.metadataMigrate).

    Example:
        workflow approve source.target.metadataMigrate
        workflow approve "*.metadataMigrate"
        workflow approve step1 step2 step3
    """
    try:
        steps = _fetch_approvable_steps(workflow_name, namespace, argo_server, token, insecure)

        if not steps:
            click.echo("No suspended steps found waiting for approval.")
            ctx.exit(ExitCode.FAILURE.value)

        matches = match_steps(steps, task_names)

        if not matches:
            click.echo(f"No suspended steps match {task_names}.")
            click.echo("Available suspended steps:")
            for _, name, disp, _ in steps:
                click.echo(f"  - {name} ({disp})")
            ctx.exit(ExitCode.FAILURE.value)

        approved, error = approve_steps(
            matches, workflow_name, namespace, argo_server, token, insecure)

        if error:
            ctx.exit(ExitCode.FAILURE.value)
        else:
            click.echo(f"\nApproved {approved} step(s).")

    except Exception as e:
        click.echo(f"Error: {str(e)}", err=True)
        ctx.exit(ExitCode.FAILURE.value)
