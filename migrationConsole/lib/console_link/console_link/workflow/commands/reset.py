"""Reset command for workflow CLI - resets workflow resources to clean state."""

import base64
import fnmatch
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


def _fetch_workflow_resources(workflow_name, namespace, argo_server, token, insecure):
    """Fetch workflow data and extract managed resource names by type."""
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    url = f"{argo_server}/api/v1/workflows/{namespace}/{workflow_name}"
    response = requests.get(url, headers=headers, verify=not insecure, timeout=30)
    if response.status_code != 200:
        return None

    nodes = response.json().get('status', {}).get('nodes', {})
    return extract_resources_from_nodes(nodes)


def extract_resources_from_nodes(nodes):
    """Extract managed resource names from workflow node data."""
    resources = {
        'proxy_deployments': set(),
        'rfs_deployments': set(),
        'coordinator_clusters': set(),
        'kafka_clusters': set(),
    }

    for node in nodes.values():
        template_name = node.get('templateName', '')
        inputs = {p['name']: p.get('value', '') for p in node.get('inputs', {}).get('parameters', [])}

        if 'proxyName' in inputs and ('proxy' in template_name.lower() or 'capture' in template_name.lower()):
            resources['proxy_deployments'].add(inputs['proxyName'])

        if 'sessionName' in inputs and ('rfs' in template_name.lower() or 'bulk' in template_name.lower()):
            session = inputs['sessionName']
            resources['rfs_deployments'].add(f"{session}-rfs")
            resources['coordinator_clusters'].add(f"{session}-rfs-coordinator")

        if 'clusterName' in inputs and 'kafka' in template_name.lower():
            resources['kafka_clusters'].add(inputs['clusterName'])

    return resources


def _delete_ignoring_not_found(fn, *args, **kwargs):
    """Call a kubernetes delete function, ignoring 404 errors."""
    try:
        fn(*args, **kwargs)
        return True
    except ApiException as e:
        if e.status == 404:
            return False
        raise


def _matches_path(name, path):
    """Check if a resource name matches the given path filter."""
    if path is None:
        return True
    return fnmatch.fnmatch(name, path)


def _reset_proxy_deployments(apps_v1, namespace, names, path):
    """Rolling update capture proxy deployments to noCapture mode."""
    count = 0
    for name in sorted(names):
        if not _matches_path(name, path):
            continue
        try:
            deployment = apps_v1.read_namespaced_deployment(name=name, namespace=namespace)
            container = deployment.spec.template.spec.containers[0]
            args = container.args or []

            # The proxy args are ["---INLINE-JSON", "<base64-encoded-json>"]
            if len(args) >= 2 and args[0] == "---INLINE-JSON":
                try:
                    config = json.loads(base64.b64decode(args[1]).decode())
                except Exception:
                    config = json.loads(args[1])
                config.pop('kafkaConnection', None)
                config['noCapture'] = True
                new_b64 = base64.b64encode(json.dumps(config).encode()).decode()
                patch_args = ["---INLINE-JSON", new_b64]
            else:
                # Fallback: just append --noCapture
                patch_args = args + ["--noCapture"]

            apps_v1.patch_namespaced_deployment(
                name=name, namespace=namespace,
                body={"spec": {"template": {"spec": {"containers": [{"name": container.name, "args": patch_args}]}}}}
            )
            logger.info(f"Updated proxy deployment '{name}' to noCapture mode")
            count += 1
        except ApiException as e:
            if e.status == 404:
                logger.info(f"Proxy deployment '{name}' not found (already removed)")
            else:
                logger.error(f"Failed to update proxy '{name}': {e.reason}")
    return count


def _reset_rfs_deployments(apps_v1, namespace, names, path):
    """Delete RFS deployments."""
    count = 0
    for name in sorted(names):
        if not _matches_path(name, path):
            continue
        if _delete_ignoring_not_found(apps_v1.delete_namespaced_deployment, name=name, namespace=namespace):
            logger.info(f"Deleted RFS deployment '{name}'")
            count += 1
        else:
            logger.info(f"RFS deployment '{name}' not found (already removed)")
    return count


def _reset_coordinator_clusters(apps_v1, core_v1, namespace, names, path):
    """Delete coordinator cluster resources (StatefulSet, Service, Secret)."""
    count = 0
    for name in sorted(names):
        if not _matches_path(name, path):
            continue
        deleted_any = False
        if _delete_ignoring_not_found(apps_v1.delete_namespaced_stateful_set, name=name, namespace=namespace):
            deleted_any = True
        if _delete_ignoring_not_found(core_v1.delete_namespaced_service, name=name, namespace=namespace):
            deleted_any = True
        if _delete_ignoring_not_found(core_v1.delete_namespaced_secret, name=f"{name}-creds", namespace=namespace):
            deleted_any = True

        if deleted_any:
            logger.info(f"Deleted coordinator cluster '{name}'")
            count += 1
        else:
            logger.info(f"Coordinator cluster '{name}' not found (already removed)")
    return count


