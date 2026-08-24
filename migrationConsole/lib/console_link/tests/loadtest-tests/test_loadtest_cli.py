"""Tests for the standalone `loadtest` CLI (submits a Workflow against the chart's per-scenario
WorkflowTemplate, overriding only what the run asks for).

Two patch targets, matching the two modules: cluster logic lives in `runs`, and the Click layer in
`cli` binds its helpers by name at import, so anything the command calls directly is patched on
`cli` and anything reached through runs.py is patched on `runs`.
"""

import json
import re
from pathlib import Path

import pytest
from unittest.mock import patch
from click.testing import CliRunner

from console_link.loadtest import runs as runs_mod
from console_link.loadtest import cli as cli_mod
from console_link.loadtest.cli import loadtest_cli
from console_link.loadtest.runs import (
    build_k6_parameters,
    build_workflow_submission,
    load_template_defaults,
    list_active_k6_runs,
)

RUNS = "console_link.loadtest.runs"
CLI = "console_link.loadtest.cli"
ENV = {"LOADTEST_NAMESPACE": "ma"}


def _template(scenario):
    """A minimal stand-in for the WorkflowTemplate the chart renders per scenario: the run's static
    values live in its parameter defaults, so the client overrides only what a run changes."""
    env = [{"name": "K6_PRESET", "value": f"{scenario}-steady"},
           {"name": "K6_OUT", "value": "opentelemetry"}]
    return {
        "apiVersion": "argoproj.io/v1alpha1", "kind": "WorkflowTemplate",
        "metadata": {"name": f"k6-{scenario}",
                     "labels": {"app": "k6-load-test", "k6-scenario": scenario}},
        "spec": {
            "entrypoint": "run",
            "arguments": {"parameters": [
                {"name": "parallelism", "value": "1"},
                {"name": "separate", "value": "false"},
                {"name": "arguments", "value": ""},
                {"name": "runnerImage", "value": "grafana/k6:2.2.0"},
                {"name": "scriptsRef", "value": "migrations/k6_scripts:latest"},
                {"name": "runnerEnv", "value": json.dumps(env)},
            ]},
        },
    }


def _fake_get_workflow_template(namespace, name):
    scenario = name[len("k6-"):] if name.startswith("k6-") else None
    return _template(scenario) if scenario in ("ingest", "search", "mixed") else None


def _runner():
    return CliRunner()


@pytest.fixture(autouse=True)
def chart_installed(monkeypatch):
    """The chart is present unless a test says otherwise. This is what the dead-end paths ask about,
    so leaving it unpatched would make every "nothing found" assertion depend on a real cluster."""
    monkeypatch.setattr(runs_mod, "list_k6_workflow_templates",
                        lambda ns: [_template("ingest")])
    yield


@pytest.fixture
def no_chart(monkeypatch):
    monkeypatch.setattr(runs_mod, "list_k6_workflow_templates", lambda ns: [])
    yield


@pytest.fixture
def cluster(monkeypatch):
    monkeypatch.setattr(cli_mod, "load_k8s_config", lambda: None)
    monkeypatch.setattr(runs_mod, "get_workflow_template", _fake_get_workflow_template)
    yield


def _submitted_parameters(body):
    return {p["name"]: p["value"]
            for p in body["spec"]["arguments"]["parameters"]}


def _env_map(body):
    """The runner env the submission carries, as name → value."""
    return {e["name"]: e["value"]
            for e in json.loads(_submitted_parameters(body)["runnerEnv"])}


