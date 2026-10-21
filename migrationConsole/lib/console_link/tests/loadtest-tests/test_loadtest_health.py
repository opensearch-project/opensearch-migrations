"""Tests for the live-health reader (health.py).

The numbers come from k6's own REST API on each runner pod, so these tests fake exactly that: the
two endpoints the module calls, in the shape k6 v1/v2 returns them. Nothing here goes near
Prometheus or the otel-collector, which is the point of the module.

The shapes below were taken from a real k6 2.2.0 run under the k6-operator (a `parallelism: 2`
ingest-steady run on kind-ma), so a k6 upgrade that changes them breaks these tests rather than
silently reporting zeros.
"""

from unittest.mock import patch

import pytest
import requests

from console_link.loadtest import health as health_mod
from console_link.loadtest.health import (
    HealthWatcher,
    format_health,
    run_health,
    run_verdict,
    runner_health,
    threshold_warning,
)

HEALTH = "console_link.loadtest.health"


def _status(running=True, vus=4, paused=False):
    return {"data": {"type": "status", "id": "default",
                     "attributes": {"status": 7, "paused": paused, "vus": vus, "vus-max": 10,
                                    "stopped": False, "running": running, "tainted": False}}}


def _metrics(reqs=1000, failed_rate=0.0, p95=42.5, tainted=()):
    """The subset of a real /v1/metrics payload the module reads, plus one metric it must ignore.

    Sample shapes differ by metric type on purpose: counter {count, rate}, rate {rate},
    trend {min..p(95)}. Reading the wrong key for a type is the mistake this guards.
    """
    entries = [
        ("http_reqs", {"type": "counter", "contains": "default", "tainted": None,
                       "sample": {"count": reqs, "rate": 24.9}}),
        ("http_req_failed", {"type": "rate", "contains": "default",
                             "tainted": "http_req_failed" in tainted,
                             "sample": {"rate": failed_rate}}),
        ("http_req_duration", {"type": "trend", "contains": "time", "tainted": None,
                               "sample": {"avg": 21.1, "max": 258.9, "med": 11.2, "min": 6.4,
                                          "p(90)": 43.4, "p(95)": p95}}),
        ("ingest_errors", {"type": "rate", "contains": "default",
                           "tainted": "ingest_errors" in tainted, "sample": {"rate": failed_rate}}),
        ("vus", {"type": "gauge", "contains": "default", "tainted": None, "sample": {"value": 4}}),
    ]
    return {"data": [{"type": "metrics", "id": mid, "attributes": a} for mid, a in entries]}


class _Response:
    def __init__(self, payload):
        self._payload = payload

    def json(self):
        return self._payload


def _api(status_payload=None, metrics_payload=None, fail=False):
    """A stand-in for requests.get that answers the two k6 endpoints by URL."""
    def get(url, timeout=None):
        if fail:
            raise requests.ConnectionError("connection refused")
        if url.endswith("/v1/status"):
            return _Response(status_payload if status_payload is not None else _status())
        if url.endswith("/v1/metrics"):
            return _Response(metrics_payload if metrics_payload is not None else _metrics())
        raise AssertionError(f"unexpected URL {url}")
    return get


