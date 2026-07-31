"""`workflow k6` — submit and manage k6 load-test runs.

Each run is a k6-operator **TestRun** CR (k6.io/v1alpha1), NOT an Argo workflow. The operator,
the scenario/preset ConfigMaps, and this command's RBAC all ship in the standalone k6LoadTest
chart (deployment/k8s/charts/components/k6LoadTest), which is installed separately from any
migration. A run is specified by a `scenario` (script) and a `config` (k6-config/*.env preset);
every preset value is overridable per run via named options or the repeatable `-o KEY=VALUE` bag.
Load is spread across `--parallelism` runner pods by k6 execution segments.

Because the infra is a separate opt-in, these commands are inert (and hidden from `--help`) unless
the TestRun CRD is present in the namespace — so a normal migration deployment cannot trigger a
load test. See TrafficCapture/trafficLoadTest/k8s/README.md for the deployment side.
"""

import calendar
import logging
import os
import subprocess
import time

import click

from ..models.utils import ExitCode, load_k8s_config, get_current_namespace
from .testrun_utils import (
    K6_GROUP,
    K6_VERSION,
    SCENARIOS_CONFIGMAP,
    PRESETS_CONFIGMAP,
    IMAGE_CONFIGMAP,
    create_testrun,
    list_testruns,
    get_testrun,
    delete_testrun,
    read_configmap,
    loadtest_installed,
)

logger = logging.getLogger(__name__)

K6_APP_LABEL = "k6-load-test"
K6_GENERATE_NAME = "k6-run-"

SCENARIOS = ["ingest", "search", "mixed"]
# Presets shipped as ConfigMap keys (k6-config/*.env). Used for completion/help; any name works.
CONFIG_PRESETS = [
    "ingest-steady", "ingest-ramp", "ingest-burst",
    "search-steady", "search-deep-paging", "search-ramp", "search-burst",
    "mixed-steady", "mixed-ramp", "mixed-burst",
]

# Stages of a TestRun that mean it is no longer active.
DONE_STAGES = {"finished", "error", "stopped"}

# Default OTLP endpoint k6 pushes metrics to (the migration's otel-collector).
DEFAULT_OTEL_ENDPOINT = "otel-collector:4317"


# ---------------------------------------------------------------------------
# Availability guard: keep the commands inert unless the load-test chart is installed.
# ---------------------------------------------------------------------------
_AVAIL_CACHE = {}


def k6_available(namespace):
    """True if load testing is enabled here.

    `K6_LOADTEST_ENABLED` (true/false) is an explicit override — used by tests and as a kill
    switch. Otherwise we probe once (cached) whether the TestRun CRD is usable in the namespace.
    """
    override = os.environ.get("K6_LOADTEST_ENABLED")
    if override is not None:
        return override.strip().lower() in ("1", "true", "yes", "on")
    if namespace not in _AVAIL_CACHE:
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
def _parse_preset(text):
    """Parse a sourceable k6-config/*.env preset into a {KEY: VALUE} dict."""
    env = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[len("export "):]
        if "=" not in line:
            continue
        key, val = line.split("=", 1)
        env[key.strip()] = val.strip().strip('"').strip("'")
    return env


def resolve_env(namespace, scenario, config_name, target_url=None, rate=None, duration=None,
                vus=None, registry_enabled=None, control_enabled=None, webdis_url=None,
                overrides_text=None):
    """Merge the selected preset with per-run overrides into the runner env (precedence order).

    preset (defaults) < named convenience (rate/duration/vus) < dedicated (target/webdis/flags) <
    generic KEY=VALUE bag. The operator owns the runner command, so env is resolved here rather
    than by an in-container shell wrapper.
    """
    preset_key = f"{config_name}.env"
    presets = read_configmap(namespace, PRESETS_CONFIGMAP)
    if preset_key not in presets:
        raise ValueError(f"unknown config preset '{config_name}' "
                         f"(no {preset_key} in ConfigMap {PRESETS_CONFIGMAP})")
    env = _parse_preset(presets[preset_key])

    if duration:
        env["DURATION"] = duration
    if rate:
        env["INGEST_RATE"] = rate
        env["SEARCH_RATE"] = rate
    if vus:
        env["INGEST_VUS"] = vus
        env["SEARCH_VUS"] = vus
    if target_url:
        env["CAPTURE_PROXY_URL"] = target_url
    if webdis_url:
        env["WEBDIS_URL"] = webdis_url
    if registry_enabled is not None:
        env["REGISTRY_ENABLED"] = str(registry_enabled).lower()
    if control_enabled is not None:
        env["CONTROL_ENABLED"] = str(control_enabled).lower()
    if overrides_text:
        for line in overrides_text.splitlines():
            line = line.strip()
            if not line:
                continue
            if "=" not in line:
                raise ValueError(f"override must be KEY=VALUE, got '{line}'")
            key, val = line.split("=", 1)
            env[key.strip()] = val.strip()
    return env


def _scenario_volume(namespace):
    """Return (volume, volumeMount) that reconstruct the scenario tree at /scripts.

    ConfigMap keys are the file paths with "/" -> "__"; we project each back to its real path so
    imports ("../lib/...") and open("../data/...") resolve.
    """
    data = read_configmap(namespace, SCENARIOS_CONFIGMAP)
    if not data:
        raise ValueError(f"ConfigMap {SCENARIOS_CONFIGMAP} is missing or empty; "
                         "is the k6LoadTest chart installed?")
    items = [{"key": key, "path": key.replace("__", "/")} for key in sorted(data)]
    volume = {"name": "scenarios", "configMap": {"name": SCENARIOS_CONFIGMAP, "items": items}}
    mount = {"name": "scenarios", "mountPath": "/scripts"}
    return volume, mount


