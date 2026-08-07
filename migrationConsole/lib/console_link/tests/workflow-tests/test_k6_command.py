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
        result = _runner().invoke(workflow_cli, ["k6", "run", "-e", "NOEQUALS"], env=ENV)
        assert result.exit_code == 2
        mock_create.assert_not_called()

    @patch(f"{K6}.create_testrun", side_effect=RuntimeError("boom"))
    def test_submit_failure_exits_nonzero(self, _create, cluster):
        result = _runner().invoke(workflow_cli, ["k6", "run"], env=ENV)
        assert result.exit_code == 1
        assert "Error submitting k6 run" in result.output

    @patch(f"{K6}.create_testrun", return_value="k6-run-xy")
    def test_unknown_preset_warns_but_runs(self, mock_create, monkeypatch):
        monkeypatch.setattr(k6mod, "load_k8s_config", lambda: None)
        monkeypatch.setattr(k6mod, "read_configmap", _fake_read_configmap)
        monkeypatch.setattr(k6mod, "list_presets",
                            lambda ns: ["ingest-steady", "search-steady", "mixed-steady"])
        result = _runner().invoke(workflow_cli, [
            "k6", "run", "--scenario", "ingest", "--config", "ingest-burst"], env=ENV)
        assert result.exit_code == 0, result.output
        assert "not found in the cluster" in result.output  # warned
        mock_create.assert_called_once()                    # but ran anyway

    @patch(f"{K6}.create_testrun", return_value="k6-run-xy")
    def test_known_preset_no_warning(self, mock_create, monkeypatch):
        monkeypatch.setattr(k6mod, "load_k8s_config", lambda: None)
        monkeypatch.setattr(k6mod, "read_configmap", _fake_read_configmap)
        monkeypatch.setattr(k6mod, "list_presets", lambda ns: ["ingest-steady", "ingest-burst"])
        result = _runner().invoke(workflow_cli, [
            "k6", "run", "--scenario", "ingest", "--config", "ingest-burst"], env=ENV)
        assert result.exit_code == 0, result.output
        assert "not found in the cluster" not in result.output

    @patch(f"{K6}.create_testrun", return_value="k6-run-xy")
    def test_custom_scenario_accepted(self, mock_create, monkeypatch):
        # --scenario is no longer a fixed Choice: any scenario present in the cluster is launchable.
        def fake(ns, name):
            return {"my-custom": json.dumps(_example("my-custom"))} if name == EXAMPLES_CONFIGMAP else {}
        monkeypatch.setattr(k6mod, "load_k8s_config", lambda: None)
        monkeypatch.setattr(k6mod, "read_configmap", fake)
        result = _runner().invoke(workflow_cli, ["k6", "run", "--scenario", "my-custom"], env=ENV)
        assert result.exit_code == 0, result.output
        body = mock_create.call_args.args[1]
        assert body["metadata"]["labels"]["k6-scenario"] == "my-custom"
        assert body["spec"]["script"]["localFile"] == "/scripts/SCENARIO_my-custom.js"