class TestRunnerHealth:
    """One runner, read straight from its k6 API."""

    def test_reads_counts_and_state(self):
        with patch(f"{HEALTH}.requests.get", _api()):
            got = runner_health("http://10.0.0.1:6565")
        assert got["running"] is True
        assert got["vus"] == 4
        assert got["requests"] == 1000
        assert got["p95_ms"] == 42.5

    def test_error_ratio_becomes_absolute_counts(self):
        """`http_req_failed` is a ratio here (unlike the OTLP export, where it counts every
        sample), so it has to be scaled by the request counter to mean anything."""
        metrics = _metrics(reqs=1000, failed_rate=0.062)
        with patch(f"{HEALTH}.requests.get", _api(metrics_payload=metrics)):
            got = runner_health("http://10.0.0.1:6565")
        assert got["requests"] == 1000
        assert got["failed"] == 62
        assert got["ok"] == 938

    def test_ok_and_failed_always_add_up(self):
        """Rounding one runner at a time keeps its own row consistent — no row may show a total
        its two halves do not reach."""
        metrics = _metrics(reqs=333, failed_rate=1 / 3)
        with patch(f"{HEALTH}.requests.get", _api(metrics_payload=metrics)):
            got = runner_health("http://10.0.0.1:6565")
        assert got["ok"] + got["failed"] == got["requests"]

    def test_every_request_failing_reads_as_one_hundred_percent(self):
        # The case that motivated all of this: a run where every request came back 401 and the
        # workflow still ended `Succeeded`.
        metrics = _metrics(reqs=6001, failed_rate=1.0)
        with patch(f"{HEALTH}.requests.get", _api(metrics_payload=metrics)):
            got = runner_health("http://10.0.0.1:6565")
        assert (got["failed"], got["ok"]) == (6001, 0)

    def test_reports_which_thresholds_are_crossed(self):
        with patch(f"{HEALTH}.requests.get",
                   _api(metrics_payload=_metrics(tainted=("http_req_failed", "ingest_errors")))):
            got = runner_health("http://10.0.0.1:6565")
        assert got["tainted"] == ["http_req_failed", "ingest_errors"]

    def test_missing_metrics_are_not_an_error(self):
        """A runner that has not made a request yet reports no `http_reqs` at all. That is "nothing
        yet", so it must read as zero rather than blow up the poll."""
        with patch(f"{HEALTH}.requests.get", _api(metrics_payload={"data": []})):
            got = runner_health("http://10.0.0.1:6565")
        assert (got["requests"], got["failed"], got["ok"]) == (0, 0, 0)
        assert got["p95_ms"] is None

    def test_unreachable_runner_is_none(self):
        with patch(f"{HEALTH}.requests.get", _api(fail=True)):
            assert runner_health("http://10.0.0.1:6565") is None

    def test_unparseable_answer_is_none(self):
        """Anything on 6565 that is not k6 (a stale endpoint, a proxy error page) reads as
        unreachable rather than raising into the caller's poll loop."""
        class _Bad(_Response):
            def json(self):
                raise ValueError("not json")

        with patch(f"{HEALTH}.requests.get", lambda url, timeout=None: _Bad(None)):
            assert runner_health("http://10.0.0.1:6565") is None


