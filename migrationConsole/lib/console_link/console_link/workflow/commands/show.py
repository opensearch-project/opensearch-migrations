"""Show command for workflow CLI - prints managed workflow output artifacts."""

from datetime import datetime, timezone
import logging
from dataclasses import dataclass
from typing import Any, Dict, Iterable, List, Optional

import click
from kubernetes import client
from kubernetes.client.rest import ApiException

from ..models.utils import get_current_namespace, load_k8s_config
from .artifact_store import ArtifactStoreError, artifact_uri, list_artifacts, read_artifact_text
from .crd_utils import CRD_GROUP, CRD_VERSION, DISPLAY_NAMES, parse_resource_path, resource_display_name
from .log import (
    _split_passthrough_args,
)

logger = logging.getLogger(__name__)

MANAGED_OUTPUT_NAMES = {
    "snapshotmigrations": ("metadataEvaluate", "metadataMigrate"),
}
OUTPUT_TASKS = {
    "evaluatemetadata": {
        "plural": "snapshotmigrations",
        "output": "metadataEvaluate",
        "display": "evaluatemetadata",
    },
    "migratemetadata": {
        "plural": "snapshotmigrations",
        "output": "metadataMigrate",
        "display": "migratemetadata",
    },
}
OUTPUT_TASK_ALIASES = {
    "metadataevaluate": "evaluatemetadata",
    "metadatamigrate": "migratemetadata",
}
OUTPUT_ROOT = "migration-outputs"
GATE_OUTPUT_TARGETS = {
    "evaluatemetadata": ("snapshotmigration", "metadataEvaluate"),
    "migratemetadata": ("snapshotmigration", "metadataMigrate"),
}


@dataclass(frozen=True)
class ManagedOutput:
    resource_name: str
    output_name: str
    ref: Dict[str, str]
    content: str


def _fetch_resource(namespace: str, resource_name: str) -> Dict[str, Any]:
    parsed = parse_resource_path(resource_name)
    if not parsed:
        raise click.ClickException("Use a resource name in type.name form, such as datasnapshot.source-snap1.")
    plural, name = parsed
    try:
        return client.CustomObjectsApi().get_namespaced_custom_object(
            group=CRD_GROUP,
            version=CRD_VERSION,
            namespace=namespace,
            plural=plural,
            name=name,
        )
    except ApiException as e:
        if e.status == 404:
            raise click.ClickException(f"Migration resource '{resource_name}' was not found.") from e
        raise click.ClickException(f"Error fetching migration resource '{resource_name}': {e}") from e


def _list_resources_for_task(namespace: str, task: str) -> List[tuple[str, Dict[str, Any]]]:
    task_info = OUTPUT_TASKS[task]
    plural = task_info["plural"]
    output_name = task_info["output"]
    custom = client.CustomObjectsApi()
    try:
        items = custom.list_namespaced_custom_object(
            group=CRD_GROUP,
            version=CRD_VERSION,
            namespace=namespace,
            plural=plural,
        ).get("items", [])
    except ApiException:
        return []
    resources = []
    for item in items:
        refs = _output_refs(item)
        if output_name in refs:
            display_name = resource_display_name(plural, item["metadata"]["name"])
            resources.append((display_name, item))
    return sorted(resources, key=lambda pair: pair[0])


def _canonical_task_name(value: Optional[str]) -> Optional[str]:
    if not value:
        return None
    normalized = value.lower()
    if normalized in OUTPUT_TASKS or normalized in OUTPUT_TASK_ALIASES:
        return OUTPUT_TASK_ALIASES.get(normalized, normalized)
    return None


def _resolve_show_target(resource_name: str, output_name: Optional[str]) -> tuple[str, Optional[str]]:
    if parse_resource_path(resource_name):
        return resource_name, output_name

    gate_type, dot, gate_target = resource_name.partition(".")
    mapped = GATE_OUTPUT_TARGETS.get(gate_type.lower())
    if dot and gate_target and mapped:
        resource_type, mapped_output = mapped
        return f"{resource_type}.{gate_target}", output_name or mapped_output
    return resource_name, output_name