class TestK6RunWait:
    """`--wait` polls the TestRun to a terminal stage and maps it onto the exit code, so a load
    test wired into a script fails the script when the run itself failed."""

    @staticmethod
    def _stages(monkeypatch, *stages):
        """Feed get_testrun a canned sequence of stages, one per poll."""
        it = iter(stages)
        monkeypatch.setattr(k6mod, "get_testrun", lambda ns, name: {"status": {"stage": next(it)}})

    @patch(f"{K6}.create_testrun", return_value="k6-run-xy")
    def test_wait_succeeds_when_run_finishes(self, _create, cluster, monkeypatch):
        self._stages(monkeypatch, "finished")
        result = _runner().invoke(workflow_cli, ["k6", "run", "--wait"], env=ENV)
        assert result.exit_code == 0, result.output
        assert "finished" in result.output

    @patch(f"{K6}.create_testrun", return_value="k6-run-xy")
    def test_wait_polls_until_terminal(self, _create, cluster, monkeypatch):
        # A run that is still going keeps the poll loop alive; --wait-interval 0 keeps it instant.
        self._stages(monkeypatch, "started", "started", "finished")
        result = _runner().invoke(
            workflow_cli, ["k6", "run", "--wait", "--wait-interval", "0"], env=ENV)
        assert result.exit_code == 0, result.output
        assert "Run k6-run-xy finished: finished" in result.output

    @patch(f"{K6}.create_testrun", return_value="k6-run-xy")
    def test_wait_exits_nonzero_when_run_errors(self, _create, cluster, monkeypatch):
        # "error" is terminal, so waiting ends promptly — but it is not success.
        self._stages(monkeypatch, "error")
        result = _runner().invoke(workflow_cli, ["k6", "run", "--wait"], env=ENV)
        assert result.exit_code == 1
        assert "finished: error" in result.output

    @patch(f"{K6}.create_testrun", return_value="k6-run-xy")
    def test_wait_exits_nonzero_when_stopped(self, _create, cluster, monkeypatch):
        self._stages(monkeypatch, "stopped")
        result = _runner().invoke(workflow_cli, ["k6", "run", "--wait"], env=ENV)
        assert result.exit_code == 1

    @patch(f"{K6}.create_testrun", return_value="k6-run-xy")
    def test_wait_times_out(self, _create, cluster, monkeypatch):
        # --timeout 0 puts the deadline in the past, so the loop gives up without polling.
        monkeypatch.setattr(k6mod, "get_testrun", lambda ns, name: {"status": {"stage": "started"}})
        result = _runner().invoke(
            workflow_cli, ["k6", "run", "--wait", "--timeout", "0"], env=ENV)
        assert result.exit_code == 1
        assert "Timed out after 0s waiting for k6-run-xy" in result.output

    @patch(f"{K6}.create_testrun", return_value="k6-run-xy")
    def test_missing_testrun_keeps_waiting_until_timeout(self, _create, cluster, monkeypatch):
        # get_testrun returns None for a run the API doesn't know about yet; that must not be
        # mistaken for a terminal stage.
        monkeypatch.setattr(k6mod, "get_testrun", lambda ns, name: None)
        result = _runner().invoke(
            workflow_cli, ["k6", "run", "--wait", "--timeout", "0"], env=ENV)
        assert result.exit_code == 1
        assert "Timed out" in result.output

    @patch(f"{K6}.create_testrun", return_value="k6-run-xy")
    def test_no_wait_returns_immediately(self, _create, cluster, monkeypatch):
        # Without --wait the command must not poll at all.
        polled = []
        monkeypatch.setattr(k6mod, "get_testrun", lambda ns, name: polled.append(name))
        result = _runner().invoke(workflow_cli, ["k6", "run"], env=ENV)
        assert result.exit_code == 0, result.output
        assert polled == []


class TestBuildTestrunSpec:
    def test_missing_example_raises(self, monkeypatch):
        monkeypatch.setattr(k6mod, "read_configmap", lambda ns, name: {})
        with pytest.raises(ValueError):
            load_example("ma", "ingest")

    def test_missing_example_lists_available(self, monkeypatch):
        monkeypatch.setattr(k6mod, "read_configmap", _fake_read_configmap)
        with pytest.raises(ValueError) as e:
            load_example("ma", "nope")
        msg = str(e.value)
        assert "available:" in msg and "ingest" in msg and "mixed" in msg


class TestCompletion:
    def test_scenario_completion_from_cluster(self, monkeypatch):
        monkeypatch.setattr(k6mod, "load_k8s_config", lambda: None)
        monkeypatch.setattr(k6mod, "get_current_namespace", lambda: "ma")
        monkeypatch.setattr(k6mod, "list_scenarios", lambda ns: ["ingest", "mixed", "search"])
        assert k6mod._complete_scenarios(None, None, "mi") == ["mixed"]

    def test_preset_completion_from_cluster(self, monkeypatch):
        monkeypatch.setattr(k6mod, "load_k8s_config", lambda: None)
        monkeypatch.setattr(k6mod, "get_current_namespace", lambda: "ma")
        monkeypatch.setattr(k6mod, "list_presets", lambda ns: ["custom-a", "custom-b"])
        assert k6mod._complete_presets(None, None, "custom-") == ["custom-a", "custom-b"]

    def test_completion_falls_back_when_offline(self, monkeypatch):
        def boom():
            raise RuntimeError("no kubeconfig")
        monkeypatch.setattr(k6mod, "load_k8s_config", boom)
        # falls back to the static hint lists rather than raising during shell completion
        assert "ingest-steady" in k6mod._complete_presets(None, None, "ingest-")
        assert k6mod._complete_scenarios(None, None, "sea") == ["search"]

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
