#!/usr/bin/env python3

"""Focused static and Helm-render checks for Kafka PVC observability."""

import argparse
import base64
import importlib.util
import json
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


KUBE_VERSION = "1.35.0"
RULE_KIND = "kind: PrometheusRule"
PROMETHEUS_ALERTS_ENABLED = "kafkaObservability.prometheusAlerts.enabled=true"
ALERTMANAGER_ENABLED = "charts.kube-prometheus-stack.values.alertmanager.enabled=true"
FILL_SEARCH = "FILL(SEARCH("
SUM_FILL_SEARCH = "SUM(" + FILL_SEARCH
GC_WIDGET_TITLE = "Kafka JVM garbage collection"
HANDLER_SATURATION_TITLE = "Kafka broker/controller request-handler utilization (average)"


def load_generator(path):
    spec = importlib.util.spec_from_file_location("grafana_generator", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def render(helm, chart, *extra, expect_success=True):
    result = subprocess.run(
        [
            helm,
            "template",
            "ma",
            str(chart),
            "--namespace",
            "ma",
            "--kube-version",
            KUBE_VERSION,
            *extra,
        ],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )
    if expect_success and result.returncode:
        raise AssertionError(result.stdout)
    if not expect_success and not result.returncode:
        raise AssertionError("Helm render unexpectedly succeeded")
    return result.stdout


def literal_block(following, marker_indent):
    """Collect one YAML literal block: the lines indented deeper than its key, dedented."""
    content_indent = None
    content = []
    for item in following:
        if not item.strip():
            content.append("")
            continue
        indentation = len(item) - len(item.lstrip(" "))
        if indentation <= marker_indent:
            break
        if content_indent is None:
            content_indent = indentation
        content.append(item[content_indent:])
    return "\n".join(content).rstrip()


def literal_blocks(text, key):
    lines = text.splitlines()
    marker = re.compile(r"^(\s*)" + re.escape(key) + r":\s*\|[-+]?\s*$")
    blocks = []
    for index, line in enumerate(lines):
        match = marker.match(line)
        if match:
            blocks.append(literal_block(lines[index + 1:], len(match.group(1))))
    return blocks


def embedded_grafana(rendered):
    blocks = literal_blocks(rendered, "capture-replay-observability.json")
    if len(blocks) != 1:
        raise AssertionError(f"expected one Grafana dashboard, found {len(blocks)}")
    return json.loads(blocks[0])


def decoded_child_values(rendered):
    return [
        base64.b64decode(value).decode()
        for value in re.findall(r'VALUES_B64="([A-Za-z0-9+/=]+)"', rendered)
    ]


class KafkaObservabilityTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.args = ARGS
        cls.chart = Path(ARGS.chart).resolve()
        cls.repo = cls.chart.parents[4]
        cls.generator_path = cls.repo / "deployment/k8s/scripts/generate-capture-replay-grafana-dashboard.py"
        cls.cloudwatch_generator_path = cls.repo / "deployment/k8s/scripts/generate-capture-replay-dashboard.py"
        cls.generator = load_generator(cls.generator_path)
        cls.default_render = render(ARGS.helm, cls.chart)
        cls.eks_render = render(
            ARGS.helm,
            cls.chart,
            "--values",
            str(cls.chart / "valuesEks.yaml"),
            "--set",
            "stageName=verify",
            "--set",
            "aws.region=us-east-2",
            "--set-string",
            "aws.account=123456789012",
            "--set",
            "aws.eksClusterName=verify-cluster",
        )

    def test_kube_state_metrics_allowlists_strimzi_pvc_labels(self):
        values = "\n".join(decoded_child_values(self.default_render))
        self.assertIn(
            "persistentvolumeclaims=[strimzi.io/cluster,strimzi.io/pool-name]",
            values,
        )

    def test_dashboard_uses_available_over_capacity_and_label_join(self):
        dashboard = embedded_grafana(self.default_render)
        panels = {panel.get("title"): panel for panel in dashboard["panels"]}
        summary = panels["Kafka disk utilization across volumes"]
        by_volume = panels["Kafka disk utilization by volume"]
        self.assertEqual(
            [target["legendFormat"] for target in summary["targets"]],
            ["Minimum", "Average", "Maximum"],
        )
        for target in summary["targets"] + by_volume["targets"]:
            expression = target["expr"]
            self.assertIn("1 -", expression)
            self.assertIn("kubelet_volume_stats_available_bytes", expression)
            self.assertIn("kubelet_volume_stats_capacity_bytes", expression)
            self.assertNotIn("kubelet_volume_stats_used_bytes", expression)
            self.assertIn("kube_persistentvolumeclaim_labels", expression)
            self.assertIn("label_strimzi_io_cluster", expression)
            self.assertIn("label_strimzi_io_pool_name", expression)

        variable = next(
            variable for variable in dashboard["templating"]["list"]
            if variable["name"] == "kafka_volume"
        )
        self.assertIn("kube_persistentvolumeclaim_labels", variable["definition"])
        self.assertNotIn("data-", variable["definition"])
        self.assertEqual(variable["allValue"], ".*")

    def test_dashboard_renders_global_thresholds(self):
        dashboard = embedded_grafana(self.default_render)
        panels = {panel.get("title"): panel for panel in dashboard["panels"]}
        thresholds = panels["Kafka disk utilization by volume"]["fieldConfig"]["defaults"]["thresholds"]
        self.assertEqual(
            [step["value"] for step in thresholds["steps"]],
            [None, 80, 90],
        )

    def test_generated_dashboard_is_current(self):
        with tempfile.TemporaryDirectory() as directory:
            generated = Path(directory) / "grafanaDashboard.yaml"
            subprocess.run(
                [sys.executable, str(self.generator_path), str(generated)],
                check=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
            )
            checked_in = self.chart / "templates/resources/grafanaDashboard.yaml"
            self.assertEqual(generated.read_text(), checked_in.read_text())

    def test_generated_cloudwatch_dashboard_is_current(self):
        with tempfile.TemporaryDirectory() as directory:
            generated = Path(directory) / "capture-replay-dashboard.json"
            subprocess.run(
                [sys.executable, str(self.cloudwatch_generator_path), "columns", str(generated)],
                check=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
            )
            checked_in = self.chart / "files/cloudwatch-dashboards/capture-replay-dashboard.json"
            self.assertEqual(generated.read_text(), checked_in.read_text())

    def test_cloudwatch_tuple_aggregates_accept_optional_source_status_dimension(self):
        dashboard = json.loads(
            (self.chart / "files/cloudwatch-dashboards/capture-replay-dashboard.json").read_text()
        )
        widgets = {
            widget.get("properties", {}).get("title"): widget
            for widget in dashboard["widgets"]
        }
        aggregate_widgets = [
            widgets["Replayed tuples/s"],
            widgets["Status-code match %"],
            widgets["Requests through the pipeline (per second)"],
            widgets["Status-code match % (includes missing source responses)"],
        ]
        expressions = [
            row[0]["expression"]
            for widget in aggregate_widgets
            for row in widget["properties"]["metrics"]
            if isinstance(row[0], dict) and "tupleComparison" in row[0].get("expression", "")
        ]
        self.assertEqual(len(expressions), 4)
        for expression in expressions:
            self.assertIn('Namespace="OpenSearchMigrations"', expression)
            self.assertNotIn("{OpenSearchMigrations,", expression)
            self.assertNotIn("FILL(SUM(SEARCH(", expression)
        match_expressions = [expression for expression in expressions if "100 *" in expression]
        self.assertEqual(len(match_expressions), 2)
        for expression in match_expressions:
            self.assertTrue(expression.startswith("IF("))
            self.assertIn("> 0, 100 *", expression)

    def test_cloudwatch_comparison_ratios_omit_zero_denominator_periods(self):
        dashboard = json.loads(
            (self.chart / "files/cloudwatch-dashboards/capture-replay-dashboard.json").read_text()
        )
        difference_widget = next(
            widget for widget in dashboard["widgets"]
            if widget.get("properties", {}).get("title") == "Response time difference, target − source (mean ms)"
        )
        expressions = [row[0]["expression"] for row in difference_widget["properties"]["metrics"]]
        self.assertEqual(len(expressions), 2)
        for expression in expressions:
            self.assertTrue(expression.startswith("IF("))
            self.assertIn("> 0, (FILL(SUM(SEARCH(", expression)

    def test_alert_rules_require_both_enablement_gates(self):
        alert_only = render(
            self.args.helm,
            self.chart,
            "--set",
            PROMETHEUS_ALERTS_ENABLED,
        )
        alertmanager_only = render(
            self.args.helm,
            self.chart,
            "--set",
            ALERTMANAGER_ENABLED,
        )
        self.assertNotIn(RULE_KIND, self.default_render)
        self.assertNotIn(RULE_KIND, alert_only)
        self.assertNotIn(RULE_KIND, alertmanager_only)

    def test_reconcile_hooks_wait_with_namespace_scoped_permissions(self):
        self.assertNotIn(
            "kubectl wait --for=condition=Established crd/prometheusrules.monitoring.coreos.com",
            self.default_render,
        )
        self.assertNotIn(
            "kubectl wait --for=condition=Established crd/podmonitors.monitoring.coreos.com",
            self.default_render,
        )
        self.assertIn(
            "kubectl get prometheusrule ma-kafka-storage --namespace ma --ignore-not-found",
            self.default_render,
        )
        self.assertIn(
            "kubectl get podmonitor ma-kafka-broker-metrics --namespace ma --ignore-not-found",
            self.default_render,
        )

    def test_delete_hooks_tolerate_missing_monitoring_crds(self):
        for kind in ("prometheusrules", "podmonitors"):
            self.assertIn(
                f'grep -qx {kind}.monitoring.coreos.com <<< "${{monitoring_resources}}"',
                self.default_render,
            )

    def test_request_handler_saturation_is_normalized_everywhere(self):
        halved = 'kafka_server_request_handler_idle_ratio{namespace="%s"}) / 2)'
        dashboard = embedded_grafana(self.default_render)
        panel = next(p for p in dashboard["panels"] if p.get("title") == HANDLER_SATURATION_TITLE)
        self.assertIn(halved % "$namespace", panel["targets"][0]["expr"])
        alerts = render(
            self.args.helm,
            self.chart,
            "--set",
            ALERTMANAGER_ENABLED,
            "--set",
            PROMETHEUS_ALERTS_ENABLED,
            "--set",
            "kafkaObservability.brokerMetrics.alertsEnabled=true",
        )
        self.assertIn(halved % "ma" + " > 80", alerts)

    def test_alert_rules_render_policy_and_promql(self):
        rendered = render(
            self.args.helm,
            self.chart,
            "--set",
            ALERTMANAGER_ENABLED,
            "--set",
            PROMETHEUS_ALERTS_ENABLED,
            "--set",
            "kafkaObservability.storage.warningFreePercent=25",
            "--set",
            "kafkaObservability.storage.criticalFreePercent=7",
            "--set",
            "kafkaObservability.storage.inodeFreePercent=8",
            "--set",
            "kafkaObservability.storage.forecastLookback=8h",
            "--set",
            "kafkaObservability.storage.projectedFillWarningHorizonHours=48",
        )
        self.assertIn(RULE_KIND, rendered)
        for alert in [
            "MigrationAssistantKafkaVolumeLowFreeSpaceWarning",
            "MigrationAssistantKafkaVolumeLowFreeSpaceCritical",
            "MigrationAssistantKafkaVolumeProjectedToFill",
            "MigrationAssistantKafkaVolumeLowFreeInodes",
            "MigrationAssistantKafkaVolumeHealthAbnormal",
        ]:
            self.assertIn(alert, rendered)
        self.assertIn("label_strimzi_io_cluster", rendered)
        self.assertIn("label_strimzi_io_pool_name", rendered)
        self.assertIn("* 100 < 25", rendered)
        self.assertIn("* 100 >= 7", rendered)
        self.assertIn("* 100 < 7", rendered)
        self.assertIn("* 100 < 8", rendered)
        self.assertIn("[8h:5m]", rendered)
        self.assertIn("172800", rendered)

    def test_eks_collector_exports_only_required_pvc_dimensions(self):
        blocks = literal_blocks(self.eks_render, "collector.yaml")
        self.assertEqual(len(blocks), 1)
        collector = blocks[0]
        self.assertIn("prometheus/kafka-pvc-cloudwatch:", collector)
        self.assertIn(
            "^kubelet_volume_stats_(available_bytes|capacity_bytes|inodes|inodes_free|health_status_abnormal)$",
            collector,
        )
        self.assertIn(
            "dimensions: [[EKSClusterName, qualifier, namespace, persistentvolumeclaim]]",
            collector,
        )
        exporter = collector.split("awsemf/kafka-pvc:", 1)[1].split("\n  awsemf/kafka-broker:", 1)[0]
        dimension_lines = [
            line.strip().lstrip("- ") for line in exporter.splitlines()
            if "dimensions:" in line
        ]
        self.assertEqual(
            dimension_lines,
            ["dimensions: [[EKSClusterName, qualifier, namespace, persistentvolumeclaim]]"],
        )
        self.assertIn("value: ${env:EKS_CLUSTER_NAME}", collector)
        self.assertIn("processors: [resource/remove_default_attributes, resource/kafka, batch]", collector)

    def test_eks_collector_exports_bounded_broker_jmx_metrics(self):
        blocks = literal_blocks(self.eks_render, "collector.yaml")
        self.assertEqual(len(blocks), 1)
        collector = blocks[0]
        self.assertIn("prometheus/kafka-broker-cloudwatch:", collector)
        self.assertIn("scrape_interval: 60s", collector)
        self.assertIn("regex: ${K8S_NODE_NAME}", collector)
        self.assertIn("regex: ^tcp-prometheus$", collector)
        self.assertIn("awsemf/kafka-broker:", collector)
        self.assertIn(
            "dimensions: [[EKSClusterName, qualifier, namespace, kafka_cluster, pod]]",
            collector,
        )
        self.assertIn(
            "dimensions: [[EKSClusterName, qualifier, namespace, kafka_cluster, pod, request, error]]",
            collector,
        )
        self.assertIn("cumulativetodelta/kafka-broker", collector)
        self.assertNotIn("topic, partition", collector)

    def test_cloudwatch_dashboard_uses_renderable_available_over_capacity(self):
        dashboard = json.loads(
            (self.chart / "files/cloudwatch-dashboards/capture-replay-dashboard.json").read_text()
        )
        widgets = {
            widget.get("properties", {}).get("title"): widget
            for widget in dashboard["widgets"]
        }
        variables = {variable["id"]: variable for variable in dashboard["variables"]}
        self.assertNotIn("KAFKA_PVC", variables)

        fleet = widgets["Kafka storage utilization (fleet, capacity-weighted)"]
        fleet_expression = fleet["properties"]["metrics"][0][0]["expression"]
        self.assertIn("1 -", fleet_expression)
        self.assertIn(SUM_FILL_SEARCH, fleet_expression)
        self.assertIn(FILL_SEARCH, fleet_expression)
        self.assertIn(", 300)", fleet_expression)
        self.assertIn("kubelet_volume_stats_available_bytes", fleet_expression)
        self.assertIn("kubelet_volume_stats_capacity_bytes", fleet_expression)
        self.assertNotIn("kubelet_volume_stats_used_bytes", fleet_expression)

        free = widgets["Kafka free space by broker volume (GiB)"]
        free_expression = free["properties"]["metrics"][0][0]["expression"]
        self.assertIn("kubelet_volume_stats_available_bytes", free_expression)
        self.assertIn(FILL_SEARCH, free_expression)
        self.assertIn("'Minimum', 300)", free_expression)
        self.assertIn("/ 1073741824", free_expression)
        self.assertEqual(
            free["properties"]["yAxis"]["left"],
            {"min": 0, "label": "GiB", "showUnits": False},
        )

        storage = widgets["Kafka storage totals (GiB)"]
        self.assertEqual(storage["properties"]["view"], "singleValue")
        storage_labels = [
            row[0]["label"] for row in storage["properties"]["metrics"]
            if isinstance(row[0], dict) and row[0].get("visible", True)
        ]
        self.assertEqual(storage_labels, ["Free (GiB)", "Used (GiB)", "Capacity (GiB)"])
        storage_expressions = [
            row[0]["expression"] for row in storage["properties"]["metrics"]
            if isinstance(row[0], dict)
        ]
        self.assertTrue(any(SUM_FILL_SEARCH in expression for expression in storage_expressions))
        self.assertTrue(any(FILL_SEARCH in expression for expression in storage_expressions))
        self.assertEqual(
            storage["properties"]["yAxis"]["left"],
            {"min": 0, "label": "GiB", "showUnits": False},
        )

        inode = widgets["Kafka inode utilization (fleet, capacity-weighted)"]
        inode_expression = next(
            row[0] for row in inode["properties"]["metrics"]
            if isinstance(row[0], dict)
        )
        self.assertIn("100 * (1 -", inode_expression["expression"])
        self.assertIn(SUM_FILL_SEARCH, inode_expression["expression"])
        self.assertEqual(inode_expression["label"], "Fleet inode utilization")

        health = widgets["Kafka abnormal volume health"]
        health_expression = health["properties"]["metrics"][0][0]
        self.assertIn("kubelet_volume_stats_health_status_abnormal", health_expression["expression"])
        self.assertNotIn("label", health_expression)

        for title in (
            "Kafka replication and ISR health",
            "Kafka controller, quorum, and uptime",
            "Kafka produce/fetch errors and throttling",
            HANDLER_SATURATION_TITLE,
            "Kafka JVM heap and process CPU",
            GC_WIDGET_TITLE,
        ):
            self.assertIn(title, widgets)
        broker_expressions = "\n".join(
            row[0]["expression"]
            for title, widget in widgets.items()
            if title and title.startswith("Kafka ")
            for row in widget.get("properties", {}).get("metrics", [])
            if row and isinstance(row[0], dict) and "expression" in row[0]
        )
        self.assertIn("kafka_cluster,pod", broker_expressions)
        self.assertIn("kafka_server_replicamanager_under_replicated_partitions", broker_expressions)
        self.assertIn("kafka_network_request_throttle_time_milliseconds", broker_expressions)
        self.assertIn("100 * SUM(", broker_expressions)
        handlers = widgets[HANDLER_SATURATION_TITLE]["properties"]["metrics"]
        self.assertTrue(all("/ 2)" in row[0]["expression"] for row in handlers))
        gc_rows = widgets[GC_WIDGET_TITLE]["properties"]["metrics"]
        self.assertEqual(len(gc_rows), 6)
        self.assertNotIn(
            "${PROP('Dim.collector')}",
            "\n".join(row[0]["label"] for row in gc_rows),
        )
        self.assertTrue(all("collector=" in row[0]["expression"] for row in gc_rows))
        for title in (
            "Kafka produce/fetch errors and throttling",
            GC_WIDGET_TITLE,
        ):
            self.assertNotIn(
                "${PROP(",
                "\n".join(
                    row[0].get("label", "")
                    for row in widgets[title]["properties"]["metrics"]
                ),
            )

        text_content = "\n".join(
            widget.get("properties", {}).get("markdown", "")
            for widget in dashboard["widgets"]
            if widget.get("type") == "text"
        )
        self.assertIn("Kafka → replayer consumer", text_content)
        self.assertLess(
            text_content.index("## Result tuples"),
            text_content.index("## Kafka broker storage"),
        )
        self.assertNotIn("���", text_content)

        for widget in widgets.values():
            for row in widget.get("properties", {}).get("metrics", []):
                if not row or not isinstance(row[0], dict) or "expression" not in row[0]:
                    continue
                self.assertIsNone(
                    re.search(r"SEARCH\([^)]*\)\s*/\s*SEARCH\(", row[0]["expression"]),
                    row[0]["expression"],
                )

    def test_cloudwatch_alarm_defaults_and_configuration(self):
        self.assertNotIn("kind: MetricAlarm", self.eks_render)
        rendered = render(
            self.args.helm,
            self.chart,
            "--values",
            str(self.chart / "valuesEks.yaml"),
            "--set",
            "stageName=verify",
            "--set",
            "aws.region=us-east-2",
            "--set-string",
            "aws.account=123456789012",
            "--set",
            "aws.eksClusterName=verify-cluster",
            "--set",
            "kafkaObservability.cloudWatchAlarms.enabled=true",
            "--set",
            "kafkaObservability.storage.warningFreePercent=25",
            "--set",
            "kafkaObservability.storage.criticalFreePercent=7",
            "--set",
            "kafkaObservability.cloudWatchAlarms.snsActionArns[0]=arn:aws:sns:us-east-2:123456789012:kafka",
        )
        self.assertEqual(rendered.count("kind: MetricAlarm"), 2)
        self.assertIn("threshold: 25", rendered)
        self.assertIn("threshold: 7", rendered)
        self.assertIn("GROUP BY persistentvolumeclaim", rendered)
        self.assertIn("expression: 100 * available / capacity", rendered)
        self.assertIn("expression: MIN(freepct)", rendered)
        self.assertIn("arn:aws:sns:us-east-2:123456789012:kafka", rendered)

    def test_cluster_name_is_propagated_by_bootstrap(self):
        # The Rust deployer asserts the same flag in its own unit tests.
        bootstrap = (self.repo / "deployment/k8s/aws/aws-bootstrap.sh").read_text()
        self.assertIn('--set aws.eksClusterName="${MIGRATIONS_EKS_CLUSTER_NAME}"', bootstrap)

    def test_broker_jmx_rules_and_monitor_are_enabled_by_default(self):
        self.assertIn("name: migration-assistant-kafka-jmx-metrics", self.default_render)
        self.assertIn("kafka_server_replicamanager_under_replicated_partitions", self.default_render)
        self.assertIn("kind: PodMonitor", self.default_render)
        self.assertIn("request=(Produce|FetchConsumer|FetchFollower)", self.default_render)
        self.assertIn(
            "java.lang<name=(.+), type=GarbageCollector><>CollectionCount",
            self.default_render,
        )
        disabled = render(
            self.args.helm,
            self.chart,
            "--set",
            "kafkaObservability.brokerMetrics.enabled=false",
        )
        self.assertIn("rules: []", disabled)
        self.assertNotIn("kind: PodMonitor", disabled)

    def test_broker_alerts_have_an_independent_enablement_gate(self):
        storage_only = render(
            self.args.helm,
            self.chart,
            "--set",
            ALERTMANAGER_ENABLED,
            "--set",
            PROMETHEUS_ALERTS_ENABLED,
        )
        self.assertNotIn("MigrationAssistantKafkaUnderReplicatedPartitions", storage_only)
        enabled = render(
            self.args.helm,
            self.chart,
            "--set",
            ALERTMANAGER_ENABLED,
            "--set",
            PROMETHEUS_ALERTS_ENABLED,
            "--set",
            "kafkaObservability.brokerMetrics.enabled=true",
            "--set",
            "kafkaObservability.brokerMetrics.alertsEnabled=true",
        )
        expected_alerts = (
            "MigrationAssistantKafkaUnderReplicatedPartitions",
            "MigrationAssistantKafkaOfflinePartitions",
            "MigrationAssistantKafkaIsrShrinking",
            "MigrationAssistantKafkaIsrChurn",
            "MigrationAssistantKafkaControllerUnavailable",
            "MigrationAssistantKafkaQuorumLeaderUnavailable",
            "MigrationAssistantKafkaRequestErrors",
            "MigrationAssistantKafkaRequestThrottled",
            "MigrationAssistantKafkaRequestHandlersSaturated",
            "MigrationAssistantKafkaJvmHeapHigh",
            "MigrationAssistantKafkaJvmGcHigh",
            "MigrationAssistantKafkaProcessCpuHigh",
            "MigrationAssistantKafkaProcessRestarted",
        )
        for alert in expected_alerts:
            self.assertIn(alert, enabled)

    def test_invalid_percentage_policy_fails_helm_render(self):
        output = render(
            self.args.helm,
            self.chart,
            "--set",
            "kafkaObservability.storage.warningFreePercent=10",
            "--set",
            "kafkaObservability.storage.criticalFreePercent=20",
            expect_success=False,
        )
        self.assertIn("criticalFreePercent must be lower", output)

    def test_invalid_forecast_lookback_fails_helm_render(self):
        for invalid_lookback in ("six hours", "1h2h", "1s1h"):
            with self.subTest(invalid_lookback=invalid_lookback):
                output = render(
                    self.args.helm,
                    self.chart,
                    "--set-string",
                    f"kafkaObservability.storage.forecastLookback={invalid_lookback}",
                    expect_success=False,
                )
                self.assertIn("forecastLookback must be", output)

    def test_valid_compound_forecast_lookback_renders(self):
        for valid_lookback in ("1h30m", "2w3d4h5m6s7ms"):
            with self.subTest(valid_lookback=valid_lookback):
                render(
                    self.args.helm,
                    self.chart,
                    "--set-string",
                    f"kafkaObservability.storage.forecastLookback={valid_lookback}",
                )


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("chart")
    parser.add_argument("--helm", default="helm")
    ARGS, remaining = parser.parse_known_args()
    unittest.main(argv=[sys.argv[0], *remaining], verbosity=2)