class TestChartMissingDiagnosis:
    """Nothing probes before doing its work. A command only asks whether the chart is installed
    once it has reached a dead end, and a failure to answer surfaces as itself — the old pre-flight
    probe turned every cluster problem into the single wrong answer "not installed"."""

    @patch(f"{CLI}.load_k8s_config")
    @patch(f"{RUNS}.list_workflows", return_value=[])
    def test_list_explains_the_empty_result(self, _list, _cfg, no_chart):
        result = _runner().invoke(loadtest_cli, ["list"], env=ENV)
        assert result.exit_code == 1
        assert "No k6 runs found." in result.output
        assert "not installed in namespace 'ma'" in result.output
        assert "k6LoadTest" in result.output

    @patch(f"{CLI}.load_k8s_config")
    @patch(f"{RUNS}.list_workflows", return_value=[])
    def test_stop_explains_the_empty_result(self, _list, _cfg, no_chart):
        result = _runner().invoke(loadtest_cli, ["stop", "--all"], env=ENV)
        assert result.exit_code == 1
        assert "No matching k6 runs." in result.output
        assert "not installed" in result.output

    @patch(f"{CLI}.load_k8s_config")
    @patch(f"{RUNS}.list_workflows", return_value=[])
    def test_empty_result_with_chart_installed_is_not_an_error(self, _list, _cfg):
        result = _runner().invoke(loadtest_cli, ["list"], env=ENV)
        assert result.exit_code == 0
        assert "No k6 runs found." in result.output
        assert "not installed" not in result.output

    @patch(f"{CLI}.load_k8s_config")
    @patch(f"{RUNS}.list_workflows", return_value=[])
    def test_probe_failure_is_not_reported_as_not_installed(self, _list, _cfg, monkeypatch):
        """The whole reason the pre-flight gate went away: a cluster error must not be translated
        into "install the chart", which sent people to reinstall a chart that was already there."""
        def boom(ns):
            raise RuntimeError("Forbidden: cannot list workflowtemplates")
        monkeypatch.setattr(runs_mod, "list_k6_workflow_templates", boom)
        result = _runner().invoke(loadtest_cli, ["list"], env=ENV)
        assert result.exit_code != 0
        assert "not installed" not in result.output
        assert "Forbidden" in str(result.output) + str(result.exception)

    @patch(f"{CLI}.load_k8s_config")
    def test_status_reports_installed_and_scenarios(self, _cfg, monkeypatch):
        monkeypatch.setattr(cli_mod, "list_scenarios", lambda ns: ["ingest", "mixed"])
        result = _runner().invoke(loadtest_cli, ["status"], env=ENV)
        assert result.exit_code == 0
        assert "installed in namespace 'ma'" in result.output
        assert "ingest, mixed" in result.output

    @patch(f"{CLI}.load_k8s_config")
    def test_status_reports_missing_chart(self, _cfg, no_chart):
        result = _runner().invoke(loadtest_cli, ["status"], env=ENV)
        assert result.exit_code == 1
        assert "not installed" in result.output


class TestLoadTestTui:
    """Bare `loadtest` opens the TUI; every subcommand keeps its own behavior."""

    @patch(f"{CLI}.load_k8s_config")
    @patch("console_link.loadtest.tui.app.LoadTestApp")
    def test_bare_command_opens_the_tui(self, mock_app, _cfg):
        result = _runner().invoke(loadtest_cli, [], env=ENV)
        assert result.exit_code == 0
        assert mock_app.call_args.args[0] == "ma"
        mock_app.return_value.run.assert_called_once()

    @patch(f"{CLI}.load_k8s_config")
    @patch("console_link.loadtest.tui.app.LoadTestApp")
    def test_tui_failure_exits_nonzero(self, mock_app, _cfg):
        mock_app.return_value.run.side_effect = RuntimeError("no terminal")
        result = _runner().invoke(loadtest_cli, [], env=ENV)
        assert result.exit_code == 1
        assert "no terminal" in result.output

    @patch(f"{CLI}.load_k8s_config")
    @patch("console_link.loadtest.tui.app.LoadTestApp")
    def test_tui_does_not_open_without_the_chart(self, mock_app, _cfg, no_chart):
        """A load-test TUI with no chart has no scenarios to launch and no runs to show, so say
        why and stop rather than opening a screen that can do nothing."""
        result = _runner().invoke(loadtest_cli, [], env=ENV)
        assert result.exit_code == 1
        assert "not installed" in result.output
        mock_app.assert_not_called()

    @patch(f"{CLI}.load_k8s_config")
    @patch("console_link.loadtest.tui.app.LoadTestApp")
    @patch(f"{RUNS}.list_workflows", return_value=[])
    def test_subcommand_does_not_open_the_tui(self, _list, mock_app, _cfg):
        _runner().invoke(loadtest_cli, ["list"], env=ENV)
        mock_app.assert_not_called()


class TestLoadTestHelp:
    def test_help_renders(self):
        for args in (["-h"], ["run", "-h"], ["list", "-h"], ["stop", "-h"], ["logs", "-h"],
                     ["status", "-h"]):
            result = _runner().invoke(loadtest_cli, args, env=ENV)
            assert result.exit_code == 0
            assert "Usage:" in result.output

    def test_help_always_lists_the_subcommands(self, no_chart):
        """Help lists every subcommand whether or not the chart is installed."""
        result = _runner().invoke(loadtest_cli, ["-h"], env=ENV)
        for name in ("run", "list", "stop", "logs", "status"):
            assert name in result.output


