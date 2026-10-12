import json
from pathlib import Path

import yaml


# Keep these repository-level policy checks in the console_link suite so CI collects them.
REPO_ROOT = Path(__file__).resolve().parents[4]
CHART_DIR = REPO_ROOT / "deployment/k8s/charts/aggregates/migrationAssistantWithArgo"
DOCKER_OTEL_DIR = REPO_ROOT / "TrafficCapture/dockerSolution/src/main/docker/otelCollector"
K6_DASHBOARD = REPO_ROOT / "deployment/k8s/charts/components/k6LoadTest/files/grafana/load-test.json"


def test_eks_application_metrics_keep_prometheus_cumulative_and_awsemf_instance_free():
    collector = _load_collector_config(CHART_DIR / "valuesEks.yaml")
    pipelines = collector["service"]["pipelines"]

    assert pipelines["metrics/app"] == {
        "receivers": ["otlp"],
        "processors": [
            "batch",
            "resource/remove_default_attributes",
            "resource/metrics",
            "cumulativetodelta",
        ],
        "exporters": ["awsemf/app"],
    }
    assert pipelines["metrics/app-prometheus"] == {
        "receivers": ["otlp"],
        "processors": [
            "batch",
            "resource/remove_telemetry_sdk_attributes",
            "resource/metrics",
        ],
        "exporters": ["prometheus"],
    }

    awsemf_deleted_keys = _deleted_resource_keys(collector, pipelines["metrics/app"])
    prometheus_deleted_keys = _deleted_resource_keys(collector, pipelines["metrics/app-prometheus"])
    assert {"service.name", "service.instance.id"} <= awsemf_deleted_keys
    assert "service.name" not in prometheus_deleted_keys
    assert "service.instance.id" not in prometheus_deleted_keys
    assert "cumulativetodelta" not in pipelines["metrics/app-prometheus"]["processors"]
    assert "resource_to_telemetry_conversion" not in collector["exporters"]["prometheus"]


def test_eks_emf_metric_names_and_dimensions_are_unchanged():
    collector = _load_collector_config(CHART_DIR / "valuesEks.yaml")

    assert collector["exporters"]["awsemf/app"] == {
        "namespace": "OpenSearchMigrations",
        "dimension_rollup_option": "NoDimensionRollup",
        "resource_to_telemetry_conversion": {"enabled": True},
    }
    assert collector["exporters"]["awsemf/cadvisor"] == {
        "namespace": "OpenSearchMigrations",
        "dimension_rollup_option": "NoDimensionRollup",
        "metric_declarations": [{
            "dimensions": [["container", "namespace"]],
            "metric_name_selectors": [
                "container_cpu_usage_seconds_total",
                "container_memory_working_set_bytes",
            ],
        }],
    }
    assert collector["service"]["pipelines"]["metrics/cadvisor"] == {
        "receivers": ["prometheus"],
        "processors": ["batch"],
        "exporters": ["awsemf/cadvisor", "prometheus"],
    }


def test_docker_awsemf_configs_remove_instance_identity_before_export():
    for config_name in ("otel-config-aws.yaml", "otel-config-aws-metrics.yaml", "otel-config-aws-debug.yaml"):
        collector = _load_yaml(DOCKER_OTEL_DIR / config_name)
        awsemf_pipelines = [
            pipeline
            for pipeline in collector["service"]["pipelines"].values()
            if any(exporter == "awsemf" or exporter.startswith("awsemf/") for exporter in pipeline["exporters"])
        ]

        assert awsemf_pipelines
        for awsemf_pipeline in awsemf_pipelines:
            assert "cumulativetodelta" in awsemf_pipeline["processors"]
            assert "service.instance.id" in _deleted_resource_keys(collector, awsemf_pipeline)
        assert collector["exporters"]["awsemf"] == {
            "namespace": "OpenSearchMigrations",
            "dimension_rollup_option": "NoDimensionRollup",
            "resource_to_telemetry_conversion": {"enabled": True},
        }

    debug_collector = _load_yaml(DOCKER_OTEL_DIR / "otel-config-aws-debug.yaml")
    assert debug_collector["service"]["pipelines"]["metrics/debug"] == {
        "receivers": ["otlp"],
        "processors": [
            "batch",
            "resource/remove_telemetry_sdk_attributes",
            "resource/metrics",
        ],
        "exporters": ["debug"],
    }
    assert "service.name" not in _deleted_resource_keys(
        debug_collector,
        debug_collector["service"]["pipelines"]["metrics/debug"],
    )
    assert "service.instance.id" not in _deleted_resource_keys(
        debug_collector,
        debug_collector["service"]["pipelines"]["metrics/debug"],
    )


def test_k6_dashboard_aggregates_capture_metrics_across_instances():
    dashboard = json.loads(K6_DASHBOARD.read_text())
    expressions = {
        target["expr"]
        for panel in dashboard["panels"]
        for target in panel.get("targets", [])
        if "expr" in target
    }

    assert 'sum(rate(fullRequest_count_total{exported_job="capture"}[1m]))' in expressions
    assert 'sum(activeConnection_count{exported_job="capture"})' in expressions
    for quantile in ("0.50", "0.95", "0.99"):
        assert (
            f'histogram_quantile({quantile}, '
            'sum by (le)(rate(gatheringRequestDuration_milliseconds_bucket'
            '{exported_job="capture"}[1m])))'
        ) in expressions
        assert (
            f'histogram_quantile({quantile}, '
            'sum by (le)(rate(gatheringResponseDuration_milliseconds_bucket'
            '{exported_job="capture"}[1m])))'
        ) in expressions


def _load_yaml(path: Path):
    return yaml.safe_load(path.read_text())


def _load_collector_config(values_path: Path):
    return yaml.safe_load(_load_yaml(values_path)["metrics"]["collectorConfig"])


def _deleted_resource_keys(collector: dict, pipeline: dict):
    deleted_keys = set()
    for processor_name in pipeline["processors"]:
        processor = collector["processors"].get(processor_name) or {}
        for attribute in processor.get("attributes", []):
            if attribute.get("action") == "delete":
                deleted_keys.add(attribute["key"])
    return deleted_keys
