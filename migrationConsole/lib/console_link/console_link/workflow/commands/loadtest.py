"""`workflow loadtest` — submit and manage k6 load-test runs.

Bare `workflow loadtest` opens the load-test TUI (tui/loadtest_app.py); the subcommands below are
the non-interactive equivalents. Both go through the same helpers, so neither can drift.

Each run is an **Argo Workflow** that creates a k6-operator **TestRun** and waits for it. The
operator, the per-scenario WorkflowTemplates, and this command's RBAC ship in the standalone
k6LoadTest chart (deployment/k8s/charts/components/k6LoadTest), which is installed separately from
any migration; the scenarios and presets themselves ride in a data image (migrations/k6_scripts,
built from TrafficCapture/trafficLoadTest) that the templates mount at /scripts on stock grafana/k6
pods.

A run is specified by a `scenario` (which names a WorkflowTemplate, and a script path under /scripts)
and a `config` (a k6-config/*.env preset from the same mount, selected with the K6_PRESET env var);
every preset value is overridable per run via named options or the repeatable `-e KEY=VALUE` bag,
since real environment variables win over the preset file. Load is spread across `--parallelism`
runner pods by k6 execution segments, which is the operator's job, not Argo's.

Because the infra is a separate opt-in, this command is inert (and hidden from `--help`) unless the
chart's WorkflowTemplates are present in the namespace — so a normal migration deployment cannot
trigger a load test. See TrafficCapture/trafficLoadTest/README.md for the deployment side.
"""

import calendar
import json
import logging
import os
import subprocess
import time

import click

from ..models.utils import ExitCode, load_k8s_config, get_current_namespace
from .testrun_utils import (
    K6_APP_LABEL,
    create_workflow,
    list_workflows,
    get_workflow,
    delete_workflow,
    get_workflow_template,
    workflow_template_name,
    list_scenarios,
    loadtest_installed,
)

logger = logging.getLogger(__name__)

# Env var the scenarios read to pick a load-profile preset from the ones in the mounted scripts
# image (see TrafficCapture/trafficLoadTest/lib/config.js).
PRESET_ENV = "K6_PRESET"

# Launchable scenarios are discovered from the cluster (list_scenarios, from the chart's
# WorkflowTemplates); SCENARIOS is only the completion fallback used when the cluster is unreachable.
SCENARIOS = ["ingest", "search", "mixed"]
# Presets live in the scripts image, so there is nothing in the cluster to enumerate: this list
# mirrors TrafficCapture/trafficLoadTest/k6-config/*.env (kept honest by a unit test). Neither
# --scenario nor --config is restricted to these — a custom scripts image may ship others, and an
# unknown preset fails fast in the pod with the list the image actually has.
CONFIG_PRESETS = [
    "ingest-steady", "ingest-ramp", "ingest-burst",
    "search-steady", "search-deep-paging", "search-ramp", "search-burst",
    "mixed-steady", "mixed-ramp", "mixed-burst",
]

# Argo workflow phases that mean a run is no longer active. The k6-operator's own TestRun stages
# (finished/error/stopped) are what the workflow's success/failure conditions watch; by the time a
# phase below appears, that verdict has already been folded into it.
DONE_PHASES = {"Succeeded", "Failed", "Error"}
SUCCESS_PHASE = "Succeeded"


# ---------------------------------------------------------------------------
# Shell completion — dynamic values from the cluster, static fallback when offline.
# ---------------------------------------------------------------------------
def _cluster_values(fetch, fallback):
    """Best-effort completion values from the cluster. Shell completion must stay fast and never
    raise, so any failure (no kubeconfig, chart not installed) falls back to the static hint list."""
    try:
        load_k8s_config()
        return fetch(get_current_namespace()) or fallback
    except Exception:
        return fallback


def _complete_scenarios(ctx, param, incomplete):
    return [v for v in _cluster_values(list_scenarios, SCENARIOS) if v.startswith(incomplete)]


