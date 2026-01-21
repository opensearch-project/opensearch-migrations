import logging
import os
import subprocess
import click
import shlex
from kubernetes import client

from .utils import auto_detect_workflow
from ..models.utils import ExitCode, load_k8s_config
from ..services.workflow_service import WorkflowService

logger = logging.getLogger(__name__)

def _get_label_selector(selector_str, prefix, workflow_name):
    """Parses and prefixes label selectors."""
    if not selector_str:
        return ""
    parts = selector_str.split(',')
    prefixed_parts = []
    for part in parts:
        if '=' in part:
            k, v = part.split('=', 1)
            # Only prefix if it's not already a fully qualified domain
            key = f"{prefix}{k}" if '/' not in k else k
            prefixed_parts.append(f"{key}={v}")
        else:
            prefixed_parts.append(part)
    return ",".join(prefixed_parts+[f"workflows.argoproj.io/workflow={workflow_name}"])


@click.command(name="output", context_settings=dict(
    ignore_unknown_options=True,
    allow_extra_args=True,
))
@click.option('--workflow-name', required=False)
@click.option(
    '--argo-server',
    default=f"http://{os.environ.get('ARGO_SERVER_SERVICE_HOST', 'localhost')}"
            f":{os.environ.get('ARGO_SERVER_SERVICE_PORT', '2746')}",
    help='Argo Server URL (default: ARGO_SERVER env var, or ARGO_SERVER_SERVICE_HOST:ARGO_SERVER_SERVICE_PORT)'
)
@click.option('--namespace', default='ma')
@click.option('--insecure', is_flag=True, default=False)
@click.option('--token', help='Bearer token for authentication')
@click.option('--prefix', default='migrations.opensearch.org/', help='Label prefix for filters')
@click.option('-l', '--selector', help='Label selector (e.g. source=a,target=b)')
@click.pass_context
def output_command(ctx, workflow_name, argo_server, namespace, insecure, token, prefix, selector):
    """
    View or tail workflow logs.
    
    Tailing Mode (uses Stern):
      workflow output -l task=create -f --since 5m
      
    History Mode (uses sort -m):
      workflow output -l task=create --all-containers
    """

    if not workflow_name:
        service = WorkflowService()
        workflow_name = auto_detect_workflow(service, namespace, argo_server, token, insecure, ctx)
        if not workflow_name:
            click.echo("No workflows found.  Use --workflow-name to wait for a specific workflow to start.")
            return

    full_selector = _get_label_selector(selector, prefix, workflow_name)

    is_follow = any(arg in ['-f', '--follow'] for arg in ctx.args)
    user_requested_ts = '--timestamps' in ctx.args

    # Create a filtered list of extra arguments to pass to the underlying tools
    # We remove -f, --follow, -l, and --selector because we provide them explicitly
    forbidden_args = {'-f', '--follow', '-l', '--selector'}

    # Handle both the flag and its value if it was passed as '--selector value'
    clean_args = []
    skip_next = False
    for i, arg in enumerate(ctx.args):
        if skip_next:
            skip_next = False
            continue
        if arg in forbidden_args:
            # If it's a flag that takes a value (like -l), skip the next arg too
            if arg in ['-l', '--selector'] and (i + 1) < len(ctx.args):
                skip_next = True
            continue
        clean_args.append(arg)

    if is_follow:
        # Tailing: Use stern
        cmd = ["stern", "-l", full_selector, "-n", namespace] + clean_args
        logger.info(f"Executing: {' '.join(cmd)}")
        
        subprocess.run(cmd)
    else:
        # historical, use kubectl through sort -m in case there are multiple results
        try:
            load_k8s_config()
            v1 = client.CoreV1Api()
            pods = v1.list_namespaced_pod(namespace, label_selector=full_selector)
        except Exception as e:
            click.echo(f"Error listing pods: {e}", err=True)
            ctx.exit(ExitCode.FAILURE.value)

        if not pods.items:
            click.echo("No pods found matching the selector.")
            return

        # Ensure --timestamps is present for internal sorting logic
        internal_args = list(extra_args)
        if '--timestamps' not in internal_args:
            internal_args.append('--timestamps')

        # Build the Bash process substitution string
        log_streams = []
        for pod in pods.items:
            pod_name = pod.metadata.name
            args_str = " ".join(shlex.quote(a) for a in internal_args)
            stream = f"<(stdbuf -oL kubectl logs {pod_name} -n {namespace} {args_str})"
            log_streams.append(stream)

        # Build the final pipeline
        # sort -m: merge pre-sorted streams
        # cut: if user didn't want timestamps, strip the first field (the timestamp)
        final_cmd = f"sort -m --stable {' '.join(log_streams)}"
        if not user_requested_ts:
            final_cmd += " | cut -d' ' -f2-"

        logger.info(f"Executing: /bin/bash -c \"{final_cmd}\"")

        # Must use executable='/bin/bash' because <() is not standard sh
        subprocess.run(final_cmd, shell=True, executable='/bin/bash')