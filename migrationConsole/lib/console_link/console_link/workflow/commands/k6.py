"""`workflow k6` — submit and manage k6 load-test runs.

Each run is an Argo Workflow submitted from the `k6-load-test` WorkflowTemplate (installed by the
migration-assistant chart when `k6.enabled=true`). A run is specified by two names baked into the
migrations/k6 image: a `scenario` (script) and a `config` (k6-config/*.env preset). Every preset
value is overridable per run via named options or the repeatable `--override KEY=VALUE` bag.

See TrafficCapture/trafficLoadTest/k8s/README.md for the deployment side.
"""

import calendar
import logging
import subprocess
import time

import click

from ..models.utils import ExitCode, load_k8s_config, get_current_namespace
from ..services.workflow_service import WorkflowService, ENDING_PHASES
from .argo_utils import (
    submit_workflow_from_template,
    list_workflows,
    stop_workflow,
    delete_workflow,
)

logger = logging.getLogger(__name__)

K6_TEMPLATE = "k6-load-test"
K6_APP_LABEL = "k6-load-test"
K6_SERVICE_ACCOUNT = "argo-workflow-executor"
K6_GENERATE_NAME = "k6-run-"

SCENARIOS = ["ingest", "search", "mixed"]
# Presets baked into the image (k6-config/*.env). Used for completion/help only; any name works.
CONFIG_PRESETS = [
    "ingest-steady", "ingest-ramp", "ingest-burst",
    "search-steady", "search-deep-paging", "search-ramp", "search-burst",
    "mixed-steady", "mixed-ramp", "mixed-burst",
]


def _age(creation_timestamp):
    """Compact age string from an RFC3339 creationTimestamp (e.g. '3m', '2h', '1d')."""
    if not creation_timestamp:
        return "?"
    try:
        # creationTimestamp is UTC RFC3339 like 2026-07-27T12:34:56Z; timegm reads it as UTC.
        created = calendar.timegm(time.strptime(creation_timestamp, "%Y-%m-%dT%H:%M:%SZ"))
        secs = max(0, int(time.time() - created))
    except (ValueError, OverflowError):
        return "?"
    for unit, size in (("d", 86400), ("h", 3600), ("m", 60)):
        if secs >= size:
            return f"{secs // size}{unit}"
    return f"{secs}s"


def _k6_workflows(namespace, scenario=None):
    """k6 workflows in the namespace, optionally narrowed to one scenario."""
    selector = f"app={K6_APP_LABEL}"
    if scenario:
        selector += f",k6-scenario={scenario}"
    return list_workflows(namespace, label_selector=selector)


def build_k6_parameters(scenario, config_name=None, target_url=None, rate=None, duration=None,
                        vus=None, registry_enabled=None, control_enabled=None,
                        overrides_text=None, extra_args=None):
    """Map run inputs to the WorkflowTemplate parameter dict (shared by the CLI and the TUI).

    Empty/None values are omitted so the preset's value is kept. `overrides_text` is a
    newline-separated KEY=VALUE bag applied last; a line without '=' raises ValueError.
    """
    params = {"scenario": scenario, "configName": config_name or f"{scenario}-steady"}
    if target_url:
        params["targetUrl"] = target_url
    if rate:
        params["rate"] = rate
    if duration:
        params["duration"] = duration
    if vus:
        params["vus"] = vus
    if registry_enabled is not None:
        params["registryEnabled"] = str(registry_enabled).lower()
    if control_enabled is not None:
        params["controlEnabled"] = str(control_enabled).lower()
    if extra_args:
        params["extraArgs"] = extra_args
    if overrides_text:
        lines = [ln.strip() for ln in overrides_text.splitlines() if ln.strip()]
        for ln in lines:
            if '=' not in ln:
                raise ValueError(f"override must be KEY=VALUE, got '{ln}'")
        if lines:
            params["overrides"] = "\n".join(lines)
    return params


def submit_k6_run(namespace, parameters):
    """Submit a k6 run from the k6-load-test WorkflowTemplate. Returns the generated name.

    The run is a **standalone** Argo Workflow: no `ownerReferences` back to any migration
    workflow, its own `generateName`, and its own `app=k6-load-test` labels. Argo never
    aggregates sibling Workflows, so a k6 run failing/stopping cannot fail a migration workflow.
    """
    return submit_workflow_from_template(
        namespace, K6_TEMPLATE, parameters=parameters,
        labels={"app": K6_APP_LABEL, "k6-scenario": parameters["scenario"]},
        service_account=K6_SERVICE_ACCOUNT, generate_name=K6_GENERATE_NAME,
    )


