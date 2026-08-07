"""`workflow k6` — submit and manage k6 load-test runs.

Each run is a k6-operator **TestRun** CR (k6.io/v1alpha1), NOT an Argo workflow. The operator,
the scenario/preset ConfigMaps, and this command's RBAC all ship in the standalone k6LoadTest
chart (deployment/k8s/charts/components/k6LoadTest), which is installed separately from any
migration. A run is specified by a `scenario` (script) and a `config` (k6-config/*.env preset);
every preset value is overridable per run via named options or the repeatable `-e KEY=VALUE` bag.
Load is spread across `--parallelism` runner pods by k6 execution segments.

Because the infra is a separate opt-in, these commands are inert (and hidden from `--help`) unless
the TestRun CRD is present in the namespace — so a normal migration deployment cannot trigger a
load test. See TrafficCapture/trafficLoadTest/README.md for the deployment side.
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
    EXAMPLES_CONFIGMAP,
    create_testrun,
    list_testruns,
    get_testrun,
    delete_testrun,
    read_configmap,
    list_presets,
    list_scenarios,
    loadtest_installed,
)

logger = logging.getLogger(__name__)

K6_APP_LABEL = "k6-load-test"

# --scenario / --config are NOT restricted to these — the live sets are discovered from the cluster
# (list_scenarios / list_presets) for shell completion, and any scenario present in the cluster is
# launchable (custom scenarios included). These literals are only the completion fallback used when
# the cluster is unreachable (e.g. tab-completing offline).
SCENARIOS = ["ingest", "search", "mixed"]
CONFIG_PRESETS = [
    "ingest-steady", "ingest-ramp", "ingest-burst",
    "search-steady", "search-deep-paging", "search-ramp", "search-burst",
    "mixed-steady", "mixed-ramp", "mixed-burst",
]

# Stages of a TestRun that mean it is no longer active.
DONE_STAGES = {"finished", "error", "stopped"}


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
    return [v for v in _cluster_values(list_presets, CONFIG_PRESETS) if v.startswith(incomplete)]


# ---------------------------------------------------------------------------
# Availability guard: keep the commands inert unless the load-test chart is installed.
# ---------------------------------------------------------------------------
_AVAIL_CACHE = {}


def k6_available(namespace, force=False):
    """True if load testing is enabled here.

    `K6_LOADTEST_ENABLED` (true/false) is an explicit override — used by tests and as a kill
    switch. Otherwise we probe (cached) whether the TestRun CRD is usable in the namespace.

    `force=True` bypasses the cache and re-probes the cluster, refreshing the cached value. The
    long-running `workflow manage` TUI uses this to notice the k6LoadTest chart being installed
    *after* the console started — a plain cached probe would pin the startup result for the life of
    the process. The env override still wins over any probe.
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


class _K6Group(click.Group):
    """A group hidden from `--help` unless the k6 load-test infra (TestRun CRD) is installed."""

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


def _k6_testruns(namespace, scenario=None):
    """k6 TestRuns in the namespace, optionally narrowed to one scenario."""
    selector = f"app={K6_APP_LABEL}"
    if scenario:
        selector += f",k6-scenario={scenario}"
    return list_testruns(namespace, label_selector=selector)


# ---------------------------------------------------------------------------
# Run assembly.
# ---------------------------------------------------------------------------
def load_example(namespace, scenario):
    """Load the chart-rendered example TestRun (JSON) for a scenario. Helm is the single source of
    the run spec (items mount, image, K6_OUT, default preset); the console only patches it."""
    data = read_configmap(namespace, EXAMPLES_CONFIGMAP)
    if scenario not in data:
        available = ", ".join(sorted(data)) or "none"
        raise ValueError(f"no example for scenario '{scenario}' in ConfigMap {EXAMPLES_CONFIGMAP} "
                         f"(available: {available}); is the k6LoadTest chart installed?")
    return json.loads(data[scenario])