def _managed_output_names_for_resource(resource_name: str) -> Iterable[str]:
    parsed = parse_resource_path(resource_name)
    if not parsed:
        return ()
    return MANAGED_OUTPUT_NAMES.get(parsed[0], ())


def _resource_display_type(resource_name: str) -> Optional[str]:
    parsed = parse_resource_path(resource_name)
    if not parsed:
        return None
    return DISPLAY_NAMES.get(parsed[0], parsed[0])


def _resource_uid(resource: Dict[str, Any]) -> Optional[str]:
    return resource.get("metadata", {}).get("uid")


def _output_refs(resource: Dict[str, Any]) -> Dict[str, Dict[str, str]]:
    outputs = resource.get("status", {}).get("outputs", {}) or {}
    return {
        name: ref
        for name, ref in outputs.items()
        if isinstance(ref, dict) and ref.get("s3Key")
    }


def read_managed_output(namespace: str, resource_name: str, output_name: str) -> ManagedOutput:
    """Read one managed output through the same CR-status path used by `workflow show`.

    Argo can serve current workflow artifacts by workflow name, node id, and
    artifact name. That is useful while the workflow CR still exists, but it is
    not a durable lookup once a later workflow replaces the old one. The
    migration CR status keeps the exact S3 key for the latest retained output,
    so shared CLI/TUI output reads use that resource-centric pointer instead.
    """
    resource = _fetch_resource(namespace, resource_name)
    refs = _output_refs(resource)
    ref = refs.get(output_name)
    if not ref:
        available = ", ".join(sorted(refs)) or "none"
        raise ArtifactStoreError(
            f"No managed output named '{output_name}' found for '{resource_name}'. "
            f"Available outputs: {available}"
        )
    return ManagedOutput(
        resource_name=resource_name,
        output_name=output_name,
        ref=ref,
        content=read_artifact_text(ref["s3Key"]),
    )


def _tasks_with_current_outputs(namespace: str) -> List[str]:
    return [
        task_info["display"]
        for task, task_info in OUTPUT_TASKS.items()
        if _list_resources_for_task(namespace, task)
    ]


def _get_task_or_resource_completions(ctx, _, incomplete):
    namespace = ctx.params.get("namespace", "ma")
    try:
        load_k8s_config()
        task_names = _tasks_with_current_outputs(namespace)
    except Exception:
        task_names = []

    return [name for name in task_names if name.startswith(incomplete)]


def _get_resource_filter_completions(ctx, _, incomplete):
    target = ctx.params.get("target")
    task = _canonical_task_name(target)
    namespace = ctx.params.get("namespace", "ma")
    if not task:
        return _get_output_name_completions(ctx, _, incomplete)
    try:
        load_k8s_config()
        names = [name for name, _ in _list_resources_for_task(namespace, task)]
    except Exception:
        names = []
    return [name for name in names if name.startswith(incomplete)]


def _history_prefix(resource_name: str, resource: Dict[str, Any], output_name: str) -> Optional[str]:
    display_type = _resource_display_type(resource_name)
    parsed = parse_resource_path(resource_name)
    uid = _resource_uid(resource)
    creation_ts = resource.get("metadata", {}).get("creationTimestamp", "")
    if not display_type or not parsed or not uid or not creation_ts:
        return None
    return f"{OUTPUT_ROOT}/{display_type}/{parsed[1]}/{creation_ts}_{uid}/{output_name}/"


def _iso_timestamp(value) -> str:
    if isinstance(value, datetime):
        return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
    if isinstance(value, (int, float)):
        return datetime.fromtimestamp(value, tz=timezone.utc).isoformat().replace("+00:00", "Z")
    return ""


