"""Generate both Reindex-from-Snapshot dashboards from one list of panels.

usage: generate-rfs-dashboards.py <cloudwatch.json> <grafanaDashboard.yaml>

Writes the chart's files/cloudwatch-dashboards/reindex-from-snapshot-dashboard.json and
templates/resources/rfsGrafanaDashboard.yaml. Panels, titles, descriptions and layout are shared; edit the
PANELS below and regenerate rather than hand-editing either output.

How each kind of series is computed (backend semantics as in generate-capture-replay-dashboard.py):
  * rate(counter): Prometheus sum(max by logical process/dimensions (rate(...))); CloudWatch delta Sum / PERIOD.
    The Prometheus max removes duplicate series during collector handoffs before taking the fleet total.
  * gauge fleet / hottest / lowest: Prometheus sum / max / min over processes; CloudWatch Sum / PERIOD (processes
    export every second), Maximum, Minimum. Prometheus first collapses collector copies by service.instance.id.
    CloudWatch has no per-process series (service.instance.id is dropped).
  * mean(histogram): Prometheus rate(_sum) / rate(_count); CloudWatch Sum / SampleCount. Percentiles exist only in
    Grafana: CloudWatch histograms arrive with Min = Max = 0.
  * Metrics with attributes are queried in CloudWatch with SEARCH over their exact dimension set, since a plain
    metric row matches one dimension set only.
"""
import json
import re
import sys

NS = "OpenSearchMigrations"
Q = "MA_QUALIFIER"
R = "REGION"
RI = "$__rate_interval"
MIB = 1048576
RFS = "documentMigration"
WC = "workCoordination"
CONTAINER = "bulk-loader"
FLEET_CAPACITY_LABEL = "% (fleet capacity)"
DOCS_PER_SECOND_UNIT = "docs/s"
ATTEMPTS_PER_SECOND_UNIT = "attempts/s"
PER_MINUTE_UNIT = "per min"
MIB_PER_SECOND_UNIT = "MiB/s"

# name -> (OTel unit as Prometheus spells it, kind, scope, attributes)
METRICS = {
    "pipelineDocsMigrated": ("count", "counter", RFS, ()),
    "pipelineBytesMigrated": ("bytes", "counter", RFS, ()),
    "pipelineShardDuration": ("milliseconds", "histogram", RFS, ()),
    "bytesSent": ("count", "counter", RFS, ()),
    "bulkAttempts": ("count", "counter", RFS, ("attempt", "outcome")),
    "bulkAttemptDuration": ("milliseconds", "histogram", RFS, ("outcome",)),
    "bulkRequestDocuments": ("count", "histogram", RFS, ()),
    "bulkRequestBytes": ("bytes", "histogram", RFS, ()),
    "bulkRequestSentBytes": ("bytes", "histogram", RFS, ()),
    "bulkBatchPreparationDuration": ("milliseconds", "histogram", RFS, ("path",)),
    "bulkRequestAssemblyDuration": ("milliseconds", "histogram", RFS, ("attempt",)),
    "bulkRequestCompressionDuration": ("milliseconds", "histogram", RFS, ("attempt",)),
    "bulkRequestStartGap": ("milliseconds", "histogram", RFS, ("attempt",)),
    "bulkResponseClassificationDuration": ("milliseconds", "histogram", RFS, ("result",)),
    "pipelineStageDocuments": ("count", "counter", RFS, ("stage",)),
    "pipelineStageBytes": ("bytes", "counter", RFS, ("stage",)),
    "pipelinePreparationQueueWait": ("milliseconds", "histogram", RFS, ()),
    "pipelinePreparationDuration": ("milliseconds", "histogram", RFS, ("outcome",)),
    "pipelinePreparationOrderedWait": ("milliseconds", "histogram", RFS, ()),
    "pipelinePackingDuration": ("milliseconds", "histogram", RFS, ()),
    "pipelineRequestQueueWait": ("milliseconds", "histogram", RFS, ()),
    "pipelineRequestGroupDuration": ("milliseconds", "histogram", RFS, ("outcome",)),
    "pipelinePackedBatchDocuments": ("count", "histogram", RFS, ()),
    "pipelinePackedBatchBytes": ("bytes", "histogram", RFS, ()),
    "pipelinePackedBatchFill": ("percent", "histogram", RFS, ("limit",)),
    "pipelinePreparationQueuedChunks": ("count", "gauge", RFS, ()),
    "pipelinePreparationQueuedDocuments": ("count", "gauge", RFS, ()),
    "pipelinePreparationQueuedBytes": ("bytes", "gauge", RFS, ()),
    "pipelinePreparationActiveChunks": ("count", "gauge", RFS, ()),
    "pipelinePreparationOrderedChunks": ("count", "gauge", RFS, ()),
    "pipelinePreparationOrderedDocuments": ("count", "gauge", RFS, ()),
    "pipelinePreparationOrderedBytes": ("bytes", "gauge", RFS, ()),
    "pipelineRequestReadyBatches": ("count", "gauge", RFS, ()),
    "pipelineRequestReadyDocuments": ("count", "gauge", RFS, ()),
    "pipelineRequestReadyBytes": ("bytes", "gauge", RFS, ()),
    "pipelineRequestActiveGroups": ("count", "gauge", RFS, ()),
    "pipelineMaxBatchDocuments": ("count", "gauge", RFS, ()),
    "pipelineMaxBatchBytes": ("bytes", "gauge", RFS, ()),
    "pipelineBatchConcurrency": ("count", "gauge", RFS, ()),
    "pipelinePreparationConcurrency": ("count", "gauge", RFS, ()),
    "pipelinePreparationChunkDocuments": ("count", "gauge", RFS, ()),
    "bulkRetries": ("count", "counter", RFS, ("errorClass",)),
    "bulkRetryDelay": ("milliseconds", "histogram", RFS, ("errorClass",)),
    "bulkDocsGaveUp": ("count", "counter", RFS, ("reason",)),
    "bulkBatchSplits": ("count", "counter", RFS, ("reason",)),
    "bulkBatchAge": ("milliseconds", "histogram", RFS, ()),
    "bulkOldestOpenBatchAge": ("milliseconds", "gauge", RFS, ()),
    "bulkThrottleStreak": ("count", "histogram", RFS, ()),
    "bulkDocsWaitingOnTarget": ("count", "gauge", RFS, ()),
    "bulkDocsTargetUnavailable": ("count", "counter", RFS, ("reason",)),
    "bulkDocumentsFailed": ("count", "counter", RFS, ("failureClass",)),
    "rfsWorkerPhase": ("count", "gauge", RFS, ("phase",)),
    "bulkAttemptGap": ("milliseconds", "histogram", RFS, ()),
    "bulkAttemptsPerBatch": ("count", "histogram", RFS, ()),
    "bulkItemErrors": ("count", "counter", RFS, ("errorType", "retryable")),
    "bulkDocsResent": ("count", "counter", RFS, ()),
    "bulkLimit": ("count", "gauge", RFS, ()),
    "bulkLimitMax": ("count", "gauge", RFS, ()),
    "bulkWorkersByLimitUtilization": ("count", "gauge", RFS, ("range",)),
    "bulkLimitThreshold": ("count", "gauge", RFS, ()),
    "bulkInFlight": ("count", "gauge", RFS, ()),
    "bulkSlotWaiters": ("count", "gauge", RFS, ()),
    "bulkSlotWait": ("milliseconds", "histogram", RFS, ()),
    "bulkLimitCuts": ("count", "counter", RFS, ("signal",)),
    "bulkLimitQuietResets": ("count", "counter", RFS, ()),
    "bulkLimitGrowthSkipped": ("count", "counter", RFS, ()),
    "bulkTimeToFullLimit": ("milliseconds", "histogram", RFS, ()),
    "retryBudgetTokens": ("count", "gauge", RFS, ()),
    "retryBudgetSpent": ("count", "counter", RFS, ("errorClass",)),
    "retryBudgetRefunded": ("count", "counter", RFS, ()),
    "retryBudgetEmpty": ("count", "counter", RFS, ()),
    "retryBudgetWait": ("milliseconds", "histogram", RFS, ()),
    "nextWorkAssignedCount": ("", "counter", WC, ()),
    "noNextWorkAvailableCount": ("", "counter", WC, ()),
}