def _complete_presets(ctx, param, incomplete):
    return [v for v in CONFIG_PRESETS if v.startswith(incomplete)]


# ---------------------------------------------------------------------------
# Availability guard: keep the commands inert unless the load-test chart is installed.
# ---------------------------------------------------------------------------
_AVAIL_CACHE = {}


def k6_available(namespace, force=False):
    """True if load testing is enabled here.

    `K6_LOADTEST_ENABLED` (true/false) is an explicit override — used by tests and as a kill
    switch. Otherwise we probe (cached) whether the chart's WorkflowTemplates are in the namespace.

    `force=True` bypasses the cache and re-probes the cluster, refreshing the cached value, for a
    long-running process that has to notice the k6LoadTest chart being installed *after* it started
    — a plain cached probe would pin the startup result for the life of the process. The env
    override still wins over any probe.
    """
    override = os.environ.get("K6_LOADTEST_ENABLED")
    if override is not None:
        return override.strip().lower() in ("1", "true", "yes", "on")
    if force or namespace not in _AVAIL_CACHE:
        try:
            load_k8s_config()
            _AVAIL_CACHE[namespace] = loadtest_installed(namespace)
        except Exception:
            _AVAIL_CACHE[namespace] = False
    return _AVAIL_CACHE[namespace]


def _require_k6(ctx, namespace):
    if not k6_available(namespace):
        click.echo("k6 load testing is not installed in this namespace.", err=True)
        click.echo("Install the standalone k6LoadTest chart "
                   "(deployment/k8s/charts/components/k6LoadTest) to enable it.", err=True)
        ctx.exit(ExitCode.FAILURE.value)


class _LoadTestGroup(click.Group):
    """A group hidden from `--help` unless the k6 load-test chart is installed."""

    @property
    def hidden(self):
        try:
            return not k6_available(get_current_namespace())
        except Exception:
            return True

    @hidden.setter
    def hidden(self, value):
        pass  # visibility is computed from cluster state, not stored


def _age(creation_timestamp):
    """Compact age string from an RFC3339 creationTimestamp (e.g. '3m', '2h', '1d')."""
    if not creation_timestamp:
        return "?"
    try:
        created = calendar.timegm(time.strptime(creation_timestamp, "%Y-%m-%dT%H:%M:%SZ"))
        secs = max(0, int(time.time() - created))
    except (ValueError, OverflowError):
        return "?"
    for unit, size in (("d", 86400), ("h", 3600), ("m", 60)):
        if secs >= size:
            return f"{secs // size}{unit}"
    return f"{secs}s"


def _k6_workflows(namespace, scenario=None):
    """k6 run Workflows in the namespace, optionally narrowed to one scenario."""
    selector = f"app={K6_APP_LABEL}"
    if scenario:
        selector += f",k6-scenario={scenario}"
    return list_workflows(namespace, label_selector=selector)


# ---------------------------------------------------------------------------
# Run assembly.
# ---------------------------------------------------------------------------
def load_template_defaults(namespace, scenario):
    """The parameter defaults of the scenario's WorkflowTemplate, as a name → value dict.

    Helm is the single source of the run spec (images, script path, K6_OUT, default preset); the
    console reads those defaults and overrides only what the run asks for, so the static values are
    never restated here.
    """
    name = workflow_template_name(scenario)
    template = get_workflow_template(namespace, name)
    if template is None:
        available = ", ".join(list_scenarios(namespace)) or "none"
        raise ValueError(f"no WorkflowTemplate '{name}' for scenario '{scenario}' "
                         f"(available: {available}); is the k6LoadTest chart installed?")
    params = template.get("spec", {}).get("arguments", {}).get("parameters", [])
    return {p["name"]: p.get("value", "") for p in params if "name" in p}