class TestLoadTestRun:
    @patch(f"{RUNS}.create_workflow", return_value="k6-ingest-xy")
    def test_submits_against_the_template(self, mock_create, cluster):
        result = _runner().invoke(loadtest_cli, [
            "run", "--scenario", "ingest", "--target", "https://p:9200",
            "--rate", "80", "--parallelism", "3",
        ], env=ENV)
        assert result.exit_code == 0, result.output
        body = mock_create.call_args.args[1]
        # A submission is a Workflow naming the scenario's template — the run spec itself (images,
        # script path, the scripts mount on both pods) stays in the template.
        assert body["kind"] == "Workflow"
        assert body["spec"]["workflowTemplateRef"]["name"] == "k6-ingest"
        assert body["metadata"]["generateName"] == "k6-ingest-"
        assert body["metadata"]["labels"] == {"app": "k6-load-test", "k6-scenario": "ingest"}
        params = _submitted_parameters(body)
        assert params["parallelism"] == "3"
        env = _env_map(body)
        assert env["K6_PRESET"] == "ingest-steady"
        assert env["K6_OUT"] == "opentelemetry"          # carried over from the template default
        assert env["CAPTURE_PROXY_URL"] == "https://p:9200"  # override wins over the preset
        assert env["INGEST_RATE"] == "80" and env["SEARCH_RATE"] == "80"  # --rate fans out
        assert "arguments" not in params

    @patch(f"{RUNS}.create_workflow", return_value="k6-ingest-xy")
    def test_only_overridden_parameters_are_submitted(self, mock_create, cluster):
        """Anything the run does not change stays with the template, so its defaults remain the one
        definition — a submission never restates the images or the pull policies."""
        _runner().invoke(loadtest_cli, ["run", "--scenario", "ingest"], env=ENV)
        params = _submitted_parameters(mock_create.call_args.args[1])
        assert set(params) == {"runnerEnv", "parallelism"}

    @patch(f"{RUNS}.create_workflow", return_value="k6-ingest-xy")
    def test_config_swaps_preset(self, mock_create, cluster):
        _runner().invoke(loadtest_cli, ["run", "--scenario", "ingest",
                                        "--config", "ingest-burst"], env=ENV)
        env = json.loads(_submitted_parameters(mock_create.call_args.args[1])["runnerEnv"])
        # replaced, not appended: exactly one K6_PRESET entry, carrying the chosen preset
        presets = [e for e in env if e["name"] == "K6_PRESET"]
        assert presets == [{"name": "K6_PRESET", "value": "ingest-burst"}]

    @patch(f"{RUNS}.create_workflow", return_value="k6-ingest-xy")
    def test_default_config_is_scenario_steady(self, mock_create, cluster):
        _runner().invoke(loadtest_cli, ["run", "--scenario", "search"], env=ENV)
        body = mock_create.call_args.args[1]
        assert _env_map(body)["K6_PRESET"] == "search-steady"

    @patch(f"{RUNS}.create_workflow", return_value="k6-ingest-xy")
    def test_override_replaces_template_env(self, mock_create, cluster):
        """A -e override of a var the template already sets must replace it, not duplicate it —
        two entries of the same name would leave the winner to the container runtime."""
        _runner().invoke(loadtest_cli, ["run", "-e", "K6_OUT=json"], env=ENV)
        env = json.loads(_submitted_parameters(mock_create.call_args.args[1])["runnerEnv"])
        assert [e for e in env if e["name"] == "K6_OUT"] == [{"name": "K6_OUT", "value": "json"}]

    @patch(f"{RUNS}.create_workflow", return_value="k6-ingest-xy")
    def test_extra_args_set_arguments(self, mock_create, cluster):
        _runner().invoke(loadtest_cli, ["run", "--extra-args", "--no-thresholds"], env=ENV)
        params = _submitted_parameters(mock_create.call_args.args[1])
        assert params["arguments"] == "--no-thresholds"

    @patch(f"{RUNS}.create_workflow")
    def test_bad_override_rejected(self, mock_create, cluster):
        result = _runner().invoke(loadtest_cli, ["run", "-e", "NOEQUALS"], env=ENV)
        assert result.exit_code == 2
        mock_create.assert_not_called()

    @patch(f"{RUNS}.create_workflow", side_effect=RuntimeError("boom"))
    def test_submit_failure_exits_nonzero(self, _create, cluster):
        result = _runner().invoke(loadtest_cli, ["run"], env=ENV)
        assert result.exit_code == 1
        assert "Error submitting k6 run" in result.output

    @patch(f"{RUNS}.create_workflow")
    def test_missing_template_names_the_chart(self, mock_create, monkeypatch):
        """No probe runs first: the real call fails, and its own message carries the diagnosis. A
        missing chart is an unmet precondition, so this is a failure (1), not bad input (2)."""
        monkeypatch.setattr(cli_mod, "load_k8s_config", lambda: None)
        monkeypatch.setattr(runs_mod, "get_workflow_template", lambda ns, name: None)
        monkeypatch.setattr(runs_mod, "list_scenarios", lambda ns: [])
        result = _runner().invoke(loadtest_cli, ["run", "--scenario", "ingest"], env=ENV)
        assert result.exit_code == 1
        assert "no WorkflowTemplate 'k6-ingest'" in result.output
        assert "k6LoadTest" in result.output
        mock_create.assert_not_called()

    @patch(f"{RUNS}.create_workflow", return_value="k6-ingest-xy")
    def test_unknown_preset_warns_but_runs(self, mock_create, cluster):
        # A custom runner image may ship its own presets, so an unrecognised name only warns.
        result = _runner().invoke(loadtest_cli, [
            "run", "--scenario", "ingest", "--config", "home-brewed"], env=ENV)
        assert result.exit_code == 0, result.output
        assert "not one of the stock presets" in result.output  # warned
        mock_create.assert_called_once()                        # but ran anyway
        assert _env_map(mock_create.call_args.args[1])["K6_PRESET"] == "home-brewed"

    @patch(f"{RUNS}.create_workflow", return_value="k6-ingest-xy")
    def test_known_preset_no_warning(self, mock_create, cluster):
        result = _runner().invoke(loadtest_cli, [
            "run", "--scenario", "ingest", "--config", "ingest-burst"], env=ENV)
        assert result.exit_code == 0, result.output
        assert "not one of the stock presets" not in result.output

    @patch(f"{RUNS}.create_workflow", return_value="k6-custom-xy")
    def test_custom_scenario_accepted(self, mock_create, monkeypatch):
        # --scenario is no longer a fixed Choice: any scenario present in the cluster is launchable.
        monkeypatch.setattr(cli_mod, "load_k8s_config", lambda: None)
        monkeypatch.setattr(runs_mod, "get_workflow_template",
                            lambda ns, name: _template("my-custom") if name == "k6-my-custom" else None)
        result = _runner().invoke(loadtest_cli, ["run", "--scenario", "my-custom"], env=ENV)
        assert result.exit_code == 0, result.output
        body = mock_create.call_args.args[1]
        assert body["metadata"]["labels"]["k6-scenario"] == "my-custom"
        assert body["spec"]["workflowTemplateRef"]["name"] == "k6-my-custom"