class TestRunHealth:
    """The whole run: every runner pod, summed."""

    @staticmethod
    def _pods(*pods):
        return patch(f"{HEALTH}.list_runner_pods", return_value=list(pods))

    def test_sums_the_runners(self):
        pods = [{"pod": "run-1", "ip": "10.0.0.1", "phase": "Running", "exit_code": None},
                {"pod": "run-2", "ip": "10.0.0.2", "phase": "Running", "exit_code": None}]
        with self._pods(*pods), patch(f"{HEALTH}.requests.get",
                                      _api(metrics_payload=_metrics(reqs=1000, failed_rate=0.1))):
            got = run_health("ma", "k6-run")
        assert (got["expected"], got["reachable"]) == (2, 2)
        assert got["requests"] == 2000
        assert got["failed"] == 200
        assert got["ok"] == 1800
        assert got["failed_pct"] == pytest.approx(10.0)
        assert got["vus"] == 8

    def test_p95_is_the_worst_runner_not_an_average(self):
        """Percentiles from separate pods cannot be merged. Reporting the worst is honest;
        averaging them would invent a number that describes no pod."""
        pods = [{"pod": "run-1", "ip": "10.0.0.1", "phase": "Running", "exit_code": None},
                {"pod": "run-2", "ip": "10.0.0.2", "phase": "Running", "exit_code": None}]

        def get(url, timeout=None):
            if url.endswith("/v1/status"):
                return _Response(_status())
            return _Response(_metrics(p95=20.0 if "10.0.0.1" in url else 90.0))

        with self._pods(*pods), patch(f"{HEALTH}.requests.get", get):
            got = run_health("ma", "k6-run")
        assert got["worst_p95_ms"] == 90.0

    def test_taints_from_any_runner_count(self):
        """One runner past its threshold is the run past its threshold — the load is one test
        split into execution segments, not two independent runs."""
        pods = [{"pod": "run-1", "ip": "10.0.0.1", "phase": "Running", "exit_code": None},
                {"pod": "run-2", "ip": "10.0.0.2", "phase": "Running", "exit_code": None}]

        def get(url, timeout=None):
            if url.endswith("/v1/status"):
                return _Response(_status())
            return _Response(_metrics(tainted=("ingest_errors",) if "10.0.0.2" in url else ()))

        with self._pods(*pods), patch(f"{HEALTH}.requests.get", get):
            got = run_health("ma", "k6-run")
        assert got["tainted"] == ["ingest_errors"]

    def test_unreachable_runner_is_counted_but_not_summed(self):
        """`reachable` is the honesty of the numbers: it says how much of the run they cover, so a
        half-read run cannot be mistaken for a run doing half the work."""
        pods = [{"pod": "run-1", "ip": "10.0.0.1", "phase": "Running", "exit_code": None},
                {"pod": "run-2", "ip": None, "phase": "Pending", "exit_code": None}]
        with self._pods(*pods), patch(f"{HEALTH}.requests.get", _api()):
            got = run_health("ma", "k6-run")
        assert (got["expected"], got["reachable"]) == (2, 1)
        assert got["requests"] == 1000
        assert [r["pod"] for r in got["runners"]] == ["run-1", "run-2"]
        assert got["runners"][1]["reachable"] is False

    def test_pod_without_ip_is_never_fetched(self):
        # No address means nothing to connect to; building "http://None:6565" would spend the
        # whole timeout proving it.
        pods = [{"pod": "run-1", "ip": None, "phase": "Pending", "exit_code": None}]
        with self._pods(*pods), patch(f"{HEALTH}.requests.get") as get:
            run_health("ma", "k6-run")
        get.assert_not_called()

    def test_no_pods_yet(self):
        with self._pods():
            got = run_health("ma", "k6-run")
        assert (got["expected"], got["reachable"], got["error"]) == (0, 0, None)

    def test_cluster_error_is_reported_not_raised(self):
        """Health is an extra on top of the phase. A cluster problem here must not take down the
        command that asked for it."""
        with patch(f"{HEALTH}.list_runner_pods", side_effect=RuntimeError("Forbidden")):
            got = run_health("ma", "k6-run")
        assert got["error"] == "Forbidden"
        assert got["reachable"] == 0


class TestHealthWatcher:
    """Watching a run over time — which is the only way to see a rate, and the only way to still
    know what happened once the runner pods are gone."""

    @staticmethod
    def _watcher(*snapshots):
        """A watcher whose polls return the given run_health results in order."""
        watcher = HealthWatcher("ma", "k6-run")
        it = iter(snapshots)
        return watcher, patch(f"{HEALTH}.run_health", side_effect=lambda *a, **k: next(it))

    @staticmethod
    def _snapshot(requests_count=0, reachable=1, tainted=(), error=None):
        return {"expected": 1, "reachable": reachable, "running": True, "vus": 4,
                "requests": requests_count, "failed": 0, "ok": requests_count,
                "failed_pct": 0.0, "worst_p95_ms": 40.0, "tainted": list(tainted),
                "runners": [], "error": error}

    def test_rate_needs_two_polls(self):
        watcher, patched = self._watcher(self._snapshot(1000), self._snapshot(3000))
        with patched:
            first = watcher.poll(elapsed=10)
            second = watcher.poll(elapsed=30)
        assert first["rate_per_sec"] is None
        assert second["rate_per_sec"] == pytest.approx(100.0)

    def test_last_good_snapshot_survives_the_end_of_the_run(self):
        """The k6 API dies with the runner process, so the final polls of a wait reach nobody.
        Replacing real numbers with that emptiness would report a finished run as a dead one."""
        watcher, patched = self._watcher(self._snapshot(5000), self._snapshot(0, reachable=0))
        with patched:
            watcher.poll(elapsed=10)
            watcher.poll(elapsed=20)
        assert watcher.last["requests"] == 5000

    def test_last_reading_survives_the_end_of_the_run(self):
        """The taints of the last poll that reached a runner, kept for the same reason as the
        counts: the pods are gone by the time the wait ends."""
        watcher, patched = self._watcher(self._snapshot(10, tainted=("http_req_failed",)),
                                         self._snapshot(0, reachable=0))
        with patched:
            watcher.poll(elapsed=10)
            watcher.poll(elapsed=20)
        assert watcher.last_tainted == ["http_req_failed"]

    def test_taints_do_not_accumulate(self):
        """A taint is k6's CURRENT evaluation and clears when the metric recovers — measured on a
        run that was over `http_req_failed` at 40s, clean from 60s, and exited 0. Keeping a union
        would turn every warm-up blip into a failed run."""
        watcher, patched = self._watcher(self._snapshot(10, tainted=("ingest_errors",)),
                                         self._snapshot(20))
        with patched:
            watcher.poll(elapsed=10)
            watcher.poll(elapsed=20)
        assert watcher.last_tainted == []

    def test_nothing_seen_leaves_no_last(self):
        watcher, patched = self._watcher(self._snapshot(0, reachable=0))
        with patched:
            watcher.poll(elapsed=10)
        assert watcher.last is None