def prom_name(name, suffix=""):
    unit, kind, _, _ = METRICS[name]
    base = f"{name}_{unit}" if unit else name
    if kind == "counter":
        return f"{base}_total"
    return base + suffix


def sel(name, where=None):
    parts = [f'otel_scope_name="{METRICS[name][2]}"'] + [f'{k}="{v}"' for k, v in (where or {}).items()]
    return "{" + ",".join(parts) + "}"


def prom_dedup(name, suffix="", where=None, rate=False, offset=None):
    """Collapse copies of a logical process series left by Prometheus collector handoffs."""
    labels = ["exported_instance", *METRICS[name][3]]
    if suffix == "_bucket":
        labels.append("le")
    metric = f"{prom_name(name, suffix)}{sel(name, where)}"
    if offset:
        metric += f" offset {offset}"
    if rate:
        metric = f"rate({metric}[{RI}])"
    return f"max by ({','.join(labels)}) ({metric})"


def only_active_processes(expression):
    """Drop gauges from exited processes that remain queryable during Prometheus's lookback window."""
    exited = prom_dedup("rfsWorkerPhase", where={"phase": "exited"})
    return f"({expression}) and on (exported_instance) ({exited} == 0)"


# --- series --------------------------------------------------------------------------------------------------
# Each series renders to PromQL and to CloudWatch metric rows. A CloudWatch "widget" (W) collects rows and ids.

class W:
    def __init__(self):
        self.rows, self.n = [], 0

    def _id(self, p):
        self.n += 1
        return f"{p}{self.n}"

    def m(self, name, stat, period=None):
        i = self._id("m")
        lib = METRICS[name][2]
        opts = {"id": i, "stat": stat, "visible": False, "region": R}
        if period:
            opts["period"] = period
        self.rows.append([NS, name, "OTelLib", lib, "qualifier", Q, opts])
        return i

    def search(self, name, stat, where=None):
        _, _, lib, attrs = METRICS[name]
        dims = ",".join(["OTelLib", *sorted(attrs), "qualifier"])
        terms = "".join(f' {k}="{v}"' for k, v in (where or {}).items())
        return f"SEARCH('{{{NS},{dims}}} MetricName=\"{name}\" OTelLib=\"{lib}\" qualifier=\"{Q}\"{terms}', '{stat}', 60)"

    def e(self, expr, label, visible=True):
        i = self._id("e")
        self.rows.append([{"expression": expr, "label": label, "id": i, "visible": visible, "region": R}])
        return i


class Rate:
    """A counter's fleet total per second (per minute with per_min), optionally split by one or more attributes.
    With one by attribute, a label containing "{by}" is a legend template: "{by}" becomes its value."""

    def __init__(self, name, label, by=None, where=None, scale=1, per_min=False):
        self.name, self.label, self.by, self.where, self.scale, self.per_min = name, label, by, where, scale, per_min

    def _factor(self):
        return (60 if self.per_min else 1) / self.scale

    def _by_fields(self):
        if not self.by:
            return ()
        return (self.by,) if isinstance(self.by, str) else tuple(self.by)

    def prom(self):
        by_fields = self._by_fields()
        by = f" by ({','.join(by_fields)})" if by_fields else ""
        # A histogram's rate is its count's: observations per second.
        suffix = "_count" if METRICS[self.name][1] == "histogram" else ""
        e = f"sum{by}({prom_dedup(self.name, suffix, self.where, rate=True)})"
        f = self._factor()
        e = e if f == 1 else f"{e} * {f:g}"
        if by_fields:
            if len(by_fields) == 1 and "{by}" in self.label:
                legend = self.label.replace("{by}", f"{{{{{by_fields[0]}}}}}")
            else:
                legend = " ".join(f"{{{{{field}}}}}" for field in by_fields)
            return [(e, legend)]
        return [(f"({e}) or vector(0)", self.label)]

    def cw(self, w):
        f = self._factor()
        scale = "" if f == 1 else f" * {f:g}"
        if METRICS[self.name][3]:
            q = w.search(self.name, "Sum", self.where)
            by_fields = self._by_fields()
            if by_fields:
                if len(by_fields) == 1 and "{by}" in self.label:
                    label = self.label.replace("{by}", f"${{PROP('Dim.{by_fields[0]}')}}")
                else:
                    label = " ".join(f"${{PROP('Dim.{field}')}}" for field in by_fields)
                return [w.e(f"{q} / 60{scale}", label)]
            return [w.e(f"SUM({q}) / 60{scale}", self.label)]
        s = w.m(self.name, "SampleCount" if METRICS[self.name][1] == "histogram" else "Sum")
        return [w.e(f"FILL({s}, 0) / PERIOD({s}){scale}", self.label)]


class HistogramSumRate:
    """The fleet total per second of values recorded in a histogram."""

    def __init__(self, name, label, scale=1):
        self.name, self.label, self.scale = name, label, scale

    def prom(self):
        expression = f"sum({prom_dedup(self.name, '_sum', rate=True)})"
        if self.scale != 1:
            expression = f"{expression} / {self.scale}"
        return [(f"({expression}) or vector(0)", self.label)]

    def cw(self, w):
        metric = w.m(self.name, "Sum")
        expression = f"FILL({metric}, 0) / PERIOD({metric})"
        if self.scale != 1:
            expression = f"{expression} / {self.scale}"
        return [w.e(expression, self.label)]


class Gauge:
    """A gauge over processes: fleet (sum), hottest (max), lowest (min) or mean (avg); per_pod lines are
    Grafana-only."""

    def __init__(self, name, label, agg="sum", scale=1):
        self.name, self.label, self.agg, self.scale = name, label, agg, scale

    def _scaled(self, e):
        return e if self.scale == 1 else f"{e} / {self.scale}"

    def prom(self):
        g = only_active_processes(prom_dedup(self.name))
        if self.agg == "pod":
            return [(self._scaled(g), "{{exported_instance}}")]
        return [(self._scaled(f"{self.agg}({g})"), self.label)]

    def cw(self, w):
        if self.agg == "pod":
            return []
        if self.agg == "sum":
            s = w.m(self.name, "Sum")
            return [w.e(self._scaled(f"{s} / PERIOD({s})"), self.label)]
        stat = {"max": "Maximum", "min": "Minimum", "avg": "Average"}[self.agg]
        return [w.e(self._scaled(w.m(self.name, stat)), self.label)]


class Mean:
    """A histogram's windowed mean, divided by scale."""

    def __init__(self, name, label, scale=1, where=None):
        self.name, self.label, self.scale, self.where = name, label, scale, where

    def prom(self):
        e = (f"sum({prom_dedup(self.name, '_sum', self.where, rate=True)}) / "
             f"sum({prom_dedup(self.name, '_count', self.where, rate=True)})")
        return [(e if self.scale == 1 else f"({e}) / {self.scale}", self.label)]

    def cw(self, w):
        if METRICS[self.name][3]:
            total = f"SUM({w.search(self.name, 'Sum', self.where)})"
            count = f"SUM({w.search(self.name, 'SampleCount', self.where)})"
        else:
            total, count = w.m(self.name, "Sum"), w.m(self.name, "SampleCount")
        e = f"{total} / {count}"
        return [w.e(e if self.scale == 1 else f"{e} / {self.scale}", self.label)]


