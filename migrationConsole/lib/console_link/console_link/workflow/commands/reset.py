"""Reset command for workflow CLI - resets workflow resources to clean state.

Discovers resettable resources from workflow nodes that have a 'resetAction' input
parameter, then executes those actions. This keeps all resource-specific knowledge
in the workflow templates, not in the CLI.

A resetAction is a JSON object like:
  {"action": "delete", "apiVersion": "apps/v1", "kind": "Deployment", "name": "my-proxy", "namespace": "ma"}
"""

import json
import logging
import os

import click
import requests
from kubernetes import client
from kubernetes.client.rest import ApiException

from ..models.utils import ExitCode, load_k8s_config
from .autocomplete_workflows import DEFAULT_WORKFLOW_NAME, get_workflow_completions

logger = logging.getLogger(__name__)


def _fetch_reset_actions(workflow_name, namespace, argo_server, token, insecure):
    """Fetch workflow nodes and extract resetAction parameters.

    Returns list of (display_name, action_dict) or None if workflow not found.
    """
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    url = f"{argo_server}/api/v1/workflows/{namespace}/{workflow_name}"
    response = requests.get(url, headers=headers, verify=not insecure, timeout=30)
    if response.status_code != 200:
        return None

    nodes = response.json().get('status', {}).get('nodes', {})
    return extract_reset_actions(nodes)


def _get_node_param(node, param_name):
    """Get a named parameter value from a workflow node's inputs."""
    for p in node.get('inputs', {}).get('parameters', []):
        if p.get('name') == param_name:
            return p.get('value', '')
    return ''


def _collect_done_keys(nodes):
    """Collect resetDone keys from succeeded nodes."""
    done_keys = set()
    for node in nodes.values():
        if node.get('phase') != 'Succeeded':
            continue
        value = _get_node_param(node, 'resetDone')
        if value:
            done_keys.add(value)
    return done_keys


def _action_key(action):
    """Compute the resource key for a resetAction."""
    return f"{action.get('apiVersion', '')}/{action.get('kind', '')}/{action.get('name', '')}"


def extract_reset_actions(nodes):
    """Extract resetAction parameters from workflow nodes, excluding those marked as resetDone."""
    done_keys = _collect_done_keys(nodes)
    actions = []
    for node in nodes.values():
        raw = _get_node_param(node, 'resetAction')
        if not raw:
            continue
        try:
            action = json.loads(raw)
        except (json.JSONDecodeError, TypeError):
            continue
        if not action or _action_key(action) in done_keys:
            continue
        actions.append((node.get('displayName', ''), action))
    return actions


def _is_custom_resource(api_version):
    """Check if an apiVersion refers to a custom resource (has a dot in the group)."""
    # Core: "v1", "apps/v1" — Custom: "kafka.strimzi.io/v1", "argoproj.io/v1alpha1"
    if '/' not in api_version:
        return False
    group = api_version.rsplit('/', 1)[0]
    return '.' in group


def _execute_reset_action(action, default_namespace):
    """Execute a single reset action against the k8s API.

    Returns True if the action was executed, False if resource was not found.
    """
    api_version = action.get('apiVersion', '')
    kind = action.get('kind', '')
    name = action.get('name', '')
    namespace = action.get('namespace', default_namespace)
    act = action.get('action', 'delete')

    if not name or not kind:
        logger.warning(f"Skipping malformed resetAction: {action}")
        return False

    try:
        if _is_custom_resource(api_version):
            group, version = api_version.rsplit('/', 1)
            custom_api = client.CustomObjectsApi()
            plural = _kind_to_plural(kind)
            if act == 'delete':
                custom_api.delete_namespaced_custom_object(
                    group=group, version=version,
                    namespace=namespace, plural=plural, name=name
                )
            elif act == 'patch':
                custom_api.patch_namespaced_custom_object(
                    group=group, version=version,
                    namespace=namespace, plural=plural, name=name,
                    body=action.get('patch', {})
                )
        else:
            # Core/apps resources
            if act == 'delete':
                _delete_core_resource(kind, name, namespace)
            elif act == 'patch':
                _patch_core_resource(kind, name, namespace, action.get('patch', {}))
        return True
    except ApiException as e:
        if e.status == 404:
            return False
        raise


def _delete_core_resource(kind, name, namespace):
    """Delete a core/apps k8s resource by kind."""
    kind_lower = kind.lower()
    if kind_lower == 'deployment':
        client.AppsV1Api().delete_namespaced_deployment(name=name, namespace=namespace)
    elif kind_lower == 'statefulset':
        client.AppsV1Api().delete_namespaced_stateful_set(name=name, namespace=namespace)
    elif kind_lower == 'service':
        client.CoreV1Api().delete_namespaced_service(name=name, namespace=namespace)
    elif kind_lower == 'secret':
        client.CoreV1Api().delete_namespaced_secret(name=name, namespace=namespace)
    else:
        logger.warning(f"Unknown kind for delete: {kind}")


