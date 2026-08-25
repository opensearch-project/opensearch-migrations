"""Tests for the standalone `loadtest` CLI (submits a Workflow against the chart's per-profile
WorkflowTemplate, overriding only what the run asks for).

Two patch targets, matching the two modules: cluster logic lives in `runs`, and the Click layer in
`cli` binds its helpers by name at import, so anything the command calls directly is patched on
`cli` and anything reached through runs.py is patched on `runs`.
"""

import re
import shutil
import subprocess
from pathlib import Path

import pytest
import yaml
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

# The settings each stand-in profile has, keyed by scenario. Short, but with the property the real
# chart has and that the client depends on: a scenario states only the settings it reads, so
# REGISTRY_ENABLED exists on mixed and nowhere else.
_SCENARIO_ENV = {
    "ingest": {"INGEST_RATE": "50", "INGEST_VUS": "20", "DURATION": "5m",
               "CONTROL_ENABLED": "false", "K6_OUT": "opentelemetry",
               "CAPTURE_PROXY_URL": "https://capture-proxy:9201"},
    "search": {"SEARCH_RATE": "50", "SEARCH_VUS": "30", "DURATION": "5m",
               "K6_OUT": "opentelemetry", "CAPTURE_PROXY_URL": "https://capture-proxy:9201"},
    "mixed": {"INGEST_RATE": "30", "SEARCH_RATE": "20", "INGEST_VUS": "15", "SEARCH_VUS": "15",
              "DURATION": "5m", "REGISTRY_ENABLED": "true", "CONTROL_ENABLED": "false",
              "WEBDIS_URL": "http://webdis:7379", "K6_OUT": "opentelemetry",
              "CAPTURE_PROXY_URL": "https://capture-proxy:9201"},
}


def _template(profile, scenario=None, env=None):
    """A stand-in for the WorkflowTemplate the chart renders per profile: the whole run lives in its
    parameter defaults — the plumbing in camelCase, every load setting in ALL_CAPS — so the client
    submits only what a run changes."""
    scenario = scenario or profile.split("-")[0]
    env = _SCENARIO_ENV.get(scenario, {}) if env is None else env
    return {
        "apiVersion": "argoproj.io/v1alpha1", "kind": "WorkflowTemplate",
        "metadata": {"name": f"k6-{profile}",
                     "labels": {"app": "k6-load-test", "k6-scenario": scenario,
                                "k6-profile": profile},
                     "annotations": {"workflows.argoproj.io/description": f"{profile} profile"}},
        "spec": {
            "entrypoint": "run",
            "arguments": {"parameters": [
                {"name": "parallelism", "value": "1"},
                {"name": "separate", "value": "false"},
                {"name": "arguments", "value": ""},
                {"name": "runnerImage", "value": "grafana/k6:2.2.0"},
                {"name": "scriptsRef", "value": "migrations/k6_scripts:latest"},
            ] + [{"name": k, "value": v} for k, v in sorted(env.items())]},
        },
    }


_PROFILES = [f"{s}-{shape}" for s in ("ingest", "search", "mixed")
             for shape in ("steady", "burst")]


def _fake_get_workflow_template(namespace, name):
    profile = name[len("k6-"):] if name.startswith("k6-") else None
    return _template(profile) if profile in _PROFILES else None


def _runner():
    return CliRunner()