class Quantile:
    """A histogram percentile; Grafana-only."""

    def __init__(self, name, label, q=0.95, scale=1):
        self.name, self.label, self.q, self.scale = name, label, q, scale

    def prom(self):
        e = (f"histogram_quantile({self.q}, sum by (le) "
             f"({prom_dedup(self.name, '_bucket', rate=True)}))")
        return [(e if self.scale == 1 else f"{e} / {self.scale}", self.label)]

    def cw(self, _w):
        return []


class ShardsCompleted:
    """Cumulative shards finished during the displayed window. Each process records pipelineShardDuration once.
    Prometheus first produces one-minute completion counts for Grafana to accumulate; CloudWatch accumulates the
    histogram's per-period sample counts directly."""

    def prom(self):
        current = prom_dedup("pipelineShardDuration", "_count")
        previous = prom_dedup("pipelineShardDuration", "_count", offset="1m")
        return [(f"count({current} unless {previous}) or vector(0)", "Completed this minute")]

    def cw(self, w):
        s = w.m("pipelineShardDuration", "SampleCount")
        return [w.e(f"RUNNING_SUM(FILL({s}, 0))", "Shards completed")]


class ShardDuration:
    """Mean shard duration in minutes; in Prometheus over the shards whose series are still exported."""

    def prom(self):
        total = prom_dedup("pipelineShardDuration", "_sum")
        count = prom_dedup("pipelineShardDuration", "_count")
        return [(f"avg({total} / {count}) / 60000", "Mean")]

    def cw(self, w):
        total, count = w.m("pipelineShardDuration", "Sum"), w.m("pipelineShardDuration", "SampleCount")
        return [w.e(f"{total} / {count} / 60000", "Mean")]


class Ratio:
    """100 * (rate of one counter or histogram sum) / (another's), as a percentage."""

    def __init__(self, num, den, label):
        self.num, self.den, self.label = num, den, label

    def _prom(self, part):
        name, where = part
        suffix = "_sum" if METRICS[name][1] == "histogram" else ""
        return f"sum({prom_dedup(name, suffix, where, rate=True)})"

    def prom(self):
        return [(f"100 * {self._prom(self.num)} / {self._prom(self.den)}", self.label)]

    def _cw(self, w, part):
        name, where = part
        if METRICS[name][3]:
            return f"SUM({w.search(name, 'Sum', where)})"
        return w.m(name, "Sum")

    def cw(self, w):
        return [w.e(f"100 * {self._cw(w, self.num)} / {self._cw(w, self.den)}", self.label)]


class WorkerPhases:
    """RFS processes by phase (rfsWorkerPhase: each process reports 1 for its current phase and 0 for the others,
    on every export); processes that have exited are left out, so the stack's height is the number running."""

    PHASES = [
        ("starting", "Starting"),
        ("acquiring_work", "Acquiring work"),
        ("downloading_shard", "Downloading shard"),
        ("migrating", "Migrating"),
        ("completing", "Completing"),
    ]

    def prom(self):
        return [
            (
                f'sum(max by (exported_instance) ({prom_name("rfsWorkerPhase")}'
                f'{{otel_scope_name="{RFS}",phase="{phase}"}}))',
                label,
            )
            for phase, label in self.PHASES
        ]

    def cw(self, w):
        return [
            w.e(
                f"SUM(SEARCH('{{{NS},OTelLib,phase,qualifier}} MetricName=\"rfsWorkerPhase\" OTelLib=\"{RFS}\" "
                f"qualifier=\"{Q}\" phase=\"{phase}\"', 'Sum', 60)) / 60",
                label,
            )
            for phase, label in self.PHASES
        ]


class AttemptOutcomeGroups:
    """Operator-facing groups of bulk request outcomes, ordered from success to increasingly severe feedback."""

    GROUPS = [
        ("Accepted first try", [("first", "ok")]),
        ("Accepted after retry", [("retry", "ok")]),
        ("Response had item errors", [("*", "item_errors")]),
        ("Target throttled", [("*", "throttled_429"), ("*", "admission_rejected")]),
        ("No response", [("*", "timeout"), ("*", "connection_reset"), ("*", "connect_failed")]),
        ("Other errors", [("*", "payload_413"), ("*", "other_4xx"), ("*", "5xx"), ("*", "other_status")]),
    ]

    @staticmethod
    def _prom_term(attempt, outcome):
        attempt_selector = "" if attempt == "*" else f',attempt="{attempt}"'
        metric = (
            f'{prom_name("bulkAttempts")}{{otel_scope_name="{RFS}"{attempt_selector},outcome="{outcome}"}}'
        )
        return f"sum(max by (exported_instance,attempt,outcome) (rate({metric}[{RI}])))"

    def prom(self):
        series = []
        for label, terms in self.GROUPS:
            expression = " + ".join(self._prom_term(attempt, outcome) for attempt, outcome in terms)
            series.append((f"({expression}) or vector(0)", label))
        return series

    def cw(self, w):
        series = []
        for label, terms in self.GROUPS:
            searches = []
            for attempt, outcome in terms:
                where = {"outcome": outcome}
                if attempt != "*":
                    where["attempt"] = attempt
                searches.append(f"SUM({w.search('bulkAttempts', 'Sum', where)})")
            series.append(w.e(f"({' + '.join(searches)}) / 60", label))
        return series


class LimitUtilizationBands:
    """Number of processes in fixed bands of current adaptive limit / configured maximum."""

    BANDS = [
        ("0-24%", "0–24%"),
        ("25-49%", "25–49%"),
        ("50-74%", "50–74%"),
        ("75-99%", "75–99%"),
        ("100%", "100%"),
    ]

    def prom(self):
        metric = prom_name("bulkWorkersByLimitUtilization")
        series = []
        for value, label in self.BANDS:
            per_process = (
                f'max by (exported_instance) ({metric}'
                f'{{otel_scope_name="{RFS}",range="{value}"}})'
            )
            series.append((f"sum({only_active_processes(per_process)})", label))
        return series

    def cw(self, w):
        return [
            w.e(f"SUM({w.search('bulkWorkersByLimitUtilization', 'Sum', {'range': value})}) / 60", label)
            for value, label in self.BANDS
        ]


class ContainerUtilization:
    """CPU or memory usage as a percentage of the RFS containers' configured limits."""

    def __init__(self, what, agg):
        self.what, self.agg = what, agg

    def prom(self):
        s = (f'{{job="otel-collector",exported_job=~"cadvisor|kubelet-cadvisor",namespace="$namespace",'
             f'container="{CONTAINER}",container!="POD",image!=""}}')
        if self.what == "cpu":
            usage = f"max by (pod) (rate(container_cpu_usage_seconds_total{s}[{RI}]))"
            quota = f"max by (pod) (container_spec_cpu_quota{s})"
            period = f"max by (pod) (container_spec_cpu_period{s})"
            limit = f"({quota}) / ({period})"
        else:
            usage = f"max by (pod) (container_memory_working_set_bytes{s})"
            limit = f"max by (pod) (container_spec_memory_limit_bytes{s})"
        if self.agg == "sum":
            expression = f"100 * sum({usage}) / sum({limit})"
            label = FLEET_CAPACITY_LABEL
        else:
            expression = f"100 * max(({usage}) / ({limit}))"
            label = "% (hottest pod)"
        return [(expression, label)]

    @staticmethod
    def _cw_search(name, stat):
        return (f"SEARCH('{{{NS},container,namespace,pod}} MetricName=\"{name}\" "
                f"container=\"{CONTAINER}\" namespace=\"ma\"', '{stat}', 60)")

    def cw(self, w):
        if self.what == "cpu" and self.agg == "max":
            # The EKS CloudWatch exporter aggregates CPU by node to avoid colliding pod datapoints. Grafana can
            # still calculate the hottest pod from the unaggregated Prometheus copy.
            return []

        name = ("container_cpu_usage_seconds_total"
                if self.what == "cpu" else "container_memory_working_set_bytes")
        stat = "Sum" if self.agg == "sum" else "Maximum"
        i = w._id("m")
        w.rows.append([NS, name, "container", CONTAINER, "namespace", "ma",
                       {"id": i, "stat": stat, "visible": False, "region": R}])
        if self.what == "cpu":
            quota = self._cw_search("container_spec_cpu_quota", "Average")
            period = self._cw_search("container_spec_cpu_period", "Average")
            usage = f"{i} / PERIOD({i})"
            # CPU quota is per pod while the CFS period is a common divisor. Summing both would cancel the pod
            # count and leave one pod's limit; sum all quotas and divide by the average period for fleet capacity.
            limit = f"SUM({quota}) / AVG({period})"
            return [w.e(f"100 * ({usage}) / ({limit})", FLEET_CAPACITY_LABEL)]

        memory_limit = self._cw_search("container_spec_memory_limit_bytes", "Average")
        if self.agg == "sum":
            # Scraped every 5 s: Sum / (PERIOD / 5) is the fleet's mean working set in the period.
            usage = f"{i} / (PERIOD({i}) / 5)"
            limit = f"SUM({memory_limit})"
            label = FLEET_CAPACITY_LABEL
        else:
            usage = i
            limit = f"MAX({memory_limit})"
            label = "% (hottest pod)"
        return [w.e(f"100 * ({usage}) / ({limit})", label)]


