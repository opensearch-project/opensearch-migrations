"""Output command for workflow CLI - shows output for workflow steps."""

import logging
import os
import click
from kubernetes import client, config
from kubernetes.client.rest import ApiException

from ..models.utils import ExitCode
from ..services.workflow_service import WorkflowService
from .status import _display_workflow_status

logger = logging.getLogger(__name__)


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

        # Auto-detect workflow if name not provided (copy pattern from approve.py)
        if not workflow_name:
            list_result = service.list_workflows(
                namespace=namespace,
                argo_server=argo_server,
                token=token,
                insecure=insecure
            )

            if not list_result['success']:
                click.echo(f"Error listing workflows: {list_result['error']}", err=True)
                ctx.exit(ExitCode.FAILURE.value)

            if list_result['count'] == 0:
                click.echo(f"Error: No workflows found in namespace {namespace}", err=True)
                ctx.exit(ExitCode.FAILURE.value)
            elif list_result['count'] > 1:
                workflows_list = ', '.join(list_result['workflows'])
                click.echo(
                    f"Error: Multiple workflows found. Please specify which one to view output for.\n"
                    f"Found workflows: {workflows_list}",
                    err=True
                )
                ctx.exit(ExitCode.FAILURE.value)

            workflow_name = list_result['workflows'][0]
            click.echo(f"Auto-detected workflow: {workflow_name}")

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
        _display_workflow_status(result)

        # Filter for Pod-type nodes from step_tree
        step_tree = result.get('step_tree', [])
        pod_nodes = [node for node in step_tree if node['type'] == 'Pod']

        if not pod_nodes:
            click.echo("\nNo pod steps found in this workflow.")
            ctx.exit(ExitCode.SUCCESS.value)

        # Display selection menu
        click.echo("\n" + "=" * 60)
        click.echo("Select a step to view output:")
        click.echo("-" * 60)
        for idx, node in enumerate(pod_nodes):
            phase_indicator = "✓" if node['phase'] == 'Succeeded' else \
                "✗" if node['phase'] in ('Failed', 'Error') else \
                "▶" if node['phase'] == 'Running' else \
                "○"
            click.echo(f"  [{idx}] {phase_indicator} {node['display_name']} ({node['phase']})")
        click.echo("  [c] Cancel")
        click.echo("-" * 60)

        # Get user selection
        choice = click.prompt("\nEnter your choice", type=str).strip().lower()

        if choice == 'c':
            click.echo("Cancelled.")
            ctx.exit(ExitCode.SUCCESS.value)

        # Validate numeric choice
        try:
            choice_idx = int(choice)
            if choice_idx < 0 or choice_idx >= len(pod_nodes):
                click.echo(f"Error: Invalid choice. Please select 0-{len(pod_nodes) - 1} or 'c'", err=True)
                ctx.exit(ExitCode.FAILURE.value)
        except ValueError:
            click.echo(
                f"Error: Invalid input. Please enter a number (0-{len(pod_nodes) - 1}) or 'c'",
                err=True
            )
            ctx.exit(ExitCode.FAILURE.value)

        selected_node = pod_nodes[choice_idx]

        # Initialize Kubernetes client
        try:
            # Try in-cluster config first (when running inside k8s)
            config.load_incluster_config()
            logger.info("Using in-cluster Kubernetes configuration")
        except config.ConfigException:
            # Fall back to kubeconfig file (local development)
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

        v1 = client.CoreV1Api()

        # Find the pod by searching for the workflow node-id annotation
        # Argo creates pods with annotation: workflows.argoproj.io/node-id = node['id']
        node_id = selected_node['id']

        click.echo(f"\nSearching for pod with node-id: {node_id}...")

        try:
            # List pods with the workflow label and find the one with matching node-id annotation
            pods = v1.list_namespaced_pod(
                namespace=namespace,
                label_selector=f"workflows.argoproj.io/workflow={workflow_name}"
            )

            # Find pod with matching node-id annotation
            pod_name = None
            for pod in pods.items:
                annotations = pod.metadata.annotations or {}
                if annotations.get('workflows.argoproj.io/node-id') == node_id:
                    pod_name = pod.metadata.name
                    break

            if not pod_name:
                click.echo(
                    f"\nError: Could not find pod for step '{selected_node['display_name']}'.\n"
                    f"Node ID: {node_id}\n"
                    f"The pod may have been cleaned up or not yet created.",
                    err=True
                )
                ctx.exit(ExitCode.FAILURE.value)

            click.echo(f"Found pod: {pod_name}")

            # Get pod details to find all containers
            pod = v1.read_namespaced_pod(name=pod_name, namespace=namespace)

            # Get list of all containers (init + regular containers)
            all_containers = []
            if pod.spec.init_containers:
                all_containers.extend([c.name for c in pod.spec.init_containers])
            if pod.spec.containers:
                all_containers.extend([c.name for c in pod.spec.containers])

            click.echo(f"Retrieving output from all containers: {', '.join(all_containers)}")

            # Display header
            click.echo("\n" + "=" * 80)
            click.echo(f"Output for: {selected_node['display_name']}")
            click.echo(f"Pod: {pod_name}")
            click.echo(f"Phase: {selected_node['phase']}")
            if tail_lines:
                click.echo(f"Showing last {tail_lines} lines per container")
            click.echo("=" * 80)

            # Retrieve and display output from each container
            for container_name in all_containers:
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
                        click.echo(f"(Container not ready or no output available)")
                    else:
                        click.echo(f"(Error retrieving output: {container_error.reason})")

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

    except Exception as e:
        click.echo(f"Error: {str(e)}", err=True)
        logger.exception("Unexpected error in output command")
        ctx.exit(ExitCode.FAILURE.value)
