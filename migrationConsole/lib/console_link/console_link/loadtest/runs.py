"""Run assembly and lifecycle for k6 load tests — everything the `loadtest` commands do to a
cluster, with no Click in it, so the CLI and the TUI drive the same code.

Each run is an **Argo Workflow** that creates a k6-operator **TestRun** and waits for it. The
operator, the per-scenario WorkflowTemplates, and this client's RBAC ship in the standalone
k6LoadTest chart (deployment/k8s/charts/components/k6LoadTest), which is installed separately from
any migration. The load-test-only migrations/k6_runner image contains the pinned k6 executable,
extensions, scenarios, and presets.

A run is specified by a load `profile` — one WorkflowTemplate, e.g. `k6-ingest-burst` — which states
every setting of that run as a named Argo parameter with a value. Submitting overrides only the
parameters the run changes, by name; anything left out keeps the template's value. There is no
preset layer beneath (`K6_PRESET` is not set in-cluster), so what the template says is what the run
gets. Load is spread across `--parallelism` runner pods by k6 execution segments, which is the
operator's job, not Argo's.

See TrafficCapture/trafficLoadTest/README.md for the deployment side.
"""

import calendar
import logging
import re
import time

from kubernetes.client.rest import ApiException

from .testrun_utils import (
    K6_APP_LABEL,
    create_workflow,
    list_workflows,
    get_workflow,
    get_workflow_template,
    runner_selector,
    workflow_template_name,
    list_k6_workflow_templates,
    list_profiles,
)

logger = logging.getLogger(__name__)

# An ALL_CAPS parameter on a WorkflowTemplate is a runner environment variable; a camelCase one
# configures the run around it (parallelism, images). The chart renders them to this convention on
# purpose, so a client can tell load settings from plumbing without a second list to maintain.
ENV_PARAM = re.compile(r"[A-Z][A-Z0-9_]*")

# Launchable scenarios and profiles are discovered from the cluster (list_scenarios / list_profiles,
# from the chart's WorkflowTemplates). These two lists are only the completion fallbacks used when
# the cluster is unreachable; neither restricts what may be run, since a chart with different values
# renders whatever profiles it was given.
SCENARIOS = ["ingest", "search", "mixed"]
PROFILES = [
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
def load_template(namespace, profile):
    """The WorkflowTemplate of a load profile, or a ValueError naming the profiles that do exist.

    Helm is the single source of the run spec — images, script path, and every load setting as a
    named parameter with a value — so nothing here restates any of it. The client reads this to
    learn what a run can be given, and submits only the parameters it changes.
    """
    name = workflow_template_name(profile)
    template = get_workflow_template(namespace, name)
    if template is None:
        available = ", ".join(list_profiles(namespace)) or "none"
        raise ValueError(f"no WorkflowTemplate '{name}' for profile '{profile}' "
                         f"(available: {available}). Install the k6LoadTest chart from "
                         f"{CHART_PATH} to enable load testing.")
    return template


def _template_parameters(template):
    """A template's parameter defaults as a name → value dict."""
    params = template.get("spec", {}).get("arguments", {}).get("parameters", [])
    return {p["name"]: p.get("value", "") for p in params if "name" in p}


def load_template_defaults(namespace, profile):
    """The parameter defaults of a profile's WorkflowTemplate, as a name → value dict."""
    return _template_parameters(load_template(namespace, profile))


def profile_catalog(namespace):
    """Every launchable profile as {profile: {scenario, description, env}}, from ONE list call.

    `env` holds only the ALL_CAPS parameters — the load settings, with the values the run would use
    if nothing were overridden. The launch form uses this to show real defaults and to offer only
    the settings a given profile actually has, instead of guessing from the profile's name.
    """
    try:
        templates = list_k6_workflow_templates(namespace)
    except ApiException:
        return {}
    catalog = {}
    for t in templates:
        meta = t.get("metadata", {})
        profile = meta.get("labels", {}).get("k6-profile")
        if not profile:
            continue
        params = _template_parameters(t)
        catalog[profile] = {
            "scenario": meta.get("labels", {}).get("k6-scenario", "?"),
            "description": meta.get("annotations", {}).get(
                "workflows.argoproj.io/description", ""),
            "env": {k: v for k, v in params.items() if ENV_PARAM.fullmatch(k)},
        }
    return catalog


# Named run options → the parameters each one sets. --rate and --vus name both the ingest and the
# search variable because a scenario reads only its own pair: the option applies to whichever the
# profile declares. A value-carrying option is applied only when it's non-empty, so leaving it off
# keeps the template's value.
_VALUE_ENV_VARS = (
    ("duration", ("DURATION",)),
    ("rate", ("INGEST_RATE", "SEARCH_RATE")),
    ("vus", ("INGEST_VUS", "SEARCH_VUS")),
    ("targetUrl", ("CAPTURE_PROXY_URL",)),
)
# The toggles are three-state: None means "keep the template's value", so they can't use the
# truthiness test above — an explicit False still has to emit an override to turn a profile's
# setting off.
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


def _override_env(params, declared, profile):
    """Per-run parameter overrides as a name → value dict, checked against what the profile has.

    `declared` is the profile's ALL_CAPS parameter names. A setting the profile does not have is
    rejected rather than dropped: Argo accepts a parameter no template references and then ignores
    it, so an unchecked `-e SEARCH_RATE=…` on an ingest run would look like it took effect and
    quietly change nothing. The one thing that is NOT an error is a fan-out option naming a variable
    this scenario has no use for (--rate on search sets SEARCH_RATE only); it fails only when the
    profile has neither.
    """
    env = {}
    for key, names in _VALUE_ENV_VARS:
        if not params.get(key):
            continue
        applicable = [n for n in names if n in declared]
        if not applicable:
            raise ValueError(f"profile '{profile}' has no {' or '.join(names)} setting")
        env.update(dict.fromkeys(applicable, params[key]))
    for key, name in _TOGGLE_ENV_VARS:
        if params.get(key) is None:
            continue
        if name not in declared:
            raise ValueError(f"profile '{profile}' has no {name} setting")
        env[name] = str(params[key]).lower()
    for key, value in _parse_overrides(params.get("overrides")).items():
        if key not in declared:
            raise ValueError(f"profile '{profile}' has no '{key}' setting "
                             f"(it has: {', '.join(sorted(declared))})")
        env[key] = value
    return env


def build_k6_parameters(scenario=None, config_name=None, parallelism=1, target_url=None, rate=None,
                        duration=None, vus=None, registry_enabled=None, control_enabled=None,
                        auth_secret_name=None, overrides_text=None, extra_args=None):
    """Normalize run inputs into a params dict (shared by the CLI and the TUI).

    A run needs a profile. Naming a scenario alone means its steady profile; naming a profile alone
    is enough, since the profile's own WorkflowTemplate says which scenario it belongs to.

    Validates the `overrides_text` bag eagerly so bad input is rejected before any API call. Names
    are checked later, against the profile that is actually installed.
    """
    if overrides_text:
        for line in overrides_text.splitlines():
            line = line.strip()
            if line and "=" not in line:
                raise ValueError(f"override must be KEY=VALUE, got '{line}'")
    if not scenario and not config_name:
        scenario = SCENARIOS[0]
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
        "authSecretName": auth_secret_name,
        "overrides": overrides_text,
        "extraArgs": extra_args,
    }


