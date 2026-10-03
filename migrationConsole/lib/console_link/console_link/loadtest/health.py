"""Live health of a k6 run, read from k6 itself.

The Argo phase of a run answers "did it complete", never "did it work". A k6 run whose every
request failed still ends as `Succeeded`: k6 exits 99 on a crossed threshold, but the k6-operator
reports that TestRun as `stage: finished`, and the WorkflowTemplate's success condition watches
exactly that stage. So the phase alone cannot tell a healthy run from a wall of 401s.

This module fills that gap from the ONE source that already knows — k6. Every runner pod serves
k6's REST API on port 6565, so the numbers here are k6's own metrics, with no otel-collector,
Prometheus or Grafana in the path. Two endpoints per runner:

    GET /v1/status    -> {running, paused, vus, vus-max, tainted, ...}
    GET /v1/metrics   -> [{id, attributes: {type, contains, tainted, sample}}, ...]

`sample` depends on the metric type: counter {count, rate}, rate {rate}, gauge {value},
trend {min, med, avg, max, p(90), p(95)}.

Two things it gives that nothing else does during a run:

* **A true error split.** k6 counts a request as failed when its status is outside 200-399, and
  `http_req_failed` here is the real ratio. (In OTLP/Prometheus mode `http_req_failed_total` is
  the count of samples, so it always equals `http_reqs_total` — see AGENT_MEMORY/metrics-pipeline.
  That trap does not apply to this API.) Absolute counts come from the ratio and the request
  counter:  failed = rate * http_reqs.count.
* **k6's own limits.** `attributes.tainted` on a metric means its threshold is crossed right now,
  so the limits come from the scenario that declared them, not from a second set invented here.

  A taint is a CURRENT reading, not a latch. k6 re-evaluates a threshold on every tick and clears
  the flag when the metric recovers — measured: a run whose first 20 requests failed showed
  `http_req_failed` tainted at 40s, clean from 60s on, and ended with k6 exiting 0. So the union
  of everything ever tainted is not a verdict; treating it as one warns about healthy runs.

The verdict on a FINISHED run therefore comes from somewhere else: the k6 container's exit code
(`run_verdict`). k6 exits 99 when a threshold is crossed at the end of the test, and that is the
same judgement its end-of-test summary prints. It is readable after the run, when the REST API is
already gone, from the pod list this module already reads.

Nothing in here fails a run. A crossed threshold is reported as a warning and the exit code stays
the workflow's, because the thresholds are tuned per scenario and a breach is not always a reason
to fail a script.

Reaching the runners: the pods are addressed by IP, from the pod list the chart already grants for
`loadtest logs`. That works from inside the cluster (the migration console). Run the CLI from a
laptop against a kubeconfig and the pod network is unreachable — every probe then fails and the
caller degrades to the phase alone, which is why no caller may treat "no health" as "unhealthy".
"""

import logging

import requests

from .testrun_utils import list_runner_pods

logger = logging.getLogger(__name__)

# k6's REST API port. The operator starts every runner with the API bound to the pod address.
RUNNER_API_PORT = 6565

# Per-request timeout for a probe. A poll interval is measured in seconds and a runner under load
# still answers this endpoint in milliseconds, so a slow answer is a dead runner, not a busy one.
DEFAULT_TIMEOUT = 3.0

# k6's exit code when a threshold is crossed at the end of the test. Every other non-zero code is
# k6 failing to run the test at all (a script error, an unreachable setup), which is a different
# thing to report — that one usually reaches the workflow phase as well.
EXIT_THRESHOLDS_CROSSED = 99

# The two metrics the aggregate is built from. Anything else is per-scenario and is reported only
# through `tainted`, so this module needs no list of scenario metric names to keep in step.
REQUESTS_METRIC = "http_reqs"
FAILED_METRIC = "http_req_failed"
DURATION_METRIC = "http_req_duration"


def _sample(metrics, metric_id, key, default=None):
    """One field of one metric's sample, or `default` when k6 has not reported it yet.

    A metric appears only once it has data, so an early poll legitimately has neither
    `http_reqs` nor `http_req_failed`. That is "nothing yet", not an error.
    """
    attributes = metrics.get(metric_id)
    if not attributes:
        return default
    value = attributes.get("sample", {}).get(key)
    return default if value is None else value


def _tainted(metrics):
    """The metric ids whose threshold k6 reports as crossed."""
    return sorted(mid for mid, attributes in metrics.items() if attributes.get("tainted"))