def _reset_kafka_clusters(custom_api, namespace, names, path):
    """Delete Kafka cluster CRD resources (Kafka, KafkaNodePool, KafkaTopic)."""
    count = 0
    for name in sorted(names):
        if not _matches_path(name, path):
            continue
        deleted_any = False

        for plural in ('kafkatopics', 'kafkanodepools'):
            try:
                items = custom_api.list_namespaced_custom_object(
                    group='kafka.strimzi.io', version='v1',
                    namespace=namespace, plural=plural,
                    label_selector=f'strimzi.io/cluster={name}'
                )
                for item in items.get('items', []):
                    _delete_ignoring_not_found(
                        custom_api.delete_namespaced_custom_object,
                        group='kafka.strimzi.io', version='v1',
                        namespace=namespace, plural=plural, name=item['metadata']['name']
                    )
            except ApiException:
                pass

        if _delete_ignoring_not_found(
            custom_api.delete_namespaced_custom_object,
            group='kafka.strimzi.io', version='v1',
            namespace=namespace, plural='kafkas', name=name
        ):
            deleted_any = True

        if deleted_any:
            logger.info(f"Deleted Kafka cluster '{name}'")
            count += 1
        else:
            logger.info(f"Kafka cluster '{name}' not found (already removed)")
    return count


def _delete_workflow_resource(custom_api, namespace, workflow_name):
    """Delete the Argo workflow."""
    if _delete_ignoring_not_found(
        custom_api.delete_namespaced_custom_object,
        group='argoproj.io', version='v1alpha1',
        namespace=namespace, plural='workflows', name=workflow_name
    ):
        logger.info(f"Deleted workflow '{workflow_name}'")
        return True
    else:
        logger.info(f"Workflow '{workflow_name}' not found (already removed)")
        return False


def reset_workflow_resources(workflow_name, namespace, argo_server, token=None, insecure=False,
                             path=None, delete_workflow=True):
    """Reset workflow resources to a clean state. Callable from code or CLI.

    Returns the number of resources affected, or -1 if workflow not found.
    """
    resources = _fetch_workflow_resources(workflow_name, namespace, argo_server, token, insecure)
    if resources is None:
        logger.warning(f"Workflow '{workflow_name}' not found in namespace '{namespace}'")
        return -1

    load_k8s_config()
    apps_v1 = client.AppsV1Api()
    core_v1 = client.CoreV1Api()
    custom_api = client.CustomObjectsApi()

    total = 0
    total += _reset_proxy_deployments(apps_v1, namespace, resources['proxy_deployments'], path)
    total += _reset_rfs_deployments(apps_v1, namespace, resources['rfs_deployments'], path)
    total += _reset_coordinator_clusters(apps_v1, core_v1, namespace, resources['coordinator_clusters'], path)
    total += _reset_kafka_clusters(custom_api, namespace, resources['kafka_clusters'], path)

    if delete_workflow and path is None:
        if _delete_workflow_resource(custom_api, namespace, workflow_name):
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
@click.option('--path', default=None, help='Resource name/glob to reset. If omitted, resets all resources.')
@click.option('--yes', '-y', is_flag=True, default=False, help='Skip confirmation prompt')
@click.pass_context
def reset_command(ctx, workflow_name, argo_server, namespace, insecure, token, path, yes):
    """Reset workflow resources to a clean state.

    Discovers and removes resources created by the workflow:
    proxy deployments are set to noCapture mode, RFS deployments are deleted,
    coordinator clusters are deleted, Kafka clusters are deleted,
    and (at root level) the workflow itself is deleted.

    Use --path to scope the reset to specific resources by name or glob pattern.

    Example:
        workflow reset
        workflow reset --path "my-proxy-*"
        workflow reset --yes
    """
    try:
        resources = _fetch_workflow_resources(workflow_name, namespace, argo_server, token, insecure)
        if resources is None:
            click.echo(f"Workflow '{workflow_name}' not found in namespace '{namespace}'.")
            ctx.exit(ExitCode.FAILURE.value)
            return

        is_root = path is None
        has_matching = any(
            _matches_path(name, path)
            for names in resources.values()
            for name in names
        )

        if not has_matching and not is_root:
            click.echo("No resources found matching the given path.")
            return

        # Show what will be reset
        click.echo(f"Resources to reset for workflow '{workflow_name}':")
        for name in sorted(resources['proxy_deployments']):
            if _matches_path(name, path):
                click.echo(f"  Proxy deployment: {name} → set noCapture")
        for name in sorted(resources['rfs_deployments']):
            if _matches_path(name, path):
                click.echo(f"  RFS deployment: {name} → delete")
        for name in sorted(resources['coordinator_clusters']):
            if _matches_path(name, path):
                click.echo(f"  Coordinator cluster: {name} → delete")
        for name in sorted(resources['kafka_clusters']):
            if _matches_path(name, path):
                click.echo(f"  Kafka cluster: {name} → delete")
        if is_root:
            click.echo(f"  Workflow: {workflow_name} → delete")

        if not yes:
            click.confirm("Proceed with reset?", abort=True)

        result = reset_workflow_resources(workflow_name, namespace, argo_server, token, insecure, path)
        click.echo(f"\nReset complete. {result} resource(s) affected.")

    except click.Abort:
        click.echo("Reset cancelled.")
    except Exception as e:
        click.echo(f"Error: {str(e)}", err=True)
        ctx.exit(ExitCode.FAILURE.value)
