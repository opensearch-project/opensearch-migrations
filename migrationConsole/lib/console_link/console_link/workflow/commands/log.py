import heapq
import sys
from contextlib import ExitStack
from collections import Counter

import click
import logging
import subprocess

from kubernetes import client
from kubernetes.client.rest import ApiException

from .autocomplete_k8s_labels import complete_label_value
from .autocomplete_workflows import DEFAULT_WORKFLOW_NAME, get_workflow_completions
from .argo_utils import DEFAULT_ARGO_SERVER_URL
from .crd_utils import (
    CRD_GROUP,
    CRD_VERSION,
    RESETTABLE_PLURALS,
    list_migration_resources,
    parse_resource_path,
    resource_display_name,
)
from ..models.utils import load_k8s_config, get_current_namespace


logger = logging.getLogger(__name__)
_RESOURCE_OUTPUT_LABELS = {'strimzi.io/cluster'}

# `workflow log` is intentionally for pod logs only. Durable command output is
# handled by the workflow templates instead: the few output-producing steps
# write explicit small artifacts under migration-outputs/<resource>/<name>/,
# patch the owning CR status with the current S3 key, and `workflow show`
# reads through that CR pointer. `workflow reset` deletes the resource prefix
# so retained output files and any Argo-created sidecar metadata are removed
# together. This lets us keep global Argo archiveLogs disabled.

# Flags recognized on either side of `--`.  When they appear in the
# pass-through args they are "promoted" so the command behaves as if
# the user had placed them before `--`.
_PROMOTABLE_FLAGS = {
    '--timestamps': 'timestamps',
    '-f': 'follow',
    '--follow': 'follow',
}


def _get_label_selector(selector_str, prefix, workflow_name):
    """Parses and prefixes label selectors."""
    parts = selector_str.split(',') if selector_str else []
    prefixed_parts = []
    for part in parts:
        if '=' in part:
            k, v = part.split('=', 1)
            key = f"{prefix}{k}" if '/' not in k else k
            prefixed_parts.append(f"{key}={v}")
        else:
            prefixed_parts.append(part)
    if workflow_name:
        prefixed_parts.append(f"workflows.argoproj.io/workflow={workflow_name}")
    return ",".join(prefixed_parts)


def _promote_known_flags(extra_args):
    """Extract known flags from pass-through args so they take effect on both sides of `--`."""
    promoted = set()
    remaining = []
    for arg in extra_args:
        flag_name = _PROMOTABLE_FLAGS.get(arg)
        if flag_name:
            promoted.add(flag_name)
        else:
            remaining.append(arg)
    return promoted, remaining


def _selector_parts(selectors, prefix, workflow_name):
    parts = []
    for selector in selectors:
        parts.extend(selector.split(','))
    return _get_label_selector(",".join(parts), prefix, workflow_name)


def _split_passthrough_args(extra_args):
    """Split Click's variadic args into before/after the original -- marker."""
    args = list(extra_args)
    try:
        before_dashdash = Counter(sys.argv[:sys.argv.index('--')])
    except ValueError:
        for idx, arg in enumerate(args):
            if arg.startswith('-'):
                return args[:idx], args[idx:]
        return args, []

    before = []
    after = []
    for arg in args:
        if before_dashdash[arg] > 0:
            before_dashdash[arg] -= 1
            before.append(arg)
        else:
            after.append(arg)
    return before, after


def _validate_scope(ctx, all_workflows):
    is_wf_set = ctx.get_parameter_source('workflow_name') != click.core.ParameterSource.DEFAULT
    if all_workflows and is_wf_set:
        click.echo("Error: --workflow-name and --all-workflows are mutually exclusive", err=True)
        ctx.exit(1)


def _validate_selector_arg(ctx, selector):
    if selector.startswith('-') or '=' not in selector:
        click.echo(f"Error: unexpected argument '{selector}'. Label selectors must be key=value.\n", err=True)
        click.echo(ctx.get_help())
        ctx.exit(1)