# Named run options → the scenario env vars each one sets. A value-carrying option is applied only
# when it's non-empty, so leaving it off keeps whatever the preset file supplies. --rate and --vus
# fan out to both the ingest and search vars because a scenario reads only its own pair.
_VALUE_ENV_VARS = (
    ("duration", ("DURATION",)),
    ("rate", ("INGEST_RATE", "SEARCH_RATE")),
    ("vus", ("INGEST_VUS", "SEARCH_VUS")),
    ("targetUrl", ("CAPTURE_PROXY_URL",)),
    ("webdisUrl", ("WEBDIS_URL",)),
)
# The toggles are three-state: None means "keep the preset's value", so they can't use the
# truthiness test above — an explicit False still has to emit an override to turn the preset off.
_TOGGLE_ENV_VARS = (
    ("registryEnabled", "REGISTRY_ENABLED"),
    ("controlEnabled", "CONTROL_ENABLED"),
)


def _parse_overrides(text):
    """The -e KEY=VALUE bag as a dict. Blank lines are skipped; anything else must carry an '='."""
    env = {}
    for line in (text or "").splitlines():
        line = line.strip()
        if not line:
            continue
        if "=" not in line:
            raise ValueError(f"override must be KEY=VALUE, got '{line}'")
        key, val = line.split("=", 1)
        env[key.strip()] = val.strip()
    return env


def _override_env(params):
    """Per-run env overrides (these win over the preset file). Named flags fan out to the vars the
    scenarios read; the -e bag is applied last, so it wins over the named flags."""
    env = {}
    for key, names in _VALUE_ENV_VARS:
        if params.get(key):
            env.update(dict.fromkeys(names, params[key]))
    for key, name in _TOGGLE_ENV_VARS:
        if params.get(key) is not None:
            env[name] = str(params[key]).lower()
    env.update(_parse_overrides(params.get("overrides")))
    return [{"name": k, "value": str(v)} for k, v in env.items()]


def build_k6_parameters(scenario, config_name=None, parallelism=1, target_url=None, rate=None,
                        duration=None, vus=None, registry_enabled=None, control_enabled=None,
                        webdis_url=None, overrides_text=None, extra_args=None):
    """Normalize run inputs into a params dict (shared by the CLI and the TUI).

    Validates the `overrides_text` bag eagerly so bad input is rejected before any API call.
    """
    if overrides_text:
        for line in overrides_text.splitlines():
            line = line.strip()
            if line and "=" not in line:
                raise ValueError(f"override must be KEY=VALUE, got '{line}'")
    return {
        "scenario": scenario,
        "configName": config_name or f"{scenario}-steady",
        "parallelism": int(parallelism or 1),
        "targetUrl": target_url,
        "rate": rate,
        "duration": duration,
        "vus": vus,
        "registryEnabled": registry_enabled,
        "controlEnabled": control_enabled,
        "webdisUrl": webdis_url,
        "overrides": overrides_text,
        "extraArgs": extra_args,
    }


def _set_env(env, name, value):
    """Return the env list with `name` set to `value`, replacing any existing entry of that name.

    The template's default already carries K6_PRESET (and the K6_OUT trio), so appending would leave
    the container with two entries of the same name and make the run depend on how the runtime
    resolves that — replacing keeps the spec unambiguous.
    """
    out = [e for e in env if e.get("name") != name]
    out.append({"name": name, "value": str(value)})
    return out


def build_workflow_submission(namespace, params):
    """Build the Workflow that runs a scenario: name its WorkflowTemplate and pass only the
    parameters that differ from the template's defaults.

    The runner env is carried whole in one `runnerEnv` parameter. We start from the template's
    default so the static vars (K6_OUT and the OTel endpoint) have exactly one definition, then point
    K6_PRESET at the chosen preset and apply the overrides — which win over the preset file, since
    the scenarios read real env vars over it. Everything else the run needs (the images, the scripts
    mount, the script path under it, labels) is already in the template.
    """
    scenario = params["scenario"]
    defaults = load_template_defaults(namespace, scenario)

    env = json.loads(defaults.get("runnerEnv") or "[]")
    env = _set_env(env, PRESET_ENV, params["configName"])
    for entry in _override_env(params):
        env = _set_env(env, entry["name"], entry["value"])

    parameters = [
        {"name": "runnerEnv", "value": json.dumps(env)},
        {"name": "parallelism", "value": str(int(params.get("parallelism", 1)))},
    ]
    if params.get("extraArgs"):
        parameters.append({"name": "arguments", "value": params["extraArgs"]})

    return {
        "apiVersion": "argoproj.io/v1alpha1",
        "kind": "Workflow",
        "metadata": {
            "generateName": f"{workflow_template_name(scenario)}-",
            "labels": {"app": K6_APP_LABEL, "k6-scenario": scenario},
        },
        "spec": {
            "workflowTemplateRef": {"name": workflow_template_name(scenario)},
            "arguments": {"parameters": parameters},
        },
    }