def build_workflow_submission(namespace, params):
    """Build the Workflow that runs a profile: name its WorkflowTemplate and pass ONLY the
    parameters the run changes.

    Everything else — the images, the scripts mount, the script path under it, and every load
    setting — is already in the template with a value, so an omitted parameter is not an unknown:
    it is the profile's own setting. The scenario label is taken from the template rather than from
    the caller, so a run cannot be filed under a scenario it did not run.
    """
    profile = params["configName"]
    template = load_template(namespace, profile)
    defaults = _template_parameters(template)
    scenario = template.get("metadata", {}).get("labels", {}).get("k6-scenario", "?")

    asked = params.get("scenario")
    if asked and asked != scenario:
        raise ValueError(f"profile '{profile}' runs scenario '{scenario}', not '{asked}'")

    declared = {k for k in defaults if ENV_PARAM.fullmatch(k)}
    overrides = _override_env(params, declared, profile)

    parameters = [{"name": k, "value": str(v)} for k, v in sorted(overrides.items())]
    parameters.append({"name": "parallelism", "value": str(int(params.get("parallelism", 1)))})
    if params.get("authSecretName"):
        parameters.append({"name": "authSecretName", "value": params["authSecretName"]})
    if params.get("extraArgs"):
        parameters.append({"name": "arguments", "value": params["extraArgs"]})

    return {
        "apiVersion": "argoproj.io/v1alpha1",
        "kind": "Workflow",
        "metadata": {
            "generateName": f"{workflow_template_name(profile)}-",
            "labels": {"app": K6_APP_LABEL, "k6-scenario": scenario, "k6-profile": profile},
        },
        "spec": {
            "workflowTemplateRef": {"name": workflow_template_name(profile)},
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
        labels = meta.get("labels", {})
        out.append({
            "name": meta.get("name", ""),
            "scenario": labels.get("k6-scenario", "?"),
            "profile": labels.get("k6-profile", "?"),
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
           "-l", runner_selector(name), "-c", "k6", "--tail=-1", "--prefix"]
    if follow:
        cmd.append("-f")
    return cmd


def wait_for_run(namespace, name, timeout, interval, on_poll=None):
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

    None of those five say whether the load test WORKED — a run that failed every request still
    ends `Succeeded` (see health.py). `on_poll(elapsed, phase)`, when given, is called once per
    non-terminal poll so a caller can read that from k6 and report it as the wait goes on. It is
    called only while the run is alive, since the k6 API dies with the runner pods.
    """
    deadline = time.time() + timeout
    started = time.time()
    while time.time() < deadline:
        wf = get_workflow(namespace, name)
        phase = (wf or {}).get("status", {}).get("phase", "")
        if phase in DONE_PHASES:
            return phase
        if on_poll:
            # Progress reporting is an extra. It must never be the reason a wait ends early, so a
            # broken callback costs its own line and nothing more.
            try:
                on_poll(time.time() - started, phase)
            except Exception as e:
                logger.debug("Progress callback failed during wait for %s: %s", name, e)
        time.sleep(interval)
    return None
