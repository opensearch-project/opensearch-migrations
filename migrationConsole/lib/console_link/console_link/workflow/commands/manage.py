"""Interactive manage command for workflow CLI - interactive tree navigation for log viewing."""
import datetime
import logging
import os
import sys
import tempfile
import click

# Internal imports
from ..models.utils import ExitCode
from ..services.workflow_service import WorkflowService
from .output import _initialize_k8s_client
from .utils import auto_detect_workflow

import psutil

from ..tui.manage_injections import make_argo_service, make_k8s_pod_scraper, WaiterInterface
from ..tui.workflow_manage_app import WorkflowTreeApp


def log_mem(context: str):
    process = psutil.Process(os.getpid())
    mem = process.memory_info().rss / 1024 / 1024
    logger.info(f"MEMORY [{context}]: {mem:.2f} MB")


# --- Logging Configuration ---
log_dir = os.path.join(tempfile.gettempdir(), "workflow_manage")
os.makedirs(log_dir, exist_ok=True)
log_file = os.path.join(log_dir, f"manage_{datetime.datetime.now().strftime('%Y%m%d_%H%M%S')}.log")

file_handler = logging.FileHandler(log_file)
file_handler.setFormatter(logging.Formatter('%(asctime)s - %(levelname)s - [%(threadName)s] - %(name)s - %(message)s'))

# Redirect ALL logging to file
root_logger = logging.getLogger()
root_logger.addHandler(file_handler)
root_logger.setLevel(logging.INFO)
# Remove any existing console handlers
for handler in root_logger.handlers[:]:
    if isinstance(handler, logging.StreamHandler) and handler.stream in (sys.stdout, sys.stderr):
        root_logger.removeHandler(handler)

logger = logging.getLogger(__name__)


# --- Entrypoint ---
@click.command(name="manage")
@click.option('--workflow-name', required=False)
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
    try:
        service = WorkflowService()
        if not workflow_name:
            workflow_name = auto_detect_workflow(service, namespace, argo_server, token, insecure, ctx)
            if not workflow_name:
                click.echo("No workflows found.  Use --workflow-name to wait for a specific workflow to start.")
                return
        app = WorkflowTreeApp(namespace, workflow_name,
                              make_argo_service(argo_server, insecure, token),
                              make_k8s_pod_scraper(_initialize_k8s_client(ctx)),
                              WaiterInterface.default(workflow_name, namespace),
                              3.0)
        app.run()
    except Exception as e:
        click.echo(f"Error: {str(e)}", err=True)
        ctx.exit(ExitCode.FAILURE.value)