def submit_k6_run(namespace, params):
    """Build + create the run Workflow from normalized params. Returns the generated name, which is
    also the name of the TestRun the workflow creates."""
    return create_workflow(namespace, build_workflow_submission(namespace, params))


def _warn_if_unknown_preset(config_name):
    """Warn (but never block) when the preset isn't one of the presets the scripts image ships.
    A custom image may ship others, so this never fails the run — and if the preset really is
    missing, the scenario stops at init with the list the image actually has."""
    if config_name not in CONFIG_PRESETS:
        click.echo(f"Note: config preset '{config_name}' is not one of the stock presets "
                   f"({', '.join(CONFIG_PRESETS)}); running anyway.", err=True)


def _workflow_parameter(workflow, name, default="-"):
    """A submitted workflow parameter's value. Absent means the run took the template's default,
    which is not on the Workflow object."""
    for p in workflow.get("spec", {}).get("arguments", {}).get("parameters", []):
        if p.get("name") == name:
            return str(p.get("value", default))
    return default


def list_runs(namespace, scenario=None, active_only=False):
    """k6 runs as UI-friendly dicts, sorted by name — the one place a run Workflow is flattened for
    display, shared by `loadtest list`, the TUI's run table, and the launch panel.

    One row per submission: the Workflow. The TestRun it drives shares its name, so `logs` and
    `stop` take the same identifier.

    `active_only=True` drops runs that reached a terminal phase.
    """
    out = []
    for wf in _k6_workflows(namespace, scenario):
        phase = wf.get("status", {}).get("phase", "")
        if active_only and phase in DONE_PHASES:
            continue
        meta = wf.get("metadata", {})
        out.append({
            "name": meta.get("name", ""),
            "scenario": meta.get("labels", {}).get("k6-scenario", "?"),
            "phase": phase or "unknown",
            "parallelism": _workflow_parameter(wf, "parallelism"),
            "age": _age(meta.get("creationTimestamp")),
        })
    out.sort(key=lambda r: r["name"])
    return out


def list_active_k6_runs(namespace):
    """Active (non-terminal) k6 runs as UI-friendly dicts: name/scenario/phase/age."""
    return list_runs(namespace, active_only=True)


def logs_command(namespace, name, follow=False):
    """The `kubectl logs` argv for a run's k6 containers. Shared by the `logs` subcommand and the
    TUI, so both show the same stream.

    The pods belong to the operator's TestRun, not to the workflow — but the template names the
    TestRun after the workflow, so the run's one name selects them.
    """
    cmd = ["kubectl", "logs", "-n", namespace,
           "-l", f"k6_cr={name},runner=true", "-c", "k6", "--tail=-1", "--prefix"]
    if follow:
        cmd.append("-f")
    return cmd


def wait_for_run(namespace, name, timeout, interval):
    """Poll a run Workflow until it reaches a terminal phase; return the phase, or None on timeout.

    The workflow's success/failure conditions already encode the operator's verdict, so a
    `Succeeded` phase means the TestRun reached stage `finished`.
    """
    deadline = time.time() + timeout
    while time.time() < deadline:
        wf = get_workflow(namespace, name)
        phase = (wf or {}).get("status", {}).get("phase", "")
        if phase in DONE_PHASES:
            return phase
        time.sleep(interval)
    return None


