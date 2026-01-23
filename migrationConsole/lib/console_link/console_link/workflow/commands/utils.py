"""Shared utility functions for workflow commands."""

import logging

from click.shell_completion import CompletionItem

from kubernetes import client

from ..models.utils import load_k8s_config

logger = logging.getLogger(__name__)

DEFAULT_WORKFLOW_NAME = "migration-workflow"


def get_workflow_completions(ctx, param, incomplete):
    """Shell completion for workflow names via k8s API."""
    try:
        load_k8s_config()
        custom_api = client.CustomObjectsApi()
        namespace = ctx.params.get('namespace') if ctx.params else None
        namespace = namespace or 'ma'

        workflows = custom_api.list_namespaced_custom_object(
            group="argoproj.io",
            version="v1alpha1",
            namespace=namespace,
            plural="workflows"
        )

        return [
            CompletionItem(wf['metadata']['name'])
            for wf in workflows.get('items', [])
            if wf['metadata']['name'].startswith(incomplete)
        ]
    except Exception:
        pass
    return []
