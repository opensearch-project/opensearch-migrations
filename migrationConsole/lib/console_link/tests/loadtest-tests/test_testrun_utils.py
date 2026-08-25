"""Tests for testrun_utils helpers (the Workflow / WorkflowTemplate plumbing behind a k6 run).

These exercise the wrappers directly. The `loadtest` command tests patch these names on
runs.py (runs_mod.create_workflow, runs_mod.get_workflow_template, ...), so the real
implementations — and the 404-vs-other-error handling they encode — are only reached from here.
"""
import pytest
from unittest.mock import MagicMock, patch

from kubernetes.client.rest import ApiException

from console_link.loadtest import testrun_utils
from console_link.loadtest.testrun_utils import (
    ARGO_GROUP,
    ARGO_VERSION,
    K6_APP_LABEL,
    WORKFLOW_PLURAL,
    WORKFLOW_TEMPLATE_PLURAL,
    create_workflow,
    delete_workflow,
    get_workflow,
    get_workflow_template,
    list_k6_workflow_templates,
    list_profiles,
    list_scenarios,
    list_workflows,
    workflow_template_name,
)


def _custom_api(**behavior):
    """A stand-in CustomObjectsApi patched over the module's client."""
    fake = MagicMock()
    for name, value in behavior.items():
        target = getattr(fake, name)
        if isinstance(value, Exception) or (isinstance(value, type) and issubclass(value, Exception)):
            target.side_effect = value
        else:
            target.return_value = value
    return patch.object(testrun_utils.client, "CustomObjectsApi", return_value=fake), fake


def _templates(*profiles):
    """The chart's templates as the API returns them: one per profile, labelled with both the
    profile and the scenario it runs."""
    return {"items": [{"metadata": {"name": f"k6-{p}",
                                    "labels": {"k6-scenario": p.split("-")[0],
                                               "k6-profile": p}}}
                      for p in profiles]}


def test_workflow_template_name():
    assert workflow_template_name("ingest-burst") == "k6-ingest-burst"


class TestListScenarios:
    def test_from_the_charts_workflow_templates(self):
        patcher, fake = _custom_api(
            list_namespaced_custom_object=_templates("ingest-steady", "search-steady",
                                                     "mixed-steady"))
        with patcher:
            assert list_scenarios("ma") == ["ingest", "mixed", "search"]
        kwargs = fake.list_namespaced_custom_object.call_args.kwargs
        assert kwargs["plural"] == WORKFLOW_TEMPLATE_PLURAL
        # Found by label, not by name — a custom scenario is launchable without touching this code.
        assert kwargs["label_selector"] == f"app={K6_APP_LABEL}"

    def test_empty_when_chart_absent(self):
        patcher, _ = _custom_api(list_namespaced_custom_object={"items": []})
        with patcher:
            assert list_scenarios("ma") == []

    def test_empty_on_api_error(self):
        # Completion and the TUI dropdown call this; they fall back to their static hints rather
        # than failing when the API is unhappy.
        patcher, _ = _custom_api(list_namespaced_custom_object=ApiException(status=404))
        with patcher:
            assert list_scenarios("ma") == []

    def test_skips_templates_without_the_label(self):
        patcher, _ = _custom_api(list_namespaced_custom_object={
            "items": [{"metadata": {"name": "k6-ingest", "labels": {"k6-scenario": "ingest"}}},
                      {"metadata": {"name": "stray", "labels": {}}}]})
        with patcher:
            assert list_scenarios("ma") == ["ingest"]

    def test_several_profiles_of_one_scenario_list_it_once(self):
        patcher, _ = _custom_api(
            list_namespaced_custom_object=_templates("ingest-steady", "ingest-burst"))
        with patcher:
            assert list_scenarios("ma") == ["ingest"]


class TestListProfiles:
    """A profile is a whole WorkflowTemplate now, so what can be run is discovered rather than
    mirrored from a list in the client."""

    def test_lists_every_profile(self):
        patcher, _ = _custom_api(
            list_namespaced_custom_object=_templates("ingest-burst", "ingest-steady",
                                                     "mixed-steady"))
        with patcher:
            assert list_profiles("ma") == ["ingest-burst", "ingest-steady", "mixed-steady"]

    def test_narrows_to_one_scenario(self):
        patcher, _ = _custom_api(
            list_namespaced_custom_object=_templates("ingest-burst", "mixed-steady"))
        with patcher:
            assert list_profiles("ma", scenario="mixed") == ["mixed-steady"]

    def test_empty_on_api_error(self):
        # Same reason as list_scenarios: completion and the launch form fall back rather than fail.
        patcher, _ = _custom_api(list_namespaced_custom_object=ApiException(status=404))
        with patcher:
            assert list_profiles("ma") == []


class TestCreateWorkflow:
    def test_returns_server_assigned_name(self):
        patcher, fake = _custom_api(
            create_namespaced_custom_object={"metadata": {"name": "k6-ingest-abc"}})
        with patcher:
            assert create_workflow("ma", {"kind": "Workflow"}) == "k6-ingest-abc"
        fake.create_namespaced_custom_object.assert_called_once_with(
            group=ARGO_GROUP, version=ARGO_VERSION, namespace="ma", plural=WORKFLOW_PLURAL,
            body={"kind": "Workflow"})

    def test_empty_name_when_response_has_no_metadata(self):
        # The name only exists server-side (the spec uses generateName), so tolerate its absence
        # rather than raising on a response shape we don't control.
        patcher, _ = _custom_api(create_namespaced_custom_object={})
        with patcher:
            assert create_workflow("ma", {}) == ""


