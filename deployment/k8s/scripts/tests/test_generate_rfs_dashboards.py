import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest


GENERATOR = Path(os.environ.get(
    "RFS_DASHBOARD_GENERATOR",
    Path(__file__).resolve().parents[1] / "generate-rfs-dashboards.py",
))
K8S_DIRECTORY = Path(__file__).resolve().parents[2]
CHART_DIRECTORY = K8S_DIRECTORY / "charts/aggregates/migrationAssistantWithArgo"
COMMITTED_CLOUDWATCH_DASHBOARD = CHART_DIRECTORY.joinpath(
    "files/cloudwatch-dashboards/reindex-from-snapshot-dashboard.json"
)
COMMITTED_GRAFANA_DASHBOARD = CHART_DIRECTORY.joinpath(
    "templates/resources/rfsGrafanaDashboard.yaml"
)
SPEC = importlib.util.spec_from_file_location("generate_rfs_dashboards", GENERATOR)
dashboard_generator = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(dashboard_generator)


def find_panels(items):
    for item in items:
        yield item
        yield from find_panels(item.get("panels", []))


class GeneratedDashboardTest(unittest.TestCase):
    def setUp(self):
        self.cloudwatch = dashboard_generator.cloudwatch()
        self.grafana = dashboard_generator.grafana()

    def test_committed_dashboards_match_generator_output(self):
        with tempfile.TemporaryDirectory() as output_directory:
            output_directory = Path(output_directory)
            generated_cloudwatch = output_directory / "cloudwatch.json"
            generated_grafana = output_directory / "grafana.yaml"
            subprocess.run(
                [
                    sys.executable,
                    str(GENERATOR),
                    str(generated_cloudwatch),
                    str(generated_grafana),
                ],
                check=True,
                capture_output=True,
                text=True,
            )

            for committed, generated in (
                (COMMITTED_CLOUDWATCH_DASHBOARD, generated_cloudwatch),
                (COMMITTED_GRAFANA_DASHBOARD, generated_grafana),
            ):
                with self.subTest(dashboard=committed.name):
                    self.assertEqual(
                        committed.read_bytes(),
                        generated.read_bytes(),
                        (
                            f"{committed} does not match the dashboard generator; "
                            "run deployment/k8s/scripts/generate-rfs-dashboards.py "
                            f"{COMMITTED_CLOUDWATCH_DASHBOARD} {COMMITTED_GRAFANA_DASHBOARD}"
                        ),
                    )

    def cloudwatch_widget(self, title):
        return next((w for w in self.cloudwatch["widgets"]
                     if w.get("properties", {}).get("title") == title), None)

    def grafana_panel(self, title):
        return next((p for p in find_panels(self.grafana["panels"])
                     if p.get("title") == title), None)

    def test_retry_budget_exhaustion_uses_right_axis(self):
        title = "Retry budget tokens and exhaustion"
        cloudwatch = self.cloudwatch_widget(title)
        self.assertIsNotNone(cloudwatch)
        self.assertEqual("per min", cloudwatch["properties"]["yAxis"]["right"]["label"])
        ran_out = next(row[-1] for row in cloudwatch["properties"]["metrics"]
                       if isinstance(row[-1], dict) and row[-1].get("label") == "Ran out per minute")
        self.assertEqual("right", ran_out["yAxis"])

        grafana = self.grafana_panel(title)
        self.assertIsNotNone(grafana)
        ran_out_target = next(t for t in grafana["targets"] if t["legendFormat"] == "Ran out per minute")
        override = next(o for o in grafana["fieldConfig"]["overrides"]
                        if o["matcher"]["options"] == ran_out_target["refId"])
        self.assertIn({"id": "custom.axisPlacement", "value": "right"}, override["properties"])

    def test_bulk_attempts_keep_first_and_retry_separate(self):
        title = "Target request detail: first vs retry by outcome"
        grafana = self.grafana_panel(title)
        self.assertIsNotNone(grafana)
        self.assertEqual(1, len(grafana["targets"]))
        target = grafana["targets"][0]
        self.assertIn("sum by (attempt,outcome)", target["expr"])
        self.assertEqual("{{attempt}} {{outcome}}", target["legendFormat"])

    def test_all_client_http_status_title_is_explicit(self):
        title = "Target latency and HTTP status codes (all clients)"
        cloudwatch = self.cloudwatch_widget(title)
        self.assertIsNotNone(cloudwatch)
        self.assertIsNone(self.grafana_panel(title))
        target_panel = next(panel for _, _, panels in dashboard_generator.SECTIONS for panel in panels
                            if panel.title == title)
        self.assertIn("inside an HTTP 200", target_panel.desc)

    def test_target_indexing_rate_is_the_fleet_total(self):
        cloudwatch = self.cloudwatch_widget("Target indexing rate and write queue")
        self.assertIsNotNone(cloudwatch)
        indexing_rate = next(
            row for row in cloudwatch["properties"]["metrics"]
            if len(row) > 1 and row[0] == "AWS/ES" and row[1] == "IndexingRate"
        )
        self.assertEqual("Sum", indexing_rate[-1]["stat"])

    def test_target_health_starts_with_rfs_observations_for_any_target(self):
        target_health = next(panels for section, _, panels in dashboard_generator.SECTIONS
                             if section == "Target health and response")
        self.assertEqual(
            ["Bulk request duration seen by RFS (s)", "Target-unavailable document attempts per minute"],
            [panel.title for panel in target_health if not panel.cw_only],
        )
        metrics = self.cloudwatch_widget("Target EBS throttling")["properties"]["metrics"]
        self.assertEqual([("AWS/ES", "ThroughputThrottle", "Maximum"), ("AWS/ES", "IopsThrottle", "Maximum")],
                         [(row[0], row[1], row[-1]["stat"]) for row in metrics])

    @unittest.skipUnless(shutil.which("helm"), "requires Helm to render the dashboard")
    def test_self_managed_target_keeps_all_rfs_widgets(self):
        rendered = subprocess.check_output([
            "helm", "template", "verify", str(CHART_DIRECTORY), "--kube-version", "1.35.0",
            "-f", str(CHART_DIRECTORY / "valuesEks.yaml"),
            "--set-string", "stageName=verify,aws.region=us-east-2,aws.account=123456789012,aws.eksClusterName=verify",
            "--set", "metrics.cloudWatch.showManagedTargetDomain=false",
            "--show-only", "templates/resources/aws/cloudwatchDashboardManifests.yaml",
        ], text=True)
        body = re.search(r'dashboardName: "[^"]*-ReindexFromSnapshot"\n\s+dashboardBody: (.+)', rendered)
        dashboard = json.loads(json.loads(body.group(1)))
        self.assertNotIn("TC_DOMAIN_NAME", {v["id"] for v in dashboard["variables"]})
        expected = [w["properties"] for w in self.cloudwatch["widgets"]
                    if not any(row[0] == "AWS/ES" for row in w["properties"].get("metrics", []))]
        self.assertEqual([p.get("title") or p["markdown"] for p in expected],
                         [w["properties"].get("title") or w["properties"]["markdown"] for w in dashboard["widgets"]])
        bottom = 0
        for widget in dashboard["widgets"]:
            self.assertLessEqual(widget["y"], bottom, "Removed domain widgets left an empty row")
            bottom = max(bottom, widget["y"] + widget["height"])

    def test_retry_limit_breakdown_aggregates_each_reason_under_a_short_label(self):
        title = "Why documents reached the retry limit (docs/min)"
        reasons = {"Response timed out": "no_response", "Repeated item error": "item_failures",
                   "Unexpected HTTP status": "unknown_failures"}
        _, expressions = self._cw_rows_and_expressions(title)
        cw = {row["label"]: row["expression"] for row in expressions if row["label"] in reasons}
        grafana = {t["legendFormat"]: t["expr"] for t in self.grafana_panel(title)["targets"]}
        self.assertEqual(reasons.keys(), grafana.keys())
        for label, reason in reasons.items():
            self.assertIn('SUM(SEARCH(', cw[label])
            for expr in (cw[label], grafana[label]):
                self.assertIn("bulkDocsGaveUp", expr)
                self.assertIn(f'reason="{reason}"', expr)

    def test_retry_wait_panels_explain_clean_runs(self):
        title = "Delay between retries for each batch (s)"
        self.assertIsNotNone(self.cloudwatch_widget(title))
        retry_gap = self.grafana_panel(title)
        self.assertIsNotNone(retry_gap)
        self.assertIn("not per connection", retry_gap["description"])
        self.assertIn("Observed retries/min", str(self.cloudwatch_widget(title)))

        slot_wait = self.grafana_panel("Retry delay breakdown (expert)")
        self.assertIsNotNone(slot_wait)
        self.assertIn("event means", slot_wait["description"])

    def test_grafana_deduplicates_collector_handoffs(self):
        expected = {
            "Source documents processed per second": "max by (exported_instance) (rate(",
            "Workers by phase": "max by (exported_instance) (",
            "Adaptive limit by worker": "max by (exported_instance) (",
            "Retry delay breakdown (expert)": "max by (exported_instance,errorClass) (rate(",
            "Bulk request duration seen by RFS (s)": (
                "max by (exported_instance,outcome,le) (last_over_time(bulkAttemptDuration_milliseconds_bucket"
            ),
            "Document and bulk request bytes (MiB/s)": "max by (exported_instance) (rate(",
            "Shards completed (cumulative)": "count(max by (exported_instance) (",
            "RFS CPU used vs allocated (cores)": "max by (pod) (rate(",
            "RFS memory used vs allocated (GiB)": "max by (pod) (",
        }
        for title, fragment in expected.items():
            panel = self.grafana_panel(title)
            self.assertIsNotNone(panel, title)
            expressions = "\n".join(target["expr"] for target in panel["targets"])
            self.assertIn(fragment, expressions, title)

    def test_operator_first_layout_and_worker_limit_distribution(self):
        self.assertEqual(
            [
                "Migration overview",
                "Throughput and worker resources",
                "Target health and response",
                "Retries and document failures",
                "Worker pipeline bottlenecks",
                "Flow-control internals",
            ],
            [section[0] for section in dashboard_generator.SECTIONS],
        )

        overview = dashboard_generator.SECTIONS[0][2]
        self.assertEqual(
            [
                "Source documents processed per second",
                "Workers by phase",
                "Target request outcomes (attempts/s)",
                "Failed documents per minute",
                "Workers by connection-limit utilization",
                "Adaptive-limit cuts per minute (fleet total)",
                "Source progress and target sends (docs/s)",
                "Requests in flight vs adaptive allowance",
                "Documents paused for target recovery",
            ],
            [panel.title for panel in overview],
        )
        self.assertEqual(
            ["Migration progress", "Worker capacity", "Target feedback"],
            dashboard_generator.SECTION_HEADERS["Migration overview"],
        )
        self.assertIn("bulkLimitMax", dashboard_generator.METRICS)
        self.assertIn("bulkWorkersByLimitUtilization", dashboard_generator.METRICS)

        phases = self.grafana_panel("Workers by phase")
        self.assertEqual(
            ["Starting", "Acquiring work", "Downloading shard", "Migrating", "Completing"],
            [target["legendFormat"] for target in phases["targets"]],
        )

        outcomes = self.grafana_panel("Target request outcomes (attempts/s)")
        self.assertEqual(
            [
                "Accepted first try",
                "Accepted after retry",
                "Response had item errors",
                "Target throttled",
                "No response",
                "Other errors",
            ],
            [target["legendFormat"] for target in outcomes["targets"]],
        )
        self.assertEqual("suffix: attempts/s", outcomes["fieldConfig"]["defaults"]["unit"])

        failures = self.grafana_panel("Failed documents per minute")
        self.assertEqual(
            ["Non-retryable error", "Retry limit reached"],
            [target["legendFormat"] for target in failures["targets"]],
        )

        cuts = self.grafana_panel("Adaptive-limit cuts per minute (fleet total)")
        self.assertEqual(
            ["HTTP 429 / request rejected", "Item-level admission rejection", "Response timeout"],
            [target["legendFormat"] for target in cuts["targets"]],
        )

        utilization = self.grafana_panel("Workers by connection-limit utilization")
        self.assertIn("number of workers", utilization["description"])
        self.assertIn("new process", utilization["description"])
        self.assertIn("downloading", utilization["description"])
        self.assertIn("without limit cuts", utilization["description"])
        self.assertEqual(
            ["0–24%", "25–49%", "50–74%", "75–99%", "100%"],
            [target["legendFormat"] for target in utilization["targets"]],
        )

    def test_cloudwatch_worker_phase_stack_has_one_labeled_series_per_phase(self):
        phases = self.cloudwatch_widget("Workers by phase")
        rows = [
            row for row in phases["properties"]["metrics"]
            if len(row) == 1 and row[0].get("visible", True)
            if row[0].get("label") not in ("Fleet idle", "Zero reported samples")
        ]
        self.assertEqual(
            ["Starting", "Acquiring work", "Downloading shard", "Migrating", "Completing"],
            [row[0]["label"] for row in rows],
        )
        self.assertTrue(all(row[0]["expression"].startswith("SUM(SEARCH(") for row in rows))

    def test_observed_run_labels_explain_units_and_target_feedback(self):
        cuts = self.grafana_panel("Adaptive-limit cuts per minute (fleet total)")
        self.assertEqual(
            ["HTTP 429 / request rejected", "Item-level admission rejection", "Response timeout"],
            [target["legendFormat"] for target in cuts["targets"]],
        )
        self.assertIn("request-level admission control", cuts["description"])

        byte_volume = self.grafana_panel("Document and bulk request bytes (MiB/s)")
        self.assertIsNotNone(byte_volume)
        self.assertIn("Three different byte boundaries", byte_volume["description"])
        self.assertIn("optional compression", byte_volume["description"])

        unavailable = self.grafana_panel("Target-unavailable document attempts per minute")
        self.assertIsNotNone(unavailable)
        self.assertIn("does not include HTTP 429", unavailable["description"])

        self.assertEqual(
            "docs / KiB",
            self.cloudwatch_widget("Bulk request size")["properties"]["yAxis"]["left"]["label"],
        )
        self.assertEqual(
            "cores",
            self.cloudwatch_widget("RFS CPU used vs allocated (cores)")["properties"]["yAxis"]["left"]["label"],
        )
        self.assertEqual(
            "GiB",
            self.cloudwatch_widget("RFS memory used vs allocated (GiB)")["properties"]["yAxis"]["left"]["label"],
        )

    def test_limit_cuts_explain_fleet_wide_steady_sawtooth(self):
        cuts = self.grafana_panel("Adaptive-limit cuts per minute (fleet total)")
        self.assertIsNotNone(cuts)
        if cuts is not None:
            self.assertIn("fleet-wide event count", cuts["description"])
            self.assertIn("need not decay to zero", cuts["description"])

    def test_cloudwatch_axes_use_operator_facing_units(self):
        expected = {
            "Target searchable documents (thousands) and used space (GB)": "thousands of docs",
            "Retry budget tokens and exhaustion": "tokens",
            "Adaptive limit state": "connections",
            "Limit growth and source bottlenecks": "events (see legend)",
        }
        for title, label in expected.items():
            self.assertEqual(
                label,
                self.cloudwatch_widget(title)["properties"]["yAxis"]["left"]["label"],
                title,
            )

    def test_pipeline_diagnostics_expose_boundaries_and_hidden_client_timings(self):
        stages = self.grafana_panel("Documents per second through pipeline stages")
        self.assertIsNotNone(stages)
        self.assertEqual(
            [
                "Source",
                "Prepared",
                "Ordered prep",
                "Packed",
                "Request started",
                "Request finished",
                "Checkpointed",
            ],
            [target["legendFormat"] for target in stages["targets"]],
        )
        stage_expressions = "\n".join(target["expr"] for target in stages["targets"])
        for stage in (
            "source",
            "prepared",
            "ordered_prepared",
            "packed",
            "request_started",
            "request_completed",
            "checkpointed",
        ):
            self.assertIn(f'stage="{stage}"', stage_expressions)

        buffered = self.grafana_panel("Documents buffered between stages")
        self.assertIsNotNone(buffered)
        self.assertEqual(
            ["Waiting for preparation", "Blocked at ordered prep fan-in", "Packed and request-ready"],
            [target["legendFormat"] for target in buffered["targets"]],
        )

        client = self.grafana_panel("Client preparation and response handling (ms)")
        self.assertIsNotNone(client)
        self.assertIn("process-wide", client["description"])
        self.assertIn("not per-request or per-connection latency", client["description"])
        expressions = "\n".join(target["expr"] for target in client["targets"])
        for metric in (
            "bulkBatchPreparationDuration",
            "bulkRequestAssemblyDuration",
            "bulkRequestCompressionDuration",
            "bulkResponseClassificationDuration",
        ):
            self.assertIn(metric, expressions)
        self.assertEqual(
            ["Classify response", "Classify no response"],
            [
                target["legendFormat"]
                for target in client["targets"]
                if "bulkResponseClassificationDuration" in target["expr"]
            ],
        )

        config = self.grafana_panel("Effective pipeline configuration")
        self.assertIsNotNone(config)
        config_expressions = "\n".join(target["expr"] for target in config["targets"])
        for metric in (
            "pipelineMaxBatchDocuments",
            "pipelineMaxBatchBytes",
            "pipelineBatchConcurrency",
            "pipelinePreparationConcurrency",
            "pipelinePreparationChunkDocuments",
        ):
            self.assertIn(metric, config_expressions)

    def test_cpu_throttling_is_available_in_both_dashboards(self):
        title = "RFS CPU throttled periods (%)"
        cloudwatch = self.cloudwatch_widget(title)
        self.assertIsNotNone(cloudwatch)
        cloudwatch_rows = json.dumps(cloudwatch["properties"]["metrics"])
        self.assertIn("container_cpu_cfs_throttled_periods_total_sum", cloudwatch_rows)
        self.assertIn("container_cpu_cfs_periods_total_sum", cloudwatch_rows)

        grafana = self.grafana_panel(title)
        self.assertIsNotNone(grafana)
        self.assertIn("container_cpu_cfs_throttled_periods_total", grafana["targets"][0]["expr"])
        self.assertIn("container_cpu_cfs_periods_total", grafana["targets"][0]["expr"])

    def test_prometheus_bulk_gauges_ignore_completed_worker_processes(self):
        for metric in (
            "bulkDocsWaitingOnTarget",
            "bulkOldestOpenBatchAge",
            "retryBudgetTokens",
            "bulkLimit",
            "bulkLimitMax",
            "bulkLimitThreshold",
            "bulkInFlight",
            "bulkSlotWaiters",
        ):
            expression = dashboard_generator.Gauge(metric, "test").prom()[0][0]
            self.assertIn("rfsWorkerPhase_count", expression, metric)
            self.assertIn('phase="exited"', expression, metric)
            self.assertIn("== 0", expression, metric)
            self.assertIn("and on (exported_instance)", expression, metric)

        for expression, _ in dashboard_generator.LimitUtilizationBands().prom():
            self.assertIn("rfsWorkerPhase_count", expression)
            self.assertIn('phase="exited"', expression)
            self.assertIn("== 0", expression)
            self.assertIn("and on (exported_instance)", expression)

    def test_each_dashboard_omits_panels_available_only_in_the_other_backend(self):
        cloudwatch_markdown = "\n".join(
            widget.get("properties", {}).get("markdown", "")
            for widget in self.cloudwatch["widgets"]
        )
        cloudwatch_titles = {
            widget.get("properties", {}).get("title")
            for widget in self.cloudwatch["widgets"]
        }
        grafana_panels = list(find_panels(self.grafana["panels"]))
        grafana_titles = {panel.get("title") for panel in grafana_panels}
        grafana_text = "\n".join(
            panel.get("options", {}).get("content", "")
            for panel in grafana_panels
            if panel.get("type") == "text"
        )

        target_titles = {
            "Target EBS throttling",
            "Target indexing rate and write queue",
            "Target CPU and JVM pressure (%)",
            "Target latency and HTTP status codes (all clients)",
            "Target searchable documents (thousands) and used space (GB)",
        }
        violations = []
        if "Grafana only:" in cloudwatch_markdown:
            violations.append("CloudWatch contains a Grafana-only placeholder")
        if "CloudWatch only:" in grafana_text:
            violations.append("Grafana contains CloudWatch-only placeholders")
        if "Adaptive limit by worker" not in grafana_titles:
            violations.append("Grafana is missing its native per-worker adaptive-limit panel")
        if "Adaptive limit by worker" in cloudwatch_titles or "Adaptive limit by worker" in cloudwatch_markdown:
            violations.append("CloudWatch contains the Grafana-native per-worker adaptive-limit panel")
        if not target_titles.issubset(cloudwatch_titles):
            violations.append("CloudWatch is missing native target-domain panels")
        if target_titles & grafana_titles:
            violations.append("Grafana contains CloudWatch-native target-domain panels")
        target_section = "Target health and response"
        if f"## {target_section}" not in cloudwatch_markdown or target_section not in grafana_titles:
            violations.append("Target health and response must be visible in both dashboards")

        self.assertEqual([], violations)

    def test_throughput_panels_align_resources_and_explain_byte_volume(self):
        overview = dashboard_generator.SECTIONS[0][2]
        self.assertEqual(
            [
                "Source progress and target sends (docs/s)",
                "Requests in flight vs adaptive allowance",
                "Documents paused for target recovery",
            ],
            [panel.title for panel in overview[-3:]],
        )

        throughput = next(
            panels for title, _, panels in dashboard_generator.SECTIONS
            if title == "Throughput and worker resources"
        )
        self.assertEqual(
            [
                "Document and bulk request bytes (MiB/s)",
                "RFS CPU used vs allocated (cores)",
                "Bulk request size",
                "Shards completed (cumulative)",
                "RFS memory used vs allocated (GiB)",
                "Workers by memory tier",
            ],
            [panel.title for panel in throughput],
        )

        byte_title = "Document and bulk request bytes (MiB/s)"
        self.assertIsNone(self.cloudwatch_widget("Migrated vs sent (MiB/s)"))
        self.assertIsNone(self.grafana_panel("Wire bytes as % of uncompressed request"))
        byte_targets = {
            target["legendFormat"]: target["expr"]
            for target in self.grafana_panel(byte_title)["targets"]
        }
        self.assertEqual(
            {
                "Processed source bytes",
                "Uncompressed bulk NDJSON",
                "Bulk request body sent",
            },
            set(byte_targets),
        )
        self.assertIn("pipelineBytesMigrated", byte_targets["Processed source bytes"])
        self.assertIn("bulkRequestBytes_bytes_sum", byte_targets["Uncompressed bulk NDJSON"])
        self.assertIn("bulkRequestSentBytes_bytes_sum", byte_targets["Bulk request body sent"])

        shard_title = "Shards completed (cumulative)"
        cloudwatch_shards = self.cloudwatch_widget(shard_title)
        self.assertTrue(any(
            row[0].get("expression", "").startswith("RUNNING_SUM(FILL(")
            for row in cloudwatch_shards["properties"]["metrics"]
            if len(row) == 1 and isinstance(row[0], dict)
        ))
        grafana_shards = self.grafana_panel(shard_title)
        self.assertEqual(
            [{
                "id": "calculateField",
                "options": {
                    "mode": "cumulativeFunctions",
                    "cumulative": {"reducer": "sum"},
                    "replaceFields": True,
                    "alias": "Shards completed",
                },
            }],
            grafana_shards["transformations"],
        )

        requests = self.grafana_panel("Requests in flight vs adaptive allowance")
        cpu = self.grafana_panel("RFS CPU used vs allocated (cores)")
        memory = self.grafana_panel("RFS memory used vs allocated (GiB)")
        self.assertEqual(8, requests["gridPos"]["x"])
        self.assertEqual(8, cpu["gridPos"]["x"])
        self.assertEqual(8, memory["gridPos"]["x"])
        self.assertLess(requests["gridPos"]["y"], cpu["gridPos"]["y"])
        self.assertLess(cpu["gridPos"]["y"], memory["gridPos"]["y"])

    def test_cloudwatch_dashboard_is_global_with_no_session_or_qualifier_inputs(self):
        # The session input proved unusable (nobody remembers a session id, and a stage name does
        # not match pod names), so the dashboard reads globally. Emission stays partitioned: the
        # qualifier/pod dimensions remain in the SEARCH schemas for future per-resource dashboards.
        variable_ids = {variable["id"] for variable in self.cloudwatch["variables"]}
        self.assertNotIn("RFS_SESSION", variable_ids)
        self.assertNotIn("MA_QUALIFIER", variable_ids)
        dashboard_text = json.dumps(self.cloudwatch)
        self.assertNotIn("RFS_SESSION", dashboard_text)
        self.assertNotIn("MA_QUALIFIER", dashboard_text)

        for title in (
            "RFS CPU used vs allocated (cores)",
            "RFS memory used vs allocated (GiB)",
            "RFS CPU throttled periods (%)",
        ):
            widget = self.cloudwatch_widget(title)
            expressions = "\n".join(
                row[0].get("expression", "")
                for row in widget["properties"]["metrics"]
                if len(row) == 1 and isinstance(row[0], dict)
            )
            self.assertNotRegex(expressions, r"SEARCH\('[^}]*,pod[,}]")

    def test_cloudwatch_item_errors_use_static_labels_and_a_traffic_backed_zero(self):
        item_errors = self.cloudwatch_widget("Item errors returned by the target (errors/s)")
        expressions = [
            row[0]
            for row in item_errors["properties"]["metrics"]
            if len(row) == 1 and isinstance(row[0], dict) and row[0].get("visible", True)
            if row[0].get("label") not in ("Fleet idle", "No reported bulk attempts")
        ]
        self.assertEqual(["Retryable", "Non-retryable"], [expression["label"] for expression in expressions])
        self.assertNotIn("${PROP(", str(item_errors))
        for expression in expressions:
            self.assertIn(
                'SUM(SEARCH(\'{OpenSearchMigrations,OTelLib,errorType,retryable,qualifier}',
                expression["expression"],
            )
            self.assertIn(
                '0 * SUM(SEARCH(\'{OpenSearchMigrations,OTelLib,attempt,outcome,qualifier}',
                expression["expression"],
            )
            self.assertIn('MetricName="bulkAttempts"', expression["expression"])

    def test_grafana_uses_single_export_request_counts_and_observed_traffic_for_healthy_zeros(self):
        title = "Item errors returned by the target (errors/s)"
        for target in self.grafana_panel(title)["targets"]:
            expr = target["expr"]
            self.assertIn("last_over_time(bulkItemErrors_count_total", expr)
            self.assertIn("offset $__rate_interval", expr)
            self.assertIn("or on() label_replace(0 * (sum(", expr)
            self.assertIn("last_over_time(bulkAttempts_count_total", expr)
            self.assertNotIn("or vector(0)", expr)

    def test_complex_bulk_panels_use_independent_request_activity(self):
        for title in ("Item errors returned by the target (errors/s)", "Client preparation and response handling (ms)"):
            _, expressions = self._cw_rows_and_expressions(title)
            status = next(row for row in expressions if row["label"] == "No reported bulk attempts")
            self.assertIn('MetricName="bulkAttempts"', status["expression"])
            self.assertNotIn("DATAPOINT_COUNT", status["expression"])

    def test_documents_sent_panel_uses_request_document_totals(self):
        title = "Source progress and target sends (docs/s)"
        cloudwatch = self.cloudwatch_widget(title)
        self.assertIsNotNone(cloudwatch)
        # Attribute-less metrics render as qualifier-agnostic SEARCH expressions now (global view).
        request_documents = next(
            row[0]["expression"] for row in cloudwatch["properties"]["metrics"]
            if len(row) == 1 and isinstance(row[0], dict) and
            'MetricName="bulkRequestDocuments"' in row[0].get("expression", "")
        )
        self.assertIn("'Sum'", request_documents)
        self.assertTrue(request_documents.startswith("SUM(SEARCH("), request_documents)

        grafana = self.grafana_panel(title)
        self.assertIsNotNone(grafana)
        targets = {target["legendFormat"]: target["expr"] for target in grafana["targets"]}
        self.assertEqual({"Sent", "Source processed", "Re-sent"}, set(targets))
        self.assertIn("bulkRequestDocuments_count_sum", targets["Sent"])

    def test_only_the_worker_chart_exposes_memory_tier_scaling(self):
        for title in ("Work items moved to a higher tier per minute", "Unfinished work by required memory tier"):
            self.assertIsNone(self.cloudwatch_widget(title))
            self.assertIsNone(self.grafana_panel(title))
        worker = "Workers by memory tier"
        memory = "RFS memory used vs allocated (GiB)"
        for workers, usage in ((self.cloudwatch_widget(worker), self.cloudwatch_widget(memory)),
                               (self.grafana_panel(worker)["gridPos"], self.grafana_panel(memory)["gridPos"])):
            self.assertEqual(workers["y"], usage["y"])
            self.assertGreater(workers["x"], usage["x"])

    def _cw_rows_and_expressions(self, title):
        metrics = self.cloudwatch_widget(title)["properties"]["metrics"]
        rows = {row[-1]["id"]: row for row in metrics if len(row) > 1}
        expressions = [row[0] for row in metrics if len(row) == 1 and isinstance(row[0], dict)]
        return rows, expressions

    def test_resource_stacks_show_measured_usage_and_one_band_to_the_limit(self):
        for title, unit in (("RFS CPU used vs allocated (cores)", "cores"),
                            ("RFS memory used vs allocated (GiB)", "GiB")):
            widget = self.cloudwatch_widget(title)
            self.assertTrue(widget["properties"]["stacked"], title)
            labels = [row[-1]["label"] for row in widget["properties"]["metrics"]
                      if row[-1].get("visible", True)]
            self.assertEqual({"tier ${PROP('Dim.tier')}"}, set(labels[:-1]), title)
            self.assertEqual("Limit above measured usage", labels[-1], title)
            rows, expressions = self._cw_rows_and_expressions(title)
            unused, awaiting = [next(key for key, row in rows.items() if row[1].endswith(suffix))
                                for suffix in ("Unused", "AwaitingSample")]
            self.assertIn(f"DATAPOINT_COUNT([{unused},{awaiting}]) == 2", expressions[-1]["expression"])
            self.assertIn(f"({unused} + {awaiting})", expressions[-1]["expression"])
            grafana = self.grafana_panel(title)
            self.assertEqual(["tier {{tier}} used", "Limit above measured usage"],
                             [target["legendFormat"] for target in grafana["targets"]], title)
            self.assertEqual(f"suffix: {unit}", grafana["fieldConfig"]["defaults"]["unit"], title)
            self.assertIn("sum by (tier)", grafana["targets"][0]["expr"])
            self.assertIn("or on (pod)", grafana["targets"][-1]["expr"])
            self.assertNotIn("kube_pod_container_info", str(widget))

    def test_cloudwatch_stacked_legends_show_observed_tiers_in_ascending_order(self):
        for title in ("Workers by memory tier", "RFS CPU used vs allocated (cores)",
                      "RFS memory used vs allocated (GiB)"):
            _, expressions = self._cw_rows_and_expressions(title)
            tier_lines = [e["expression"] for e in expressions if e["label"] == "tier ${PROP('Dim.tier')}"]
            overflow, *ordered = tier_lines
            self.assertIn(" NOT (", overflow, title)
            tiers = re.findall(r'tier="(\d+)"', overflow)
            self.assertEqual([str(tier) for tier in range(10)], tiers, title)
            self.assertEqual([[tier] for tier in tiers],
                             [re.findall(r'tier="(\d+)"', expr) for expr in reversed(ordered)], title)

    def test_resource_charts_use_matched_samples_without_dividing_by_coverage(self):
        for resource, name, unit in [("CPU", "Cpu", "cores"), ("memory", "Memory", "GiB")]:
            title = f"RFS {resource} used vs allocated ({unit})"
            rows, expressions = self._cw_rows_and_expressions(title)
            used = expressions[0]["expression"]
            self.assertIn(f'MetricName="rfsFleet{name}Used"', used)
            self.assertEqual({"rfsFleet" + name + suffix for suffix in ["AwaitingSample", "Unused"]},
                             {r[1] for r in rows.values()} - {"rfsFleetCensusSamples"})
            self.assertNotRegex(used, r"/ [em]\d+")
            self.assertIn("FILL(", used)
            self.assertIn(" > 0, 0)", used)
            self.assertIn("kube_pod_container_status_running", self.grafana_panel(title)["targets"][-1]["expr"])

    def test_hottest_pod_is_paired_with_its_own_running_limit_in_grafana(self):
        for resource in ["CPU", "memory"]:
            title = f"Hottest RFS pod by {resource} limit"
            self.assertIsNone(self.cloudwatch_widget(title))
            target = self.grafana_panel(title)["targets"][0]
            for term in ("topk(1,", "/ on (pod)", "kube_pod_container_resource_limits",
                         "kube_pod_container_status_running"):
                self.assertIn(term, target["expr"])
            self.assertEqual("{{pod}}", target["legendFormat"])

    def test_dashboard_pod_pattern_matches_the_collectors_tier_label(self):
        values = (CHART_DIRECTORY / "valuesEks.yaml").read_text()
        patterns = set(re.findall(r"regex: '(\.\*-rfs-t\(\[0-9\]\+\)[^']*)'", values))
        self.assertEqual({dashboard_generator.TIER_POD_PATTERN}, patterns)
        name = "example-rfs-t12-7467b44f5f-ct9jm"
        self.assertEqual("12", re.fullmatch(dashboard_generator.TIER_POD_PATTERN, name).group(1))

    def test_fleet_census_has_one_writer_during_rollouts(self):
        template = (CHART_DIRECTORY / "templates/resources/otel/rfsFleetResources.yaml").read_text()
        self.assertIn("replicas: 1", template)
        self.assertIn("type: Recreate", template)
        self.assertNotIn("prometheus/ksm-cloudwatch", (CHART_DIRECTORY / "valuesEks.yaml").read_text())


if __name__ == "__main__":
    unittest.main()