def _history_entries(resource_name: str, resource: Dict[str, Any],
                     output_names: Iterable[str]) -> List[Dict[str, Any]]:
    entries = []
    for output_name in output_names:
        prefix = _history_prefix(resource_name, resource, output_name)
        if not prefix:
            continue
        for item in list_artifacts(prefix):
            key = str(item["key"])
            if not key.endswith(".log"):
                continue
            timestamp = _iso_timestamp(item.get("last_modified"))
            entries.append({
                "resource": resource_name,
                "output": output_name,
                "timestamp": timestamp,
                "key": key,
                "size": item.get("size", 0),
            })
    return sorted(entries, key=lambda e: (e["timestamp"], e["output"], e["key"]))


def _print_history(entries: List[Dict[str, Any]]) -> None:
    if not entries:
        click.echo("No retained output artifacts found.")
        return
    for idx, entry in enumerate(entries, 1):
        click.echo(
            f"{idx:>3}  {entry['timestamp']:<25}  {entry['resource']:<36}  {entry['output']:<18}  "
            f"{entry['size']:>8} bytes  {artifact_uri(entry['key'])}"
        )


def _select_history_entry(entries: List[Dict[str, Any]], selector: str) -> Optional[Dict[str, Any]]:
    if selector.isdigit():
        idx = int(selector)
        if 1 <= idx <= len(entries):
            return entries[idx - 1]
        return None
    for entry in entries:
        if entry["timestamp"] == selector:
            return entry
    return None


def _get_output_name_completions(ctx, _, incomplete):
    resource_name = ctx.params.get("target")
    if not resource_name:
        return []
    namespace = ctx.params.get("namespace", "ma")
    try:
        load_k8s_config()
        resource = _fetch_resource(namespace, resource_name)
        names = _output_refs(resource).keys()
    except Exception:
        names = _managed_output_names_for_resource(resource_name)
    return [name for name in sorted(names) if name.startswith(incomplete)]


def _print_output_refs(ctx, refs: Dict[str, Dict[str, str]], clean: bool = False) -> None:
    multiple = len(refs) > 1
    for name, ref in refs.items():
        try:
            content = read_artifact_text(ref["s3Key"])
        except ArtifactStoreError as e:
            click.echo(f"Error fetching output '{name}': {e}", err=True)
            ctx.exit(1)
            return
        if multiple and not clean:
            workflow_name = ref.get("workflowName")
            header = f"=== {name}"
            if workflow_name:
                header += f" / {workflow_name}"
            header += " ==="
            click.echo(header)
        click.echo(content.rstrip("\n"))


def _print_latest_outputs(ctx, targets: List[tuple[str, str, Dict[str, str]]],
                          always_header: bool = False,
                          clean: bool = False) -> None:
    show_header = (always_header or len(targets) > 1) and not clean
    for resource_name, output_name, ref in targets:
        if show_header:
            when = ref.get("workflowCreationTimestamp") or ref.get("workflowName") or "unknown time"
            click.echo(f"=== {resource_name} / {output_name} / {when} ===")
        _print_output_refs(ctx, {output_name: ref}, clean=clean)


def _resource_matches_filter(resource_name: str, resource_filter: Optional[str]) -> bool:
    if not resource_filter:
        return True
    return resource_name == resource_filter or resource_name.split(".", 1)[-1] == resource_filter


def _task_resources(namespace: str, task: str,
                    resource_filter: Optional[str]) -> List[tuple[str, Dict[str, Any]]]:
    return [
        pair for pair in _list_resources_for_task(namespace, task)
        if _resource_matches_filter(pair[0], resource_filter)
    ]


def _show_task_outputs(ctx, namespace: str, task: str, resource_filter: Optional[str],
                       clean: bool = False) -> None:
    task_info = OUTPUT_TASKS[task]
    output_name = task_info["output"]
    resources = _task_resources(namespace, task, resource_filter)
    if not resources:
        subject = f" matching '{resource_filter}'" if resource_filter else ""
        click.echo(f"No {task_info['display']} output found for current resources{subject}.")
        return

    targets = []
    for resource_name, resource in resources:
        ref = _output_refs(resource).get(output_name)
        if ref:
            targets.append((resource_name, output_name, ref))
    _print_latest_outputs(ctx, targets, always_header=True, clean=clean)