def runner_health(base_url, timeout=DEFAULT_TIMEOUT):
    """One runner's health from its k6 REST API, or None if the API did not answer.

    None covers every reason a runner cannot be read — not started, already exited, not routable
    — because the caller does the same thing with all of them: leave that runner out of the totals
    and say how many of the expected runners answered.
    """
    try:
        status = requests.get(f"{base_url}/v1/status", timeout=timeout).json()
        payload = requests.get(f"{base_url}/v1/metrics", timeout=timeout).json()
    except (requests.RequestException, ValueError) as e:
        logger.debug("k6 API at %s did not answer: %s", base_url, e)
        return None

    attributes = status.get("data", {}).get("attributes", {})
    metrics = {m.get("id"): m.get("attributes", {}) for m in payload.get("data", []) if m.get("id")}

    count = int(_sample(metrics, REQUESTS_METRIC, "count", 0))
    # A ratio over the same requests `http_reqs` counts, so it scales back to absolute counts
    # exactly. Rounding one runner at a time keeps each row's ok + failed equal to its own total.
    failed = int(round(_sample(metrics, FAILED_METRIC, "rate", 0.0) * count))
    return {
        "running": bool(attributes.get("running")),
        "paused": bool(attributes.get("paused")),
        "vus": int(attributes.get("vus", 0) or 0),
        "requests": count,
        "failed": failed,
        "ok": count - failed,
        "p95_ms": _sample(metrics, DURATION_METRIC, "p(95)"),
        "tainted": _tainted(metrics),
    }


def _aggregate(runners):
    """Sum the runners that answered into one picture of the run.

    Counters and taints combine exactly. A p95 does NOT: percentiles from separate pods cannot be
    merged, so this reports the worst runner's p95 and says so, rather than averaging percentiles
    into a number that means nothing.
    """
    reporting = [r for r in runners if r.get("reachable")]
    # Only from runners that have actually made a request. k6 reports the duration trend as zeros
    # before the first one lands, and "p95=0ms" reads as an impossibly fast run rather than as
    # a run that has not started working yet.
    percentiles = [r["p95_ms"] for r in reporting if r.get("p95_ms") is not None and r["requests"]]
    tainted = sorted({t for r in reporting for t in r.get("tainted", ())})
    requests_total = sum(r["requests"] for r in reporting)
    failed = sum(r["failed"] for r in reporting)
    return {
        "expected": len(runners),
        "reachable": len(reporting),
        "running": any(r.get("running") for r in reporting),
        "vus": sum(r["vus"] for r in reporting),
        "requests": requests_total,
        "failed": failed,
        "ok": requests_total - failed,
        "failed_pct": (100.0 * failed / requests_total) if requests_total else 0.0,
        "worst_p95_ms": max(percentiles) if percentiles else None,
        "tainted": tainted,
        "runners": runners,
    }


def run_health(namespace, name, timeout=DEFAULT_TIMEOUT):
    """A run's health, polled from every runner pod's k6 API.

    Always returns a picture, never raises: health is an extra on top of the phase, so a cluster
    error here must not take down the command that asked. The failure is reported in `error`, and
    `reachable` says how much of the run the numbers actually cover.
    """
    try:
        pods = list_runner_pods(namespace, name)
    except Exception as e:
        logger.debug("Could not list runner pods for %s: %s", name, e)
        return dict(_aggregate([]), error=str(e))

    runners = []
    for pod in pods:
        health = runner_health(f"http://{pod['ip']}:{RUNNER_API_PORT}", timeout) if pod["ip"] \
            else None
        runners.append(dict(health or {}, pod=pod["pod"], reachable=health is not None))
    return dict(_aggregate(runners), error=None)


def run_verdict(namespace, name):
    """k6's own verdict on a finished run, from the runner pods' exit codes.

    Returns {thresholds_crossed, failed_runners, exit_codes, known}. `known` is False when no
    runner has exited yet, or when the pods have already been reaped — then there is nothing to
    say, and a caller must fall back to what it observed rather than declare the run clean.

    This is the reading that survives the run. The REST API dies with the k6 process, so by the
    time a wait ends its last poll reaches nobody; the exit code is still there, and it is k6's
    end-of-test judgement rather than a sample of one moment.
    """
    try:
        pods = list_runner_pods(namespace, name)
    except Exception as e:
        logger.debug("Could not read runner exit codes for %s: %s", name, e)
        pods = []
    codes = {p["pod"]: p["exit_code"] for p in pods if p["exit_code"] is not None}
    return {
        "known": bool(codes),
        "exit_codes": codes,
        "thresholds_crossed": any(c == EXIT_THRESHOLDS_CROSSED for c in codes.values()),
        # Any other non-zero code: k6 could not run the test, rather than the test failing its
        # limits. Reported apart so the two are not explained to a reader as the same thing.
        "failed_runners": sorted(p for p, c in codes.items()
                                 if c not in (0, EXIT_THRESHOLDS_CROSSED)),
    }