def _filter_selectors(source, target, snapshot, task, from_snapshot_migration, labels):
    selectors = []
    for key, value in (
        ('source', source),
        ('target', target),
        ('snapshot', snapshot),
        ('task', task),
        ('from-snapshot-migration', from_snapshot_migration),
    ):
        if value:
            selectors.append(f"{key}={value}")
    selectors.extend(labels)
    return selectors


def _migration_resource_names(namespace):
    return [
        resource_display_name(plural, name)
        for plural, name, _, _ in list_migration_resources(namespace)
    ]


def _get_resource_completions(ctx, _, incomplete):
    namespace = ctx.params.get('namespace', 'ma')
    try:
        load_k8s_config()
        names = _migration_resource_names(namespace)
    except Exception:
        return []
    return [name for name in names if name.startswith(incomplete)]


def _find_resource_object(namespace, resource_name):
    """Find a migration CR by typed type.name path, falling back to raw name."""
    custom = client.CustomObjectsApi()
    parsed = parse_resource_path(resource_name)
    search_plurals = [parsed[0]] if parsed else RESETTABLE_PLURALS
    search_name = parsed[1] if parsed else resource_name
    for plural in search_plurals:
        try:
            return custom.get_namespaced_custom_object(
                group=CRD_GROUP,
                version=CRD_VERSION,
                namespace=namespace,
                plural=plural,
                name=search_name,
            )
        except ApiException as e:
            if e.status != 404:
                raise
    return None


def _resource_label_selectors(ctx, namespace, resource_name, prefix):
    resource = _find_resource_object(namespace, resource_name)
    if not resource:
        click.echo(f"No migration resource matching '{resource_name}'.", err=True)
        ctx.exit(1)
    labels = resource.get('metadata', {}).get('labels', {}) or {}
    selectors = [
        f"{key}={value}"
        for key, value in sorted(labels.items())
        if (key.startswith(prefix) or key in _RESOURCE_OUTPUT_LABELS) and value
    ]
    if not selectors:
        click.echo(f"Migration resource '{resource_name}' has no output labels.", err=True)
        ctx.exit(1)
    return selectors


def _list_available_labels(workflow_name, all_workflows, namespace, **kwargs):
    from .autocomplete_k8s_labels import _fetch_workflow_labels
    effective_name = None if all_workflows else workflow_name
    argo_server = kwargs.get('argo_server', DEFAULT_ARGO_SERVER_URL)
    token = kwargs.get('token')
    insecure = kwargs.get('insecure', True)
    label_map, _ = _fetch_workflow_labels(
        effective_name or DEFAULT_WORKFLOW_NAME, namespace,
        argo_server, token, insecure)
    if not label_map:
        click.echo("No matching pods found.")
    else:
        for key in sorted(label_map):
            for val in sorted(label_map[key]):
                click.echo(f"{key}={val}")


def _run_output(ctx, namespace, selector, follow, timestamps, passthrough_args):
    promoted, passthrough_args = _promote_known_flags(list(passthrough_args))
    follow = follow or 'follow' in promoted
    timestamps = timestamps or 'timestamps' in promoted

    if '--help' in passthrough_args:
        _show_underlying_help(follow)
        return

    if follow:
        _run_tailing_mode(namespace, selector, timestamps, passthrough_args)
    else:
        _run_history_mode(ctx, namespace, selector, timestamps, passthrough_args)


