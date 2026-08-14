"""Tests for testrun_utils helpers (k6 TestRun / ConfigMap plumbing).

These exercise the wrappers directly. The `workflow loadtest` command tests patch these names on
the loadtest module (ltmod.create_testrun, ltmod.read_configmap, ...), so the real implementations
— and the 404-vs-other-error handling they encode — are only reached from here.
"""
import pytest
from unittest.mock import MagicMock, patch

from kubernetes.client.rest import ApiException

from console_link.workflow.commands import testrun_utils
from console_link.workflow.commands.testrun_utils import (
    K6_GROUP,
    K6_PLURAL,
    K6_VERSION,
    create_testrun,
    delete_testrun,
    get_testrun,
    list_scenarios,
    list_testruns,
    loadtest_installed,
    read_configmap,
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


def _core_api(**behavior):
    """A stand-in CoreV1Api patched over the module's client."""
    fake = MagicMock()
    for name, value in behavior.items():
        target = getattr(fake, name)
        if isinstance(value, Exception):
            target.side_effect = value
        else:
            target.return_value = value
    return patch.object(testrun_utils.client, "CoreV1Api", return_value=fake), fake


def test_list_scenarios_from_examples_configmap():
    # keys of the k6-testrun-examples ConfigMap are the launchable scenario names
    data = {"ingest": "{...}", "search": "{...}", "mixed": "{...}"}
    with patch.object(testrun_utils, "read_configmap", return_value=data) as rc:
        assert list_scenarios("ma") == ["ingest", "mixed", "search"]
    rc.assert_called_once_with("ma", testrun_utils.EXAMPLES_CONFIGMAP)


def test_list_scenarios_empty_when_absent():
    with patch.object(testrun_utils, "read_configmap", return_value={}):
        assert list_scenarios("ma") == []


class TestCreateTestrun:
    def test_returns_server_assigned_name(self):
        patcher, fake = _custom_api(
            create_namespaced_custom_object={"metadata": {"name": "k6-run-abc"}})
        with patcher:
            assert create_testrun("ma", {"kind": "TestRun"}) == "k6-run-abc"
        fake.create_namespaced_custom_object.assert_called_once_with(
            group=K6_GROUP, version=K6_VERSION, namespace="ma", plural=K6_PLURAL,
            body={"kind": "TestRun"})

    def test_empty_name_when_response_has_no_metadata(self):
        # The name only exists server-side (the spec uses generateName), so tolerate its absence
        # rather than raising on a response shape we don't control.
        patcher, _ = _custom_api(create_namespaced_custom_object={})
        with patcher:
            assert create_testrun("ma", {}) == ""


class TestListTestruns:
    def test_returns_items(self):
        patcher, fake = _custom_api(
            list_namespaced_custom_object={"items": [{"metadata": {"name": "k6-run-a"}}]})
        with patcher:
            assert list_testruns("ma") == [{"metadata": {"name": "k6-run-a"}}]
        # No selector was asked for, so none is sent (an empty one would not mean the same thing).
        assert "label_selector" not in fake.list_namespaced_custom_object.call_args.kwargs

    def test_empty_when_no_items_key(self):
        patcher, _ = _custom_api(list_namespaced_custom_object={})
        with patcher:
            assert list_testruns("ma") == []

    def test_forwards_label_selector(self):
        patcher, fake = _custom_api(list_namespaced_custom_object={"items": []})
        with patcher:
            assert list_testruns("ma", label_selector="app=k6-load-test") == []
        assert fake.list_namespaced_custom_object.call_args.kwargs["label_selector"] == \
            "app=k6-load-test"


class TestGetTestrun:
    def test_returns_the_object(self):
        tr = {"metadata": {"name": "k6-run-a"}, "status": {"stage": "started"}}
        patcher, fake = _custom_api(get_namespaced_custom_object=tr)
        with patcher:
            assert get_testrun("ma", "k6-run-a") == tr
        fake.get_namespaced_custom_object.assert_called_once_with(
            group=K6_GROUP, version=K6_VERSION, namespace="ma", plural=K6_PLURAL, name="k6-run-a")

    def test_none_when_absent(self):
        patcher, _ = _custom_api(get_namespaced_custom_object=ApiException(status=404))
        with patcher:
            assert get_testrun("ma", "gone") is None

    def test_reraises_non_404(self):
        # An RBAC denial must not be reported as "no such run" — that would silently mask a
        # misconfigured cluster as an empty result.
        patcher, _ = _custom_api(get_namespaced_custom_object=ApiException(status=403))
        with patcher, pytest.raises(ApiException):
            get_testrun("ma", "k6-run-a")


class TestDeleteTestrun:
    def test_true_when_deleted(self):
        patcher, fake = _custom_api(delete_namespaced_custom_object={})
        with patcher:
            assert delete_testrun("ma", "k6-run-a") is True
        fake.delete_namespaced_custom_object.assert_called_once_with(
            group=K6_GROUP, version=K6_VERSION, namespace="ma", plural=K6_PLURAL, name="k6-run-a")

    def test_true_when_already_gone(self):
        # Stopping is idempotent: a run that finished and was reaped between listing and stopping
        # counts as stopped, which is what the TUI's "Stopped n/m" tally relies on.
        patcher, _ = _custom_api(delete_namespaced_custom_object=ApiException(status=404))
        with patcher:
            assert delete_testrun("ma", "gone") is True

    def test_false_on_other_api_error(self):
        patcher, _ = _custom_api(delete_namespaced_custom_object=ApiException(status=403))
        with patcher:
            assert delete_testrun("ma", "k6-run-a") is False


class TestReadConfigmap:
    def test_returns_data(self):
        cm = MagicMock()
        cm.data = {"ingest": "{}"}
        patcher, fake = _core_api(read_namespaced_config_map=cm)
        with patcher:
            assert read_configmap("ma", "k6-testrun-examples") == {"ingest": "{}"}
        fake.read_namespaced_config_map.assert_called_once_with(
            name="k6-testrun-examples", namespace="ma")

    def test_empty_when_configmap_has_no_data(self):
        cm = MagicMock()
        cm.data = None
        patcher, _ = _core_api(read_namespaced_config_map=cm)
        with patcher:
            assert read_configmap("ma", "k6-testrun-examples") == {}

    def test_empty_when_absent(self):
        patcher, _ = _core_api(read_namespaced_config_map=ApiException(status=404))
        with patcher:
            assert read_configmap("ma", "nope") == {}

    def test_reraises_non_404(self):
        patcher, _ = _core_api(read_namespaced_config_map=ApiException(status=403))
        with patcher, pytest.raises(ApiException):
            read_configmap("ma", "k6-testrun-examples")


class TestLoadtestInstalled:
    def test_true_when_crd_is_listable(self):
        patcher, fake = _custom_api(list_namespaced_custom_object={"items": []})
        with patcher:
            assert loadtest_installed("ma") is True
        # Probe only — it must not pull the whole list back just to answer "is this installed?".
        assert fake.list_namespaced_custom_object.call_args.kwargs["limit"] == 1

    def test_false_when_crd_is_absent(self):
        patcher, _ = _custom_api(list_namespaced_custom_object=ApiException(status=404))
        with patcher:
            assert loadtest_installed("ma") is False

    def test_false_on_any_other_failure(self):
        # Deliberately broad: no kubeconfig, RBAC denial, connection refused — a normal migration
        # deployment must leave the `workflow loadtest` commands inert rather than surfacing an error.
        patcher, _ = _custom_api(list_namespaced_custom_object=RuntimeError("no kubeconfig"))
        with patcher:
            assert loadtest_installed("ma") is False
