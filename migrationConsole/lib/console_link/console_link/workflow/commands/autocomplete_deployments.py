"""Shared tab-completion for pausable Deployments."""

import json
import logging
import tempfile
import time
from pathlib import Path

from click.shell_completion import CompletionItem

from .autocomplete_workflows import DEFAULT_WORKFLOW_NAME

logger = logging.getLogger(__name__)

_CACHE_TTL_SECONDS = 10


def _get_cache_file(workflow_name: str, kind: str) -> Path:
    cache_dir = Path(tempfile.gettempdir()) / "workflow_completions"
    cache_dir.mkdir(exist_ok=True)
    return cache_dir / f"deployments_{kind}_{workflow_name}.json"


def _fetch_deployment_names(workflow_name: str, namespace: str, kind: str = "all") -> list[str]:
    """Fetch pausable deployment task names from K8s.

    kind: "running" (non-paused), "paused", or "all"
    """
    from ..services.deployment_service import DeploymentService
    try:
        service = DeploymentService()
        deployments = service.discover_pausable_deployments(workflow_name, namespace)
        if kind == "paused":
            deployments = [d for d in deployments if d.is_paused]
        elif kind == "running":
            deployments = [d for d in deployments if not d.is_paused]
        return [d.display_name for d in deployments]
    except Exception:
        return []


def _get_cached_names(ctx, kind: str) -> list[str]:
    workflow_name = ctx.params.get('workflow_name') or DEFAULT_WORKFLOW_NAME
    cache_file = _get_cache_file(workflow_name, kind)

    if cache_file.exists() and (time.time() - cache_file.stat().st_mtime) < _CACHE_TTL_SECONDS:
        try:
            return json.loads(cache_file.read_text())
        except Exception:
            pass

    try:
        namespace = ctx.params.get('namespace', 'ma')
        names = _fetch_deployment_names(workflow_name, namespace, kind=kind)
        cache_file.write_text(json.dumps(names))
        return names
    except Exception:
        return []


def get_running_deployment_completions(ctx, _, incomplete):
    """Tab-completion for running (non-paused) pausable Deployments."""
    names = _get_cached_names(ctx, kind="running")
    return [CompletionItem(n) for n in names if n.startswith(incomplete)]


def get_paused_deployment_completions(ctx, _, incomplete):
    """Tab-completion for paused Deployments."""
    names = _get_cached_names(ctx, kind="paused")
    return [CompletionItem(n) for n in names if n.startswith(incomplete)]


def get_all_deployment_completions(ctx, _, incomplete):
    """Tab-completion for all pausable Deployments."""
    names = _get_cached_names(ctx, kind="all")
    return [CompletionItem(n) for n in names if n.startswith(incomplete)]