class TestLoadTestRunWait:
    """`--wait` polls the run Workflow to a terminal phase and maps it onto the exit code, so a load
    test wired into a script fails the script when the run itself failed."""

    @staticmethod
    def _phases(monkeypatch, *phases):
        """Feed get_workflow a canned sequence of phases, one per poll."""
        it = iter(phases)
        monkeypatch.setattr(runs_mod, "get_workflow", lambda ns, name: {"status": {"phase": next(it)}})

    @patch(f"{RUNS}.create_workflow", return_value="k6-ingest-xy")
    def test_wait_succeeds_when_run_finishes(self, _create, cluster, monkeypatch):
        self._phases(monkeypatch, "Succeeded")
        result = _runner().invoke(loadtest_cli, ["run", "--wait"], env=ENV)
        assert result.exit_code == 0, result.output
        assert "Succeeded" in result.output

    @patch(f"{RUNS}.create_workflow", return_value="k6-ingest-xy")
    def test_wait_polls_until_terminal(self, _create, cluster, monkeypatch):
        # A run that is still going keeps the poll loop alive; --wait-interval 0 keeps it instant.
        self._phases(monkeypatch, "Running", "Running", "Succeeded")
        result = _runner().invoke(
            loadtest_cli, ["run", "--wait", "--wait-interval", "0"], env=ENV)
        assert result.exit_code == 0, result.output
        assert "Run k6-ingest-xy finished: Succeeded" in result.output

    @patch(f"{RUNS}.create_workflow", return_value="k6-ingest-xy")
    def test_wait_exits_nonzero_when_run_fails(self, _create, cluster, monkeypatch):
        # The workflow's failureCondition folds the operator's `error` stage into this phase.
        self._phases(monkeypatch, "Failed")
        result = _runner().invoke(loadtest_cli, ["run", "--wait"], env=ENV)
        assert result.exit_code == 1
        assert "finished: Failed" in result.output

    @patch(f"{RUNS}.create_workflow", return_value="k6-ingest-xy")
    def test_wait_exits_nonzero_on_workflow_error(self, _create, cluster, monkeypatch):
        self._phases(monkeypatch, "Error")
        result = _runner().invoke(loadtest_cli, ["run", "--wait"], env=ENV)
        assert result.exit_code == 1

    @patch(f"{RUNS}.create_workflow", return_value="k6-ingest-xy")
    def test_wait_times_out(self, _create, cluster, monkeypatch):
        # --timeout 0 puts the deadline in the past, so the loop gives up without polling.
        monkeypatch.setattr(runs_mod, "get_workflow", lambda ns, name: {"status": {"phase": "Running"}})
        result = _runner().invoke(
            loadtest_cli, ["run", "--wait", "--timeout", "0"], env=ENV)
        assert result.exit_code == 1
        assert "Timed out after 0s waiting for k6-ingest-xy" in result.output

    @patch(f"{RUNS}.create_workflow", return_value="k6-ingest-xy")
    def test_missing_workflow_keeps_waiting_until_timeout(self, _create, cluster, monkeypatch):
        # get_workflow returns None for a run the API doesn't know about yet; that must not be
        # mistaken for a terminal phase.
        monkeypatch.setattr(runs_mod, "get_workflow", lambda ns, name: None)
        result = _runner().invoke(
            loadtest_cli, ["run", "--wait", "--timeout", "0"], env=ENV)
        assert result.exit_code == 1
        assert "Timed out" in result.output

    @patch(f"{RUNS}.create_workflow", return_value="k6-ingest-xy")
    def test_no_wait_returns_immediately(self, _create, cluster, monkeypatch):
        # Without --wait the command must not poll at all.
        polled = []
        monkeypatch.setattr(runs_mod, "get_workflow", lambda ns, name: polled.append(name))
        result = _runner().invoke(loadtest_cli, ["run"], env=ENV)
        assert result.exit_code == 0, result.output
        assert polled == []


