"""Output command for workflow CLI - shows output for workflow steps."""

import logging
import os
import click
from kubernetes import client
from kubernetes.client.rest import ApiException

from ..models.utils import ExitCode, load_k8s_config
from ..services.workflow_service import WorkflowService
from .status import _display_workflow_status
from .utils import auto_detect_workflow

logger = logging.getLogger(__name__)


def _get_phase_indicator(phase):
    """Get the visual indicator for a workflow phase."""
    if phase == 'Succeeded':
        return "✓"
    elif phase in ('Failed', 'Error'):
        return "✗"
    elif phase == 'Running':
        return "▶"
    else:
        return "○"


def _display_step_menu(pod_nodes):
    """Display the step selection menu."""
    click.echo("\n" + "=" * 60)
    click.echo("Select a step to view output:")
    click.echo("-" * 60)
    for idx, node in enumerate(pod_nodes):
        phase_indicator = _get_phase_indicator(node['phase'])
        click.echo(f"  [{idx}] {phase_indicator} {node['display_name']} ({node['phase']})")
    click.echo("  [c] Cancel")
    click.echo("-" * 60)


def _get_user_choice(pod_nodes, ctx):
    """Get and validate user's step selection."""
    choice = click.prompt("\nEnter your choice", type=str).strip().lower()

    if choice == 'c':
        click.echo("Cancelled.")
        return None

    try:
        choice_idx = int(choice)
        if choice_idx < 0 or choice_idx >= len(pod_nodes):
            click.echo(f"Error: Invalid choice. Please select 0-{len(pod_nodes) - 1} or 'c'", err=True)
            ctx.exit(ExitCode.FAILURE.value)
        return choice_idx
    except ValueError:
        click.echo(
            f"Error: Invalid input. Please enter a number (0-{len(pod_nodes) - 1}) or 'c'",
            err=True
        )
        ctx.exit(ExitCode.FAILURE.value)


def _initialize_k8s_client(ctx):
    """Initialize Kubernetes client with appropriate configuration.

    Attempts to load in-cluster config first (for pods), then falls back to kubeconfig.

    Args:
        ctx: Click context for exit handling

    Returns:
        client.CoreV1Api: Kubernetes Core V1 API client

    Exits:
        If neither configuration method succeeds
    """
    try:
        load_k8s_config()
    except Exception as e:
        click.echo(
            f"Error: Could not load Kubernetes configuration. "
            f"Make sure kubectl is configured or you're running inside a cluster.\n"
            f"Details: {e}",
            err=True
        )
        ctx.exit(ExitCode.FAILURE.value)
    return client.CoreV1Api()


def _find_pod_by_node_id(k8s_core_api, namespace, workflow_name, node_id, selected_node, ctx):
    """Find the pod associated with a workflow node ID."""
    click.echo(f"\nSearching for pod with node-id: {node_id}...")

    pods = k8s_core_api.list_namespaced_pod(
        namespace=namespace,
        label_selector=f"workflows.argoproj.io/workflow={workflow_name}"
    )

    for pod in pods.items:
        annotations = pod.metadata.annotations or {}
        if annotations.get('workflows.argoproj.io/node-id') == node_id:
            return pod.metadata.name

    click.echo(
        f"\nError: Could not find pod for step '{selected_node['display_name']}'.\n"
        f"Node ID: {node_id}\n"
        f"The pod may have been cleaned up or not yet created.",
        err=True
    )
    ctx.exit(ExitCode.FAILURE.value)


def _get_all_container_names(pod):
    """Get list of all container names from a pod."""
    all_containers = []
    if pod.spec.init_containers:
        all_containers.extend([c.name for c in pod.spec.init_containers])
    if pod.spec.containers:
        all_containers.extend([c.name for c in pod.spec.containers])
    return all_containers


