"""Tests for the `workflow k6` command group (k6-operator TestRun backend)."""

import pytest
from unittest.mock import patch
from click.testing import CliRunner

from console_link.workflow.cli import workflow_cli
from console_link.workflow.commands import k6 as k6mod
from console_link.workflow.commands.k6 import (
    build_k6_parameters,
    submit_k6_run,
    resolve_env,
    build_testrun_spec,
    _scenario_volume,
    list_active_k6_runs,
)
from console_link.workflow.commands.testrun_utils import (
    PRESETS_CONFIGMAP,
    SCENARIOS_CONFIGMAP,
    IMAGE_CONFIGMAP,
)

K6 = "console_link.workflow.commands.k6"
# Env that makes the availability guard pass without a cluster.
ENV = {"K6_LOADTEST_ENABLED": "true", "WORKFLOW_NAMESPACE": "ma"}


def _fake_read_configmap(namespace, name):
    if name == PRESETS_CONFIGMAP:
        return {
            "ingest-steady.env": "# comment\nINGEST_RATE=50\nDURATION=5m\n"
                                 "CAPTURE_PROXY_URL=https://preset:9200",
            "search-steady.env": "SEARCH_RATE=50\nDURATION=5m",
            "mixed-steady.env": "INGEST_RATE=30\nSEARCH_RATE=20",
        }
    if name == SCENARIOS_CONFIGMAP:
        return {
            "scenarios__ingest.js": "x",
            "lib__data__nyc_taxis__documents.js": "y",
            "data__nyc_taxis__mapping.json": "z",
        }
    if name == IMAGE_CONFIGMAP:
        return {"k6Image": "grafana/k6:1.0", "k6PullPolicy": "IfNotPresent"}
    return {}


def _runner():
    return CliRunner()


@pytest.fixture(autouse=True)
def _clear_availability_cache():
    k6mod._AVAIL_CACHE.clear()
    yield
    k6mod._AVAIL_CACHE.clear()


@pytest.fixture
def cluster(monkeypatch):
    """Patch the k8s-touching seams so run/list/stop work without a cluster."""
    monkeypatch.setattr(k6mod, "load_k8s_config", lambda: None)
    monkeypatch.setattr(k6mod, "read_configmap", _fake_read_configmap)
    yield


class TestAvailabilityGuard:
    def test_env_override(self, monkeypatch):
        monkeypatch.setenv("K6_LOADTEST_ENABLED", "true")
        assert k6mod.k6_available("ma") is True
        monkeypatch.setenv("K6_LOADTEST_ENABLED", "false")
        assert k6mod.k6_available("ma") is False

    def test_group_hidden_reflects_availability(self, monkeypatch):
        monkeypatch.setenv("K6_LOADTEST_ENABLED", "false")
        assert k6mod.k6_group.hidden is True
        monkeypatch.setenv("K6_LOADTEST_ENABLED", "true")
        assert k6mod.k6_group.hidden is False

    def test_probe_used_when_env_unset(self, monkeypatch):
        monkeypatch.delenv("K6_LOADTEST_ENABLED", raising=False)
        monkeypatch.setattr(k6mod, "load_k8s_config", lambda: None)
        monkeypatch.setattr(k6mod, "loadtest_installed", lambda ns: True)
        assert k6mod.k6_available("ma") is True
        monkeypatch.setattr(k6mod, "loadtest_installed", lambda ns: False)
        k6mod._AVAIL_CACHE.clear()
        assert k6mod.k6_available("ma") is False

    def test_commands_inert_when_unavailable(self):
        env = {"K6_LOADTEST_ENABLED": "false", "WORKFLOW_NAMESPACE": "ma"}
        for args in (["k6", "run"], ["k6", "list"], ["k6", "stop", "--all"]):
            result = _runner().invoke(workflow_cli, args, env=env)
            assert result.exit_code == 1
            assert "not installed" in result.output


class TestK6Help:
    def test_help_renders(self):
        for args in (["k6", "-h"], ["k6", "run", "-h"], ["k6", "list", "-h"],
                     ["k6", "stop", "-h"], ["k6", "logs", "-h"]):
            result = _runner().invoke(workflow_cli, args, env=ENV)
            assert result.exit_code == 0
            assert "Usage:" in result.output