class TestLoadTemplateDefaults:
    def test_missing_template_raises(self, monkeypatch):
        monkeypatch.setattr(runs_mod, "get_workflow_template", lambda ns, name: None)
        monkeypatch.setattr(runs_mod, "list_scenarios", lambda ns: [])
        with pytest.raises(ValueError):
            load_template_defaults("ma", "ingest")

    def test_missing_template_lists_available(self, monkeypatch):
        monkeypatch.setattr(runs_mod, "get_workflow_template", _fake_get_workflow_template)
        monkeypatch.setattr(runs_mod, "list_scenarios", lambda ns: ["ingest", "mixed", "search"])
        with pytest.raises(ValueError) as e:
            load_template_defaults("ma", "nope")
        msg = str(e.value)
        assert "available:" in msg and "ingest" in msg and "mixed" in msg

    def test_defaults_are_read_from_the_template(self, monkeypatch):
        monkeypatch.setattr(runs_mod, "get_workflow_template", _fake_get_workflow_template)
        defaults = load_template_defaults("ma", "ingest")
        assert defaults["runnerImage"] == "grafana/k6:2.2.0"
        assert defaults["scriptsRef"] == "migrations/k6_scripts:latest"


class TestCompletion:
    def test_scenario_completion_from_cluster(self, monkeypatch):
        monkeypatch.setattr(cli_mod, "load_k8s_config", lambda: None)
        monkeypatch.setattr(cli_mod, "get_current_namespace", lambda: "ma")
        monkeypatch.setattr(cli_mod, "list_scenarios", lambda ns: ["ingest", "mixed", "search"])
        assert cli_mod._complete_scenarios(None, None, "mi") == ["mixed"]

    def test_preset_completion_is_the_images_presets(self):
        # Presets ship in the runner image, so completion needs no cluster at all.
        assert cli_mod._complete_presets(None, None, "ingest-") == [
            p for p in runs_mod.CONFIG_PRESETS if p.startswith("ingest-")]

    def test_completion_falls_back_when_offline(self, monkeypatch):
        def boom():
            raise RuntimeError("no kubeconfig")
        monkeypatch.setattr(cli_mod, "load_k8s_config", boom)
        # falls back to the static hint lists rather than raising during shell completion
        assert "ingest-steady" in cli_mod._complete_presets(None, None, "ingest-")
        assert cli_mod._complete_scenarios(None, None, "sea") == ["search"]

    def test_registry_and_bag_overrides(self, monkeypatch):
        monkeypatch.setattr(runs_mod, "get_workflow_template", _fake_get_workflow_template)
        p = build_k6_parameters(scenario="mixed", registry_enabled=True,
                                overrides_text="INGEST_RATE=7\nFOO=bar")
        body = build_workflow_submission("ma", p)
        env = _env_map(body)
        assert env["REGISTRY_ENABLED"] == "true"
        assert env["INGEST_RATE"] == "7" and env["FOO"] == "bar"


