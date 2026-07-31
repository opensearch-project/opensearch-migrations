"""Tests for the `workflow k6` command group (loads chart-rendered example TestRuns + patches)."""

import json
import pytest
from unittest.mock import patch
from click.testing import CliRunner

from console_link.workflow.cli import workflow_cli
from console_link.workflow.commands import k6 as k6mod
from console_link.workflow.commands.k6 import (
    build_k6_parameters,
    build_testrun_spec,
    load_example,
    list_active_k6_runs,
)
from console_link.workflow.commands.testrun_utils import EXAMPLES_CONFIGMAP

K6 = "console_link.workflow.commands.k6"
ENV = {"K6_LOADTEST_ENABLED": "true", "WORKFLOW_NAMESPACE": "ma"}


def _example(scenario):
    """A minimal stand-in for what the chart renders into k6-testrun-examples (flat mount)."""
    vol = {"name": "scenarios", "configMap": {"name": "k6-scenarios"}}
    mount = {"name": "scenarios", "mountPath": "/scripts"}
    pod = {"image": "grafana/k6:1.0", "imagePullPolicy": "IfNotPresent",
           "volumeMounts": [mount], "volumes": [vol]}
    return {
        "apiVersion": "k6.io/v1alpha1", "kind": "TestRun",
        "metadata": {"generateName": "k6-run-",
                     "labels": {"app": "k6-load-test", "k6-scenario": scenario}},
        "spec": {
            "parallelism": 1,
            "script": {"localFile": f"/scripts/SCENARIO_{scenario}.js"},
            "initializer": dict(pod),
            "runner": dict(pod,
                           envFrom=[{"configMapRef": {"name": f"k6-preset-{scenario}-steady"}}],
                           env=[{"name": "K6_OUT", "value": "opentelemetry"}]),
        },
    }


def _fake_read_configmap(namespace, name):
    if name == EXAMPLES_CONFIGMAP:
        return {s: json.dumps(_example(s)) for s in ("ingest", "search", "mixed")}
    return {}


def _runner():
    return CliRunner()


@pytest.fixture(autouse=True)
def _clear_cache():
    k6mod._AVAIL_CACHE.clear()
    yield
    k6mod._AVAIL_CACHE.clear()


@pytest.fixture
def cluster(monkeypatch):
    monkeypatch.setattr(k6mod, "load_k8s_config", lambda: None)
    monkeypatch.setattr(k6mod, "read_configmap", _fake_read_configmap)
    yield


def _env_map(body):
    return {e["name"]: e["value"] for e in body["spec"]["runner"]["env"]}


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
    def test_patches_example(self, mock_create, cluster):
        result = _runner().invoke(workflow_cli, [
            "k6", "run", "--scenario", "ingest", "--target", "https://p:9200",
            "--rate", "80", "--parallelism", "3",
        ], env=ENV)
        assert result.exit_code == 0, result.output
        body = mock_create.call_args.args[1]
        spec = body["spec"]
        # example structure preserved
        assert body["kind"] == "TestRun"
        assert spec["script"]["localFile"] == "/scripts/SCENARIO_ingest.js"
        assert spec["runner"]["volumes"][0]["configMap"]["name"] == "k6-scenarios"  # flat mount kept
        assert spec["initializer"]["volumes"] == spec["runner"]["volumes"]
        # patched bits
        assert spec["parallelism"] == 3
        assert spec["runner"]["envFrom"][0]["configMapRef"]["name"] == "k6-preset-ingest-steady"
        env = _env_map(body)
        assert env["K6_OUT"] == "opentelemetry"          # from the example
        assert env["CAPTURE_PROXY_URL"] == "https://p:9200"  # override wins over envFrom
        assert env["INGEST_RATE"] == "80" and env["SEARCH_RATE"] == "80"  # --rate fans out
        assert "arguments" not in spec

    @patch(f"{K6}.create_testrun", return_value="k6-run-xy")
    def test_config_swaps_preset(self, mock_create, cluster):
        _runner().invoke(workflow_cli, ["k6", "run", "--scenario", "ingest",
                                        "--config", "ingest-burst"], env=ENV)
        body = mock_create.call_args.args[1]
        assert body["spec"]["runner"]["envFrom"][0]["configMapRef"]["name"] == "k6-preset-ingest-burst"

    @patch(f"{K6}.create_testrun", return_value="k6-run-xy")
    def test_default_config_is_scenario_steady(self, mock_create, cluster):
        _runner().invoke(workflow_cli, ["k6", "run", "--scenario", "search"], env=ENV)
        body = mock_create.call_args.args[1]
        assert body["spec"]["runner"]["envFrom"][0]["configMapRef"]["name"] == "k6-preset-search-steady"

    @patch(f"{K6}.create_testrun", return_value="k6-run-xy")
    def test_extra_args_set_arguments(self, mock_create, cluster):
        _runner().invoke(workflow_cli, ["k6", "run", "--extra-args", "--no-thresholds"], env=ENV)
        assert mock_create.call_args.args[1]["spec"]["arguments"] == "--no-thresholds"

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


class TestBuildTestrunSpec:
    def test_missing_example_raises(self, monkeypatch):
        monkeypatch.setattr(k6mod, "read_configmap", lambda ns, name: {})
        with pytest.raises(ValueError):
            load_example("ma", "ingest")

    def test_registry_and_bag_overrides(self, monkeypatch):
        monkeypatch.setattr(k6mod, "read_configmap", _fake_read_configmap)
        p = build_k6_parameters(scenario="mixed", registry_enabled=True,
                                overrides_text="INGEST_RATE=7\nFOO=bar")
        body = build_testrun_spec("ma", p)
        env = _env_map(body)
        assert env["REGISTRY_ENABLED"] == "true"
        assert env["INGEST_RATE"] == "7" and env["FOO"] == "bar"


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
        assert p["configName"] == "search-steady" and p["parallelism"] == 1

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

    def test_no_owner_references(self, monkeypatch):
        monkeypatch.setattr(k6mod, "read_configmap", _fake_read_configmap)
        body = build_testrun_spec("ma", build_k6_parameters(scenario="ingest"))
        assert "ownerReferences" not in body["metadata"]
        assert body["metadata"]["generateName"] == "k6-run-"
        assert body["kind"] == "TestRun"


class TestK6Logs:
    @patch(f"{K6}.subprocess.run")
    def test_logs_builds_kubectl_command(self, mock_run):
        _runner().invoke(workflow_cli, ["k6", "logs", "k6-run-a"], env=ENV)
        cmd = mock_run.call_args.args[0]
        assert cmd[:2] == ["kubectl", "logs"]
        assert "-c" in cmd and "k6" in cmd
        assert "k6_cr=k6-run-a,runner=true" in cmd

    @patch(f"{K6}.subprocess.run")
    def test_logs_follow(self, mock_run):
        _runner().invoke(workflow_cli, ["k6", "logs", "k6-run-a", "-f"], env=ENV)
        assert "-f" in mock_run.call_args.args[0]