class HealthWatcher:
    """Polls one run's health and remembers what it saw.

    Remembering is the point. The k6 API lives in the runner process, so it disappears at the very
    moment a run ends — poll a finished run and every probe is refused. `last` is therefore the
    newest poll that reached at least one runner, so the end of a wait can still report what the
    run was doing instead of the zeros of a torn-down pod.

    Successive polls also give a request rate, which one poll cannot.

    What it deliberately does NOT keep is a running union of tainted metrics. A taint is k6's
    current evaluation and clears when the metric recovers, so that union would report a warm-up
    blip as a failed run. `last_tainted` is the newest reading; the verdict on a finished run is
    `run_verdict`.
    """

    def __init__(self, namespace, name, timeout=DEFAULT_TIMEOUT):
        self._namespace = namespace
        self._name = name
        self._timeout = timeout
        self.last = None

    @property
    def last_tainted(self):
        """The metrics over their threshold at the last poll that reached a runner."""
        return list(self.last["tainted"]) if self.last else []

    def verdict(self):
        """k6's end-of-run verdict, once the runners have exited. See run_verdict."""
        return run_verdict(self._namespace, self._name)

    def poll(self, elapsed=None):
        """Poll once. Returns the health, with `elapsed` and a `rate_per_sec` from the last poll.

        A poll that reached nobody does not overwrite `last`: an unreachable runner means "cannot
        see", and replacing good numbers with zeros would report a healthy run as a dead one.
        """
        health = run_health(self._namespace, self._name, self._timeout)
        health["elapsed"] = elapsed
        health["rate_per_sec"] = self._rate(health, elapsed)
        if health["reachable"]:
            self.last = health
        return health

    def _rate(self, health, elapsed):
        """Requests per second since the last poll, or None when there is nothing to compare."""
        previous = self.last
        if not health["reachable"] or previous is None or elapsed is None:
            return None
        window = elapsed - (previous.get("elapsed") or 0)
        if window <= 0:
            return None
        return max(0.0, (health["requests"] - previous["requests"]) / window)


# ---------------------------------------------------------------------------
# Display. The numbers are assembled above; these turn one poll into text, so the CLI and the TUI
# report a run the same way.
# ---------------------------------------------------------------------------
def format_health(health):
    """One line of health for a poll, e.g.

        [01:30] 2/2 runners  vus=4  reqs=7550 (84/s)  ok=7100  err=450 (6.0%)  p95=44ms  ! ...

    Degrades on purpose. Before the runners are up there is nothing to count, and after they exit
    there is nobody to ask; both say so in words rather than printing a row of zeros that reads
    like a run doing no work.
    """
    stamp = _clock(health.get("elapsed"))
    if health.get("error"):
        return f"{stamp}health unavailable: {health['error']}"
    if not health["expected"]:
        return f"{stamp}waiting for runner pods"
    if not health["reachable"]:
        return f"{stamp}0/{health['expected']} runners reporting"

    parts = [f"{health['reachable']}/{health['expected']} runners", f"vus={health['vus']}"]
    rate = health.get("rate_per_sec")
    parts.append(f"reqs={health['requests']}" + (f" ({rate:.0f}/s)" if rate is not None else ""))
    parts.append(f"ok={health['ok']}")
    parts.append(f"err={health['failed']} ({health['failed_pct']:.1f}%)")
    if health["worst_p95_ms"] is not None:
        parts.append(f"p95={health['worst_p95_ms']:.0f}ms")
    line = f"{stamp}" + "  ".join(parts)
    if health["tainted"]:
        # "over threshold", not "crossed": this is where the metric stands at this poll, and k6
        # clears the flag if it recovers. What the run is finally judged on is threshold_warning.
        line += f"  ! over threshold: {', '.join(health['tainted'])}"
    return line


def _clock(elapsed):
    """'[mm:ss] ' for a poll's elapsed seconds, or '' when the caller is not timing anything."""
    if elapsed is None:
        return ""
    return f"[{int(elapsed) // 60:02d}:{int(elapsed) % 60:02d}] "


def threshold_warning(verdict, name, last_tainted=()):
    """The warning to close a finished run with, or None when there is nothing to warn about.

    Driven by k6's exit code (`run_verdict`), NOT by the taints seen along the way — a metric that
    went over its threshold during warm-up and recovered is a passing run, and warning about it
    would train a reader to ignore the warning that matters.

    `last_tainted` only names the metrics, since an exit code cannot. It is the last poll's
    reading, so it can be empty or stale even for a real breach; the wording never promises it is
    the whole list, and points at the k6 summary for that.

    Falls back to the last reading when no exit code could be read (pods reaped, or a wait that
    timed out), and says so — an unread verdict is not a pass.

    A warning, never a failure: the exit code stays the workflow's. It says why the phase and this
    disagree, because a `Succeeded` next to a crossed threshold reads like a contradiction until
    you know the operator does not fail a run over one.
    """
    metrics = f": {', '.join(last_tainted)}" if last_tainted else ""
    footer = (f"         The workflow phase does not reflect this — the k6-operator reports a\n"
              f"         threshold breach as a finished run. For k6's own summary, see:\n"
              f"           loadtest logs {name}")

    if verdict.get("failed_runners"):
        return (f"WARNING: k6 exited with an error on {', '.join(verdict['failed_runners'])} "
                f"(exit codes: {verdict['exit_codes']}).\n"
                f"         The run did not finish normally. See:  loadtest logs {name}")
    if verdict.get("known"):
        if not verdict["thresholds_crossed"]:
            return None
        return f"WARNING: k6 ended this run over its thresholds{metrics}\n{footer}"
    if not last_tainted:
        return None
    return (f"WARNING: k6 was over its thresholds when last read{metrics}\n"
            f"         Its final verdict could not be read — the runner pods are gone.\n{footer}")