# ---------------------------------------------------------------------------
# CLI.
# ---------------------------------------------------------------------------
@click.group(name="loadtest", cls=_LoadTestGroup, invoke_without_command=True)
@click.option('--namespace', default=get_current_namespace, hidden=True, envvar='WORKFLOW_NAMESPACE')
@click.option('--refresh-interval', default=5.0, type=float, hidden=True,
              help="Seconds between run-table refreshes in the TUI.")
@click.pass_context
def loadtest_group(ctx, namespace, refresh_interval):
    """Submit and manage k6 load-test runs (k6-operator TestRuns).

    With no subcommand this opens the load-test TUI: a live table of runs, plus launch, stop and
    log viewing.
    """
    if ctx.invoked_subcommand is not None:
        return
    _require_k6(ctx, namespace)
    # Imported here so the subcommands (and shell completion) never pay for Textual.
    from ..tui.loadtest_app import LoadTestApp
    try:
        load_k8s_config()
        LoadTestApp(namespace, refresh_interval=refresh_interval).run()
    except Exception as e:
        click.echo(f"Error: {e}", err=True)
        ctx.exit(ExitCode.FAILURE.value)


@loadtest_group.command(name="run")
@click.option('--scenario', default="ingest", show_default=True, shell_complete=_complete_scenarios,
              help="Scenario to run — any scenario present in the cluster, including custom ones.")
@click.option('--config', 'config_name', default=None, shell_complete=_complete_presets,
              help="k6-config preset name (default: <scenario>-steady).")
@click.option('--parallelism', default=1, type=int, show_default=True,
              help="Number of runner pods. k6 splits the load across them via execution segments, "
                   "so --rate/--vus are GLOBAL totals divided among runners.")
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
@click.option('--override', '-e', 'overrides', multiple=True, metavar='KEY=VALUE',
              help="Extra env override, applied after the preset (matches k6-run.sh's -e). Repeatable.")
@click.option('--extra-args', default=None, help="Extra flags for `k6 run` (e.g. --no-thresholds).")
@click.option('--namespace', default=get_current_namespace, hidden=True, envvar='WORKFLOW_NAMESPACE')
@click.option('--wait', is_flag=True, default=False, help="Wait for the run to complete.")
@click.option('--timeout', default=600, type=int, help="Seconds to wait with --wait (default 600).")
@click.option('--wait-interval', default=5, type=int, help="Seconds between status checks with --wait.")
@click.pass_context
def k6_run(ctx, namespace, wait, timeout, wait_interval, **run_opts):
    """Submit a k6 run.

    \b
    Examples:
      workflow loadtest run --scenario ingest --target https://my-proxy:9200
      workflow loadtest run --scenario search --config search-deep-paging --rate 100 --duration 10m
      workflow loadtest run --scenario mixed --parallelism 4 --registry-enabled -e INGEST_RATE=80
    """
    _require_k6(ctx, namespace)
    # Every remaining option names a build_k6_parameters keyword (that's why the click options
    # above spell out `config_name`/`target_url`), so the run spec is assembled by forwarding the
    # bag wholesale. `overrides` is the one exception: it arrives as a repeated-flag tuple and has
    # to be flattened into the newline-delimited text that build_k6_parameters expects.
    overrides = run_opts.pop("overrides", ())
    try:
        params = build_k6_parameters(
            overrides_text=("\n".join(overrides) if overrides else None), **run_opts)
    except ValueError as e:
        click.echo(f"Error: --override {e}", err=True)
        ctx.exit(ExitCode.INVALID_INPUT.value)
        return
    scenario = params["scenario"]
    target_url = params["targetUrl"]
    config_name = params["configName"]

    try:
        load_k8s_config()
        _warn_if_unknown_preset(config_name)
        name = submit_k6_run(namespace, params)
    except ValueError as e:
        click.echo(f"Error: {e}", err=True)
        ctx.exit(ExitCode.INVALID_INPUT.value)
        return
    except Exception as e:
        click.echo(f"Error submitting k6 run: {e}", err=True)
        ctx.exit(ExitCode.FAILURE.value)
        return

    click.echo(f"Submitted k6 run: {name}")
    target_note = f" target={target_url}" if target_url else ""
    click.echo(f"  scenario={scenario} config={config_name} parallelism={params['parallelism']}{target_note}")
    click.echo(f"\nWatch:  workflow loadtest list\n        workflow loadtest logs {name} -f")

    if wait:
        click.echo(f"\nWaiting for completion (timeout {timeout}s)...")
        phase = wait_for_run(namespace, name, timeout, wait_interval)
        if phase is None:
            click.echo(f"Timed out after {timeout}s waiting for {name}.", err=True)
            ctx.exit(ExitCode.FAILURE.value)
        click.echo(f"Run {name} finished: {phase}")
        if phase != SUCCESS_PHASE:
            ctx.exit(ExitCode.FAILURE.value)