class TestListWorkflows:
    def test_returns_items(self):
        patcher, fake = _custom_api(
            list_namespaced_custom_object={"items": [{"metadata": {"name": "k6-ingest-a"}}]})
        with patcher:
            assert list_workflows("ma") == [{"metadata": {"name": "k6-ingest-a"}}]
        # No selector was asked for, so none is sent (an empty one would not mean the same thing).
        assert "label_selector" not in fake.list_namespaced_custom_object.call_args.kwargs

    def test_empty_when_no_items_key(self):
        patcher, _ = _custom_api(list_namespaced_custom_object={})
        with patcher:
            assert list_workflows("ma") == []

    def test_forwards_label_selector(self):
        patcher, fake = _custom_api(list_namespaced_custom_object={"items": []})
        with patcher:
            assert list_workflows("ma", label_selector="app=k6-load-test") == []
        assert fake.list_namespaced_custom_object.call_args.kwargs["label_selector"] == \
            "app=k6-load-test"


class TestGetWorkflow:
    def test_returns_the_object(self):
        wf = {"metadata": {"name": "k6-ingest-a"}, "status": {"phase": "Running"}}
        patcher, fake = _custom_api(get_namespaced_custom_object=wf)
        with patcher:
            assert get_workflow("ma", "k6-ingest-a") == wf
        fake.get_namespaced_custom_object.assert_called_once_with(
            group=ARGO_GROUP, version=ARGO_VERSION, namespace="ma", plural=WORKFLOW_PLURAL,
            name="k6-ingest-a")

    def test_none_when_absent(self):
        patcher, _ = _custom_api(get_namespaced_custom_object=ApiException(status=404))
        with patcher:
            assert get_workflow("ma", "gone") is None

    def test_reraises_non_404(self):
        # An RBAC denial must not be reported as "no such run" — that would silently mask a
        # misconfigured cluster as an empty result.
        patcher, _ = _custom_api(get_namespaced_custom_object=ApiException(status=403))
        with patcher, pytest.raises(ApiException):
            get_workflow("ma", "k6-ingest-a")


class TestGetWorkflowTemplate:
    def test_returns_the_object(self):
        wt = {"metadata": {"name": "k6-ingest"}}
        patcher, fake = _custom_api(get_namespaced_custom_object=wt)
        with patcher:
            assert get_workflow_template("ma", "k6-ingest") == wt
        assert fake.get_namespaced_custom_object.call_args.kwargs["plural"] == \
            WORKFLOW_TEMPLATE_PLURAL

    def test_none_when_absent(self):
        patcher, _ = _custom_api(get_namespaced_custom_object=ApiException(status=404))
        with patcher:
            assert get_workflow_template("ma", "k6-nope") is None

    def test_reraises_non_404(self):
        patcher, _ = _custom_api(get_namespaced_custom_object=ApiException(status=403))
        with patcher, pytest.raises(ApiException):
            get_workflow_template("ma", "k6-ingest")


class TestDeleteWorkflow:
    def test_true_when_deleted(self):
        patcher, fake = _custom_api(delete_namespaced_custom_object={})
        with patcher:
            assert delete_workflow("ma", "k6-ingest-a") is True
        fake.delete_namespaced_custom_object.assert_called_once_with(
            group=ARGO_GROUP, version=ARGO_VERSION, namespace="ma", plural=WORKFLOW_PLURAL,
            name="k6-ingest-a")

    def test_false_when_already_gone(self):
        # A 404 is not a stop. Reporting it as one made `stop` announce that it had stopped a run
        # that was never there, hiding the case the user needs to see: a typo, or a run that had
        # already finished and been reaped.
        patcher, _ = _custom_api(delete_namespaced_custom_object=ApiException(status=404))
        with patcher:
            assert delete_workflow("ma", "gone") is False

    def test_other_api_errors_propagate(self):
        # An RBAC denial is not "already gone" — the caller must see it rather than read it as
        # a successful stop or a missing run.
        patcher, _ = _custom_api(delete_namespaced_custom_object=ApiException(status=403))
        with patcher, pytest.raises(ApiException):
            delete_workflow("ma", "k6-ingest-a")


class TestListK6WorkflowTemplates:
    """The chart's own WorkflowTemplates are what proves load testing is available here. Argo itself
    always ships with the migration, so the Workflow CRD proves nothing."""

    def test_selects_the_chart_templates_by_label(self):
        patcher, fake = _custom_api(list_namespaced_custom_object=_templates("ingest"))
        with patcher:
            assert len(list_k6_workflow_templates("ma")) == 1
        kwargs = fake.list_namespaced_custom_object.call_args.kwargs
        assert kwargs["plural"] == WORKFLOW_TEMPLATE_PLURAL
        assert kwargs["label_selector"] == f"app={K6_APP_LABEL}"

    def test_empty_when_chart_absent(self):
        patcher, _ = _custom_api(list_namespaced_custom_object={"items": []})
        with patcher:
            assert list_k6_workflow_templates("ma") == []

    def test_errors_propagate(self):
        # This is what tells a caller whether the chart is installed, so a cluster error must not
        # be swallowed into "not installed" — that answer would send the user to reinstall a chart
        # that is already there.
        patcher, _ = _custom_api(list_namespaced_custom_object=RuntimeError("no kubeconfig"))
        with patcher, pytest.raises(RuntimeError):
            list_k6_workflow_templates("ma")