def _runner_image(namespace):
    data = read_configmap(namespace, IMAGE_CONFIGMAP)
    return (data.get("k6Image", "grafana/k6:latest"),
            data.get("k6PullPolicy", "IfNotPresent"))


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


def build_testrun_spec(namespace, params, image, pull_policy):
    """Assemble a TestRun body from normalized params. Standalone: its own generateName + labels,
    no ownerReferences, so a k6 run cannot affect a migration."""
    scenario = params["scenario"]
    env = resolve_env(
        namespace, scenario, params["configName"],
        target_url=params.get("targetUrl"), rate=params.get("rate"),
        duration=params.get("duration"), vus=params.get("vus"),
        registry_enabled=params.get("registryEnabled"),
        control_enabled=params.get("controlEnabled"),
        webdis_url=params.get("webdisUrl"), overrides_text=params.get("overrides"),
    )
    volume, mount = _scenario_volume(namespace)

    env_list = [{"name": k, "value": str(v)} for k, v in sorted(env.items())]
    otel_endpoint = os.environ.get("K6_OTEL_ENDPOINT", DEFAULT_OTEL_ENDPOINT)
    # Metrics output is set via K6_OUT (runner env), NOT `--out` in spec.arguments: the operator
    # passes spec.arguments to the initializer's `k6 archive` too, which rejects run-only flags
    # like `--out`. Env-based output is ignored by archive and honored by `k6 run`.
    env_list += [
        {"name": "K6_OUT", "value": "opentelemetry"},
        {"name": "K6_OTEL_GRPC_EXPORTER_ENDPOINT", "value": otel_endpoint},
        {"name": "K6_OTEL_GRPC_EXPORTER_INSECURE", "value": "true"},
    ]

    pod = {"image": image, "imagePullPolicy": pull_policy,
           "volumeMounts": [mount], "volumes": [volume]}
    initializer = dict(pod)          # initializer must also see the script to archive/inspect it
    runner = dict(pod, env=env_list)

    spec = {
        "parallelism": int(params.get("parallelism", 1)),
        "script": {"localFile": f"/scripts/scenarios/{scenario}.js"},
        "initializer": initializer,
        "runner": runner,
    }
    # extra_args go to `k6 run` via spec.arguments. Note the operator also feeds these to the
    # initializer's `k6 archive`, so only archive-compatible flags are safe here.
    if params.get("extraArgs"):
        spec["arguments"] = params["extraArgs"]

    return {
        "apiVersion": f"{K6_GROUP}/{K6_VERSION}",
        "kind": "TestRun",
        "metadata": {
            "generateName": K6_GENERATE_NAME,
            "labels": {"app": K6_APP_LABEL, "k6-scenario": scenario},
        },
        "spec": spec,
    }


def submit_k6_run(namespace, params):
    """Build + create a TestRun from normalized params. Returns the generated name."""
    image, pull_policy = _runner_image(namespace)
    body = build_testrun_spec(namespace, params, image, pull_policy)
    return create_testrun(namespace, body)


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
@click.option('--scenario', type=click.Choice(SCENARIOS), default="ingest", show_default=True,
              help="Scenario script to run.")
@click.option('--config', 'config_name', default=None,
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
@click.option('--override', '-o', 'overrides', multiple=True, metavar='KEY=VALUE',
              help="Extra env override, applied after the preset. Repeatable.")
@click.option('--extra-args', default=None, help="Extra flags for `k6 run` (e.g. --no-thresholds).")
@click.option('--namespace', default=get_current_namespace, hidden=True, envvar='WORKFLOW_NAMESPACE')
@click.option('--wait', is_flag=True, default=False, help="Wait for the run to complete.")
@click.option('--timeout', default=600, type=int, help="Seconds to wait with --wait (default 600).")
@click.option('--wait-interval', default=5, type=int, help="Seconds between status checks with --wait.")
@click.pass_context
def k6_run(ctx, scenario, config_name, parallelism, target_url, rate, duration, vus,
           registry_enabled, control_enabled, overrides, extra_args,
           namespace, wait, timeout, wait_interval):
    """Submit a k6 run.

    \b
    Examples:
      workflow k6 run --scenario ingest --target https://my-proxy:9200
      workflow k6 run --scenario search --config search-deep-paging --rate 100 --duration 10m
      workflow k6 run --scenario mixed --parallelism 4 --registry-enabled -o INGEST_RATE=80
    """
    _require_k6(ctx, namespace)
    try:
        params = build_k6_parameters(
            scenario=scenario, config_name=config_name, parallelism=parallelism,
            target_url=target_url, rate=rate, duration=duration, vus=vus,
            registry_enabled=registry_enabled, control_enabled=control_enabled,
            overrides_text=("\n".join(overrides) if overrides else None), extra_args=extra_args,
        )
    except ValueError as e:
        click.echo(f"Error: --override {e}", err=True)
        ctx.exit(ExitCode.INVALID_INPUT.value)
        return
    config_name = params["configName"]

    try:
        load_k8s_config()
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
@click.option('--scenario', type=click.Choice(SCENARIOS), default=None, help="Filter by scenario.")
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
@click.option('--scenario', type=click.Choice(SCENARIOS), default=None,
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