def _task_history_entries(namespace: str, task: str,
                          resource_filter: Optional[str]) -> List[Dict[str, Any]]:
    task_info = OUTPUT_TASKS[task]
    resources = _task_resources(namespace, task, resource_filter)
    entries = []
    for resource_name, resource in resources:
        entries.extend(_history_entries(resource_name, resource, [task_info["output"]]))
    return sorted(entries, key=lambda e: (e["timestamp"], e["resource"], e["key"]))


def _list_show_targets(namespace: str, task: Optional[str]) -> None:
    if task:
        resources = _list_resources_for_task(namespace, task)
        if not resources:
            click.echo(f"No {OUTPUT_TASKS[task]['display']} output found for current resources.")
            return
        for resource_name, _ in resources:
            click.echo(resource_name)
        return

    found_any = False
    for task_name, task_info in OUTPUT_TASKS.items():
        resources = _list_resources_for_task(namespace, task_name)
        if not resources:
            continue
        found_any = True
        click.echo(f"{task_info['display']}:")
        for resource_name, _ in resources:
            click.echo(f"  {resource_name}")
    if not found_any:
        click.echo("No managed workflow outputs found for current resources.")


def _exit_with_help(ctx, message: str) -> None:
    click.echo(f"{message}\n", err=True)
    click.echo(ctx.get_help())
    ctx.exit(1)


def _load_k8s_config_or_exit(ctx) -> bool:
    try:
        load_k8s_config()
        return True
    except Exception as e:
        click.echo(f"Error: could not load Kubernetes configuration: {e}", err=True)
        ctx.exit(1)
        return False


def _show_resource_list_error(ctx) -> bool:
    click.echo("Error: --list accepts an optional output task, not a resource.\n", err=True)
    click.echo(ctx.get_help())
    ctx.exit(1)
    return False


def _print_history_or_run(ctx, entries: List[Dict[str, Any]], run_selector: Optional[str]) -> None:
    if not run_selector:
        _print_history(entries)
        return

    entry = _select_history_entry(entries, run_selector)
    if not entry:
        click.echo(f"No retained output artifact matches '{run_selector}'.", err=True)
        ctx.exit(1)
        return

    try:
        click.echo(read_artifact_text(entry["key"]).rstrip("\n"))
    except ArtifactStoreError as e:
        click.echo(f"Error fetching output '{entry['output']}': {e}", err=True)
        ctx.exit(1)


def _show_task_history(ctx, namespace: str, task: str,
                       resource_filter: Optional[str],
                       run_selector: Optional[str]) -> None:
    try:
        entries = _task_history_entries(namespace, task, resource_filter)
    except ArtifactStoreError as e:
        click.echo(f"Error listing output history: {e}", err=True)
        ctx.exit(1)
        return
    _print_history_or_run(ctx, entries, run_selector)


def _handle_task_show(ctx, namespace: str, task: str, selector: Optional[str],
                      list_resources: bool, history: bool,
                      run_selector: Optional[str], clean: bool) -> None:
    if list_resources:
        _list_show_targets(namespace, task)
        return
    if history or run_selector:
        _show_task_history(ctx, namespace, task, selector, run_selector)
        return
    _show_task_outputs(ctx, namespace, task, selector, clean=clean)


def _fetch_resource_and_refs(
    ctx,
    namespace: str,
    resource_name: str,
) -> Optional[tuple[Dict[str, Any], Dict[str, Dict[str, str]]]]:
    try:
        resource = _fetch_resource(namespace, resource_name)
        return resource, _output_refs(resource)
    except click.ClickException as e:
        e.show()
        ctx.exit(1)
        return None