def _output_options(func):
    func = click.option('--workflow-name', default=DEFAULT_WORKFLOW_NAME, shell_complete=get_workflow_completions,
                        hidden=True,
                        help='Workflow name to show output for (default: WORKFLOW_NAME env var)')(func)
    func = click.option('--all-workflows', is_flag=True, default=False,
                        hidden=True,
                        help='Show output for all workflows instead of a single workflow')(func)
    func = click.option(
        '--argo-server',
        default=DEFAULT_ARGO_SERVER_URL, hidden=True, envvar='ARGO_SERVER',
        help='Argo Server URL (default: ARGO_SERVER env var, or ARGO_SERVER_SERVICE_HOST:ARGO_SERVER_SERVICE_PORT)'
    )(func)
    func = click.option('--namespace', default=get_current_namespace, hidden=True, envvar='WORKFLOW_NAMESPACE',
                        help='Kubernetes namespace')(func)
    func = click.option('--insecure', is_flag=True, default=True, hidden=True, envvar='WORKFLOW_INSECURE',
                        help='Skip TLS certificate verification (default: True)')(func)
    func = click.option('--token', hidden=True, envvar='ARGO_TOKEN', help='Bearer token for authentication')(func)
    func = click.option('--prefix', default='migrations.opensearch.org/',
                        help='Label prefix for filters (default: migrations.opensearch.org/)')(func)
    func = click.option('-f', '--follow', is_flag=True, default=False,
                        help='Stream live logs via stern instead of showing history')(func)
    func = click.option('--timestamps', is_flag=True, default=False,
                        help='Show timestamps in log output')(func)
    return func


@click.group(name="log", invoke_without_command=False)
def log_command():
    """View or tail workflow logs.

    \b
    Subcommands:
      all       Show all logs for the selected workflow.
      resource  Show logs for one migration resource.
      filter    Show logs matching one or more label filters.

    \b
    Arguments after -- are forwarded to the underlying tool
    (kubectl logs for history, stern for follow mode).
    Use -- --help to see what the underlying tool supports.
    """


@log_command.command("all")
@_output_options
@click.argument('extra_args', nargs=-1, type=click.UNPROCESSED)
@click.pass_context
def output_all(ctx, workflow_name, all_workflows, namespace, prefix,
               follow, timestamps, extra_args, **kwargs):
    """Show all logs for the selected workflow.

    \b
    Examples:
      workflow log all
      workflow log all -- --since=1h --tail=100
      workflow log all -f -- --container=proxy
    """
    _validate_scope(ctx, all_workflows)
    before_dashdash, passthrough_args = _split_passthrough_args(extra_args)
    if before_dashdash:
        click.echo(f"Error: unexpected argument '{before_dashdash[0]}'.\n", err=True)
        click.echo(ctx.get_help())
        ctx.exit(1)
    effective_name = None if all_workflows else workflow_name
    full_selector = _selector_parts([], prefix, effective_name)
    _run_output(ctx, namespace, full_selector, follow, timestamps, passthrough_args)


@log_command.command("resource")
@click.option('--list', 'list_labels', is_flag=True, default=False,
              help='List available migration resources and exit')
@_output_options
@click.argument('resource_name', required=False, shell_complete=_get_resource_completions)
@click.argument('extra_args', nargs=-1, type=click.UNPROCESSED)
@click.pass_context
def output_resource(ctx, list_labels, workflow_name, all_workflows, namespace, prefix,
                    follow, timestamps, resource_name, extra_args, **kwargs):
    """Show logs for one migration resource.

    \b
    Examples:
      workflow log resource captureproxy.my-proxy
      workflow log resource snapshotmigration.migration-0 -- --tail=100
    """
    _validate_scope(ctx, all_workflows)
    if list_labels:
        try:
            load_k8s_config()
            names = _migration_resource_names(namespace)
        except Exception as e:
            click.echo(f"Error listing migration resources: {e}", err=True)
            ctx.exit(1)
            return
        if not names:
            click.echo("No migration resources found.")
        else:
            for name in sorted(names):
                click.echo(name)
        return

    if resource_name is None:
        click.echo("Error: specify a migration resource or --list.\n", err=True)
        click.echo(ctx.get_help())
        ctx.exit(1)

    before_dashdash, passthrough_args = _split_passthrough_args(extra_args)
    if before_dashdash:
        click.echo("Error: resource accepts exactly one migration resource.\n", err=True)
        click.echo(ctx.get_help())
        ctx.exit(1)

    try:
        load_k8s_config()
    except Exception as e:
        click.echo(f"Error: could not load Kubernetes configuration: {e}", err=True)
        ctx.exit(1)
        return
    resource_selectors = _resource_label_selectors(ctx, namespace, resource_name, prefix)
    uses_external_resource_selector = any(
        selector.startswith('strimzi.io/cluster=') for selector in resource_selectors
    )
    effective_name = None if all_workflows or uses_external_resource_selector else workflow_name
    full_selector = _selector_parts(resource_selectors, prefix, effective_name)
    _run_output(ctx, namespace, full_selector, follow, timestamps, passthrough_args)