# Named run options → the scenario env vars each one sets. A value-carrying option is applied only
# when it's non-empty, so leaving it off keeps whatever the preset's envFrom supplies. --rate and
# --vus fan out to both the ingest and search vars because a scenario reads only its own pair.
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
    """Per-run env overrides (these win over the preset's envFrom). Named flags fan out to the vars
    the scenarios read; the -e bag is applied last, so it wins over the named flags."""
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


def build_testrun_spec(namespace, params):
    """Load the scenario's example TestRun and patch it: swap the envFrom preset, append env
    overrides (which win over envFrom), set parallelism, and pass any extra `k6 run` args. The
    example already carries the items mount, image, K6_OUT, labels, and generateName."""
    testrun = load_example(namespace, params["scenario"])
    runner = testrun["spec"]["runner"]

    preset_ref = {"configMapRef": {"name": f"k6-preset-{params['configName']}"}}
    if runner.get("envFrom"):
        runner["envFrom"][0] = preset_ref
    else:
        runner["envFrom"] = [preset_ref]
    runner.setdefault("env", []).extend(_override_env(params))

    testrun["spec"]["parallelism"] = int(params.get("parallelism", 1))
    if params.get("extraArgs"):
        testrun["spec"]["arguments"] = params["extraArgs"]
    return testrun


def submit_k6_run(namespace, params):
    """Build + create a TestRun from normalized params. Returns the generated name."""
    return create_testrun(namespace, build_testrun_spec(namespace, params))


def _warn_if_unknown_preset(namespace, config_name):
    """Warn (but never block) when the resolved config preset isn't among the cluster's presets. A
    missing preset usually means the run's envFrom points at a ConfigMap that won't exist, so the
    heads-up is useful — but custom/ad-hoc presets are allowed, so this never fails the run. Silent
    if the preset list can't be fetched (don't cry wolf when we simply couldn't look)."""
    try:
        available = list_presets(namespace)
    except Exception:
        return
    if available and config_name not in available:
        click.echo(f"Note: config preset '{config_name}' not found in the cluster "
                   f"(available: {', '.join(available)}); running anyway.", err=True)


def list_active_k6_runs(namespace):
    """Active (non-terminal) k6 runs as UI-friendly dicts: name/scenario/phase/age."""
    out = []
    for tr in _k6_testruns(namespace):
        stage = tr.get("status", {}).get("stage", "")
        if stage in DONE_STAGES:
            continue
        meta = tr.get("metadata", {})
        out.append({
            "name": meta.get("name", ""),
            "scenario": meta.get("labels", {}).get("k6-scenario", "?"),
            "phase": stage or "unknown",
            "age": _age(meta.get("creationTimestamp")),
        })
    out.sort(key=lambda r: r["name"])
    return out


