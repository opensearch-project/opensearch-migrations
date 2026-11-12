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


def literal_blocks(text, key):
    lines = text.splitlines()
    blocks = []
    marker = re.compile(r"^(\s*)" + re.escape(key) + r":\s*\|[-+]?\s*$")
    for index, line in enumerate(lines):
        match = marker.match(line)
        if not match:
            continue
        marker_indent = len(match.group(1))
        content_indent = None
        content = []
        for item in lines[index + 1:]:
            if item.strip():
                indentation = len(item) - len(item.lstrip(" "))
                if indentation <= marker_indent:
                    break
                content_indent = content_indent if content_indent is not None else indentation
                item = item[content_indent:]
            else:
                item = ""
            content.append(item)
        blocks.append("\n".join(content).rstrip())
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


def joined_kafka_pvcs(volume_pvcs, pvc_labels):
    """Model the on(namespace,persistentvolumeclaim) PromQL label join."""
    return {
        pvc
        for pvc in volume_pvcs
        if pvc_labels.get(pvc, {}).get("strimzi.io/cluster")
        and pvc_labels.get(pvc, {}).get("strimzi.io/pool-name")
    }


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

    def test_single_volume_broker_pvc_is_selected(self):
        labels = {
            "data-capture-dual-role-0": {
                "strimzi.io/cluster": "capture",
                "strimzi.io/pool-name": "dual-role",
            },
        }
        self.assertEqual(joined_kafka_pvcs(labels, labels), {"data-capture-dual-role-0"})

    def test_multiple_jbod_volumes_are_selected(self):
        pvcs = {"data-0-capture-dual-role-0", "data-1-capture-dual-role-0"}
        labels = {
            pvc: {"strimzi.io/cluster": "capture", "strimzi.io/pool-name": "dual-role"}
            for pvc in pvcs
        }
        self.assertEqual(joined_kafka_pvcs(pvcs, labels), pvcs)

    def test_multiple_brokers_are_selected(self):
        pvcs = {"data-capture-dual-role-0", "data-capture-dual-role-1", "data-capture-dual-role-2"}
        labels = {
            pvc: {"strimzi.io/cluster": "capture", "strimzi.io/pool-name": "dual-role"}
            for pvc in pvcs
        }
        self.assertEqual(joined_kafka_pvcs(pvcs, labels), pvcs)

    def test_unrelated_data_pvc_is_excluded(self):
        pvcs = {"data-capture-dual-role-0", "data-postgresql-0"}
        labels = {
            "data-capture-dual-role-0": {
                "strimzi.io/cluster": "capture",
                "strimzi.io/pool-name": "dual-role",
            },
            "data-postgresql-0": {"app": "postgresql"},
        }
        self.assertEqual(joined_kafka_pvcs(pvcs, labels), {"data-capture-dual-role-0"})

    def test_controller_only_scope_is_documented_as_inapplicable(self):
        guide = (self.repo / "deployment/k8s/dashboards/KAFKA_OBSERVABILITY.md").read_text()
        self.assertIn("controller-only", guide)
        self.assertIn("dual-role", guide)

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

    def test_alert_rules_require_both_enablement_gates(self):
        alert_only = render(
            self.args.helm,
            self.chart,
            "--set",
            "kafkaObservability.prometheusAlerts.enabled=true",
        )
        alertmanager_only = render(
            self.args.helm,
            self.chart,
            "--set",
            "charts.kube-prometheus-stack.values.alertmanager.enabled=true",
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

    def test_alert_rules_render_policy_and_promql(self):
        rendered = render(
            self.args.helm,
            self.chart,
            "--set",
            "charts.kube-prometheus-stack.values.alertmanager.enabled=true",
            "--set",
            "kafkaObservability.prometheusAlerts.enabled=true",
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
        self.assertIn("SUM(FILL(SEARCH(", fleet_expression)
        self.assertIn("FILL(SEARCH(", fleet_expression)
        self.assertIn(", 300)", fleet_expression)
        self.assertIn("kubelet_volume_stats_available_bytes", fleet_expression)
        self.assertIn("kubelet_volume_stats_capacity_bytes", fleet_expression)
        self.assertNotIn("kubelet_volume_stats_used_bytes", fleet_expression)

        free = widgets["Kafka free space by broker volume (GiB)"]
        free_expression = free["properties"]["metrics"][0][0]["expression"]
        self.assertIn("kubelet_volume_stats_available_bytes", free_expression)
        self.assertIn("FILL(SEARCH(", free_expression)
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
        self.assertTrue(any("SUM(FILL(SEARCH(" in expression for expression in storage_expressions))
        self.assertTrue(any("FILL(SEARCH(" in expression for expression in storage_expressions))
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
        self.assertIn("SUM(FILL(SEARCH(", inode_expression["expression"])
        self.assertEqual(inode_expression["label"], "Fleet inode utilization")

        health = widgets["Kafka abnormal volume health"]
        health_expression = health["properties"]["metrics"][0][0]
        self.assertIn("kubelet_volume_stats_health_status_abnormal", health_expression["expression"])
        self.assertNotIn("label", health_expression)

        for title in (
            "Kafka replication and ISR health",
            "Kafka controller, quorum, and uptime",
            "Kafka produce/fetch errors and throttling",
            "Kafka request-handler saturation",
            "Kafka JVM heap and process CPU",
            "Kafka JVM garbage collection",
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
        handlers = widgets["Kafka request-handler saturation"]["properties"]["metrics"]
        self.assertTrue(all("/ 2)" in row[0]["expression"] for row in handlers))
        gc_rows = widgets["Kafka JVM garbage collection"]["properties"]["metrics"]
        self.assertEqual(len(gc_rows), 6)
        self.assertNotIn(
            "${PROP('Dim.collector')}",
            "\n".join(row[0]["label"] for row in gc_rows),
        )
        self.assertTrue(all("collector=" in row[0]["expression"] for row in gc_rows))
        for title in (
            "Kafka produce/fetch errors and throttling",
            "Kafka JVM garbage collection",
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

    def test_cluster_name_is_propagated_by_both_deployers(self):
        bootstrap = (self.repo / "deployment/k8s/aws/aws-bootstrap.sh").read_text()
        rust = (self.repo / "deployment/k8s/aws/cli/src/command/app.rs").read_text()
        self.assertIn('--set aws.eksClusterName="${MIGRATIONS_EKS_CLUSTER_NAME}"', bootstrap)
        self.assertIn("aws.eksClusterName={eks_cluster_name}", rust)

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
        setup_kafka = (
            self.repo
            / "orchestrationSpecs/packages/migration-workflow-templates/src/workflowTemplates/setupKafka.ts"
        ).read_text()
        self.assertIn('type: "jmxPrometheusExporter"', setup_kafka)
        self.assertIn("configMapKeyRef: expr.makeDict", setup_kafka)
        self.assertIn('name: KAFKA_JMX_METRICS_CONFIG_MAP', setup_kafka)

    def test_broker_alerts_have_an_independent_enablement_gate(self):
        storage_only = render(
            self.args.helm,
            self.chart,
            "--set",
            "charts.kube-prometheus-stack.values.alertmanager.enabled=true",
            "--set",
            "kafkaObservability.prometheusAlerts.enabled=true",
        )
        self.assertNotIn("MigrationAssistantKafkaUnderReplicatedPartitions", storage_only)
        enabled = render(
            self.args.helm,
            self.chart,
            "--set",
            "charts.kube-prometheus-stack.values.alertmanager.enabled=true",
            "--set",
            "kafkaObservability.prometheusAlerts.enabled=true",
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


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("chart")
    parser.add_argument("--helm", default="helm")
    ARGS, remaining = parser.parse_known_args()
    unittest.main(argv=[sys.argv[0], *remaining], verbosity=2)