def _show_resource_history(ctx, resource_name: str, resource: Dict[str, Any],
                           output_name: Optional[str],
                           run_selector: Optional[str]) -> None:
    output_names = [output_name] if output_name else _managed_output_names_for_resource(resource_name)
    try:
        entries = _history_entries(resource_name, resource, output_names)
    except ArtifactStoreError as e:
        click.echo(f"Error listing output history: {e}", err=True)
        ctx.exit(1)
        return
    _print_history_or_run(ctx, entries, run_selector)


def _show_current_resource_outputs(ctx, resource_name: str, output_name: Optional[str],
                                   refs: Dict[str, Dict[str, str]], clean: bool) -> None:
    if output_name:
        if output_name not in refs:
            available = ", ".join(sorted(refs)) or "none"
            click.echo(f"No managed output named '{output_name}' found for '{resource_name}'.")
            click.echo(f"Available outputs: {available}")
            return
        refs = {output_name: refs[output_name]}

    if not refs:
        expected = ", ".join(_managed_output_names_for_resource(resource_name))
        suffix = f" Expected outputs: {expected}." if expected else ""
        click.echo(f"No managed stdout output found for migration resource '{resource_name}'.{suffix}")
        return

    _print_output_refs(ctx, dict(sorted(refs.items())), clean=clean)


def _handle_resource_show(ctx, namespace: str, target: str, selector: Optional[str],
                          list_resources: bool, history: bool,
                          run_selector: Optional[str], clean: bool) -> None:
    if list_resources and not _show_resource_list_error(ctx):
        return

    resource_name, output_name = _resolve_show_target(target, selector)
    resource_and_refs = _fetch_resource_and_refs(ctx, namespace, resource_name)
    if not resource_and_refs:
        return
    resource, refs = resource_and_refs

    if history or run_selector:
        _show_resource_history(ctx, resource_name, resource, output_name, run_selector)
        return
    _show_current_resource_outputs(ctx, resource_name, output_name, refs, clean)


@click.command(name="show")
@click.option('--list', 'list_resources', is_flag=True, default=False,
              help='List available migration resources and exit')
@click.option('--history', is_flag=True, default=False,
              help='List retained output artifacts for this resource UID')
@click.option('--run', 'run_selector',
              help='Show a retained artifact by --history index or ISO timestamp')
@click.option('--clean', is_flag=True, default=False,
              help='Print only output content, without section headers')
@click.option('--namespace', default=get_current_namespace, hidden=True, envvar='WORKFLOW_NAMESPACE')
@click.argument('target', required=False, shell_complete=_get_task_or_resource_completions)
@click.argument('selector', required=False, shell_complete=_get_resource_filter_completions)
@click.argument('extra_args', nargs=-1, type=click.UNPROCESSED)
@click.pass_context
def show_command(ctx, list_resources, history, run_selector, clean, namespace, target, selector, extra_args):
    """Show managed stdout output for workflow tasks or resources.

    \b
    Examples:
      workflow show evaluatemetadata
      workflow show migratemetadata snapshotmigration.migration-0
      workflow show evaluatemetadata.source-target-snap1-migration-0
      workflow show snapshotmigration.migration-0 metadataMigrate --history
      workflow show snapshotmigration.migration-0 metadataMigrate --run 2
    """
    if target is None:
        if list_resources:
            try:
                load_k8s_config()
                _list_show_targets(namespace, None)
            except Exception as e:
                click.echo(f"Error listing workflow outputs: {e}", err=True)
                ctx.exit(1)
            return
        _exit_with_help(ctx, "Error: specify a task, migration resource, or --list.")
        return

    before_dashdash, passthrough_args = _split_passthrough_args(extra_args)
    if before_dashdash or passthrough_args:
        _exit_with_help(ctx, "Error: show does not accept extra arguments.")
        return

    if not _load_k8s_config_or_exit(ctx):
        return

    task = _canonical_task_name(target)
    if task:
        _handle_task_show(ctx, namespace, task, selector, list_resources, history, run_selector, clean)
        return

    _handle_resource_show(ctx, namespace, target, selector, list_resources, history, run_selector, clean)