class ContainerCpuThrottling:
    """Percentage of the RFS containers' CFS scheduling periods in which their CPU quota throttled them."""

    def prom(self):
        s = (f'{{job="otel-collector",exported_job=~"cadvisor|kubelet-cadvisor",namespace="$namespace",'
             f'container="{CONTAINER}",container!="POD",image!=""}}')
        throttled = f"sum(rate(container_cpu_cfs_throttled_periods_total{s}[{RI}]))"
        periods = f"sum(rate(container_cpu_cfs_periods_total{s}[{RI}]))"
        return [(f"100 * ({throttled}) / ({periods})", "% throttled periods")]

    def cw(self, w):
        dimensions = f"{{{NS},container,namespace,pod}}"
        common = f'container="{CONTAINER}" namespace="ma"'
        throttled = (
            f"SUM(SEARCH('{dimensions} MetricName=\"container_cpu_cfs_throttled_periods_total\" "
            f"{common}', 'Sum', 60))"
        )
        periods = (
            f"SUM(SEARCH('{dimensions} MetricName=\"container_cpu_cfs_periods_total\" "
            f"{common}', 'Sum', 60))"
        )
        return [w.e(f"100 * ({throttled}) / ({periods})", "% throttled periods")]


class Domain:
    """An AWS/ES metric for the target domain; CloudWatch-only."""

    def __init__(self, metric, stat, label, scale=None):
        self.metric, self.stat, self.label, self.scale = metric, stat, label, scale

    def prom(self):
        return []

    def cw(self, w):
        i = w._id("m")
        w.rows.append(["AWS/ES", self.metric, "DomainName", "TC_DOMAIN_NAME", "ClientId", "ACCOUNT_ID",
                       {"id": i, "stat": self.stat, "visible": self.scale is None, "region": R, "label": self.label,
                        "period": 60}])
        if self.scale is not None:
            return [w.e(f"{i} / {self.scale}", self.label)]
        return [i]


class Right:
    """Draws a series against the panel's right axis (right_unit), for a series whose scale or unit differs."""

    def __init__(self, series):
        self.series = series

    def prom(self):
        return self.series.prom()

    def cw(self, w):
        ids = self.series.cw(w)
        for row in w.rows:
            if row[-1].get("id") in ids:
                row[-1]["yAxis"] = "right"
        return ids


# --- panels --------------------------------------------------------------------------------------------------

class Panel:
    def __init__(self, title, desc, unit, series, stacked=False, cw_only=False, grafana_only=False,
                 right_unit=None, transformations=None, min_interval=None):
        self.title, self.desc, self.unit, self.series, self.stacked = title, desc, unit, series, stacked
        self.cw_only, self.grafana_only, self.right_unit = cw_only, grafana_only, right_unit
        self.transformations, self.min_interval = transformations or [], min_interval