@pytest.fixture(autouse=True)
def chart_installed(monkeypatch):
    """The chart is present unless a test says otherwise. This is what the dead-end paths ask about,
    so leaving it unpatched would make every "nothing found" assertion depend on a real cluster."""
    monkeypatch.setattr(runs_mod, "list_k6_workflow_templates",
                        lambda ns: [_template(p) for p in _PROFILES])
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
    """The load settings the submission overrides, as name → value. Everything it does NOT carry is
    the profile's own value, which stays in the template."""
    return {k: v for k, v in _submitted_parameters(body).items()
            if runs_mod.ENV_PARAM.fullmatch(k)}


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
    def test_status_reports_installed_and_profiles(self, _cfg, chart_installed):
        result = _runner().invoke(loadtest_cli, ["status"], env=ENV)
        assert result.exit_code == 0
        assert "installed in namespace 'ma'" in result.output
        # Each launchable profile, with what it does — the answer to "what can I run here".
        assert "mixed-burst" in result.output
        assert "mixed-burst profile" in result.output

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
    @patch(f"{RUNS}.create_workflow", return_value="k6-ingest-steady-xy")
    def test_submits_against_the_template(self, mock_create, cluster):
        result = _runner().invoke(loadtest_cli, [
            "run", "--scenario", "ingest", "--target", "https://p:9200",
            "--rate", "80", "--parallelism", "3",
        ], env=ENV)
        assert result.exit_code == 0, result.output
        body = mock_create.call_args.args[1]
        # A submission is a Workflow naming the profile's template — the run spec itself (images,
        # script path, the scripts mount, and every setting not overridden) stays in the template.
        assert body["kind"] == "Workflow"
        assert body["spec"]["workflowTemplateRef"]["name"] == "k6-ingest-steady"
        assert body["metadata"]["generateName"] == "k6-ingest-steady-"
        assert body["metadata"]["labels"] == {"app": "k6-load-test", "k6-scenario": "ingest",
                                              "k6-profile": "ingest-steady"}
        params = _submitted_parameters(body)
        assert params["parallelism"] == "3"
        env = _env_map(body)
        assert env["CAPTURE_PROXY_URL"] == "https://p:9200"
        assert env["INGEST_RATE"] == "80"
        assert "arguments" not in params

    @patch(f"{RUNS}.create_workflow", return_value="k6-ingest-steady-xy")
    def test_only_overridden_parameters_are_submitted(self, mock_create, cluster):
        """Anything the run does not change stays with the template, so its defaults remain the one
        definition — a submission never restates the images, the pull policies, or the load."""
        _runner().invoke(loadtest_cli, ["run", "--scenario", "ingest"], env=ENV)
        params = _submitted_parameters(mock_create.call_args.args[1])
        assert set(params) == {"parallelism"}

    @patch(f"{RUNS}.create_workflow", return_value="k6-ingest-burst-xy")
    def test_config_selects_the_profiles_template(self, mock_create, cluster):
        """The profile IS the template, so choosing one is not an override of anything: nothing
        about the load is restated in the submission."""
        result = _runner().invoke(loadtest_cli, ["run", "--config", "ingest-burst"], env=ENV)
        assert result.exit_code == 0, result.output
        body = mock_create.call_args.args[1]
        assert body["spec"]["workflowTemplateRef"]["name"] == "k6-ingest-burst"
        assert body["metadata"]["labels"]["k6-profile"] == "ingest-burst"
        assert _env_map(body) == {}

    @patch(f"{RUNS}.create_workflow", return_value="k6-search-steady-xy")
    def test_default_profile_is_scenario_steady(self, mock_create, cluster):
        _runner().invoke(loadtest_cli, ["run", "--scenario", "search"], env=ENV)
        body = mock_create.call_args.args[1]
        assert body["spec"]["workflowTemplateRef"]["name"] == "k6-search-steady"

    @patch(f"{RUNS}.create_workflow", return_value="k6-ingest-steady-xy")
    def test_scenario_comes_from_the_template_not_the_caller(self, mock_create, cluster):
        """A profile knows its own scenario, so a caller cannot file a run under the wrong one."""
        result = _runner().invoke(loadtest_cli, [
            "run", "--scenario", "ingest", "--config", "mixed-steady"], env=ENV)
        assert result.exit_code == 1
        assert "runs scenario 'mixed', not 'ingest'" in result.output
        mock_create.assert_not_called()

    @patch(f"{RUNS}.create_workflow", return_value="k6-ingest-steady-xy")
    def test_override_is_one_named_parameter(self, mock_create, cluster):
        """An override names the parameter it replaces, so the runner env cannot end up with the
        same variable twice — which of two same-named entries wins is not the load test's to
        decide."""
        _runner().invoke(loadtest_cli, ["run", "-e", "DURATION=30s"], env=ENV)
        params = _submitted_parameters(mock_create.call_args.args[1])
        assert params["DURATION"] == "30s"

    @patch(f"{RUNS}.create_workflow")
    def test_override_of_a_setting_the_profile_lacks_is_refused(self, mock_create, cluster):
        """Argo accepts a parameter no template uses and then ignores it, so a name that does not
        belong to the profile has to fail here — otherwise it looks like it took effect."""
        result = _runner().invoke(loadtest_cli, [
            "run", "--config", "ingest-steady", "-e", "SEARCH_RATE=5"], env=ENV)
        assert result.exit_code == 1
        assert "has no 'SEARCH_RATE' setting" in result.output
        assert "INGEST_RATE" in result.output          # names what it does have
        mock_create.assert_not_called()

    @patch(f"{RUNS}.create_workflow", return_value="k6-mixed-steady-xy")
    def test_rate_fans_out_to_the_streams_the_profile_has(self, mock_create, cluster):
        """--rate names both stream variables; it applies to whichever the profile declares. On
        mixed that is both, on ingest only INGEST_RATE (asserted above)."""
        _runner().invoke(loadtest_cli, ["run", "--config", "mixed-steady", "--rate", "9"], env=ENV)
        env = _env_map(mock_create.call_args.args[1])
        assert env["INGEST_RATE"] == "9" and env["SEARCH_RATE"] == "9"

    @patch(f"{RUNS}.create_workflow")
    def test_toggle_the_profile_lacks_is_refused(self, mock_create, cluster):
        result = _runner().invoke(loadtest_cli, [
            "run", "--config", "search-steady", "--registry-enabled"], env=ENV)
        assert result.exit_code == 1
        assert "no REGISTRY_ENABLED setting" in result.output
        mock_create.assert_not_called()

    @patch(f"{RUNS}.create_workflow", return_value="k6-mixed-steady-xy")
    def test_toggle_off_overrides_a_profile_that_has_it_on(self, mock_create, cluster):
        """The mixed profiles turn the ring on, so turning it off must emit an override — leaving
        it out would silently keep the profile's value."""
        _runner().invoke(loadtest_cli, [
            "run", "--config", "mixed-steady", "--no-registry-enabled"], env=ENV)
        assert _env_map(mock_create.call_args.args[1])["REGISTRY_ENABLED"] == "false"

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
        monkeypatch.setattr(runs_mod, "list_profiles", lambda ns: [])
        result = _runner().invoke(loadtest_cli, ["run", "--scenario", "ingest"], env=ENV)
        assert result.exit_code == 1
        assert "no WorkflowTemplate 'k6-ingest-steady'" in result.output
        assert "k6LoadTest" in result.output
        mock_create.assert_not_called()

    @patch(f"{RUNS}.create_workflow")
    def test_unknown_profile_names_the_ones_that_exist(self, mock_create, cluster, monkeypatch):
        """A profile is a cluster object now, so an unknown name is answerable exactly — no warning
        that runs anyway and fails later in a pod."""
        monkeypatch.setattr(runs_mod, "list_profiles", lambda ns: _PROFILES)
        result = _runner().invoke(loadtest_cli, ["run", "--config", "home-brewed"], env=ENV)
        assert result.exit_code == 1
        assert "no WorkflowTemplate 'k6-home-brewed'" in result.output
        assert "ingest-burst" in result.output
        mock_create.assert_not_called()

    @patch(f"{RUNS}.create_workflow", return_value="k6-custom-xy")
    def test_custom_profile_accepted(self, mock_create, monkeypatch):
        # Neither flag is a fixed Choice: a chart installed with different values renders whatever
        # profiles it was given, and those are launchable.
        monkeypatch.setattr(cli_mod, "load_k8s_config", lambda: None)
        monkeypatch.setattr(
            runs_mod, "get_workflow_template",
            lambda ns, name: _template("house-mix", scenario="my-custom",
                                       env={"INGEST_RATE": "1"}) if name == "k6-house-mix" else None)
        result = _runner().invoke(loadtest_cli, ["run", "--config", "house-mix"], env=ENV)
        assert result.exit_code == 0, result.output
        body = mock_create.call_args.args[1]
        assert body["metadata"]["labels"]["k6-scenario"] == "my-custom"
        assert body["spec"]["workflowTemplateRef"]["name"] == "k6-house-mix"


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
        monkeypatch.setattr(runs_mod, "list_profiles", lambda ns: [])
        with pytest.raises(ValueError):
            load_template_defaults("ma", "ingest-steady")

    def test_missing_template_lists_available(self, monkeypatch):
        monkeypatch.setattr(runs_mod, "get_workflow_template", _fake_get_workflow_template)
        monkeypatch.setattr(runs_mod, "list_profiles", lambda ns: _PROFILES)
        with pytest.raises(ValueError) as e:
            load_template_defaults("ma", "nope")
        msg = str(e.value)
        assert "available:" in msg and "ingest-steady" in msg and "mixed-burst" in msg

    def test_defaults_are_read_from_the_template(self, monkeypatch):
        monkeypatch.setattr(runs_mod, "get_workflow_template", _fake_get_workflow_template)
        defaults = load_template_defaults("ma", "ingest-steady")
        assert defaults["runnerImage"] == "grafana/k6:2.2.0"
        assert defaults["scriptsRef"] == "migrations/k6_scripts:latest"
        assert defaults["INGEST_RATE"] == "50"      # the load is in the template too


