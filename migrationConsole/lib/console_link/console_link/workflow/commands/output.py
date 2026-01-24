import heapq
from contextlib import ExitStack

import click
import logging
import os
import subprocess

from kubernetes import client

from .autocomplete_k8s_labels import _get_label_selector, get_label_completions
from .autocomplete_workflows import DEFAULT_WORKFLOW_NAME, get_workflow_completions
from ..models.utils import load_k8s_config


logger = logging.getLogger(__name__)


@click.command(name="output", context_settings={
    "ignore_unknown_options": True,
    "allow_extra_args": True
})
@click.option('--workflow-name', default=DEFAULT_WORKFLOW_NAME, shell_complete=get_workflow_completions)
@click.option('--all-workflows', is_flag=True, default=False, help='Show output for all workflows')
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
@click.option(
    '-l', '--selector',
    help='Label selector (e.g. source=a,target=b)',
    shell_complete=get_label_completions
)
# We define these just so Click "eats" them from the command line and keeps ctx.args clean
@click.option('-f', '--follow', is_flag=True, expose_value=False)
@click.option('--timestamps', is_flag=True, expose_value=False)
@click.pass_context
def output_command(ctx, workflow_name, all_workflows, namespace, prefix, selector, **kwargs):
    """View or tail workflow logs."""
    # 1. Validation
    _validate_inputs(ctx, all_workflows)

    # 2. Setup
    effective_name = None if all_workflows else workflow_name
    full_selector = _get_label_selector(selector, prefix, effective_name)
    is_follow = ctx.get_parameter_source('follow') != click.core.ParameterSource.DEFAULT

    # 3. Execution Branching
    if is_follow:
        _run_tailing_mode(namespace, full_selector, ctx.args)
    else:
        _run_history_mode(ctx, namespace, full_selector)


def _validate_inputs(ctx, all_workflows):
    """Ensure mutually exclusive flags are respected."""
    is_wf_set = ctx.get_parameter_source('workflow_name') != click.core.ParameterSource.DEFAULT
    if all_workflows and is_wf_set:
        click.echo("Error: --workflow-name and --all-workflows are mutually exclusive", err=True)
        ctx.exit(1)


def _run_tailing_mode(namespace, selector, clean_args):
    """Externalize stern execution."""
    cmd = ["stern", "-l", selector, "-n", namespace] + clean_args
    subprocess.run(cmd)


def _run_history_mode(ctx, namespace, selector):
    """Handle the complex merging of historical logs from multiple pods."""
    user_requested_ts = ctx.get_parameter_source('timestamps') != click.core.ParameterSource.DEFAULT

    pods = []
    try:
        load_k8s_config()
        v1 = client.CoreV1Api()
        pods = v1.list_namespaced_pod(namespace, label_selector=selector)
    except Exception as e:
        click.echo(f"Error listing pods: {e}", err=True)
        ctx.exit(1)

    if not pods.items:
        click.echo("No pods found matching the selector.")
        return

    with ExitStack() as stack:
        processes = []
        for pod in pods.items:
            cmd = ["kubectl", "logs", "--timestamps", pod.metadata.name, "-n", namespace] + ctx.args
            p = stack.enter_context(subprocess.Popen(
                cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, bufsize=1
            ))
            processes.append((pod.metadata.name, p))

        # Merge and Stream
        streams = [p.stdout for _, p in processes]
        _stream_merged_logs(heapq.merge(*streams), user_requested_ts)
        _check_process_errors(processes)


def _stream_merged_logs(merged_logs, show_ts):
    """Handle formatting and printing of the log lines."""
    try:
        for line in merged_logs:
            if not show_ts:
                # Strip RFC3339 timestamp
                parts = line.split(" ", 1)
                line = parts[1] if len(parts) > 1 else line
            click.echo(line.rstrip())
    except KeyboardInterrupt:
        click.echo("\nInterrupted by user.", err=True)


def _check_process_errors(processes):
    """Report errors from finished kubectl processes."""
    for name, p in processes:
        if p.poll() is not None and p.returncode != 0:
            err_out = p.stderr.read().strip()
            if err_out:
                click.echo(f"[{name}] Error: {err_out}", err=True)
