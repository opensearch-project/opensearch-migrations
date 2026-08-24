"""Run assembly and lifecycle for k6 load tests — everything the `loadtest` commands do to a
cluster, with no Click in it, so the CLI and the TUI drive the same code.

Each run is an **Argo Workflow** that creates a k6-operator **TestRun** and waits for it. The
operator, the per-scenario WorkflowTemplates, and this client's RBAC ship in the standalone
k6LoadTest chart (deployment/k8s/charts/components/k6LoadTest), which is installed separately from
any migration; the scenarios and presets themselves ride in a data image (migrations/k6_scripts,
built from TrafficCapture/trafficLoadTest) that the templates mount at /scripts on stock grafana/k6
pods.

A run is specified by a `scenario` (which names a WorkflowTemplate, and a script path under /scripts)
and a `config` (a k6-config/*.env preset from the same mount, selected with the K6_PRESET env var);
every preset value is overridable per run via named options or the repeatable `-e KEY=VALUE` bag,
since real environment variables win over the preset file. Load is spread across `--parallelism`
runner pods by k6 execution segments, which is the operator's job, not Argo's.

See TrafficCapture/trafficLoadTest/README.md for the deployment side.
"""

import calendar
import json
import logging
import time

from .testrun_utils import (
    K6_APP_LABEL,
    create_workflow,
    list_workflows,
    get_workflow,
    get_workflow_template,
    workflow_template_name,
    list_k6_workflow_templates,
    list_scenarios,
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

CHART_PATH = "deployment/k8s/charts/components/k6LoadTest"


# ---------------------------------------------------------------------------
# Diagnosis: why is there nothing here?
# ---------------------------------------------------------------------------
def chart_missing_hint(namespace):
    """The install hint if this namespace holds no k6 WorkflowTemplates, else None.

    Call this ONLY on a dead-end path — when a command already has nothing useful to show, or when
    the TUI is about to open a screen that could do nothing. It is not a pre-flight gate: the real
    call reports what actually went wrong far better than a probe can, and a probe run ahead of
    every command used to turn *any* cluster problem (an expired kubeconfig, a missing RBAC verb, a
    transient API error) into the single wrong answer "not installed", sending users to reinstall a
    chart that was already there.

    For that reason this deliberately does NOT catch exceptions, and calls
    list_k6_workflow_templates rather than list_scenarios, which swallows ApiException. A caller
    that already has a real error on screen may suppress a failure here and simply omit the hint;
    a caller for which this is the only diagnosis must let it propagate.
    """
    if list_k6_workflow_templates(namespace):
        return None
    return (f"The k6LoadTest chart is not installed in namespace '{namespace}' — no k6 "
            f"WorkflowTemplates found.\nInstall it from {CHART_PATH} to enable load testing.")


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


def k6_workflows(namespace, scenario=None):
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
    client reads those defaults and overrides only what the run asks for, so the static values are
    never restated here.
    """
    name = workflow_template_name(scenario)
    template = get_workflow_template(namespace, name)
    if template is None:
        available = ", ".join(list_scenarios(namespace)) or "none"
        raise ValueError(f"no WorkflowTemplate '{name}' for scenario '{scenario}' "
                         f"(available: {available}). Install the k6LoadTest chart from "
                         f"{CHART_PATH} to enable load testing.")
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
    for wf in k6_workflows(namespace, scenario):
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

    The phase is Argo's `.status.phase` on the Workflow, NOT the k6-operator's `.status.stage` on
    the TestRun it drives. Argo defines five:

      Pending    - accepted, no pod scheduled yet
      Running    - the resource task is creating the TestRun or polling its stage
      Succeeded  - terminal, the only success
      Failed     - terminal, the run itself failed
      Error      - terminal, Argo could not run the step (RBAC, a malformed manifest, the
                   `activeDeadlineSeconds` cap)

    `Failed` vs `Error` is worth reading as a hint about where to look: the former points at
    the load test, the latter at the setup around it.
    """
    deadline = time.time() + timeout
    while time.time() < deadline:
        wf = get_workflow(namespace, name)
        phase = (wf or {}).get("status", {}).get("phase", "")
        if phase in DONE_PHASES:
            return phase
        time.sleep(interval)
    return None