class TestProfileCatalog:
    """One list call must answer both "what can I run" and "what does each one set", so the launch
    form never has to guess a profile's settings from its name."""

    def test_catalog_carries_scenario_description_and_env(self, chart_installed):
        catalog = runs_mod.profile_catalog("ma")
        assert set(catalog) == set(_PROFILES)
        entry = catalog["mixed-steady"]
        assert entry["scenario"] == "mixed"
        assert entry["description"] == "mixed-steady profile"
        assert entry["env"]["REGISTRY_ENABLED"] == "true"

    def test_catalog_holds_only_the_load_settings(self, chart_installed):
        """The plumbing (images, parallelism) is not something the launch form offers, and the
        ALL_CAPS convention is what separates the two."""
        env = runs_mod.profile_catalog("ma")["ingest-steady"]["env"]
        assert "runnerImage" not in env and "parallelism" not in env
        assert "INGEST_RATE" in env

    def test_catalog_is_empty_without_a_chart(self, no_chart):
        assert runs_mod.profile_catalog("ma") == {}


class TestCompletion:
    def test_scenario_completion_from_cluster(self, monkeypatch):
        monkeypatch.setattr(cli_mod, "load_k8s_config", lambda: None)
        monkeypatch.setattr(cli_mod, "get_current_namespace", lambda: "ma")
        monkeypatch.setattr(cli_mod, "list_scenarios", lambda ns: ["ingest", "mixed", "search"])
        assert cli_mod._complete_scenarios(None, None, "mi") == ["mixed"]

    def test_profile_completion_from_cluster(self, monkeypatch):
        # Profiles are cluster objects now, so completion offers what is actually installed.
        monkeypatch.setattr(cli_mod, "load_k8s_config", lambda: None)
        monkeypatch.setattr(cli_mod, "get_current_namespace", lambda: "ma")
        monkeypatch.setattr(cli_mod, "list_profiles", lambda ns: ["mixed-steady", "house-mix"])
        assert cli_mod._complete_profiles(None, None, "house") == ["house-mix"]

    def test_completion_falls_back_when_offline(self, monkeypatch):
        def boom():
            raise RuntimeError("no kubeconfig")
        monkeypatch.setattr(cli_mod, "load_k8s_config", boom)
        # falls back to the static hint lists rather than raising during shell completion
        assert "ingest-steady" in cli_mod._complete_profiles(None, None, "ingest-")
        assert cli_mod._complete_scenarios(None, None, "sea") == ["search"]

    def test_registry_and_bag_overrides(self, monkeypatch):
        monkeypatch.setattr(runs_mod, "get_workflow_template", _fake_get_workflow_template)
        p = build_k6_parameters(config_name="mixed-steady", registry_enabled=True,
                                overrides_text="INGEST_RATE=7\nDURATION=1m")
        body = build_workflow_submission("ma", p)
        env = _env_map(body)
        assert env["REGISTRY_ENABLED"] == "true"
        assert env["INGEST_RATE"] == "7" and env["DURATION"] == "1m"