class TestFormatHealth:
    """One poll as one line. Every degraded case says what it is instead of printing zeros."""

    @staticmethod
    def _health(**overrides):
        base = {"expected": 2, "reachable": 2, "running": True, "vus": 4, "requests": 7550,
                "failed": 450, "ok": 7100, "failed_pct": 5.96, "worst_p95_ms": 44.0,
                "tainted": [], "runners": [], "error": None, "elapsed": 90,
                "rate_per_sec": 84.0}
        return {**base, **overrides}

    def test_full_line(self):
        line = format_health(self._health())
        assert line == ("[01:30] 2/2 runners  vus=4  reqs=7550 (84/s)  ok=7100  "
                        "err=450 (6.0%)  p95=44ms")

    def test_thresholds_are_called_out(self):
        # "over threshold", present tense: this is where the metric stands at this poll, and k6
        # clears the flag if it recovers.
        line = format_health(self._health(tainted=["http_req_failed", "ingest_errors"]))
        assert line.endswith("! over threshold: http_req_failed, ingest_errors")

    def test_p95_omitted_before_the_first_request(self):
        # k6 reports the duration trend as zeros until a request lands, and "p95=0ms" reads as an
        # impossibly fast run rather than one that has not started working.
        assert "p95" not in format_health(
            self._health(requests=0, ok=0, failed=0, worst_p95_ms=None))

    def test_rate_omitted_on_the_first_poll(self):
        assert "(84/s)" not in format_health(self._health(rate_per_sec=None))

    def test_no_pods_says_so(self):
        assert format_health(self._health(expected=0, reachable=0)) == \
            "[01:30] waiting for runner pods"

    def test_no_reachable_runner_says_so(self):
        assert format_health(self._health(reachable=0)) == "[01:30] 0/2 runners reporting"

    def test_error_says_so(self):
        assert format_health(self._health(error="Forbidden")) == \
            "[01:30] health unavailable: Forbidden"

    def test_no_clock_when_nothing_is_being_timed(self):
        # `loadtest health` shows one poll of a run it did not start, so there is no elapsed time
        # to print — and a "[00:00]" there would be a lie about the run's age.
        assert format_health(self._health(elapsed=None)).startswith("2/2 runners")