def _patch_core_resource(kind, name, namespace, patch):
    """Patch a core/apps k8s resource by kind."""
    kind_lower = kind.lower()
    if kind_lower == 'deployment':
        client.AppsV1Api().patch_namespaced_deployment(name=name, namespace=namespace, body=patch)
    elif kind_lower == 'statefulset':
        client.AppsV1Api().patch_namespaced_stateful_set(name=name, namespace=namespace, body=patch)
    else:
        logger.warning(f"Unknown kind for patch: {kind}")


def _kind_to_plural(kind):
    """Convert a k8s Kind to its plural form for the API."""
    mapping = {
        'kafka': 'kafkas',
        'kafkanodepool': 'kafkanodepools',
        'kafkatopic': 'kafkatopics',
    }
    return mapping.get(kind.lower(), kind.lower() + 's')


def _delete_argo_workflow(namespace, workflow_name):
    """Delete the Argo workflow resource. Returns True if deleted."""
    try:
        custom_api = client.CustomObjectsApi()
        custom_api.delete_namespaced_custom_object(
            group='argoproj.io', version='v1alpha1',
            namespace=namespace, plural='workflows', name=workflow_name
        )
        logger.info(f"Deleted workflow '{workflow_name}'")
        return True
    except ApiException as e:
        if e.status == 404:
            logger.info(f"Workflow '{workflow_name}' not found (already removed)")
        else:
            logger.error(f"Failed to delete workflow: {e}")
    return False


def _execute_and_log(display_name, action, namespace):
    """Execute a single reset action and log the result. Returns 1 if acted, 0 otherwise."""
    kind = action.get('kind', '')
    name = action.get('name', '')
    act = action.get('action', 'delete')
    try:
        if _execute_reset_action(action, namespace):
            logger.info(f"Reset {act} {kind} '{name}' (from: {display_name})")
            return 1
        logger.info(f"{kind} '{name}' not found (already removed)")
    except Exception as e:
        logger.error(f"Failed to {act} {kind} '{name}': {e}")
    return 0


def reset_workflow_resources(workflow_name, namespace, argo_server, token=None, insecure=False,
                             delete_workflow=True):
    """Reset workflow resources to a clean state. Callable from code or CLI.

    Returns the number of resources affected, or -1 if workflow not found.
    """
    actions = _fetch_reset_actions(workflow_name, namespace, argo_server, token, insecure)
    if actions is None:
        logger.warning(f"Workflow '{workflow_name}' not found in namespace '{namespace}'")
        return -1

    if not actions and not delete_workflow:
        logger.info("No resetAction parameters found in workflow nodes.")
        return 0

    load_k8s_config()
    total = sum(_execute_and_log(name, action, namespace) for name, action in actions)

    if delete_workflow and _delete_argo_workflow(namespace, workflow_name):
        total += 1

    logger.info(f"Reset complete. {total} resource(s) affected.")
    return total


@click.command(name="reset")
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
@click.option('--yes', '-y', is_flag=True, default=False, help='Skip confirmation prompt')
@click.pass_context
def reset_command(ctx, workflow_name, argo_server, namespace, insecure, token, yes):
    """Reset workflow resources to a clean state.

    Discovers resettable resources from workflow nodes that declare a resetAction
    input parameter, then executes those actions. The workflow itself is also deleted.

    Example:
        workflow reset
        workflow reset --yes
        workflow reset --workflow-name my-workflow
    """
    try:
        actions = _fetch_reset_actions(workflow_name, namespace, argo_server, token, insecure)
        if actions is None:
            click.echo(f"Workflow '{workflow_name}' not found in namespace '{namespace}'.")
            ctx.exit(ExitCode.FAILURE.value)
            return

        if not actions:
            click.echo("No resettable resources found in workflow.")
            if not yes:
                click.confirm(f"Delete workflow '{workflow_name}' anyway?", abort=True)
        else:
            click.echo(f"Resources to reset for workflow '{workflow_name}':")
            for display_name, action in actions:
                act = action.get('action', 'delete')
                kind = action.get('kind', '')
                name = action.get('name', '')
                click.echo(f"  {kind} '{name}' → {act}  (from: {display_name})")
            click.echo(f"  Workflow '{workflow_name}' → delete")

            if not yes:
                click.confirm("Proceed with reset?", abort=True)

        result = reset_workflow_resources(workflow_name, namespace, argo_server, token, insecure)
        click.echo(f"\nReset complete. {result} resource(s) affected.")

    except click.Abort:
        click.echo("Reset cancelled.")
    except Exception as e:
        click.echo(f"Error: {str(e)}", err=True)
        ctx.exit(ExitCode.FAILURE.value)
