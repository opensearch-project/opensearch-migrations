"""Tests for testrun_utils helpers (k6 TestRun / ConfigMap plumbing)."""
from unittest.mock import MagicMock, patch

from kubernetes.client.rest import ApiException

from console_link.workflow.commands import testrun_utils
from console_link.workflow.commands.testrun_utils import list_presets, list_scenarios, PRESET_LABEL


def _cm(preset_name):
    cm = MagicMock()
    cm.metadata.labels = {"app": "k6-load-test", PRESET_LABEL: preset_name}
    return cm


def test_list_presets_returns_sorted_names():
    fake = MagicMock()
    fake.list_namespaced_config_map.return_value.items = [
        _cm("mixed-steady"), _cm("ingest-steady"), _cm("ingest-burst"),
    ]
    with patch.object(testrun_utils.client, "CoreV1Api", return_value=fake):
        assert list_presets("ma") == ["ingest-burst", "ingest-steady", "mixed-steady"]
    # discovered via the preset label, not a hardcoded list
    fake.list_namespaced_config_map.assert_called_once_with(
        namespace="ma", label_selector=PRESET_LABEL)


def test_list_presets_skips_unlabeled():
    unlabeled = MagicMock()
    unlabeled.metadata.labels = None
    fake = MagicMock()
    fake.list_namespaced_config_map.return_value.items = [_cm("ingest-steady"), unlabeled]
    with patch.object(testrun_utils.client, "CoreV1Api", return_value=fake):
        assert list_presets("ma") == ["ingest-steady"]


def test_list_presets_empty_on_api_error():
    fake = MagicMock()
    fake.list_namespaced_config_map.side_effect = ApiException(status=403)
    with patch.object(testrun_utils.client, "CoreV1Api", return_value=fake):
        assert list_presets("ma") == []


def test_list_scenarios_from_examples_configmap():
    # keys of the k6-testrun-examples ConfigMap are the launchable scenario names
    data = {"ingest": "{...}", "search": "{...}", "mixed": "{...}"}
    with patch.object(testrun_utils, "read_configmap", return_value=data) as rc:
        assert list_scenarios("ma") == ["ingest", "mixed", "search"]
    rc.assert_called_once_with("ma", testrun_utils.EXAMPLES_CONFIGMAP)


def test_list_scenarios_empty_when_absent():
    with patch.object(testrun_utils, "read_configmap", return_value={}):
        assert list_scenarios("ma") == []