def _fake_run(name, scenario, phase, parallelism):
    """A run as the API returns it: the Workflow, with parallelism among its submitted parameters."""
    return {
        "metadata": {"name": name, "labels": {"app": "k6-load-test", "k6-scenario": scenario},
                     "creationTimestamp": "2026-07-27T00:00:00Z"},
        "spec": {"workflowTemplateRef": {"name": f"k6-{scenario}"},
                 "arguments": {"parameters": [{"name": "parallelism", "value": str(parallelism)}]}},
        "status": {"phase": phase},
    }


FAKE_RUNS = [
    _fake_run("k6-ingest-a", "ingest", "Running", 2),
    _fake_run("k6-mixed-b", "mixed", "Succeeded", 1),
]


class TestLoadTestList:
    @patch(f"{CLI}.load_k8s_config")
    @patch(f"{RUNS}.list_workflows", return_value=FAKE_RUNS)
    def test_list_selector_and_rows(self, mock_list, _cfg):
        result = _runner().invoke(loadtest_cli, ["list"], env=ENV)
        assert result.exit_code == 0
        assert mock_list.call_args.kwargs["label_selector"] == "app=k6-load-test"
        assert "k6-ingest-a" in result.output and "Running" in result.output

    @patch(f"{CLI}.load_k8s_config")
    @patch(f"{RUNS}.list_workflows", return_value=FAKE_RUNS)
    def test_list_scenario_filter(self, mock_list, _cfg):
        _runner().invoke(loadtest_cli, ["list", "--scenario", "mixed"], env=ENV)
        assert mock_list.call_args.kwargs["label_selector"] == "app=k6-load-test,k6-scenario=mixed"

    @patch(f"{CLI}.load_k8s_config")
    @patch(f"{RUNS}.list_workflows", return_value=[])
    def test_list_empty(self, _list, _cfg):
        result = _runner().invoke(loadtest_cli, ["list"], env=ENV)
        assert "No k6 runs found." in result.output

    @patch(f"{CLI}.load_k8s_config")
    @patch(f"{RUNS}.list_workflows", side_effect=RuntimeError("api down"))
    def test_list_api_error_is_reported_as_itself(self, _list, _cfg):
        result = _runner().invoke(loadtest_cli, ["list"], env=ENV)
        assert result.exit_code == 1
        assert "api down" in result.output
        assert "not installed" not in result.output


class TestLoadTestStop:
    @patch(f"{CLI}.load_k8s_config")
    @patch(f"{CLI}.delete_workflow", return_value=True)
    def test_stop_by_name_deletes(self, mock_delete, _cfg):
        result = _runner().invoke(loadtest_cli, ["stop", "k6-ingest-a"], env=ENV)
        assert result.exit_code == 0
        assert "k6-ingest-a: stopped" in result.output
        mock_delete.assert_called_once_with("ma", "k6-ingest-a")

    @patch(f"{CLI}.load_k8s_config")
    @patch(f"{CLI}.delete_workflow", return_value=False)
    def test_stop_reports_a_run_that_was_not_there(self, mock_delete, _cfg):
        """A 404 used to be reported as success, so `stop` announced a stop that never happened —
        which hid exactly the case worth knowing about: a typo, or an already-reaped run."""
        result = _runner().invoke(loadtest_cli, ["stop", "k6-typo"], env=ENV)
        assert result.exit_code == 3
        assert "k6-typo: not found" in result.output

    @patch(f"{CLI}.load_k8s_config")
    @patch(f"{RUNS}.list_workflows", return_value=[FAKE_RUNS[1]])
    @patch(f"{CLI}.delete_workflow", return_value=True)
    def test_stop_scenario(self, mock_delete, mock_list, _cfg):
        result = _runner().invoke(loadtest_cli, ["stop", "--scenario", "mixed"], env=ENV)
        assert result.exit_code == 0
        mock_delete.assert_called_once_with("ma", "k6-mixed-b")

    def test_stop_conflicting_selectors(self):
        result = _runner().invoke(loadtest_cli, ["stop", "somename", "--all"], env=ENV)
        assert result.exit_code == 2

    @patch(f"{CLI}.load_k8s_config")
    @patch(f"{RUNS}.list_workflows", return_value=[])
    def test_stop_no_match(self, _list, _cfg):
        result = _runner().invoke(loadtest_cli, ["stop", "--all"], env=ENV)
        assert "No matching k6 runs." in result.output