SECTIONS = [
    # Three aligned columns answer the first operator questions: result, worker capacity, and target feedback.
    ("Operator overview", False, [
        Panel("Documents migrated per second",
              "Documents successfully written to the target, fleet total per second. A sustained drop matters only "
              "after comparing it with the worker-capacity and target-feedback panels in the same row.",
              DOCS_PER_SECOND_UNIT, [Rate("pipelineDocsMigrated", "Documents/s")]),
        Panel("Workers by phase",
              "Number of RFS worker processes in each lifecycle phase. The stack is ordered from the first phase at "
              "the bottom to the last at the top: starting, acquiring work, downloading and unpacking a shard, "
              "migrating documents, then recording completion. Many downloading workers with few migrating workers "
              "points to snapshot download/extraction rather than the target. If most workers are migrating, use "
              "the utilization and target-feedback panels below to judge whether adding workers could help. Each "
              "process handles one work item and exits. Grafana may briefly overcount a terminated process until "
              "Prometheus marks its series stale.",
              "count", [WorkerPhases()], stacked=True),
        Panel("Target request outcomes (attempts/s)",
              "Bulk HTTP requests per second, grouped into operator-facing outcomes. The stack is ordered with "
              "requests accepted on the first try at the bottom, then requests accepted after retry, item errors, "
              "target throttling, no response, and other errors. A growing upper portion means more request work is "
              "being repeated or rejected. The detailed first-versus-retry outcome matrix is in the expert section.",
              ATTEMPTS_PER_SECOND_UNIT, [AttemptOutcomeGroups()], stacked=True),
        Panel("Documents requiring manual action (per minute)",
              "Documents that did not migrate and were recorded as terminal failures, by failure class. "
              "NON_RETRYABLE is a document/request problem that retrying cannot fix; RETRYABLE_EXHAUSTED was given "
              "up after its retry policy. These are the documents an operator must inspect and replay or correct. "
              "Target throttles and temporary unavailability do not appear here unless a document ultimately fails.",
              PER_MINUTE_UNIT, [
                  Rate("bulkDocumentsFailed", "Cannot be retried",
                       where={"failureClass": "NON_RETRYABLE"}, per_min=True),
                  Rate("bulkDocumentsFailed", "Retries exhausted",
                       where={"failureClass": "RETRYABLE_EXHAUSTED"}, per_min=True),
              ], stacked=True),
        Panel("Workers by connection-limit utilization",
              "The number of workers in each band of current adaptive connection limit divided by that worker's "
              "configured maximum. The stack height is the number of workers reporting. A new process starts with "
              "a low allowance while downloading and ramps after migration begins, so most workers in low bands "
              "without limit cuts—especially alongside the Downloading phase—means shard turnover rather than "
              "target pushback. One low worker while the others are at 100% suggests a slow shard; most workers in "
              "low bands while cuts are occurring means adding workers is unlikely to help.",
              "count", [LimitUtilizationBands()], stacked=True),
        Panel("Adaptive-limit cuts per minute (fleet total)",
              "The fleet-wide event count across all RFS worker processes, grouped by the feedback that caused each "
              "worker to reduce its allowed target concurrency: HTTP 429 (including request-level admission "
              "control), item-level admission rejection inside a successful bulk HTTP response, or response "
              "timeout. Workers independently probe upward again after cutting; under sustained target saturation, "
              "recurring cuts form an expected sawtooth and need not decay to zero. Correlate cuts with the adaptive "
              "allowance and utilization bands to see the size and breadth of target pushback.",
              PER_MINUTE_UNIT, [
                  Rate("bulkLimitCuts", "HTTP 429 / request rejected",
                       where={"signal": "throttled_429"}, per_min=True),
                  Rate("bulkLimitCuts", "Item-level admission rejection",
                       where={"signal": "admission_rejected"}, per_min=True),
                  Rate("bulkLimitCuts", "Response timeout", where={"signal": "timeout"}, per_min=True),
              ], stacked=True),
        Panel("Documents sent vs migrated per second",
              "Documents included in bulk requests on every attempt, documents successfully migrated, and the "
              "subset sent again during retries. Sent can briefly lead migrated while requests are in flight. "
              "Sustained re-sends or a widening sent-versus-migrated gap means retries or failures are consuming "
              "target capacity.",
              DOCS_PER_SECOND_UNIT, [HistogramSumRate("bulkRequestDocuments", "Sent"),
                                     Rate("pipelineDocsMigrated", "Migrated"),
                                     Rate("bulkDocsResent", "Re-sent")]),
        Panel("Requests in flight vs adaptive allowance",
              "Fleet totals for bulk requests currently in flight, the workers' current adaptive allowances, their "
              "configured maximums, and requests waiting for an allowance slot. The configured maximum is only a "
              "ceiling, not a count of connections that RFS tries to keep open. An allowance grows only after a "
              "successful request that used its last available slot or had another request waiting. If in-flight "
              "work stays below the current allowance with no waiters, snapshot reading or request assembly is not "
              "producing enough concurrent work to justify growth, so raising the maximum will not help. Allowances "
              "well below the maximum while waiters and limit cuts are present instead show target backpressure.",
              "count", [Gauge("bulkInFlight", "Requests in flight"),
                        Gauge("bulkLimit", "Current adaptive limit"),
                        Gauge("bulkLimitMax", "Configured maximum"),
                        Gauge("bulkSlotWaiters", "Waiting for a limit slot")]),
        Panel("Documents paused for target recovery",
              "Documents in retrying batches whose most recent attempt was throttled or found the target unable to "
              "take writes. This is not time spent waiting for a normal bulk response. The value falls when retries "
              "succeed; a sustained nonzero value means migration work is being held for target recovery.",
              "docs", [Gauge("bulkDocsWaitingOnTarget", "Paused documents")]),
    ]),
    ("Throughput and request shape", False, [
        Panel("Document and bulk request bytes (MiB/s)",
              "Three different byte boundaries, shown as absolute rates. Migrated document source is the original "
              "document-source payload successfully advanced by the pipeline; it excludes bulk action metadata and "
              "NDJSON framing. Uncompressed bulk NDJSON is the assembled request body for every attempt, including "
              "metadata, framing, and retries. Bulk request body sent is that same body after optional compression. "
              "Compare the two request lines to see compression; compare document send/re-send rates above to "
              "separate retries from request overhead.",
              MIB_PER_SECOND_UNIT, [Rate("pipelineBytesMigrated", "Migrated document source", scale=MIB),
                                    HistogramSumRate("bulkRequestBytes", "Uncompressed bulk NDJSON", scale=MIB),
                                    HistogramSumRate("bulkRequestSentBytes", "Bulk request body sent", scale=MIB)]),
        Panel("RFS CPU utilization (%)",
              "Bulk-loader CPU usage divided by its configured CPU limit. This panel and RFS memory are vertically "
              "aligned under the request allowance panel so worker resource saturation can be compared with unused "
              "allowance. Both dashboards show the fleet's share of total configured CPU; Grafana also shows the "
              "hottest pod. CloudWatch cannot calculate the hottest pod because EKS aggregates CPU by node before "
              "export.",
              "percent", [ContainerUtilization("cpu", "sum"), ContainerUtilization("cpu", "max")]),
        Panel("Bulk request size",
              "Mean documents per bulk request and mean KiB per request before and after compression. Falling request "
              "sizes can explain lower MiB/s even when request rate is steady; retries and batch splits can change "
              "the observed size.",
              "docs / KiB", [Mean("bulkRequestDocuments", "Documents per request"),
                             Mean("bulkRequestBytes", "KiB per request (NDJSON)", scale=1024),
                             Mean("bulkRequestSentBytes", "KiB per request (sent)", scale=1024)]),
        Panel("Shards completed (cumulative)",
              "Work items (shards) completed since the beginning of the displayed time window. A process migrates "
              "one shard and records its duration once. Set the dashboard start before the run began to see progress "
              "from zero to the snapshot's total shard count.",
              "count", [ShardsCompleted()],
              transformations=[{
                  "id": "calculateField",
                  "options": {
                      "mode": "cumulativeFunctions",
                      "cumulative": {"reducer": "sum"},
                      "replaceFields": True,
                      "alias": "Shards completed",
                  },
              }],
              min_interval="1m"),
        Panel("RFS memory utilization (%)",
              "Bulk-loader working-set memory divided by its configured memory limit: fleet capacity and hottest "
              "pod. It is vertically aligned with RFS CPU and the request allowance panel above.",
              "percent", [ContainerUtilization("mem", "sum"), ContainerUtilization("mem", "max")]),
    ]),
    ("Pipeline performance diagnosis", False, [
        Panel("Documents per second through pipeline stages",
              "The same source documents counted at successive bounded pipeline boundaries. Source is chunked for "
              "preparation; prepared has completed validation/encoding; ordered prep has cleared the ordered "
              "fan-in; packed has entered an exact target batch; request started/finished bracket the sink call; "
              "checkpointed can advance source progress. A sustained rate drop between adjacent lines locates the "
              "stage that is not feeding its consumer.",
              DOCS_PER_SECOND_UNIT, [
                  Rate("pipelineStageDocuments", "Source", where={"stage": "source"}),
                  Rate("pipelineStageDocuments", "Prepared", where={"stage": "prepared"}),
                  Rate("pipelineStageDocuments", "Ordered prep", where={"stage": "ordered_prepared"}),
                  Rate("pipelineStageDocuments", "Packed", where={"stage": "packed"}),
                  Rate("pipelineStageDocuments", "Request started", where={"stage": "request_started"}),
                  Rate("pipelineStageDocuments", "Request finished", where={"stage": "request_completed"}),
                  Rate("pipelineStageDocuments", "Checkpointed", where={"stage": "checkpointed"}),
              ]),
        Panel("Documents buffered between stages",
              "Current source documents waiting for a preparation rail, completed prepared documents held behind "
              "an earlier chunk by ordered fan-in, and packed documents ready for the request fan-out. Ordered-prep "
              "growth exposes head-of-line blocking; ready-document growth with low HTTP in-flight work points to "
              "request-fan-out scheduling or sink work.",
              "docs", [
                  Gauge("pipelinePreparationQueuedDocuments", "Waiting for preparation"),
                  Gauge("pipelinePreparationOrderedDocuments", "Blocked at ordered prep fan-in"),
                  Gauge("pipelineRequestReadyDocuments", "Packed and request-ready"),
              ]),
        Panel("Time waiting between pipeline stages (ms)",
              "Mean queue residence per chunk or batch: waiting for a preparation rail, waiting after preparation "
              "for ordered fan-in, and waiting after exact packing for the request fan-out. These are boundary "
              "waits, not target response time.",
              "ms", [
                  Mean("pipelinePreparationQueueWait", "Preparation queue"),
                  Mean("pipelinePreparationOrderedWait", "Ordered prep fan-in"),
                  Mean("pipelineRequestQueueWait", "Packed-to-request"),
              ]),
        Panel("MiB per second through pipeline stages",
              "Bytes crossing selected pipeline boundaries. Source and checkpointed bytes are original document "
              "source. Prepared, packed, and request-started bytes are exact encoded NDJSON operations including "
              "action metadata and framing. The change from source to prepared quantifies bulk-format overhead.",
              MIB_PER_SECOND_UNIT, [
                  Rate("pipelineStageBytes", "Source", where={"stage": "source"}, scale=MIB),
                  Rate("pipelineStageBytes", "Prepared", where={"stage": "prepared"}, scale=MIB),
                  Rate("pipelineStageBytes", "Packed", where={"stage": "packed"}, scale=MIB),
                  Rate("pipelineStageBytes", "Request started", where={"stage": "request_started"}, scale=MIB),
                  Rate("pipelineStageBytes", "Checkpointed source", where={"stage": "checkpointed"}, scale=MIB),
              ]),
        Panel("MiB buffered between stages",
              "Current source bytes waiting for preparation, exact encoded bytes held by the ordered preparation "
              "fan-in, and exact encoded bytes packed and ready for requests. A persistent reservoir shows where "
              "memory is being used and which downstream boundary is not draining.",
              "MiB", [
                  Gauge("pipelinePreparationQueuedBytes", "Waiting for preparation", scale=MIB),
                  Gauge("pipelinePreparationOrderedBytes", "Blocked at ordered prep fan-in", scale=MIB),
                  Gauge("pipelineRequestReadyBytes", "Packed and request-ready", scale=MIB),
              ]),
        Panel("Active and queued pipeline work",
              "Counts of chunks or request groups at the main concurrency boundaries. Preparation active is work "
              "on CPU rails. Ordered chunks completed out of order and are waiting to be released. Request-ready "
              "batches are packed but not subscribed; active request groups are executing sink work.",
              "count", [
                  Gauge("pipelinePreparationQueuedChunks", "Prep chunks queued"),
                  Gauge("pipelinePreparationActiveChunks", "Prep chunks active"),
                  Gauge("pipelinePreparationOrderedChunks", "Prep chunks ordered-blocked"),
                  Gauge("pipelineRequestReadyBatches", "Request-ready batches"),
                  Gauge("pipelineRequestActiveGroups", "Request groups active"),
              ]),
        Panel("Pipeline service time (ms)",
              "Mean CPU preparation time per chunk, serial exact-packer time per chunk, and complete sink-call time "
              "per successful request group. Request-group duration includes target requests and their response "
              "handling, so compare it with Bulk attempt duration and the client-side timings.",
              "ms", [
                  Mean("pipelinePreparationDuration", "Prepare chunk", where={"outcome": "success"}),
                  Mean("pipelinePackingDuration", "Exact packer"),
                  Mean("pipelineRequestGroupDuration", "Request group", where={"outcome": "success"}),
              ]),
        Panel("Packed target-batch shape",
              "Mean exact documents and MiB per target batch, plus utilization of the configured document and byte "
              "limits. A batch may be near one limit and far below the other. Values above 100% identify a single "
              "oversized operation that must be isolated.",
              "short", [
                  Mean("pipelinePackedBatchDocuments", "Documents"),
                  Mean("pipelinePackedBatchBytes", "MiB", scale=MIB),
                  Mean("pipelinePackedBatchFill", "Document-limit fill (%)", where={"limit": "documents"}),
                  Mean("pipelinePackedBatchFill", "Byte-limit fill (%)", where={"limit": "bytes"}),
              ]),
        Panel("Client preparation and response handling (ms)",
              "Client-side work after a request group reaches OpenSearchClient: final client repacking, contiguous "
              "NDJSON assembly, gzip, and response classification/compaction. Gap between starts is process-wide "
              "request pacing, not per-request or per-connection latency. The client repacking timer does not "
              "include the upstream preparation and ordered packing shown elsewhere in this section.",
              "ms", [
                  Mean("bulkBatchPreparationDuration", "Client repacking"),
                  Mean("bulkRequestStartGap", "Gap between request starts"),
                  Mean("bulkRequestAssemblyDuration", "Request assembly"),
                  Mean("bulkRequestCompressionDuration", "Gzip"),
                  Mean("bulkResponseClassificationDuration", "Classify response",
                       where={"result": "response"}),
                  Mean("bulkResponseClassificationDuration", "Classify no response",
                       where={"result": "no_response"}),
              ]),
        Panel("RFS CPU throttled periods (%)",
              "Percentage of Linux CFS scheduling periods in which bulk-loader containers wanted more CPU than "
              "their configured limits allowed. High CPU utilization without throttled periods means workers are "
              "using their allocation; sustained throttling means preparation, packing, compression, or response "
              "work is being delayed by the pod CPU limit.",
              "percent", [ContainerCpuThrottling()]),
        Panel("Effective pipeline configuration",
              "Mean process configuration reported by the running workers. These values make benchmark graphs "
              "self-describing when lanes use different images or tuning combinations. Batch bytes are shown in "
              "MiB; the other lines are document, chunk, group, or concurrency counts as named.",
              "short", [
                  Gauge("pipelineMaxBatchDocuments", "Max documents per target batch", "avg"),
                  Gauge("pipelineMaxBatchBytes", "Max target-batch MiB", "avg", scale=MIB),
                  Gauge("pipelineBatchConcurrency", "Request concurrency ceiling", "avg"),
                  Gauge("pipelinePreparationConcurrency", "Preparation rails", "avg"),
                  Gauge("pipelinePreparationChunkDocuments", "Documents per preparation chunk", "avg"),
              ]),
    ]),
    ("Target domain", True, [
        Panel("Target indexing rate and write queue",
              "Fleet-total AWS/ES IndexingRate (operations per minute) and, on the right axis, the write thread "
              "pool's queue (maximum over nodes) and rejections. Indexing-pressure rejections (429s for too many "
              "bytes in flight) aren't in the thread pool: see Bulk attempts by outcome and the 4xx count.",
              "ops/min", [Domain("IndexingRate", "Sum", "Indexing rate (fleet ops/min)"),
                          Right(Domain("ThreadpoolWriteQueue", "Maximum", "Write queue (max)")),
                          Right(Domain("ThreadpoolWriteRejected", "Sum", "Write rejected"))],
              cw_only=True, right_unit="count"),
        Panel("Target CPU and JVM pressure (%)",
              "AWS/ES CPUUtilization and JVMMemoryPressure, maximum over nodes.",
              "percent", [Domain("CPUUtilization", "Maximum", "CPU (max node)"),
                          Domain("JVMMemoryPressure", "Maximum", "JVM pressure (max node)")], cw_only=True),
        Panel("Target latency and HTTP status codes (all clients)",
              "AWS/ES IndexingLatency (ms per operation) and, on the right axis, 4xx and 5xx responses per minute, "
              "from every client. Bulk item errors such as cluster_block_exception are carried inside an HTTP 200 "
              "bulk response and do not appear as 4xx here; correlate this panel with Bulk attempts, Why the target "
              "couldn't take documents, and Item errors.",
              "ms", [Domain("IndexingLatency", "Average", "Indexing latency (ms)"),
                     Right(Domain("4xx", "Sum", "4xx")), Right(Domain("5xx", "Sum", "5xx"))],
              cw_only=True, right_unit=PER_MINUTE_UNIT),
        Panel("Target searchable documents (thousands) and used space (GB)",
              "AWS/ES SearchableDocuments and, on the right axis, ClusterUsedSpace.",
              "thousands of docs",
              [Domain("SearchableDocuments", "Maximum", "Searchable docs (k)", scale=1000),
               Right(Domain("ClusterUsedSpace", "Maximum", "Used space (GB)", scale=1024))],
              cw_only=True, right_unit="GB"),
    ]),
    ("Retry and recovery", True, [
        Panel("Delay between retries for each batch (s)",
              "For each retrying batch, time from the end of its previous HTTP attempt until its next attempt was "
              "sent. This is per batch/retry chain, not per connection. It includes planned backoff and any later "
              "wait for retry capacity or a target-request slot. No line means no retries happened in the selected "
              "window. The panel shows the mean and p95.",
              "s", [Mean("bulkAttemptGap", "Mean", scale=1000),
                    Quantile("bulkAttemptGap", "p95", scale=1000)]),
        Panel("Target-unavailable document attempts per minute",
              "Document attempts that could not be written because the target was unavailable, grouped by reason: "
              "a write block, unavailable shards, HTTP 503, or connection failure/reset. This does not include HTTP "
              "429 throttles; correlate the overview's paused-document gauge with its limit-cut panel for those. "
              "A document is counted again on each attempt, so this is refusal volume rather than a "
              "distinct-document count.",
              PER_MINUTE_UNIT, [Rate("bulkDocsTargetUnavailable", "{by}", by="reason", per_min=True)], stacked=True),
        Panel("Bulk attempt duration (s)",
              "Time from sending a bulk HTTP request until its response or transport error. The panel shows the "
              "mean, p50, and p99. Durations approaching the configured bulk response timeout indicate responses "
              "are arriving too slowly.",
              "s", [Mean("bulkAttemptDuration", "Mean", scale=1000),
                    Quantile("bulkAttemptDuration", "p50", q=0.5, scale=1000),
                    Quantile("bulkAttemptDuration", "p99", q=0.99, scale=1000)]),
        Panel("Batch completion time and oldest unfinished batch (s)",
              "A batch is one request-sized group of documents followed through all of its retries. Mean completion "
              "time runs from the first attempt until the batch succeeds or gives up. Oldest unfinished is the "
              "highest age of any still-open batch in a worker. If the oldest rises while other batches complete, "
              "some early work may be starved.",
              "s", [Mean("bulkBatchAge", "Mean completion time", scale=1000),
                    Gauge("bulkOldestOpenBatchAge", "Oldest unfinished (highest worker)", "max", scale=1000)]),
        Panel("Retry budget tokens and exhaustion",
              "Tokens remaining in each worker's shared retry budget (500 initially), shown for the lowest and "
              "highest worker. The right axis counts times retries had to wait because a worker ran out. A low "
              "minimum means at least one worker is seeing sustained failures even if the rest are healthy.",
              "tokens", [Gauge("retryBudgetTokens", "Lowest worker", "min"),
                         Gauge("retryBudgetTokens", "Highest worker", "max"),
                         Right(Rate("retryBudgetEmpty", "Ran out per minute", per_min=True))],
              right_unit=PER_MINUTE_UNIT),
    ]),
    ("Failure diagnosis", True, [
        Panel("Documents given up after retries (per minute)",
              "Documents abandoned after repeated unknown status, no-response, or item failures. This explains the "
              "RETRYABLE_EXHAUSTED portion of the overview's manual-action graph. "
              "Throttling and temporary target unavailability do not give documents up by themselves.",
              PER_MINUTE_UNIT, [Rate("bulkDocsGaveUp", "", by="reason", per_min=True)]),
        Panel("Item errors per second",
              "Per-document errors returned inside otherwise successful bulk HTTP responses. Retried errors may "
              "reflect target state; terminal errors were attributed to the document and recorded for manual action.",
              "errors/s", [Rate("bulkItemErrors", "{by} (retried)", by="errorType", where={"retryable": "true"}),
                           Rate("bulkItemErrors", "{by} (terminal)", by="errorType", where={"retryable": "false"})],
              stacked=True),
    ]),
    ("Flow-control internals (expert)", True, [
        Panel("Target request detail: first vs retry by outcome",
              "Every bulk HTTP attempt split by first attempt or retry and by the exact internal outcome. Use this "
              "after the grouped overview graph identifies a problem and the distinction between first attempts and "
              "retries matters.",
              ATTEMPTS_PER_SECOND_UNIT, [Rate("bulkAttempts", "", by=("attempt", "outcome"))], stacked=True),
        Panel("Adaptive limit by worker",
              "Each worker's current adaptive target-concurrency limit. Workers sharing an overloaded target should "
              "move downward together; one low outlier can instead indicate a slow shard or a worker-local problem.",
              "connections", [Gauge("bulkLimit", "", "pod")], grafana_only=True),
        Panel("Adaptive limit state",
              "Mean and highest current worker limit plus the mean threshold separating fast initial growth from "
              "slower congestion-avoidance growth. Use the operator overview for configured ceiling, utilization "
              "bands, actual in-flight requests, and target-driven cuts.",
              "connections", [Gauge("bulkLimit", "Mean worker", "avg"),
                              Gauge("bulkLimit", "Highest worker", "max"),
                              Gauge("bulkLimitThreshold", "Growth threshold (mean)", "avg")]),
        Panel("Limit growth and source bottlenecks",
              "Quiet resets restore fast growth after five minutes without target throttling. Growth skipped counts "
              "successful requests that did not increase the limit because the worker did not fill its existing "
              "allowance, often because shard reading was slower than target writes. Time to full limit is how long "
              "a worker took to first reach its configured maximum; no line means none reached it.",
              "events (see legend)", [Rate("bulkLimitQuietResets", "Quiet resets/min", per_min=True),
                                      Rate("bulkLimitGrowthSkipped", "Growth skipped/s"),
                                      Right(Mean("bulkTimeToFullLimit", "Time to full limit (s)", scale=1000))],
              right_unit="s"),
        Panel("Retry delay breakdown (expert)",
              "Mean duration of the internal waits that can contribute to a batch's retry delay: planned backoff "
              "(including Retry-After), waiting for retry-budget capacity after widespread failures, and waiting for "
              "the worker's adaptive target-request slot. These are event means, not counts of waiting requests.",
              "s", [Mean("bulkRetryDelay", "Planned backoff", scale=1000),
                    Mean("retryBudgetWait", "Retry-budget wait", scale=1000),
                    Mean("bulkSlotWait", "Target-slot wait", scale=1000)]),
        Panel("Batches split per minute (expert)",
              "Number of request batches divided into smaller batches: status and no_response use binary splitting "
              "to isolate repeatedly failing documents; too_large reduces a request the target rejected for size. "
              "The actual request sizes reaching the target are shown in Throughput and request shape.",
              PER_MINUTE_UNIT, [Rate("bulkBatchSplits", "", by="reason", per_min=True)], stacked=True),
    ]),
]

