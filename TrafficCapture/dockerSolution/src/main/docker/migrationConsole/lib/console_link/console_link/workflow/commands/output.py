"""Output command for workflow CLI - shows output for workflow steps."""

import logging
import os
import click
from kubernetes import client, config
from kubernetes.client.rest import ApiException

from ..models.utils import ExitCode
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
    """Initialize Kubernetes client with appropriate configuration."""
    try:
        config.load_incluster_config()
        logger.info("Using in-cluster Kubernetes configuration")
    except config.ConfigException:
        try:
            config.load_kube_config()
            logger.info("Using kubeconfig file")
        except config.ConfigException as e:
            click.echo(
                f"Error: Could not load Kubernetes configuration. "
                f"Make sure kubectl is configured or you're running inside a cluster.\n"
                f"Details: {e}",
                err=True
            )
            ctx.exit(ExitCode.FAILURE.value)
    return client.CoreV1Api()


def _find_pod_by_node_id(v1, namespace, workflow_name, node_id, selected_node, ctx):
    """Find the pod associated with a workflow node ID."""
    click.echo(f"\nSearching for pod with node-id: {node_id}...")

    pods = v1.list_namespaced_pod(
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


def _display_container_output(v1, pod_name, namespace, container_name, tail_lines):
    """Display output from a single container."""
    click.echo(f"\n{'─' * 80}")
    click.echo(f"Container: {container_name}")
    click.echo(f"{'─' * 80}\n")

    try:
        container_output = v1.read_namespaced_pod_log(
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


def _display_pod_output(v1, pod_name, namespace, selected_node, tail_lines, ctx):
    """Display output from all containers in a pod."""
    try:
        click.echo(f"Found pod: {pod_name}")

        pod = v1.read_namespaced_pod(name=pod_name, namespace=namespace)
        all_containers = _get_all_container_names(pod)

        click.echo(f"Retrieving output from all containers: {', '.join(all_containers)}")

        # Display header
        click.echo("\n" + "=" * 80)
        click.echo(f"Output for: {selected_node['display_name']}")
        click.echo(f"Pod: {pod_name}")
        click.echo(f"Phase: {selected_node['phase']}")
        if tail_lines:
            click.echo(f"Showing last {tail_lines} lines per container")
        click.echo("=" * 80)

        # Display output from each container
        for container_name in all_containers:
            _display_container_output(v1, pod_name, namespace, container_name, tail_lines)

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
    default=100,
    help='Number of lines to show from the end of the output (default: 100)'
)
@click.pass_context
def output_command(ctx, workflow_name, argo_server, namespace, insecure, token, tail_lines):
    """Show output for workflow steps.

    Displays workflow steps and allows interactive selection to view output.
    Only shows output for Pod-type steps that have executed.

    Example:
        workflow output
        workflow output my-workflow
        workflow output --tail-lines 50
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
        v1 = _initialize_k8s_client(ctx)
        node_id = selected_node['id']

        try:
            pod_name = _find_pod_by_node_id(v1, namespace, workflow_name, node_id, selected_node, ctx)
            _display_pod_output(v1, pod_name, namespace, selected_node, tail_lines, ctx)

        except ApiException as e:
            click.echo(f"\nError retrieving output: {e}", err=True)
            ctx.exit(ExitCode.FAILURE.value)

    except Exception as e:
        click.echo(f"Error: {str(e)}", err=True)
        logger.exception("Unexpected error in output command")
        ctx.exit(ExitCode.FAILURE.value)