class TestK6Run:
    @patch(f"{K6}.create_testrun", return_value="k6-run-xy")
    def test_builds_testrun_body(self, mock_create, cluster):
        result = _runner().invoke(workflow_cli, [
            "k6", "run", "--scenario", "ingest", "--target", "https://p:9200",
            "--rate", "80", "--parallelism", "3",
        ], env=ENV)
        assert result.exit_code == 0, result.output
        body = mock_create.call_args.args[1]
        assert body["kind"] == "TestRun"
        assert body["metadata"]["generateName"] == "k6-run-"
        assert body["metadata"]["labels"] == {"app": "k6-load-test", "k6-scenario": "ingest"}
        spec = body["spec"]
        assert spec["parallelism"] == 3
        assert spec["script"]["localFile"] == "/scripts/scenarios/ingest.js"
        # default has no arguments (no run-only flags leak into `k6 archive`)
        assert "arguments" not in spec
        env = {e["name"]: e["value"] for e in spec["runner"]["env"]}
        assert env["CAPTURE_PROXY_URL"] == "https://p:9200"   # dedicated override beats preset
        assert env["INGEST_RATE"] == "80"                     # named override
        assert env["K6_OUT"] == "opentelemetry"               # metrics via env, not --out
        # items projection decodes __ -> / on both runner and initializer
        items = spec["runner"]["volumes"][0]["configMap"]["items"]
        assert {"key": "lib__data__nyc_taxis__documents.js",
                "path": "lib/data/nyc_taxis/documents.js"} in items
        assert spec["initializer"]["volumes"] == spec["runner"]["volumes"]

    @patch(f"{K6}.create_testrun", return_value="k6-run-xy")
    def test_extra_args_set_arguments(self, mock_create, cluster):
        _runner().invoke(workflow_cli, ["k6", "run", "--extra-args", "--no-thresholds"], env=ENV)
        assert mock_create.call_args.args[1]["spec"]["arguments"] == "--no-thresholds"

    @patch(f"{K6}.create_testrun", return_value="k6-run-xy")
    def test_default_config_is_scenario_steady(self, mock_create, cluster):
        result = _runner().invoke(workflow_cli, ["k6", "run", "--scenario", "search"], env=ENV)
        assert result.exit_code == 0, result.output
        # search-steady preset was resolved (SEARCH_RATE present)
        env = {e["name"]: e["value"] for e in mock_create.call_args.args[1]["spec"]["runner"]["env"]}
        assert env["SEARCH_RATE"] == "50"

    @patch(f"{K6}.create_testrun")
    def test_bad_override_rejected(self, mock_create, cluster):
        result = _runner().invoke(workflow_cli, ["k6", "run", "-o", "NOEQUALS"], env=ENV)
        assert result.exit_code == 2
        mock_create.assert_not_called()

    @patch(f"{K6}.create_testrun", side_effect=RuntimeError("boom"))
    def test_submit_failure_exits_nonzero(self, _create, cluster):
        result = _runner().invoke(workflow_cli, ["k6", "run"], env=ENV)
        assert result.exit_code == 1
        assert "Error submitting k6 run" in result.output


class TestResolveEnv:
    def test_precedence(self, monkeypatch):
        monkeypatch.setattr(k6mod, "read_configmap", _fake_read_configmap)
        env = resolve_env("ma", "ingest", "ingest-steady", target_url="https://x:9200",
                          rate="99", duration="1m", overrides_text="INGEST_RATE=7\nFOO=bar")
        assert env["CAPTURE_PROXY_URL"] == "https://x:9200"   # dedicated beats preset
        assert env["DURATION"] == "1m"                        # named beats preset (5m)
        assert env["INGEST_RATE"] == "7"                      # bag beats named (99)
        assert env["FOO"] == "bar"

    def test_unknown_preset_raises(self, monkeypatch):
        monkeypatch.setattr(k6mod, "read_configmap", _fake_read_configmap)
        with pytest.raises(ValueError):
            resolve_env("ma", "ingest", "does-not-exist")


class TestScenarioVolume:
    def test_items_decode_paths(self, monkeypatch):
        monkeypatch.setattr(k6mod, "read_configmap", _fake_read_configmap)
        volume, mount = _scenario_volume("ma")
        assert mount == {"name": "scenarios", "mountPath": "/scripts"}
        paths = {i["path"] for i in volume["configMap"]["items"]}
        assert "scenarios/ingest.js" in paths
        assert "data/nyc_taxis/mapping.json" in paths

    def test_missing_configmap_raises(self, monkeypatch):
        monkeypatch.setattr(k6mod, "read_configmap", lambda ns, name: {})
        with pytest.raises(ValueError):
            _scenario_volume("ma")


FAKE_TESTRUNS = [
    {"metadata": {"name": "k6-run-a", "labels": {"k6-scenario": "ingest"},
                  "creationTimestamp": "2026-07-27T00:00:00Z"},
     "spec": {"parallelism": 2}, "status": {"stage": "started"}},
    {"metadata": {"name": "k6-run-b", "labels": {"k6-scenario": "mixed"},
                  "creationTimestamp": "2026-07-27T00:00:00Z"},
     "spec": {"parallelism": 1}, "status": {"stage": "finished"}},
]