@loadtest_group.command(name="list")
@click.option('--scenario', default=None, shell_complete=_complete_scenarios,
              help="Filter by scenario.")
@click.option('--namespace', default=get_current_namespace, hidden=True, envvar='WORKFLOW_NAMESPACE')
@click.pass_context
def k6_list(ctx, scenario, namespace):
    """List k6 runs."""
    _require_k6(ctx, namespace)
    try:
        load_k8s_config()
        runs = list_runs(namespace, scenario)
    except Exception as e:
        click.echo(f"Error listing k6 runs: {e}", err=True)
        ctx.exit(ExitCode.FAILURE.value)
        return

    if not runs:
        click.echo("No k6 runs found.")
        return

    rows = [(r["name"], r["scenario"], r["phase"], r["parallelism"], r["age"]) for r in runs]

    header = ("NAME", "SCENARIO", "STAGE", "PARALLEL", "AGE")
    widths = [max(len(str(r[i])) for r in (header, *rows)) for i in range(len(header))]
    click.echo("  ".join(str(c).ljust(widths[i]) for i, c in enumerate(header)))
    for r in rows:
        click.echo("  ".join(str(c).ljust(widths[i]) for i, c in enumerate(r)))


@loadtest_group.command(name="stop")
@click.argument('name', required=False)
@click.option('--all', 'stop_all', is_flag=True, default=False, help="Stop all k6 runs.")
@click.option('--scenario', default=None, shell_complete=_complete_scenarios,
              help="Stop all k6 runs for one scenario.")
@click.option('--namespace', default=get_current_namespace, hidden=True, envvar='WORKFLOW_NAMESPACE')
@click.pass_context
def k6_stop(ctx, name, stop_all, scenario, namespace):
    """Stop k6 run(s).

    Stopping a run deletes its Workflow; the TestRun is owned by it, so the CR and the operator's
    runner/initializer pods go with it (there is no graceful pause).

    \b
    Examples:
      workflow loadtest stop k6-ingest-abc12
      workflow loadtest stop --scenario mixed
      workflow loadtest stop --all
    """
    _require_k6(ctx, namespace)
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
        deleted = delete_workflow(namespace, n)
        click.echo(f"{n}: {'stopped' if deleted else 'stop failed'}")


@loadtest_group.command(name="logs")
@click.argument('name')
@click.option('-f', '--follow', is_flag=True, default=False, help="Stream live logs.")
@click.option('--namespace', default=get_current_namespace, hidden=True, envvar='WORKFLOW_NAMESPACE')
@click.pass_context
def k6_logs(ctx, name, follow, namespace):
    """Show logs for a k6 run (the runner pods' k6 containers).

    \b
    Examples:
      workflow loadtest logs k6-ingest-abc12
      workflow loadtest logs k6-ingest-abc12 -f
    """
    _require_k6(ctx, namespace)
    subprocess.run(logs_command(namespace, name, follow))
