from copy import deepcopy
from pathlib import Path

import yaml

from integ_test.test_cases.basic_tests import Test0001SingleDocumentBackfill as _Test0001SingleDocumentBackfill
from integ_test.test_cases.ma_argo_test_base import MATestUserArguments


REPO_ROOT = Path(__file__).resolve().parents[5]
CHART_DIR = REPO_ROOT / "deployment/k8s/charts/aggregates/migrationAssistantWithArgo"


def test_default_single_document_backfill_does_not_configure_tracing():
    test_case = _Test0001SingleDocumentBackfill(_make_user_args())
    test_case.prepare_workflow_snapshot_and_migration_config()

    assert not _contains_key(test_case.workflow_snapshot_and_migration_config, "otelTraceCollectorEndpoint")
    assert not _contains_key(test_case.workflow_snapshot_and_migration_config, "otelCollectorEndpoint")


def test_default_chart_values_do_not_enable_trace_backend_packages():
    values = _load_yaml(CHART_DIR / "values.yaml")

    assert values["tracing"]["enabled"] is False
    assert values["conditionalPackageInstalls"]["jaeger"] is False
    assert values["conditionalPackageInstalls"]["grafana"] is False


def test_eks_chart_values_keep_traces_jaeger_and_grafana_disabled_by_default():
    values = _deep_merge(_load_yaml(CHART_DIR / "values.yaml"), _load_yaml(CHART_DIR / "valuesEks.yaml"))

    assert values["tracing"]["enabled"] is False
    assert values["tracing"]["backend"] == "xray"
    assert values["conditionalPackageInstalls"]["jaeger"] is False
    assert values["conditionalPackageInstalls"]["grafana"] is False


def _make_user_args():
    return MATestUserArguments(
        source_version="ES_7.x",
        target_version="OS_2.x",
        unique_id="observability-defaults",
        reuse_clusters=False,
    )


def _load_yaml(path: Path):
    return yaml.safe_load(path.read_text())


def _deep_merge(base: dict, overlay: dict):
    merged = deepcopy(base)
    for key, value in overlay.items():
        if isinstance(value, dict) and isinstance(merged.get(key), dict):
            merged[key] = _deep_merge(merged[key], value)
        else:
            merged[key] = deepcopy(value)
    return merged


def _contains_key(value, expected_key: str) -> bool:
    if isinstance(value, dict):
        return expected_key in value or any(_contains_key(v, expected_key) for v in value.values())
    if isinstance(value, list):
        return any(_contains_key(v, expected_key) for v in value)
    return False