def _fake_run(name, profile, phase, parallelism):
    """A run as the API returns it: the Workflow, with parallelism among its submitted parameters."""
    scenario = profile.split("-")[0]
    return {
        "metadata": {"name": name,
                     "labels": {"app": "k6-load-test", "k6-scenario": scenario,
                                "k6-profile": profile},
                     "creationTimestamp": "2026-07-27T00:00:00Z"},
        "spec": {"workflowTemplateRef": {"name": f"k6-{profile}"},
                 "arguments": {"parameters": [{"name": "parallelism", "value": str(parallelism)}]}},
        "status": {"phase": phase},
    }


FAKE_RUNS = [
    _fake_run("k6-ingest-a", "ingest-burst", "Running", 2),
    _fake_run("k6-mixed-b", "mixed-steady", "Succeeded", 1),
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
        assert body["metadata"]["generateName"] == "k6-ingest-steady-"
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


# ---------------------------------------------------------------------------
# The chart IS the run specification now, so these guard it against the sources
# it has to agree with: the scenario scripts, and the presets a local run uses.
# ---------------------------------------------------------------------------
_REPO = Path(__file__).resolve().parents[5]
# Supplied by the template from .Values.captureProxyUrl (deployment topology, not a load setting),
# so it is the one key a scenario reads that its `scenarios.<name>.env` block must NOT restate.
_CHART_SUPPLIED = {"CAPTURE_PROXY_URL"}
_CREDENTIALS = {"AUTH_MODE", "AUTH_USERNAME", "AUTH_PASSWORD"}
# EXECUTOR decides which timing knob a profile has, so these are stated per profile rather than
# scenario-wide: a constant-rate profile has DURATION, a ramping one has its stage list. Stating the
# other would advertise a knob that does nothing — and would let `--duration 30s` be accepted on a
# ramping profile, changing nothing.
_STAGE_KEYS = {"RAMP_STAGES", "INGEST_RAMP_STAGES", "SEARCH_RAMP_STAGES"}
_TIMING = _STAGE_KEYS | {"DURATION"}


def _repo_dir(rel):
    d = _REPO / rel
    if not d.exists():
        pytest.skip("running outside a repo checkout (e.g. inside the console image)")
    return d


def _chart_values():
    return yaml.safe_load(
        (_repo_dir("deployment/k8s/charts/components/k6LoadTest") / "values.yaml").read_text())


def _resolved(values, profile):
    """A profile's settings as the rendered template states them: the scenario-wide values with the
    profile's own on top. This mirrors the `merge` in k6-workflowtemplates.yaml."""
    p = values["profiles"][profile]
    env = dict(values["scenarios"][p["scenario"]]["env"])
    env.update(p.get("env") or {})
    return {k: str(v) for k, v in env.items()}


def _scenario_source(scenario):
    """The scenario script and every lib it reaches, concatenated.

    lib/config.js is skipped: it defines CFG rather than reading any setting from it, so the `CFG.X`
    in its own docstring is not a key anything looks up.
    """
    d = _repo_dir("TrafficCapture/trafficLoadTest")
    start = d / "scenarios" / f"{scenario}.js"
    seen, queue, out = set(), [start], []
    while queue:
        path = queue.pop().resolve()
        if path in seen or not path.is_file() or path.name == "config.js":
            continue
        seen.add(path)
        text = path.read_text()
        out.append(text)
        for rel in re.findall(r"from '(\.[^']+\.js)'", text):
            queue.append(path.parent / rel)
    return "\n".join(out)


def _script_fallbacks(scenario):
    """The `CFG.KEY || 'value'` defaults the scenario's own code applies."""
    return dict(re.findall(r"CFG\.([A-Z][A-Z0-9_]*)\s*\|\|\s*'([^']*)'",
                           _scenario_source(scenario)))


def _keys_read(scenario):
    return set(re.findall(r"CFG\.([A-Z][A-Z0-9_]*)", _scenario_source(scenario)))


def _preset_file(profile):
    path = _repo_dir("TrafficCapture/trafficLoadTest") / "k6-config" / f"{profile}.env"
    out = {}
    for line in path.read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            out[key.strip()] = value.strip()
    return out


class TestChartStatesTheWholeRun:
    """A run gets exactly what its WorkflowTemplate says, with no layer under it. That is only
    trustworthy while the chart states every setting the scenario reads — a key left out would fall
    back to a value in JavaScript that nothing in the cluster shows."""

    @pytest.mark.parametrize("scenario", ("ingest", "search", "mixed"))
    def test_every_setting_a_scenario_reads_is_stated(self, scenario):
        stated = set(_chart_values()["scenarios"][scenario]["env"])
        missing = _keys_read(scenario) - stated - _CHART_SUPPLIED - _TIMING
        assert missing == set(), (
            f"scenario '{scenario}' reads {sorted(missing)}, which no profile states; add them to "
            f"scenarios.{scenario}.env in the k6LoadTest values.yaml")

    def test_each_profile_states_exactly_one_timing_knob(self):
        """The timing setting is the one thing EXECUTOR decides, so it belongs to the profile. A
        profile that stated both would show a reader a duration its run does not follow."""
        values = _chart_values()
        for profile, p in sorted(values["profiles"].items()):
            env = _resolved(values, profile)
            read = _keys_read(p["scenario"])
            stages = {k for k in _STAGE_KEYS & read if k in env}
            if env["EXECUTOR"].startswith("ramping"):
                assert stages, f"{profile} ramps but states no stage list"
                assert "DURATION" not in env, f"{profile} ramps yet states DURATION"
            else:
                assert env.get("DURATION"), f"{profile} holds a rate but states no DURATION"
                assert not stages, f"{profile} holds a rate yet states {sorted(stages)}"

    def test_no_profile_blanks_a_value_its_scenario_states(self):
        """Helm's `merge` is not a precedence operator: it fills an EMPTY destination value from the
        source, so a profile blanking an inherited setting would silently get it back. Values are
        additive for that reason — state a setting where it applies instead of unsetting it."""
        values = _chart_values()
        for profile, p in sorted(values["profiles"].items()):
            scenario_env = values["scenarios"][p["scenario"]]["env"]
            for key, value in (p.get("env") or {}).items():
                if str(value) == "" and str(scenario_env.get(key, "")) != "":
                    raise AssertionError(
                        f"profile '{profile}' blanks {key}, which scenario '{p['scenario']}' sets to "
                        f"'{scenario_env[key]}'. Helm's merge writes the scenario value back over it.")

    @pytest.mark.parametrize("scenario", ("ingest", "search", "mixed"))
    def test_nothing_unread_is_stated(self, scenario):
        """The other direction: a setting no scenario reads is dead weight that reads as live
        configuration. SEED_DOC_COUNT sat in six presets like that until it was removed."""
        stated = set(_chart_values()["scenarios"][scenario]["env"])
        assert stated - _keys_read(scenario) == set()

    @pytest.mark.parametrize("scenario", ("ingest", "search", "mixed"))
    def test_stated_values_match_the_script_fallbacks(self, scenario):
        """A scenario-wide value duplicates a `CFG.X || 'default'` fallback in the script. Where the
        two disagree the chart wins silently, so the script's default becomes dead code."""
        env = {k: str(v) for k, v in _chart_values()["scenarios"][scenario]["env"].items()}
        fallbacks = _script_fallbacks(scenario)
        mismatched = {k: (v, fallbacks[k]) for k, v in env.items()
                      if k in fallbacks and fallbacks[k] != v}
        assert mismatched == {}, f"chart value != script fallback for {mismatched}"

    def test_capture_proxy_url_matches_the_script_fallback(self):
        """This one comes from .Values.captureProxyUrl rather than from a scenario block, so it
        needs the same check by itself."""
        values = _chart_values()
        for scenario in ("ingest", "search", "mixed"):
            assert "CAPTURE_PROXY_URL" not in values["scenarios"][scenario]["env"]
            assert values["captureProxyUrl"] == _script_fallbacks(scenario)["CAPTURE_PROXY_URL"]

    def test_credentials_have_no_value(self):
        """Credentials are per-run inputs. A value here would ship in every rendered template. The
        keys are still stated, so a run can pass them by name."""
        for scenario, block in _chart_values()["scenarios"].items():
            for key in _CREDENTIALS & set(block["env"]):
                assert block["env"][key] == "", f"{scenario}.{key} carries a value"

    def test_a_profile_only_changes_settings_of_its_scenario(self):
        """A profile may state anything its scenario reads — including a timing knob the scenario
        block leaves out — but nothing else. A key the scenario never reads would ride into the
        runner env as configuration that does nothing."""
        values = _chart_values()
        for profile, p in sorted(values["profiles"].items()):
            allowed = set(values["scenarios"][p["scenario"]]["env"]) | _keys_read(p["scenario"])
            unknown = set(p.get("env") or {}) - allowed
            assert unknown == set(), f"profile '{profile}' sets {sorted(unknown)}, which " \
                                     f"scenario '{p['scenario']}' does not read"

    def test_no_value_holds_a_single_quote(self):
        """The templates substitute parameters into single-quoted YAML scalars, so one of these
        would produce a TestRun manifest that fails to parse at submit time. Helm fails the render
        too; this says so before a render is ever attempted."""
        values = _chart_values()
        for profile in values["profiles"]:
            for key, value in _resolved(values, profile).items():
                assert "'" not in value, f"{profile}.{key} holds a single quote"


class TestChartProfilesMatchTheLocalPresets:
    """k6-config/*.env now serves only a local `k6 run`; the chart profiles serve the cluster. They
    describe the same load, so they must stay equal — a drift would make a local reproduction of a
    cluster run quietly different."""

    def test_profile_names_match_the_env_files(self):
        on_disk = sorted(p.stem for p in
                         (_repo_dir("TrafficCapture/trafficLoadTest") / "k6-config").glob("*.env"))
        assert sorted(_chart_values()["profiles"]) == on_disk

    def test_each_preset_value_matches_the_profile(self):
        values = _chart_values()
        for profile in sorted(values["profiles"]):
            resolved = _resolved(values, profile)
            for key, value in _preset_file(profile).items():
                assert key in resolved, f"{profile}.env sets {key}, which the chart does not state"
                assert resolved[key] == value, (
                    f"{profile}: preset has {key}={value}, chart has {resolved[key]}")


class TestFallbackListsMatchTheChart:
    """The client's hardcoded lists are only used when the cluster cannot be reached, so nothing
    fails when they drift — which is exactly why they need a test."""

    def test_profiles_fallback_matches(self):
        assert sorted(runs_mod.PROFILES) == sorted(_chart_values()["profiles"])

    def test_scenarios_fallback_matches(self):
        assert sorted(runs_mod.SCENARIOS) == sorted(_chart_values()["scenarios"])

    def test_tui_fallback_profiles_match(self):
        from console_link.loadtest.tui.launch_modal import _FALLBACK_PROFILES
        assert sorted(_FALLBACK_PROFILES) == sorted(runs_mod.PROFILES)


class TestRenderedChartMatchesTheValues:
    """Renders the chart with helm and checks the OUTPUT, not the values model.

    Everything above reasons about values.yaml the way the template is *meant* to combine it. That
    is the gap a bug can live in: `DURATION: ""` on a ramping profile modelled correctly here and
    still rendered as `5m`, because Helm's `merge` fills an empty destination from the source. Only
    rendering catches that class.

    It also pins the one invariant the template can no longer get for free. The parameter list and
    the runner env list used to come from a single loop, so they could not drift; the chart-owned
    settings are now declared in both places by hand, and a key added to one and forgotten in the
    other would either be a parameter nothing reads or an env var nobody can override.

    Skipped where helm is absent, like the other checks are outside a repo checkout.
    """

    @staticmethod
    def _rendered():
        helm = shutil.which("helm")
        if helm is None:
            pytest.skip("helm is not installed")
        chart = _repo_dir("deployment/k8s/charts/components/k6LoadTest")
        out = subprocess.run(
            [helm, "template", "t", str(chart), "--namespace", "ma",
             "--show-only", "templates/k6-workflowtemplates.yaml"],
            capture_output=True, text=True)
        if out.returncode != 0:
            pytest.fail(f"helm template failed:\n{out.stderr}")
        return {d["metadata"]["name"]: d
                for d in yaml.safe_load_all(out.stdout) if d and d.get("kind") == "WorkflowTemplate"}

    @staticmethod
    def _runner_env(template, defaults):
        """The TestRun's runner env, with the workflow parameters substituted as Argo does it."""
        manifest = template["spec"]["templates"][0]["resource"]["manifest"]
        substituted = re.sub(r"\{\{workflow\.parameters\.([A-Za-z0-9_-]+)\}\}",
                             lambda m: defaults[m.group(1)], manifest)
        substituted = substituted.replace("{{workflow.name}}", "a-run")
        return {e["name"]: e["value"]
                for e in yaml.safe_load(substituted)["spec"]["runner"]["env"]}

    def test_every_profile_renders_a_template(self):
        assert set(self._rendered()) == {f"k6-{p}" for p in _chart_values()["profiles"]}

    def test_the_parameters_and_the_runner_env_are_the_same_set(self):
        for name, template in sorted(self._rendered().items()):
            defaults = {p["name"]: p.get("value", "")
                        for p in template["spec"]["arguments"]["parameters"]}
            settings = {k for k in defaults if runs_mod.ENV_PARAM.fullmatch(k)}
            env = self._runner_env(template, defaults)
            assert settings == set(env), (
                f"{name}: declared as parameters but not in the runner env: "
                f"{sorted(settings - set(env))}; in the env but not declared: "
                f"{sorted(set(env) - settings)}")

    def test_rendered_values_are_what_the_values_file_says(self):
        """The load from scenario+profile, plus the two chart-owned groups, with nothing altered on
        the way through Helm."""
        values = _chart_values()
        for name, template in sorted(self._rendered().items()):
            profile = name[len("k6-"):]
            expected = dict(_resolved(values, profile))
            expected["CAPTURE_PROXY_URL"] = str(values["captureProxyUrl"])
            expected.update({k: str(v) for k, v in values["k6Output"].items()})
            defaults = {p["name"]: p.get("value", "")
                        for p in template["spec"]["arguments"]["parameters"]}
            actual = {k: v for k, v in defaults.items() if runs_mod.ENV_PARAM.fullmatch(k)}
            assert actual == expected, f"{name}: rendered settings differ from values.yaml"

    def test_a_ramping_profile_renders_no_duration(self):
        """The specific regression: the stage list carries the timing, so DURATION must not exist —
        not as an empty string either, or `--duration` would be accepted and change nothing."""
        for name, template in sorted(self._rendered().items()):
            defaults = {p["name"]: p.get("value", "")
                        for p in template["spec"]["arguments"]["parameters"]}
            if defaults.get("EXECUTOR", "").startswith("ramping"):
                assert "DURATION" not in defaults, f"{name} ramps yet renders a DURATION parameter"