def _wait_for_testrun(namespace, name, timeout, interval):
    """Poll a TestRun until it reaches a terminal stage; return the stage, or None on timeout."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        tr = get_testrun(namespace, name)
        stage = (tr or {}).get("status", {}).get("stage", "")
        if stage in DONE_STAGES:
            return stage
        time.sleep(interval)
    return None


# ---------------------------------------------------------------------------
# CLI.
# ---------------------------------------------------------------------------
@click.group(name="k6", cls=_K6Group)
def k6_group():
    """Submit and manage k6 load-test runs (k6-operator TestRuns)."""


@k6_group.command(name="run")
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
      workflow k6 run --scenario ingest --target https://my-proxy:9200
      workflow k6 run --scenario search --config search-deep-paging --rate 100 --duration 10m
      workflow k6 run --scenario mixed --parallelism 4 --registry-enabled -e INGEST_RATE=80
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
        _warn_if_unknown_preset(namespace, config_name)
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
    click.echo(f"\nWatch:  workflow k6 list\n        workflow k6 logs {name} -f")

    if wait:
        click.echo(f"\nWaiting for completion (timeout {timeout}s)...")
        stage = _wait_for_testrun(namespace, name, timeout, wait_interval)
        if stage is None:
            click.echo(f"Timed out after {timeout}s waiting for {name}.", err=True)
            ctx.exit(ExitCode.FAILURE.value)
        click.echo(f"Run {name} finished: {stage}")
        if stage != "finished":
            ctx.exit(ExitCode.FAILURE.value)


@k6_group.command(name="list")
@click.option('--scenario', default=None, shell_complete=_complete_scenarios,
              help="Filter by scenario.")
@click.option('--namespace', default=get_current_namespace, hidden=True, envvar='WORKFLOW_NAMESPACE')
@click.pass_context
def k6_list(ctx, scenario, namespace):
    """List k6 runs."""
    _require_k6(ctx, namespace)
    try:
        load_k8s_config()
        items = _k6_testruns(namespace, scenario)
    except Exception as e:
        click.echo(f"Error listing k6 runs: {e}", err=True)
        ctx.exit(ExitCode.FAILURE.value)
        return

    if not items:
        click.echo("No k6 runs found.")
        return

    rows = []
    for tr in items:
        meta = tr.get("metadata", {})
        rows.append((
            meta.get("name", "?"),
            meta.get("labels", {}).get("k6-scenario", "?"),
            tr.get("status", {}).get("stage", "unknown"),
            str(tr.get("spec", {}).get("parallelism", "-")),
            _age(meta.get("creationTimestamp")),
        ))
    rows.sort(key=lambda r: r[0])

    header = ("NAME", "SCENARIO", "STAGE", "PARALLEL", "AGE")
    widths = [max(len(str(r[i])) for r in (header, *rows)) for i in range(len(header))]
    click.echo("  ".join(str(c).ljust(widths[i]) for i, c in enumerate(header)))
    for r in rows:
        click.echo("  ".join(str(c).ljust(widths[i]) for i, c in enumerate(r)))


@k6_group.command(name="stop")
@click.argument('name', required=False)
@click.option('--all', 'stop_all', is_flag=True, default=False, help="Stop all k6 runs.")
@click.option('--scenario', default=None, shell_complete=_complete_scenarios,
              help="Stop all k6 runs for one scenario.")
@click.option('--namespace', default=get_current_namespace, hidden=True, envvar='WORKFLOW_NAMESPACE')
@click.pass_context
def k6_stop(ctx, name, stop_all, scenario, namespace):
    """Stop k6 run(s).

    Stopping a TestRun deletes the CR; the operator tears down its runner/initializer pods (there
    is no graceful pause).

    \b
    Examples:
      workflow k6 stop k6-run-abc12
      workflow k6 stop --scenario mixed
      workflow k6 stop --all
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
            names = [tr.get("metadata", {}).get("name", "")
                     for tr in _k6_testruns(namespace, scenario)]
            names = [n for n in names if n]
    except Exception as e:
        click.echo(f"Error resolving k6 runs: {e}", err=True)
        ctx.exit(ExitCode.FAILURE.value)
        return

    if not names:
        click.echo("No matching k6 runs.")
        return

    for n in names:
        deleted = delete_testrun(namespace, n)
        click.echo(f"{n}: {'stopped' if deleted else 'stop failed'}")


@k6_group.command(name="logs")
@click.argument('name')
@click.option('-f', '--follow', is_flag=True, default=False, help="Stream live logs.")
@click.option('--namespace', default=get_current_namespace, hidden=True, envvar='WORKFLOW_NAMESPACE')
@click.pass_context
def k6_logs(ctx, name, follow, namespace):
    """Show logs for a k6 run (the runner pods' k6 containers).

    \b
    Examples:
      workflow k6 logs k6-run-abc12
      workflow k6 logs k6-run-abc12 -f
    """
    _require_k6(ctx, namespace)
    cmd = ["kubectl", "logs", "-n", namespace,
           "-l", f"k6_cr={name},runner=true", "-c", "k6", "--tail=-1", "--prefix"]
    if follow:
        cmd.append("-f")
    subprocess.run(cmd)