SECTION_HEADERS = {
    "Operator overview": ["Migration results", "Worker capacity", "Target feedback"],
    "Throughput and request shape": ["Migration volume", "Worker resources", "Request shape"],
}

READING_GUIDE = (
    "**How to read this dashboard.** The first section is aligned into three vertical stories: migration results, "
    "worker capacity, and target feedback. Read across a row to explain a rate change, then down a column for detail. "
    "The second section shows throughput and request shape; later sections are diagnosis and controller internals. "
    "Rates are fleet totals unless marked otherwise. CloudWatch fleet gauges are one-minute averages, so worker "
    "counts can be fractional during transitions. The controls are specified in "
    "RFS/docs/BULK_TRAFFIC_CONTROL.md.")

PANEL_W, PANEL_H, COLUMNS = 8, 6, 3


def layout(collapse=False, backend=None):
    """Yield backend-native sections, optional column headers, and panels. A collapsed Grafana section takes one
    line and nests its panels in the row; expanded sections retain the same three-column alignment."""
    y = 2
    for title, collapsed, panels in SECTIONS:
        if backend == "cloudwatch":
            panels = [panel for panel in panels if not panel.grafana_only]
        elif backend == "grafana":
            panels = [panel for panel in panels if not panel.cw_only]
        if not panels:
            continue
        yield ("section", (title, collapsed), 0, y)
        y += 1
        headers = SECTION_HEADERS.get(title, [])
        for i, header in enumerate(headers):
            yield ("header", header, i * PANEL_W, y)
        if headers:
            y += 1
        for i, panel in enumerate(panels):
            yield ("panel", panel, (i % COLUMNS) * PANEL_W, y + (i // COLUMNS) * PANEL_H)
        if not (collapse and collapsed):
            y += ((len(panels) + COLUMNS - 1) // COLUMNS) * PANEL_H


# --- CloudWatch -----------------------------------------------------------------------------------------------

CW_VARIABLES = [
    {"type": "property", "property": "region", "inputType": "input", "id": "REGION", "label": "Region",
     "defaultValue": "placeholder-region", "visible": False},
    {"type": "property", "property": "DomainName", "inputType": "select", "id": "TC_DOMAIN_NAME",
     "label": "Target Cluster Domain Name",
     "search": "{AWS/ES,ClientId,DomainName} MetricName=\"CPUUtilization\"", "populateFrom": "DomainName",
     "defaultValue": "placeholder-name", "visible": True},
    {"type": "pattern", "pattern": "ACCOUNT_ID", "inputType": "input", "id": "ACCOUNT_ID", "label": "Account ID",
     "defaultValue": "placeholder-account-id", "visible": False},
    {"type": "pattern", "pattern": "MA_QUALIFIER", "inputType": "input", "id": "MA_QUALIFIER",
     "label": "Migration Qualifier", "defaultValue": "placeholder-qualifier", "visible": False},
]


def cloudwatch():
    widgets = [{"type": "text", "x": 0, "y": 0, "width": 24, "height": 2,
                "properties": {"markdown": READING_GUIDE, "background": "transparent"}}]
    for kind, item, x, y in layout(backend="cloudwatch"):
        if kind == "section":
            widgets.append({"type": "text", "x": x, "y": y, "width": 24, "height": 1,
                            "properties": {"markdown": f"## {item[0]}", "background": "transparent"}})
            continue
        if kind == "header":
            widgets.append({"type": "text", "x": x, "y": y, "width": PANEL_W, "height": 1,
                            "properties": {"markdown": f"### {item}", "background": "transparent"}})
            continue
        w = W()
        for s in item.series:
            s.cw(w)
        if not w.rows:
            continue
        y_axis = {"left": {"label": item.unit, "showUnits": False, "min": 0}}
        if item.right_unit:
            y_axis["right"] = {"label": item.right_unit, "showUnits": False, "min": 0}
        widgets.append({"type": "metric", "x": x, "y": y, "width": PANEL_W, "height": PANEL_H,
                        "properties": {"title": item.title, "view": "timeSeries", "stacked": item.stacked,
                                       "region": R, "period": 60, "stat": "Sum", "metrics": w.rows,
                                       "yAxis": y_axis}})
    return {"variables": CW_VARIABLES, "widgets": widgets}


# --- Grafana --------------------------------------------------------------------------------------------------

DS = {"type": "prometheus", "uid": "${DS_PROMETHEUS}"}
GRAFANA_UNITS = {DOCS_PER_SECOND_UNIT: "suffix: docs/s", MIB_PER_SECOND_UNIT: "suffix: MiB/s",
                 PER_MINUTE_UNIT: "suffix: /min", "s": "s",
                 "min": "m", "count": "short", "short": "short", "ops": "ops", "percent": "percent", "docs": "short",
                 ATTEMPTS_PER_SECOND_UNIT: "suffix: attempts/s", "errors/s": "suffix: errors/s",
                 "ops/min": "suffix: /min", "ms": "ms", "GB": "suffix: GB", "docs / KiB": "short",
                 "cores": "suffix: cores", "MiB": "suffix: MiB", "thousands of docs": "short",
                 "tokens": "suffix: tokens", "connections": "suffix: connections",
                 "events (see legend)": "short"}


def grafana():  # NOSONAR - one renderer keeps panel ordering, row ownership, and overrides together
    panels = [{"type": "text", "title": "", "gridPos": {"h": 2, "w": 24, "x": 0, "y": 0},
               "options": {"mode": "markdown", "content": READING_GUIDE}}]
    pid = 1
    row = None
    for kind, item, x, y in layout(collapse=True, backend="grafana"):
        pid += 1
        if kind == "section":
            title, collapsed = item
            row = {"type": "row", "id": pid, "title": title, "collapsed": collapsed,
                   "gridPos": {"h": 1, "w": 24, "x": 0, "y": y}, "panels": []}
            panels.append(row)
            continue
        if kind == "header":
            panels.append({"type": "text", "id": pid, "title": "", "transparent": True,
                           "gridPos": {"h": 1, "w": PANEL_W, "x": x, "y": y},
                           "options": {"mode": "markdown", "content": f"### {item}"}})
            continue
        # A collapsed row holds its panels; an expanded one is followed by them.
        out = row["panels"] if row["collapsed"] else panels
        grid = {"h": PANEL_H, "w": PANEL_W, "x": x, "y": y}
        targets, overrides = [], []
        for s in item.series:
            for expr, legend in s.prom():
                ref = chr(65 + len(targets))
                targets.append({"refId": ref, "expr": expr, "legendFormat": legend, "datasource": DS})
                if isinstance(s, Right):
                    overrides.append({"matcher": {"id": "byFrameRefID", "options": ref}, "properties": [
                        {"id": "custom.axisPlacement", "value": "right"},
                        {"id": "unit", "value": GRAFANA_UNITS[item.right_unit]}]})
        defaults = {"unit": GRAFANA_UNITS[item.unit], "min": 0}
        if item.stacked:
            defaults["custom"] = {"stacking": {"mode": "normal", "group": "A"}, "fillOpacity": 40}
        out.append({"type": "timeseries", "id": pid, "title": item.title, "description": item.desc,
                    "datasource": DS, "targets": targets, "gridPos": grid,
                    "fieldConfig": {"defaults": defaults, "overrides": overrides},
                    "options": {"legend": {"displayMode": "list", "placement": "bottom", "showLegend": True},
                                "tooltip": {"mode": "multi", "sort": "desc"}}})
        if item.transformations:
            out[-1]["transformations"] = item.transformations
        if item.min_interval:
            out[-1]["interval"] = item.min_interval
    return {
        "id": None, "uid": "reindex-from-snapshot", "title": "Reindex from Snapshot",
        "tags": ["rfs", "backfill", "kubernetes"], "timezone": "browser", "schemaVersion": 39, "version": 1,
        "refresh": "15s", "time": {"from": "now-3h", "to": "now"},
        "templating": {"list": [
            {"name": "DS_PROMETHEUS", "type": "datasource", "query": "prometheus", "label": "Prometheus"},
            {"name": "namespace", "type": "constant", "query": "ma", "hide": 2},
        ]},
        # Events posted to Grafana with the "rfs" tag (a test harness's injected failures, an operator's notes)
        # show as vertical lines on every panel.
        "annotations": {"list": [
            {"builtIn": 1, "datasource": {"type": "grafana", "uid": "-- Grafana --"}, "enable": True, "hide": True,
             "iconColor": "rgba(0, 211, 255, 1)", "name": "Annotations & Alerts", "type": "dashboard"},
            {"datasource": {"type": "grafana", "uid": "-- Grafana --"}, "enable": True, "iconColor": "red",
             "name": "RFS events", "target": {"type": "tags", "tags": ["rfs"], "matchAny": True, "limit": 100}},
        ]},
        "panels": panels,
    }


def grafana_configmap(dashboard):
    # Grafana legend formats ({{outcome}}) are Helm actions too; emit them as raw strings.
    body = re.sub(r"\{\{(.*?)\}\}", lambda m: "{{`{{" + m.group(1) + "}}`}}",
                  json.dumps(dashboard, indent=2, ensure_ascii=False))
    indented = "\n".join("    " + line for line in body.splitlines())
    return ("{{- /* Generated by deployment/k8s/scripts/generate-rfs-dashboards.py; regenerate rather than edit. */}}\n"
            "{{- if .Values.metrics.enabled }}\n"
            "apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: reindex-from-snapshot-dashboard\n"
            "  labels:\n    grafana_dashboard: \"1\"\n"
            "data:\n  reindex-from-snapshot.json: |\n" + indented + "\n{{- end }}\n")


if __name__ == "__main__":
    cw_out, grafana_out = sys.argv[1], sys.argv[2]
    cw = cloudwatch()
    open(cw_out, "w").write(json.dumps(cw, indent=4, ensure_ascii=False) + "\n")
    dash = grafana()
    open(grafana_out, "w").write(grafana_configmap(dash))
    print(cw_out, len(cw["widgets"]), "widgets;", grafana_out, len(dash["panels"]), "panels")
