"""Tests for testrun_utils helpers (the Workflow / WorkflowTemplate plumbing behind a k6 run).

These exercise the wrappers directly. The `workflow loadtest` command tests patch these names on
the loadtest module (ltmod.create_workflow, ltmod.get_workflow_template, ...), so the real
implementations — and the 404-vs-other-error handling they encode — are only reached from here.
"""
import pytest
from unittest.mock import MagicMock, patch

from kubernetes.client.rest import ApiException

from console_link.workflow.commands import testrun_utils
from console_link.workflow.commands.testrun_utils import (
    ARGO_GROUP,
    ARGO_VERSION,
    K6_APP_LABEL,
    WORKFLOW_PLURAL,
    WORKFLOW_TEMPLATE_PLURAL,
    create_workflow,
    delete_workflow,
    get_workflow,
    get_workflow_template,
    list_scenarios,
    list_workflows,
    loadtest_installed,
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


def _templates(*scenarios):
    return {"items": [{"metadata": {"name": f"k6-{s}", "labels": {"k6-scenario": s}}}
                      for s in scenarios]}


def test_workflow_template_name():
    assert workflow_template_name("ingest") == "k6-ingest"


class TestListScenarios:
    def test_from_the_charts_workflow_templates(self):
        patcher, fake = _custom_api(
            list_namespaced_custom_object=_templates("ingest", "search", "mixed"))
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

    def test_true_when_already_gone(self):
        # Stopping is idempotent: a run that finished and was reaped between listing and stopping
        # counts as stopped, which is what the TUI's "Stopped n/m" tally relies on.
        patcher, _ = _custom_api(delete_namespaced_custom_object=ApiException(status=404))
        with patcher:
            assert delete_workflow("ma", "gone") is True

    def test_false_on_other_api_error(self):
        patcher, _ = _custom_api(delete_namespaced_custom_object=ApiException(status=403))
        with patcher:
            assert delete_workflow("ma", "k6-ingest-a") is False


class TestLoadtestInstalled:
    def test_true_when_the_chart_templates_exist(self):
        patcher, fake = _custom_api(list_namespaced_custom_object=_templates("ingest"))
        with patcher:
            assert loadtest_installed("ma") is True
        # Argo itself always ships with the migration, so the probe must be for the chart's own
        # templates rather than for the Workflow CRD.
        assert fake.list_namespaced_custom_object.call_args.kwargs["plural"] == \
            WORKFLOW_TEMPLATE_PLURAL

    def test_false_when_chart_absent(self):
        patcher, _ = _custom_api(list_namespaced_custom_object={"items": []})
        with patcher:
            assert loadtest_installed("ma") is False

    def test_false_on_any_other_failure(self):
        # Deliberately broad: no kubeconfig, RBAC denial, connection refused — a normal migration
        # deployment must leave the `workflow loadtest` commands inert rather than surfacing an error.
        patcher, _ = _custom_api(list_namespaced_custom_object=RuntimeError("no kubeconfig"))
        with patcher:
            assert loadtest_installed("ma") is False