class TestBuildParams:
    def test_defaults_include_parallelism(self):
        p = build_k6_parameters(scenario="search")
        assert p["configName"] == "search-steady" and p["parallelism"] == 1

    def test_bad_override_raises(self):
        with pytest.raises(ValueError):
            build_k6_parameters(scenario="ingest", overrides_text="NOEQUALS")


class TestListActiveRuns:
    @patch(f"{RUNS}.list_workflows", return_value=FAKE_RUNS)
    def test_excludes_terminal_phases(self, _list):
        active = list_active_k6_runs("ma")
        assert [r["name"] for r in active] == ["k6-ingest-a"]
        assert active[0]["scenario"] == "ingest" and active[0]["phase"] == "Running"

    @patch(f"{RUNS}.list_workflows", return_value=FAKE_RUNS)
    def test_list_runs_keeps_finished_runs(self, _list):
        """The TUI table shows history, so the unfiltered call keeps terminal runs — and carries
        the parallelism column the CLI table also prints."""
        runs = runs_mod.list_runs("ma")
        assert [r["name"] for r in runs] == ["k6-ingest-a", "k6-mixed-b"]
        assert all("parallelism" in r for r in runs)


class TestIsolation:
    """A k6 run must be its own top-level Workflow so it can't fail a migration workflow."""

    def test_no_owner_references(self, monkeypatch):
        monkeypatch.setattr(runs_mod, "get_workflow_template", _fake_get_workflow_template)
        body = build_workflow_submission("ma", build_k6_parameters(scenario="ingest"))
        assert "ownerReferences" not in body["metadata"]
        assert body["metadata"]["generateName"] == "k6-ingest-"
        assert body["kind"] == "Workflow"


class TestLoadTestLogs:
    @patch(f"{CLI}.subprocess.run")
    def test_logs_builds_kubectl_command(self, mock_run):
        mock_run.return_value.returncode = 0
        _runner().invoke(loadtest_cli, ["logs", "k6-ingest-a"], env=ENV)
        cmd = mock_run.call_args.args[0]
        assert cmd[:2] == ["kubectl", "logs"]
        assert "-c" in cmd and "k6" in cmd
        # the TestRun shares the workflow's name, so one identifier reaches the k6 pods
        assert "k6_cr=k6-ingest-a,runner=true" in cmd

    @patch(f"{CLI}.subprocess.run")
    def test_logs_follow(self, mock_run):
        mock_run.return_value.returncode = 0
        _runner().invoke(loadtest_cli, ["logs", "k6-ingest-a", "-f"], env=ENV)
        assert "-f" in mock_run.call_args.args[0]

    @patch(f"{CLI}.subprocess.run")
    def test_logs_propagates_the_kubectl_exit_code(self, mock_run):
        """kubectl's failure used to be discarded, so `loadtest logs` exited 0 on a Forbidden."""
        mock_run.return_value.returncode = 1
        result = _runner().invoke(loadtest_cli, ["logs", "k6-ingest-a"], env=ENV)
        assert result.exit_code == 1

    @patch(f"{CLI}.subprocess.run")
    def test_logs_adds_the_hint_when_the_chart_is_missing(self, mock_run, no_chart):
        # The pod-read grant ships with the chart, so a missing chart surfaces here as a kubectl
        # Forbidden. kubectl has already said what went wrong; the hint only explains why.
        mock_run.return_value.returncode = 1
        result = _runner().invoke(loadtest_cli, ["logs", "k6-ingest-a"], env=ENV)
        assert result.exit_code == 1
        assert "not installed" in result.output

    @patch(f"{CLI}.subprocess.run")
    def test_hint_failure_never_masks_the_kubectl_error(self, mock_run, monkeypatch):
        """Here the hint is an extra, not the diagnosis, so a failure to produce it is dropped."""
        def boom(ns):
            raise RuntimeError("api down")
        monkeypatch.setattr(runs_mod, "list_k6_workflow_templates", boom)
        mock_run.return_value.returncode = 7
        result = _runner().invoke(loadtest_cli, ["logs", "k6-ingest-a"], env=ENV)
        assert result.exit_code == 7
        assert result.exception is None or isinstance(result.exception, SystemExit)