def list_active_k6_runs(namespace):
    """Active (non-terminal) k6 runs as UI-friendly dicts: name/scenario/phase/age."""
    out = []
    for wf in _k6_workflows(namespace):
        status = wf.get("status", {})
        phase = status.get("phase", "Unknown")
        if phase in ENDING_PHASES:
            continue
        meta = wf.get("metadata", {})
        out.append({
            "name": meta.get("name", ""),
            "scenario": meta.get("labels", {}).get("k6-scenario", "?"),
            "phase": phase,
            "age": _age(meta.get("creationTimestamp")),
        })
    out.sort(key=lambda r: r["name"])
    return out


@click.group(name="k6")
def k6_group():
    """Submit and manage k6 load-test runs (Argo workflows)."""


@k6_group.command(name="run")
@click.option('--scenario', type=click.Choice(SCENARIOS), default="ingest", show_default=True,
              help="Scenario script to run.")
@click.option('--config', 'config_name', default=None,
              help="k6-config preset name (default: <scenario>-steady).")
@click.option('--target', 'target_url', default=None,
              help="Capture Proxy URL, e.g. https://<proxy>.ma.svc.cluster.local:9200. "
                   "If omitted, the preset's CAPTURE_PROXY_URL is used.")
@click.option('--rate', default=None, help="Override request rate (INGEST_RATE/SEARCH_RATE).")
@click.option('--duration', default=None, help="Override DURATION (e.g. 5m, 30s).")
@click.option('--vus', default=None, help="Override pre-allocated VUs.")
@click.option('--registry-enabled/--no-registry-enabled', 'registry_enabled', default=None,
              help="Force the mixed/consistency ring buffer on/off (default: keep preset).")
@click.option('--control-enabled/--no-control-enabled', 'control_enabled', default=None,
              help="Force the chaos control bus on/off (default: keep preset).")
@click.option('--override', '-o', 'overrides', multiple=True, metavar='KEY=VALUE',
              help="Extra env override, applied after the preset. Repeatable.")
@click.option('--extra-args', default=None, help="Extra flags for `k6 run` (e.g. --no-thresholds).")
@click.option('--namespace', default=get_current_namespace, hidden=True, envvar='WORKFLOW_NAMESPACE')
@click.option('--wait', is_flag=True, default=False, help="Wait for the run to complete.")
@click.option('--timeout', default=600, type=int, help="Seconds to wait with --wait (default 600).")
@click.option('--wait-interval', default=5, type=int, help="Seconds between status checks with --wait.")
@click.pass_context
def k6_run(ctx, scenario, config_name, target_url, rate, duration, vus,
           registry_enabled, control_enabled, overrides, extra_args,
           namespace, wait, timeout, wait_interval):
    """Submit a k6 run.

    \b
    Examples:
      workflow k6 run --scenario ingest --target https://my-proxy:9200
      workflow k6 run --scenario search --config search-deep-paging --rate 100 --duration 10m
      workflow k6 run --scenario mixed --registry-enabled -o INGEST_RATE=80 -o SEARCH_RATE=40
    """
    try:
        parameters = build_k6_parameters(
            scenario=scenario, config_name=config_name, target_url=target_url,
            rate=rate, duration=duration, vus=vus,
            registry_enabled=registry_enabled, control_enabled=control_enabled,
            overrides_text=("\n".join(overrides) if overrides else None), extra_args=extra_args,
        )
    except ValueError as e:
        click.echo(f"Error: --override {e}", err=True)
        ctx.exit(ExitCode.INVALID_INPUT.value)
        return
    config_name = parameters["configName"]

    try:
        load_k8s_config()
        name = submit_k6_run(namespace, parameters)
    except Exception as e:
        click.echo(f"Error submitting k6 run: {e}", err=True)
        click.echo("\nIs the k6-load-test WorkflowTemplate installed? "
                   "(the chart installs it when k6.enabled=true)", err=True)
        ctx.exit(ExitCode.FAILURE.value)

    click.echo(f"Submitted k6 run: {name}")
    target_note = f" target={target_url}" if target_url else ""
    click.echo(f"  scenario={scenario} config={config_name}{target_note}")
    click.echo(f"\nWatch:  workflow k6 list\n        workflow k6 logs {name} -f")

    if wait:
        click.echo(f"\nWaiting for completion (timeout {timeout}s)...")
        try:
            phase, _ = WorkflowService().wait_for_workflow_completion(
                namespace=namespace, workflow_name=name, timeout=timeout, interval=wait_interval)
            click.echo(f"Run {name} finished: {phase}")
            if phase != "Succeeded":
                ctx.exit(ExitCode.FAILURE.value)
        except TimeoutError as e:
            click.echo(str(e), err=True)
            ctx.exit(ExitCode.FAILURE.value)