def _stream_container_logs(k8s_core_api, pod_name, namespace, container_name, tail_lines):
    """Stream logs from a container in real-time.

    Args:
        k8s_core_api: Kubernetes CoreV1Api client
        pod_name: Name of the pod
        namespace: Kubernetes namespace
        container_name: Name of the container
        tail_lines: Number of initial lines to show
    """
    try:
        # Use follow=True and _preload_content=False for streaming
        stream = k8s_core_api.read_namespaced_pod_log(
            name=pod_name,
            namespace=namespace,
            container=container_name,
            tail_lines=tail_lines,
            follow=True,
            _preload_content=False
        )

        # Stream logs line by line
        try:
            for line in stream:
                click.echo(line.decode('utf-8'), nl=False)
        except Exception:
            # Stream ended or was interrupted, this is normal
            pass

    except ApiException as e:
        if e.status == 400:
            click.echo("(Container not ready or no output available)")
        else:
            click.echo(f"(Error streaming output: {e.reason})")


def _display_container_output(k8s_core_api, pod_name, namespace, container_name, tail_lines, follow=False):
    """Display output from a single container.

    Args:
        k8s_core_api: Kubernetes CoreV1Api client
        pod_name: Name of the pod
        namespace: Kubernetes namespace
        container_name: Name of the container
        tail_lines: Number of lines to show
        follow: If True, stream logs in real-time; if False, show static snapshot
    """
    click.echo(f"\n{'─' * 80}")
    click.echo(f"Container: {container_name}")
    click.echo(f"{'─' * 80}\n")

    if follow:
        # Stream logs in real-time
        _stream_container_logs(k8s_core_api, pod_name, namespace, container_name, tail_lines)
    else:
        # Display static snapshot
        try:
            container_output = k8s_core_api.read_namespaced_pod_log(
                name=pod_name,
                namespace=namespace,
                container=container_name,
                tail_lines=tail_lines
            )

            if container_output:
                click.echo(container_output)
            else:
                click.echo("(No output available)")

        except ApiException as container_error:
            if container_error.status == 400:
                click.echo("(Container not ready or no output available)")
            else:
                click.echo(f"(Error retrieving output: {container_error.reason})")


def _display_pod_output(k8s_core_api, pod_name, namespace, selected_node, tail_lines, follow, ctx):
    """Display output from all containers in a pod.

    Args:
        k8s_core_api: Kubernetes CoreV1Api client
        pod_name: Name of the pod
        namespace: Kubernetes namespace
        selected_node: Selected workflow node information
        tail_lines: Number of lines to show
        follow: If True, stream logs in real-time; if False, show static snapshot
        ctx: Click context
    """
    try:
        click.echo(f"Found pod: {pod_name}")

        pod = k8s_core_api.read_namespaced_pod(name=pod_name, namespace=namespace)

        # Get container lists
        init_containers = [c.name for c in pod.spec.init_containers] if pod.spec.init_containers else []
        main_containers = [c.name for c in pod.spec.containers] if pod.spec.containers else []
        all_containers = init_containers + main_containers

        if follow:
            # In follow mode, only stream from user containers (exclude Argo sidecars like 'wait' and 'init')
            # Filter out Argo executor containers to only show actual user workload output
            user_containers = [c for c in main_containers if c not in ('wait', 'init')]
            containers_to_stream = user_containers if user_containers else main_containers
            click.echo(f"Streaming output from container(s): {', '.join(containers_to_stream)}")
            click.echo("(Press Ctrl+C to stop streaming)")
        else:
            # In static mode, show all containers
            containers_to_stream = all_containers
            click.echo(f"Retrieving output from all containers: {', '.join(all_containers)}")

        # Display header
        click.echo("\n" + "=" * 80)
        click.echo(f"Output for: {selected_node['display_name']}")
        click.echo(f"Pod: {pod_name}")
        click.echo(f"Phase: {selected_node['phase']}")
        if follow:
            click.echo(f"Mode: Streaming (showing last {tail_lines} lines, then following)")
        elif tail_lines:
            click.echo(f"Showing last {tail_lines} lines per container")
        click.echo("=" * 80)

        # Display output from each container
        try:
            for container_name in containers_to_stream:
                _display_container_output(k8s_core_api, pod_name, namespace, container_name, tail_lines, follow)
        except KeyboardInterrupt:
            click.echo("\n\nStreaming interrupted by user (Ctrl+C)")
            return

        click.echo("\n" + "=" * 80)

    except ApiException as e:
        if e.status == 404:
            click.echo(
                f"\nError: Pod '{pod_name}' not found in namespace '{namespace}'.\n"
                f"The pod may have been cleaned up or not yet created.",
                err=True
            )
        else:
            click.echo(f"\nError retrieving output: {e}", err=True)
        ctx.exit(ExitCode.FAILURE.value)


