"""Interactive manage command for workflow CLI - interactive tree navigation for log viewing."""
import datetime
import logging
import os
import sys
import tempfile
import click
from kubernetes import client

# Internal imports
from ..models.utils import ExitCode, load_k8s_config
from .autocomplete_workflows import DEFAULT_WORKFLOW_NAME, get_workflow_completions

import psutil

from ..tui.manage_injections import make_argo_service, make_k8s_pod_scraper, WaiterInterface
from ..tui.workflow_manage_app import WorkflowTreeApp


logger = logging.getLogger(__name__)


def log_mem(context: str):
    process = psutil.Process(os.getpid())
    mem = process.memory_info().rss / 1024 / 1024
    logger.info(f"MEMORY [{context}]: {mem:.2f} MB")


def _configure_file_logging():
    """Configure logging to redirect to file only when manage command runs."""
    log_dir = os.path.join(tempfile.gettempdir(), "workflow_manage")
    os.makedirs(log_dir, exist_ok=True)
    log_file = os.path.join(log_dir, f"manage_{datetime.datetime.now().strftime('%Y%m%d_%H%M%S')}.log")

    file_handler = logging.FileHandler(log_file)
    file_handler.setFormatter(
        logging.Formatter('%(asctime)s - %(levelname)s - [%(threadName)s] - %(name)s - %(message)s')
    )

    root_logger = logging.getLogger()
    root_logger.addHandler(file_handler)
    root_logger.setLevel(logging.INFO)
    
    # Disable console handlers by setting their level to CRITICAL+1 (effectively silencing them)
    # This avoids closing streams which breaks Click's CliRunner in tests
    for handler in root_logger.handlers:
        if isinstance(handler, logging.StreamHandler) and handler.stream in (sys.stdout, sys.stderr):
            handler.setLevel(logging.CRITICAL + 1)


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
    # Explicitly use the loaded configuration to avoid threading/global state issues
    api_client = client.ApiClient(client.Configuration.get_default_copy())
    return client.CoreV1Api(api_client)


# --- Entrypoint ---
@click.command(name="manage")
@click.option('--workflow-name', default=DEFAULT_WORKFLOW_NAME, shell_complete=get_workflow_completions)
@click.option(
    '--argo-server',
    default=f"http://{os.environ.get('ARGO_SERVER_SERVICE_HOST', 'localhost')}"
            f":{os.environ.get('ARGO_SERVER_SERVICE_PORT', '2746')}",
    help='Argo Server URL (default: ARGO_SERVER env var, or ARGO_SERVER_SERVICE_HOST:ARGO_SERVER_SERVICE_PORT)'
)
@click.option('--namespace', default='ma')
@click.option('--insecure', is_flag=True, default=False)
@click.option('--token')
@click.pass_context
def manage_command(ctx, workflow_name, argo_server, namespace, insecure, token):
    _configure_file_logging()  # Configure logging when command actually runs
    try:
        app = WorkflowTreeApp(namespace, workflow_name,
                              make_argo_service(argo_server, insecure, token),
                              make_k8s_pod_scraper(_initialize_k8s_client(ctx)),
                              WaiterInterface.default(workflow_name, namespace),
                              3.0)
        app.run()
    except Exception as e:
        click.echo(f"Error: {str(e)}", err=True)
        ctx.exit(ExitCode.FAILURE.value)