class TestRunVerdict:
    """k6's judgement on a FINISHED run, from the runner pods' exit codes. This is what survives
    the run — the REST API goes with the process, the pod status does not."""

    @staticmethod
    def _pods(*exit_codes):
        pods = [{"pod": f"run-{i}", "ip": None, "phase": "Succeeded", "exit_code": code}
                for i, code in enumerate(exit_codes, start=1)]
        return patch(f"{HEALTH}.list_runner_pods", return_value=pods)

    def test_zero_is_a_pass(self):
        with self._pods(0, 0):
            got = run_verdict("ma", "k6-run")
        assert got["known"] is True
        assert got["thresholds_crossed"] is False
        assert got["failed_runners"] == []

    def test_ninety_nine_is_a_threshold_breach(self):
        with self._pods(99, 99):
            got = run_verdict("ma", "k6-run")
        assert got["thresholds_crossed"] is True
        assert got["failed_runners"] == []

    def test_one_breaching_runner_is_enough(self):
        # The load is one test split into execution segments, so one segment past its limits is
        # the test past its limits.
        with self._pods(0, 99):
            assert run_verdict("ma", "k6-run")["thresholds_crossed"] is True

    def test_other_non_zero_codes_are_k6_failing_to_run(self):
        """A script error is not a threshold breach, and saying so would send a reader to tune
        limits that were never the problem."""
        with self._pods(0, 107):
            got = run_verdict("ma", "k6-run")
        assert got["thresholds_crossed"] is False
        assert got["failed_runners"] == ["run-2"]

    def test_unknown_while_the_run_is_still_going(self):
        with self._pods(None, None):
            assert run_verdict("ma", "k6-run")["known"] is False

    def test_unknown_when_the_pods_are_gone(self):
        with patch(f"{HEALTH}.list_runner_pods", return_value=[]):
            assert run_verdict("ma", "k6-run")["known"] is False

    def test_cluster_error_is_unknown_not_a_pass(self):
        with patch(f"{HEALTH}.list_runner_pods", side_effect=RuntimeError("Forbidden")):
            assert run_verdict("ma", "k6-run")["known"] is False


class TestThresholdWarning:
    """The line a wait closes on. It is driven by the exit code, so a metric that recovered does
    not get warned about."""

    PASSED = {"known": True, "thresholds_crossed": False, "failed_runners": [], "exit_codes": {}}
    CROSSED = {"known": True, "thresholds_crossed": True, "failed_runners": [], "exit_codes": {}}
    UNKNOWN = {"known": False, "thresholds_crossed": False, "failed_runners": [], "exit_codes": {}}

    def test_none_when_k6_passed(self):
        assert threshold_warning(self.PASSED, "k6-run") is None

    def test_a_recovered_metric_is_not_warned_about(self):
        """The false positive this replaced: a run that was over `http_req_failed` during warm-up,
        recovered, and exited 0. k6 passed it, so nothing is reported."""
        assert threshold_warning(self.PASSED, "k6-run", ["http_req_failed"]) is None

    def test_names_the_metrics_and_where_to_look(self):
        warning = threshold_warning(self.CROSSED, "k6-run", ["http_req_failed"])
        assert "http_req_failed" in warning
        assert "loadtest logs k6-run" in warning

    def test_warns_on_the_exit_code_even_without_metric_names(self):
        """The last poll can miss a threshold crossed in the closing seconds. The exit code cannot,
        so the warning must not depend on having a name to print."""
        warning = threshold_warning(self.CROSSED, "k6-run", [])
        assert "over its thresholds" in warning

    def test_explains_the_disagreement_with_the_phase(self):
        # A `Succeeded` phase beside a crossed threshold reads as a contradiction until the
        # operator's part is spelled out, so the text has to carry it.
        assert "k6-operator" in threshold_warning(self.CROSSED, "k6-run", ["ingest_errors"])

    def test_a_k6_error_is_reported_as_itself(self):
        verdict = {"known": True, "thresholds_crossed": False, "failed_runners": ["run-2"],
                   "exit_codes": {"run-1": 0, "run-2": 107}}
        warning = threshold_warning(verdict, "k6-run")
        assert "exited with an error" in warning
        assert "run-2" in warning

    def test_an_unread_verdict_falls_back_to_the_last_reading(self):
        warning = threshold_warning(self.UNKNOWN, "k6-run", ["http_req_failed"])
        assert "when last read" in warning
        assert "could not be read" in warning

    def test_an_unread_verdict_with_a_clean_last_reading_says_nothing(self):
        assert threshold_warning(self.UNKNOWN, "k6-run", []) is None


def test_module_targets_the_k6_api_port():
    """6565 is k6's REST API. It is not configurable on the operator's runners, so a change here
    means the module stopped reading k6 itself."""
    assert health_mod.RUNNER_API_PORT == 6565


def test_the_threshold_exit_code_is_k6s():
    """99 is k6's "thresholds crossed" exit. Every other non-zero code means it could not run the
    test at all, which is a different report."""
    assert health_mod.EXIT_THRESHOLDS_CROSSED == 99