class TestK6List:
    @patch(f"{K6}.load_k8s_config")
    @patch(f"{K6}.list_testruns", return_value=FAKE_TESTRUNS)
    def test_list_selector_and_rows(self, mock_list, _cfg):
        result = _runner().invoke(workflow_cli, ["k6", "list"], env=ENV)
        assert result.exit_code == 0
        assert mock_list.call_args.kwargs["label_selector"] == "app=k6-load-test"
        assert "k6-run-a" in result.output and "started" in result.output

    @patch(f"{K6}.load_k8s_config")
    @patch(f"{K6}.list_testruns", return_value=FAKE_TESTRUNS)
    def test_list_scenario_filter(self, mock_list, _cfg):
        _runner().invoke(workflow_cli, ["k6", "list", "--scenario", "mixed"], env=ENV)
        assert mock_list.call_args.kwargs["label_selector"] == "app=k6-load-test,k6-scenario=mixed"

    @patch(f"{K6}.load_k8s_config")
    @patch(f"{K6}.list_testruns", return_value=[])
    def test_list_empty(self, _list, _cfg):
        result = _runner().invoke(workflow_cli, ["k6", "list"], env=ENV)
        assert "No k6 runs found." in result.output


class TestK6Stop:
    @patch(f"{K6}.load_k8s_config")
    @patch(f"{K6}.delete_testrun", return_value=True)
    def test_stop_by_name_deletes(self, mock_delete, _cfg):
        result = _runner().invoke(workflow_cli, ["k6", "stop", "k6-run-a"], env=ENV)
        assert result.exit_code == 0
        mock_delete.assert_called_once_with("ma", "k6-run-a")

    @patch(f"{K6}.load_k8s_config")
    @patch(f"{K6}.list_testruns", return_value=[FAKE_TESTRUNS[1]])
    @patch(f"{K6}.delete_testrun", return_value=True)
    def test_stop_scenario(self, mock_delete, mock_list, _cfg):
        result = _runner().invoke(workflow_cli, ["k6", "stop", "--scenario", "mixed"], env=ENV)
        assert result.exit_code == 0
        assert mock_list.call_args.kwargs["label_selector"] == "app=k6-load-test,k6-scenario=mixed"
        mock_delete.assert_called_once_with("ma", "k6-run-b")

    def test_stop_conflicting_selectors(self):
        result = _runner().invoke(workflow_cli, ["k6", "stop", "somename", "--all"], env=ENV)
        assert result.exit_code == 2

    @patch(f"{K6}.load_k8s_config")
    @patch(f"{K6}.list_testruns", return_value=[])
    def test_stop_no_match(self, _list, _cfg):
        result = _runner().invoke(workflow_cli, ["k6", "stop", "--all"], env=ENV)
        assert "No matching k6 runs." in result.output


class TestBuildParams:
    def test_defaults_include_parallelism(self):
        p = build_k6_parameters(scenario="search")
        assert p["scenario"] == "search"
        assert p["configName"] == "search-steady"
        assert p["parallelism"] == 1

    def test_full_mapping(self):
        p = build_k6_parameters(
            scenario="mixed", config_name="mixed-steady", parallelism=4, target_url="https://p:9200",
            rate="80", duration="10m", vus="20", registry_enabled=True, control_enabled=False,
            overrides_text="INGEST_RATE=80", extra_args="--no-thresholds")
        assert p["parallelism"] == 4
        assert p["targetUrl"] == "https://p:9200"
        assert p["registryEnabled"] is True and p["controlEnabled"] is False
        assert p["extraArgs"] == "--no-thresholds"

    def test_bad_override_raises(self):
        with pytest.raises(ValueError):
            build_k6_parameters(scenario="ingest", overrides_text="NOEQUALS")


class TestListActiveRuns:
    @patch(f"{K6}.list_testruns", return_value=FAKE_TESTRUNS)
    def test_excludes_terminal_stages(self, _list):
        active = list_active_k6_runs("ma")
        assert [r["name"] for r in active] == ["k6-run-a"]
        assert active[0]["scenario"] == "ingest" and active[0]["phase"] == "started"


class TestIsolation:
    """A k6 run must be a standalone TestRun so it can't fail a migration workflow."""

    def test_submitted_testrun_has_no_owner_references(self, monkeypatch):
        monkeypatch.setattr(k6mod, "read_configmap", _fake_read_configmap)
        captured = {}
        monkeypatch.setattr(k6mod, "create_testrun",
                            lambda ns, body: captured.update(body) or "k6-run-x")
        submit_k6_run("ma", build_k6_parameters(scenario="ingest"))
        assert "ownerReferences" not in captured["metadata"]        # no owner cascade
        assert captured["metadata"]["generateName"] == "k6-run-"    # its own name
        assert captured["kind"] == "TestRun"


class TestK6Logs:
    @patch(f"{K6}.subprocess.run")
    def test_logs_builds_kubectl_command(self, mock_run):
        _runner().invoke(workflow_cli, ["k6", "logs", "k6-run-a"], env=ENV)
        cmd = mock_run.call_args.args[0]
        assert cmd[:2] == ["kubectl", "logs"]
        assert "-c" in cmd and "k6" in cmd
        assert "k6_cr=k6-run-a,runner=true" in cmd
        assert "-f" not in cmd

    @patch(f"{K6}.subprocess.run")
    def test_logs_follow(self, mock_run):
        _runner().invoke(workflow_cli, ["k6", "logs", "k6-run-a", "-f"], env=ENV)
        assert "-f" in mock_run.call_args.args[0]