@click.command(name="output")
@click.argument('workflow_name', required=False)
@click.option(
    '--argo-server',
    default=f"http://{os.environ.get('ARGO_SERVER_SERVICE_HOST', 'localhost')}"
    f":{os.environ.get('ARGO_SERVER_SERVICE_PORT', '2746')}",
    help='Argo Server URL (default: ARGO_SERVER env var, or ARGO_SERVER_SERVICE_HOST:ARGO_SERVER_SERVICE_PORT)'
)
@click.option(
    '--namespace',
    default='ma',
    help='Kubernetes namespace for the workflow (default: ma)'
)
@click.option(
    '--insecure',
    is_flag=True,
    default=False,
    help='Skip TLS certificate verification'
)
@click.option(
    '--token',
    help='Bearer token for authentication'
)
@click.option(
    '--tail-lines',
    type=int,
    default=500,
    help='Number of lines to show from the end of the output (default: 500)'
)
@click.option(
    '--follow',
    '-f',
    is_flag=True,
    default=False,
    help='Stream logs in real-time (similar to tail -f). Press Ctrl+C to stop.'
)
@click.pass_context
def output_command(ctx, workflow_name, argo_server, namespace, insecure, token, tail_lines, follow):
    """Show output for workflow steps.

    Displays workflow steps and allows interactive selection to view output.
    Only shows output for Pod-type steps that have executed.

    Use --follow/-f to stream logs in real-time for running workflows.

    Example:
        workflow output
        workflow output my-workflow
        workflow output --tail-lines 50
        workflow output --follow
        workflow output -f --tail-lines 200
    """
    try:
        service = WorkflowService()

        # Auto-detect workflow if name not provided
        if not workflow_name:
            workflow_name = auto_detect_workflow(service, namespace, argo_server, token, insecure, ctx)
            if not workflow_name:
                return

        # Get workflow status
        result = service.get_workflow_status(
            workflow_name=workflow_name,
            namespace=namespace,
            argo_server=argo_server,
            token=token,
            insecure=insecure
        )

        if not result['success']:
            click.echo(f"Error getting workflow status: {result['error']}", err=True)
            ctx.exit(ExitCode.FAILURE.value)

        # Display workflow status using existing function from status.py
        # Don't show the output hint since we're already in the output command
        _display_workflow_status(result, show_output_hint=False)

        # Filter for Pod-type nodes from step_tree
        step_tree = result.get('step_tree', [])
        pod_nodes = [node for node in step_tree if node['type'] == 'Pod']

        if not pod_nodes:
            click.echo("\nNo pod steps found in this workflow.")
            return

        # Display selection menu and get user choice
        _display_step_menu(pod_nodes)
        choice_idx = _get_user_choice(pod_nodes, ctx)
        if choice_idx is None:
            return

        selected_node = pod_nodes[choice_idx]

        # Initialize Kubernetes client and find pod
        k8s_core_api = _initialize_k8s_client(ctx)
        node_id = selected_node['id']

        try:
            pod_name = _find_pod_by_node_id(k8s_core_api, namespace, workflow_name, node_id, selected_node, ctx)
            _display_pod_output(k8s_core_api, pod_name, namespace, selected_node, tail_lines, follow, ctx)

        except ApiException as e:
            click.echo(f"\nError retrieving output: {e}", err=True)
            ctx.exit(ExitCode.FAILURE.value)

    except Exception as e:
        click.echo(f"Error: {str(e)}", err=True)
        logger.exception("Unexpected error in output command")
        ctx.exit(ExitCode.FAILURE.value)