@log_command.command("filter")
@click.option('--list', 'list_labels', is_flag=True, default=False,
              help='List available label selectors and exit')
@click.option('--source', shell_complete=complete_label_value('source'),
              help='Filter output by source label')
@click.option('--target', shell_complete=complete_label_value('target'),
              help='Filter output by target label')
@click.option('--snapshot', shell_complete=complete_label_value('snapshot'),
              help='Filter output by snapshot label')
@click.option('--task', shell_complete=complete_label_value('task'),
              help='Filter output by task label')
@click.option('--from-snapshot-migration', shell_complete=complete_label_value('from-snapshot-migration'),
              help='Filter output by from-snapshot-migration label')
@click.option('--label', 'labels', multiple=True,
              help='Additional raw key=value label selector. May be used multiple times.')
@_output_options
@click.argument('extra_args', nargs=-1, type=click.UNPROCESSED)
@click.pass_context
def output_filter(ctx, **params):
    """Show logs matching one or more label filters.

    \b
    Examples:
      workflow log filter --source mycluster --target target1
      workflow log filter --snapshot snap1 --task metadataMigrate
      workflow log filter --label custom.example/key=value -- --since=1h
    """
    workflow_name = params['workflow_name']
    all_workflows = params['all_workflows']
    namespace = params['namespace']
    prefix = params['prefix']
    labels = params['labels']

    _validate_scope(ctx, all_workflows)
    if params['list_labels']:
        _list_available_labels(workflow_name, all_workflows, namespace, **params)
        return

    selectors, passthrough_args = _split_passthrough_args(params['extra_args'])
    if selectors:
        click.echo(f"Error: unexpected argument '{selectors[0]}'. Use filter options such as --task or --label.\n",
                   err=True)
        click.echo(ctx.get_help())
        ctx.exit(1)

    selectors = _filter_selectors(
        params['source'], params['target'], params['snapshot'], params['task'],
        params['from_snapshot_migration'], labels)
    if not selectors:
        click.echo("Error: specify --list or one or more filter options.\n", err=True)
        click.echo(ctx.get_help())
        ctx.exit(1)

    for selector in labels:
        _validate_selector_arg(ctx, selector)

    effective_name = None if all_workflows else workflow_name
    full_selector = _selector_parts(selectors, prefix, effective_name)
    _run_output(ctx, namespace, full_selector, params['follow'], params['timestamps'], passthrough_args)


def _show_underlying_help(follow):
    """Run a single --help invocation of the underlying tool."""
    if follow:
        subprocess.run(["stern", "--help"])
    else:
        subprocess.run(["kubectl", "logs", "--help"])


def _run_tailing_mode(namespace, selector, timestamps, passthrough_args):
    """Externalize stern execution."""
    cmd = ["stern", "-l", selector, "-n", namespace]
    if timestamps:
        cmd.append("--timestamps")
    cmd.extend(passthrough_args)
    subprocess.run(cmd)


def _run_history_mode(ctx, namespace, selector, show_timestamps, passthrough_args):
    """Handle the complex merging of historical logs from multiple pods."""
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
            cmd = ["kubectl", "logs", "--timestamps", pod.metadata.name, "-n", namespace] + passthrough_args
            p = stack.enter_context(subprocess.Popen(
                cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, bufsize=1
            ))
            processes.append((pod.metadata.name, p))

        # Merge and Stream
        streams = [p.stdout for _, p in processes]
        _stream_merged_logs(heapq.merge(*streams), show_timestamps)
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