@k6_group.command(name="list")
@click.option('--scenario', type=click.Choice(SCENARIOS), default=None, help="Filter by scenario.")
@click.option('--namespace', default=get_current_namespace, hidden=True, envvar='WORKFLOW_NAMESPACE')
@click.pass_context
def k6_list(ctx, scenario, namespace):
    """List k6 runs."""
    try:
        load_k8s_config()
        items = _k6_workflows(namespace, scenario)
    except Exception as e:
        click.echo(f"Error listing k6 runs: {e}", err=True)
        ctx.exit(ExitCode.FAILURE.value)
        return

    if not items:
        click.echo("No k6 runs found.")
        return

    rows = []
    for wf in items:
        meta = wf.get("metadata", {})
        status = wf.get("status", {})
        rows.append((
            meta.get("name", "?"),
            meta.get("labels", {}).get("k6-scenario", "?"),
            status.get("phase", "Unknown"),
            status.get("progress", "-"),
            _age(meta.get("creationTimestamp")),
        ))
    rows.sort(key=lambda r: r[0])

    header = ("NAME", "SCENARIO", "PHASE", "PROGRESS", "AGE")
    widths = [max(len(str(r[i])) for r in (header, *rows)) for i in range(len(header))]
    line = "  ".join(str(c).ljust(widths[i]) for i, c in enumerate(header))
    click.echo(line)
    for r in rows:
        click.echo("  ".join(str(c).ljust(widths[i]) for i, c in enumerate(r)))


@k6_group.command(name="stop")
@click.argument('name', required=False)
@click.option('--all', 'stop_all', is_flag=True, default=False, help="Stop all k6 runs.")
@click.option('--scenario', type=click.Choice(SCENARIOS), default=None,
              help="Stop all k6 runs for one scenario.")
@click.option('--delete', 'do_delete', is_flag=True, default=False,
              help="Delete the run(s) as well as stopping.")
@click.option('--namespace', default=get_current_namespace, hidden=True, envvar='WORKFLOW_NAMESPACE')
@click.pass_context
def k6_stop(ctx, name, stop_all, scenario, do_delete, namespace):
    """Stop (and optionally delete) k6 run(s).

    \b
    Examples:
      workflow k6 stop k6-run-abc12
      workflow k6 stop --scenario mixed --delete
      workflow k6 stop --all
    """
    if sum(bool(x) for x in (name, stop_all, scenario)) != 1:
        click.echo("Error: specify exactly one of NAME, --all, or --scenario.", err=True)
        ctx.exit(ExitCode.INVALID_INPUT.value)

    try:
        load_k8s_config()
        if name:
            names = [name]
        else:
            names = [wf.get("metadata", {}).get("name", "")
                     for wf in _k6_workflows(namespace, scenario)]
            names = [n for n in names if n]
    except Exception as e:
        click.echo(f"Error resolving k6 runs: {e}", err=True)
        ctx.exit(ExitCode.FAILURE.value)
        return

    if not names:
        click.echo("No matching k6 runs.")
        return

    for n in names:
        stopped = stop_workflow(namespace, n)
        action = "stopped" if stopped else "could not stop (maybe finished)"
        if do_delete:
            deleted = delete_workflow(namespace, n)
            action += "; deleted" if deleted else "; delete failed"
        click.echo(f"{n}: {action}")


@k6_group.command(name="logs")
@click.argument('name')
@click.option('-f', '--follow', is_flag=True, default=False, help="Stream live logs.")
@click.option('--namespace', default=get_current_namespace, hidden=True, envvar='WORKFLOW_NAMESPACE')
def k6_logs(name, follow, namespace):
    """Show logs for a k6 run (the k6 'main' container).

    \b
    Examples:
      workflow k6 logs k6-run-abc12
      workflow k6 logs k6-run-abc12 -f
    """
    cmd = ["kubectl", "logs", "-n", namespace,
           "-l", f"workflows.argoproj.io/workflow={name}", "-c", "main", "--tail=-1"]
    if follow:
        cmd.append("-f")
    subprocess.run(cmd)