class TestPresetAndScenarioListsMatchTheImage:
    """The scenarios and presets live in the runner image, not in the cluster, so nothing here can
    discover them at runtime — these lists are hand-maintained hints for completion, the TUI
    dropdown and the unknown-preset warning. Keep them honest against the sources the image is
    built from (TrafficCapture/trafficLoadTest), which is the only thing that can drift."""

    @staticmethod
    def _load_test_dir():
        d = Path(__file__).resolve().parents[5] / "TrafficCapture" / "trafficLoadTest"
        if not d.is_dir():
            pytest.skip("running outside a repo checkout (e.g. inside the console image)")
        return d

    def test_config_presets_match_the_env_files(self):
        on_disk = sorted(p.stem for p in (self._load_test_dir() / "k6-config").glob("*.env"))
        assert sorted(runs_mod.CONFIG_PRESETS) == on_disk

    def test_scenarios_match_the_scenario_scripts(self):
        on_disk = sorted(p.stem for p in (self._load_test_dir() / "scenarios").glob("*.js"))
        assert sorted(runs_mod.SCENARIOS) == on_disk

    def test_tui_fallback_presets_match(self):
        from console_link.loadtest.tui.launch_modal import _FALLBACK_PRESETS
        assert sorted(_FALLBACK_PRESETS) == sorted(runs_mod.CONFIG_PRESETS)


class TestChartRunnerEnvDefaults:
    """The k6LoadTest chart states some runner env vars explicitly, so a reader of the template can
    see what a run is tuned with. `runnerEnv` is the highest-priority layer, not a defaults layer:
    lib/config.js resolves a run as `{...preset, ...__ENV}`. Two things therefore have to hold, and
    both are invisible until a run silently uses the wrong profile."""

    @staticmethod
    def _load_test_dir():
        d = Path(__file__).resolve().parents[5] / "TrafficCapture" / "trafficLoadTest"
        if not d.is_dir():
            pytest.skip("running outside a repo checkout (e.g. inside the console image)")
        return d

    @staticmethod
    def _template():
        chart = Path(__file__).resolve().parents[5] / "deployment/k8s/charts/components/k6LoadTest"
        p = chart / "templates/k6-workflowtemplates.yaml"
        if not p.is_file():
            pytest.skip("running outside a repo checkout")
        return p.read_text()

    def _chart_env(self):
        """The (name, value) pairs the chart puts in runnerEnv, read from the Helm source so the
        test needs no helm binary. K6_* are k6's own knobs, not scenario config.

        `value` is None when the entry takes a Helm value rather than a literal (e.g.
        `"value" $.Values.captureProxyUrl`). Those keys still count for every check that cares about
        the key alone — matching only literals would let a Helm-valued entry smuggle in a
        preset-owned key unnoticed.
        """
        pairs = re.findall(r'dict "name" "([A-Z0-9_]+)" "value" (?:"([^"]*)"|(\S+?)\)?\n)',
                           self._template())
        return [(k, lit if lit else None) for k, lit, dyn in pairs if not k.startswith("K6_")]

    def _preset_keys(self):
        keys = set()
        for f in (self._load_test_dir() / "k6-config").glob("*.env"):
            for line in f.read_text().splitlines():
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    keys.add(line.split("=", 1)[0].strip())
        return keys

    def test_no_preset_owned_key_is_stated_in_the_chart(self):
        """A key any preset sets must NOT be in runnerEnv: the entry would beat the preset for every
        run, so `--config ingest-burst` would silently keep the value stated here. The ramp and burst
        presets omit DURATION on purpose, because RAMP_STAGES carries their timing."""
        clashes = sorted({k for k, _ in self._chart_env()} & self._preset_keys())
        assert clashes == [], (
            f"chart runnerEnv states preset-owned key(s) {clashes}; they would override "
            f"every k6-config/*.env preset")

    def test_stated_values_match_the_script_fallbacks(self):
        """Each stated value duplicates a `CFG.X || 'default'` fallback in the scripts. Where they
        disagree the chart wins silently, so the script's default would become dead code."""
        src = "\n".join(p.read_text() for d in ("scenarios", "lib")
                        for p in (self._load_test_dir() / d).glob("*.js"))
        fallbacks = dict(re.findall(r"CFG\.([A-Z0-9_]+)\s*\|\|\s*'([^']*)'", src))
        mismatched = {k: (v, fallbacks.get(k)) for k, v in self._chart_env()
                      if v is not None and k in fallbacks and fallbacks[k] != v}
        assert mismatched == {}, f"chart value != script fallback for {mismatched}"

    def test_credentials_are_never_stated_in_the_chart(self):
        """Credentials are per-run inputs. A default here would ship in every rendered template."""
        stated = {k for k, _ in self._chart_env()}
        assert not (stated & {"AUTH_USERNAME", "AUTH_PASSWORD", "AUTH_MODE"})
