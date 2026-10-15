"""Generate the Grafana twin of the capture/replay CloudWatch dashboard.

usage: generate-capture-replay-grafana-dashboard.py <grafanaDashboard.yaml>

Writes the chart's templates/resources/grafanaDashboard.yaml. Panels, titles and layout mirror
generate-capture-replay-dashboard.py; change both together and regenerate rather than hand-editing either output.
"""
import json
import sys

DS = {"type": "prometheus", "uid": "${DS_PROMETHEUS}"}
MIB = 1048576
RI = "$__rate_interval"
STALE = (" Per-process series from restarted pods linger for up to the exporter's 5 minute metric_expiration, "
         "so fleet sums of gauges can briefly over-count after pod churn.")
TOPB = (" Percentiles come from histogram buckets whose highest finite bound is 30 s, or 1 h for lifetimes, lag and "
        "waits on a slow peer: once more than 5% of observations exceed that bound the p95 line pins there, so trust "
        "the mean above it.")


def cadv(container, extra=""):
    # cAdvisor reaches Prometheus only through the OTel collector's re-export (direct kubelet cAdvisor scraping is
    # disabled); pin that source so a re-enabled kubelet scrape cannot double count.
    return ('job="otel-collector",exported_job=~"cadvisor|kubelet-cadvisor",namespace="$namespace",'
            f'container="{container}",container!="POD",image!=""{extra}')


def rate(m, sel=""):
    return f"sum(rate({m}{{{sel}}}[{RI}]))" if sel else f"sum(rate({m}[{RI}]))"


def per_min(m, sel=""):
    return f"({rate(m, sel)} * 60) or vector(0)"


def mean(h, scale=1, sel=""):
    s = f"{{{sel}}}" if sel else ""
    e = f"sum(rate({h}_sum{s}[{RI}])) / sum(rate({h}_count{s}[{RI}]))"
    return e if scale == 1 else f"{e} / {scale}"


def p95(h, scale=1, sel=""):
    s = f"{{{sel}}}" if sel else ""
    e = f"histogram_quantile(0.95, sum by (le) (rate({h}_bucket{s}[{RI}])))"
    return e if scale == 1 else f"{e} / {scale}"


# The proxy and the replayer both export waitingForResponseDuration, so pin the scope where a name is shared.
PROXY = 'otel_scope_name="captureProxy"'
REPLAYER = 'otel_scope_name="replayer"'


def hottest(g, by="exported_instance"):
    return f"max(sum by ({by}) ({g}))"


LAG5 = ("(sum(rate(lagBetweenSourceAndTargetRequests_milliseconds_sum[5m])) / "
        "sum(rate(lagBetweenSourceAndTargetRequests_milliseconds_count[5m])) / 1000)")
SPEEDUP = f"1 - deriv({LAG5}[10m:1m])"
FLOOR = "${catchup_floor_seconds:raw}"
# 0 while lag is at or below the floor (caught up); no value while lag is above it and not falling.
ETA = (f"(((({LAG5} - {FLOOR}) / 3600) / (-deriv({LAG5}[10m:1m]))) and on() ({LAG5} > {FLOOR}) "
       f"and on() (deriv({LAG5}[10m:1m]) < -0.01)) or on() (0 * {LAG5} and on() ({LAG5} <= {FLOOR}))")
TUPLES = rate("tupleComparison_total")
TRUE_SEL = 'statusCodesMatch="true"'
MATCH_PCT = "100 * " + rate("tupleComparison_total", TRUE_SEL) + " / " + TUPLES


class P:
    def __init__(self, title, desc, kind="timeseries", unit=None, runit=None, stacked=False, mn=0, mx=None):
        self.title, self.desc, self.kind, self.unit, self.runit = title, desc, kind, unit, runit
        self.stacked, self.mn, self.mx, self.t = stacked, mn, mx, []

    def q(self, expr, legend, right=False, instant=False):
        self.t.append((expr, legend, right, instant))
        return self

    def build(self, x, y, w, h):
        targets = []
        for i, (e, lg, _, inst) in enumerate(self.t):
            t = {"refId": chr(65 + i), "expr": e, "legendFormat": lg}
            if inst:
                t["instant"] = True
            targets.append(t)
        d = {}
        if self.unit:
            d["unit"] = self.unit
        if self.mn is not None:
            d["min"] = self.mn
        if self.mx is not None:
            d["max"] = self.mx
        if self.stacked:
            d["custom"] = {"stacking": {"mode": "normal", "group": "A"}, "fillOpacity": 40}
        overrides = []
        for e, lg, right, _ in self.t:
            if right:
                props = [{"id": "custom.axisPlacement", "value": "right"}]
                if self.runit:
                    props.append({"id": "unit", "value": self.runit})
                overrides.append({"matcher": {"id": "byName", "options": lg}, "properties": props})
        p = {"type": self.kind, "title": self.title, "description": self.desc, "datasource": DS,
             "targets": targets, "fieldConfig": {"defaults": d, "overrides": overrides},
             "gridPos": {"h": h, "w": w, "x": x, "y": y}}
        if self.kind == "stat":
            p["options"] = {"graphMode": "area", "colorMode": "none", "reduceOptions": {"calcs": ["lastNotNull"]},
                            "textMode": "value_and_name"}
        else:
            p["options"] = {"legend": {"displayMode": "list", "placement": "bottom", "showLegend": True},
                            "tooltip": {"mode": "multi", "sort": "desc"}}
        return p


def text(title, md, x, y, w, h):
    return {"type": "text", "title": title, "gridPos": {"h": h, "w": w, "x": x, "y": y},
            "options": {"mode": "markdown", "content": md}}


def header(md, x, y, w):
    return {"type": "text", "title": "", "transparent": True, "gridPos": {"h": 1, "w": w, "x": x, "y": y},
            "options": {"mode": "markdown", "content": md}}


def row(title, y):
    return {"type": "row", "title": title, "collapsed": False, "gridPos": {"h": 1, "w": 24, "x": 0, "y": y},
            "panels": []}


CW_ONLY = ("This panel exists only on the CloudWatch dashboard: it reads AWS/ES domain metrics "
           "(CPUUtilization, JVMMemoryPressure, ThreadpoolWriteQueue, ThreadpoolWriteRejected, 5xx{extra}) "
           "for the {which} domain, which are not exported to Prometheus. Open the CloudWatch "
           "capture-replay dashboard and pick the domain there.")

# ---------------------------------------------------------------------------------------------


def kpis():
    return [
        P("Captured requests/s", "Fully parsed HTTP requests observed by the capture proxy, fleet total per second. "
                                 "Empty when the proxy is scaled to 0.",
          "stat", "reqps").q(rate("fullRequest_count_total"), "Captured req/s"),
        P("Replayed tuples/s", "Source/target tuples compared by the replayers (tupleComparison), fleet total "
                               "per second, including tuples whose source response is missing.",
          "stat", "ops").q(TUPLES, "Replayed tuples/s"),
        P("Mean replay lag (s)", "Windowed mean of lagBetweenSourceAndTargetRequests (sum/count rates), in seconds.",
          "stat", "s").q(mean("lagBetweenSourceAndTargetRequests_milliseconds", 1000), "Mean lag (s)"),
        P("Catch-up ETA to floor (hours)", "Hours for the 5-minute mean replay lag to reach the selected floor at "
                                           "the current lag-reduction rate; 0 when lag is already at or below it.",
          "stat", "h").q(ETA, "Hours to floor (0 = caught up)"),
        P("Actual speedup (5 min)", "1 minus the 10-minute derivative of the 5-minute mean replay lag. Above "
                                    "1 means replay is gaining on real time; 1 is real time; below 1 means lag "
                                    "is growing.",
          "stat", "short", mn=None).q(SPEEDUP, "Speedup"),
        P("Status-code match %", "Matching tuples divided by all tuples (including those without a source response), "
                                 "over the rate window.",
          "stat", "percent", mx=100).q(MATCH_PCT, "Match %"),
    ]


def kpi_target_text(x, y, w, h):
    return text("Target CPU / JVM pressure (max node %)",
                "CloudWatch-only: AWS/ES `CPUUtilization` / `JVMMemoryPressure` for the target domain are not "
                "in Prometheus.",
                x, y, w, h)


def flow_requests():
    return (P("Requests through the pipeline (per second)", "Fleet-total per-second rates at each stage, from "
                                                            "capture to tuple comparison.", unit="ops")
            .q(rate("fullRequest_count_total"), "Proxy requests captured/s")
            .q(rate("capturePublishingRecordsAccepted_records_total"), "Proxy Kafka records published/s")
            .q(rate("kafkaSourceRecordsRead_records_total"), "Replayer Kafka records read/s")
            .q(rate("targetTransactionCount_total"), "Replayer target transactions/s")
            .q(TUPLES, "Tuples compared/s"))


def flow_bytes():

    def q(m):
        return f"{rate(m)} / {MIB}"
    return (P("Bytes through the pipeline (MiB/s)", "Application-observed bytes at each stage, MiB = 1,048,576 "
                                                    "bytes. The CloudWatch version also plots the capture NLB's "
                                                    "ProcessedBytes, which has no Prometheus source.",
              unit="suffix: MiB/s")
            .q(q("bytesRead_bytes_total"), "Proxy request bytes (client → proxy)")
            .q(q("bytesWritten_bytes_total"), "Proxy response bytes (proxy → client)")
            .q(q("capturePublishingBytesAccepted_bytes_total"), "Proxy → Kafka published")
            .q(q("kafkaBytesRead_bytes_total"), "Kafka → replayer read")
            .q(q("bytesWrittenToTarget_bytes_total"), "Replayer → target requests")
            .q(q("bytesReadFromTarget_bytes_total"), "Target → replayer responses"))


def flow_lag():
    return (P("Replay lag, schedule slip and speedup",
              "Windowed mean replay lag and schedule slip (histogram sum/count rates) with the selected catch-up "
              "floor; p95 lag from buckets." + TOPB +
              " Right axis: 1 minus the 10-minute derivative of the 5-minute mean lag (speedup ×).", unit="s", runit="short", mn=None)
            .q(mean("lagBetweenSourceAndTargetRequests_milliseconds", 1000), "Mean replay lag (s)")
            .q(p95("lagBetweenSourceAndTargetRequests_milliseconds", 1000), "p95 replay lag (s)")
            .q(mean("scheduleLag_milliseconds", 1000), "Mean schedule slip (s)")
            .q(f"vector({FLOOR})", "Catch-up floor (s)")
            .q(SPEEDUP, "Actual speedup (5 min)", right=True))


def flow_eta():
    return (P("Catch-up ETA to floor (hours)",
              "Hours for the 5-minute mean replay lag to reach the selected floor at the current 10-minute lag-reduction "
              "rate; 0 when lag is already at or below the floor. Gaps are intentional while lag is above the floor "
              "and not falling by more than 0.01 s/s. This is not a Kafka backlog drain time.",
              unit="h")
            .q(ETA, "Hours to floor at current reduction rate"))


# --- capture proxy ---
def proxy_publish():
    g = "capturePublishingRecordsAwaitingAcknowledgement_records"
    return (P("Proxy → Kafka publishing", "Records accepted by the Kafka producer and acknowledged by Kafka, "
                                          "per second; right axis: records awaiting acknowledgement." + STALE,
              unit="ops", runit="short")
            .q(rate("capturePublishingRecordsAccepted_records_total"), "Records accepted/s")
            .q(rate("capturePublishingCompletions_records_total", 'outcome="acknowledged"'), "Acknowledged/s")
            .q(f"sum({g})", "Records awaiting ack (fleet)", right=True)
            .q(hottest(g), "Records awaiting ack (hottest pod)", right=True))


def proxy_latency():
    return (P("Proxy latency (mean ms)", "Windowed means (histogram sum/count rates) plus p95 of Kafka publish "
                                         "→ ack." + TOPB, unit="ms")
            .q(mean("gatheringRequestDuration_milliseconds"), "Gather request")
            .q(mean("waitingForResponseDuration_milliseconds", sel=PROXY), "Wait for source's first byte")
            .q(mean("gatheringResponseDuration_milliseconds"), "Gather source response")
            .q(mean("kafkaCommitDuration_milliseconds"), "Kafka publish → ack")
            .q(p95("kafkaCommitDuration_milliseconds"), "Kafka publish → ack p95"))


def proxy_connections():
    return (P("Proxy connections and writers", "Fleet totals of client connections and writer routing state, "
                                               "the hottest pod's connections, and connection closes per second "
                                               "by reason." + STALE, unit="short")
            .q("sum(captureConnectionsActive_connections)", "Client connections (fleet)")
            .q(hottest("captureConnectionsActive_connections"), "Client connections (hottest pod)")
            .q("sum(captureWritersActive_writers)", "Active writers (fleet)")
            .q("sum(captureWritersDraining_writers)", "Draining writers (fleet)")
            .q(f"sum by (reason) (rate(captureConnectionCloses_connections_total[{RI}]))", "Closes/s {{reason}}"))


def proxy_errors():
    p = P("Proxy faults (per minute)", "Fault counter increases per minute (rate × 60); zero when a counter has "
                                       "never incremented.", unit="short")
    for m, lab in [("capturePublisherFailures_failures_total", "Publisher failures"),
                   ("captureWritesRejected_writes_total", "Writes rejected"),
                   ("captureHeartbeatDeadlineViolations_violations_total", "Heartbeat deadline violations"),
                   ("gatheringRequestExceptionCount_total", "Request exceptions"),
                   ("gatheringResponseExceptionCount_total", "Source response exceptions"),
                   ("kafkaCommitExceptionCount_total", "Kafka commit exceptions")]:
        p.q(per_min(m), lab)
    p.q(per_min("capturePublishingCompletions_records_total", 'outcome="failed"'), "Failed publications")
    p.q("sum(captureGapActive_processes) or vector(0)", "Processes with an active capture gap")
    return p


def cadvisor(title, container):
    sel = cadv(container)
    return (P(title, f"cAdvisor metrics for container \"{container}\" in $namespace (OTel-collector re-export "
                     "only, so nothing is double counted). Right axis: working-set bytes, average and hottest "
                     "pod.",
              unit="short", runit="bytes")
            .q(f"sum(rate(container_cpu_usage_seconds_total{{{sel}}}[{RI}]))", "CPU cores (fleet)")
            .q(f"avg(sum by (pod) (container_memory_working_set_bytes{{{sel}}}))", "Working set (avg pod)", right=True)
            .q(f"max(sum by (pod) (container_memory_working_set_bytes{{{sel}}}))", "Working set (hottest pod)", right=True))


# --- kafka ---
def kafka_consume():
    return (P("Replayer consumer: read vs committed (records/s)", "Kafka records read and committed by the replayers, "
                                                                  "and commit resolutions by outcome, per second.", unit="ops")
            .q(rate("kafkaSourceRecordsRead_records_total"), "Read/s")
            .q(rate("kafkaSourceRecordsCommitted_records_total"), "Committed/s")
            .q(f"sum by (outcome) (rate(kafkaSourceCommitResolutions_events_total[{RI}]))", "Commit resolutions/s {{outcome}}"))


def kafka_backlog():
    return (P("Kafka backlog behind the replayers (records)",
              "Unread lag = end offset minus position over each replayer's assigned partitions (kafkaSourceConsumerLag), "
              "summed across the fleet; uncommitted backlog adds records the replayers hold but have not committed. "
              "Right axis: assigned partitions whose lag is unknown." + STALE,
              unit="short", runit="short")
            .q("sum(kafkaSourceConsumerLag_records)", "Unread lag (fleet)")
            .q("sum(kafkaSourceConsumerLag_records) + sum(kafkaSourceRecordsOutstanding_records)", "Uncommitted backlog = unread + "
                                                                                                   "held (fleet)")
            .q("max(kafkaSourceMaxPartitionLag_records)", "Largest single-partition lag")
            .q("sum(kafkaSourceLagUnknownPartitions_partitions)", "Partitions with unknown lag", right=True))


def kafka_commit_heads():
    g = "kafkaSourceOldestCommitHeadAge_milliseconds"
    return (P("Commit-head age (seconds since broker append)",
              "Age of the oldest uncommitted commit head on any partition (max) and the median across replayer "
              "processes; right axis: partitions whose head has been held for more than 5 minutes." + STALE,
              unit="s", runit="short")
            .q(f"max({g}) / 1000", "Oldest head (any pod)")
            .q(f"quantile(0.5, max by (exported_instance) ({g})) / 1000", "Oldest head per pod (p50 across pods)")
            .q("sum(kafkaSourceStaleCommitHeadPartitions_partitions)", "Partitions with head held > 5 min (fleet)", right=True))


def kafka_rebalance():
    p = P("Consumer-group churn (per minute)", "Consumer-group churn counter increases per minute (rate × 60), "
                                               "plus the fleet total of commit-uncertain partitions (gauge)." + STALE, unit="short")
    for m, lab in [("rebalanceCallbackCount_total", "Rebalance callbacks"),
                   ("kafkaSourceGenerationsRetired_total", "Generations retired"),
                   ("kafkaSourceRevocationsReachingDeadline_total", "Revocations reaching deadline"),
                   ("kafkaSourceRecordsCancelled_records_total", "Records cancelled"),
                   ("kafkaSourceRecordsAbandonedAtRevocation_records_total", "Records abandoned at revocation")]:
        p.q(per_min(m), lab)
    p.q("sum(replayKafkaCommitUncertainPartitions_partitions) or vector(0)", "Commit-uncertain partitions (fleet)")
    return p


def kafka_poll():
    return (P("Kafka poll and commit timing (mean ms)", "Windowed means (histogram sum/count rates) plus p95 "
                                                        "poll duration." + TOPB, unit="ms")
            .q(mean("kafkaPollDuration_milliseconds"), "Poll")
            .q(p95("kafkaPollDuration_milliseconds"), "Poll p95")
            .q(mean("commitDuration_milliseconds"), "Commit")
            .q(mean("rebalanceCallbackDuration_milliseconds"), "Rebalance callback"))


def kafka_brokers():
    w = f'sum by (pod, device) (rate(container_fs_writes_bytes_total{{{cadv("kafka")}}}[{RI}]))'
    r = f'sum by (pod, device) (rate(container_fs_reads_bytes_total{{{cadv("kafka")}}}[{RI}]))'
    return (P("Kafka broker CPU and disk (EBS) throughput",
              "Broker CPU cores (fleet) and cAdvisor filesystem throughput for the kafka containers; right axis "
              "MiB/s, fleet totals and the busiest (pod, device) volume.",
              unit="short", runit="suffix: MiB/s")
            .q(f"sum(rate(container_cpu_usage_seconds_total{{{cadv('kafka')}}}[{RI}]))", "CPU cores (fleet)")
            .q(f"sum({w}) / {MIB}", "Disk writes (fleet)", right=True)
            .q(f"sum({r}) / {MIB}", "Disk reads (fleet)", right=True)
            .q(f"max({w}) / {MIB}", "Disk writes (busiest volume)", right=True))


# --- skew ---
def skew(title, inner, desc):
    p = P(title, desc + " Each process is one exported_instance (pod_uuid);" + STALE.replace(" Per-process", " per-process")
          .replace("so fleet sums of gauges can briefly over-count", "so a restarted pod can briefly appear twice"), unit="short")
    for qn, lab in [("0.1", "p10"), ("0.5", "p50"), ("0.9", "p90")]:
        p.q(f"quantile({qn}, {inner})", lab)
    p.q(f"max({inner})", "max")
    return p


def skews():
    return [
        skew("Proxy skew: client connections per pod", "sum by (exported_instance) (captureConnectionsActive_connections)",
             "Distribution of active client connections across capture-proxy processes."),
        skew("Replayer skew: partitions owned per pod", "sum by (exported_instance) (kafkaSourceAssignedPartitions_partitions)",
             "Distribution of assigned Kafka partitions across replayer processes."),
        skew("Replayer skew: Kafka records read per pod-second", f"sum by (exported_instance) (rate(kafkaSourceRecordsRead_records_total[{RI}]))",
             "Distribution of per-process Kafka read rates (records/s) across replayers."),
        skew("Replayer skew: active target permits per pod", "sum by (exported_instance) (targetAttemptPermitsActive_permits)",
             "Distribution of active target-attempt permits across replayer processes."),
    ]


# --- intake ---
def intake_population():
    return (P("Replayer in-flight work (fleet total)", "Fleet totals of replayer in-flight populations." + STALE, unit="short")
            .q("sum(replayActiveContexts_contexts)", "Active contexts (fleet)")
            .q("sum(replayActiveOwnerOperations_operations)", "Owner operations (fleet)")
            .q("sum(replayLongRunningOwnerOperations_operations)", "Long-running owner operations (fleet)")
            .q("sum(replayIntakeActiveRecordTrackers_records)", "Record trackers (fleet)")
            .q("sum(replayIntakeRetryReadyRequestSupply_requests)", "Retry-ready request supply (fleet)"))


def intake_demand():
    return (P("Intake demand (backpressure)", "Intake demand evaluations per second by demand state (stacked).", unit="ops", stacked=True)
            .q(f"sum by (demandState) (rate(replayIntakeDemandEvaluations_evaluations_total[{RI}]))", "{{demandState}}/s"))


def intake_framing():
    return (P("Source response framing (per second)", "How captured source requests/responses were reconstituted "
                                                      "and framed, per second.", unit="ops")
            .q(rate("replayIntakeRequestsReconstituted_requests_total"), "Requests reconstituted")
            .q(rate("replayIntakeResponsesProvenComplete_responses_total"), "Responses proven complete")
            .q(rate("replayIntakeResponsesUnprovenComplete_responses_total"), "Responses unproven complete")
            .q(f"sum by (incompleteReason) (rate(replayIntakeResponsesIncomplete_responses_total[{RI}]))", "Incomplete {{incompleteReason}}")
            .q(rate("replayIntakeSourceConnectionsExpired_connections_total"), "Source connections expired"))


def intake_age():
    return (P("Oldest work (hottest pod, seconds)", "Age of the oldest open replay context and of the oldest held "
                                                    "Kafka record on the hottest process." + STALE, unit="s")
            .q("max(replayOldestContextAge_milliseconds) / 1000", "Oldest context")
            .q("max(replayOldestKafkaRecordAge_milliseconds) / 1000", "Oldest Kafka record"))


def contexts_by_activity():
    return (P("Active contexts by activity (fleet total)", "Fleet total of active replay contexts by activity." + STALE, unit="short")
            .q("sum by (activity) (replayActiveContextsByActivity_contexts)", "{{activity}}"))


def ops_by_type():
    return (P("Owner operations by type (fleet total)", "Fleet total of active owner operations by operation "
                                                        "type." + STALE, unit="short")
            .q("sum by (operationType) (replayActiveOwnerOperationsByType_operations)", "{{operationType}}"))


# --- target ---
def target_latency():
    p = P("Replayer → target latency (mean seconds)", "Windowed means (histogram sum/count rates) of each target-request "
                                                      "phase, plus p95 target transaction." + TOPB, unit="s")
    p.q(mean("targetTransactionDuration_milliseconds", 1000), "Target transaction")
    p.q(p95("targetTransactionDuration_milliseconds", 1000), "Target transaction p95")
    for h, lab in [("requestConnectingDuration", "Connect"), ("requestSendingDuration", "Send"),
                   ("waitingForResponseDuration", "Wait for response"), ("receivingResponseDuration", "Receive response")]:
        p.q(mean(h + "_milliseconds", 1000, sel=REPLAYER), lab)
    return p


def target_connections():
    return (P("Target connections", "Open target connections (fleet total gauge); right axis: connection lifecycle "
                                    "events per second." + STALE, unit="short", runit="ops")
            .q("sum(activeTargetConnections)", "Open target connections (fleet)")
            .q(rate("connectionsOpened_total"), "Opened/s", right=True)
            .q(rate("connectionsClosedCount_total"), "Closed/s", right=True)
            .q(rate("requestConnectingExceptionCount_total"), "Connect exceptions/s", right=True)
            .q(rate("nonRetryableConnectionFailures_total"), "Non-retryable failures/s", right=True))


def target_permits():
    return (P("Target permits and retries", "Active and pending target-attempt permits (fleet totals); right "
                                            "axis: retries, exhausted retries and attempts without a response, "
                                            "per second." + STALE,
              unit="short", runit="ops")
            .q("sum(targetAttemptPermitsActive_permits)", "Permits active (fleet)")
            .q("sum(targetAttemptPermitAcquisitionsPending_requests)", "Permits pending (fleet)")
            .q(rate("numRetriedRequests_count_total"), "Retried requests/s", right=True)
            .q(rate("targetRetriesExhausted_requests_total"), "Retries exhausted/s", right=True)
            .q(f"sum by (noResponseKind) (rate(targetAttemptsWithoutResponse_attempts_total[{RI}]))", "No response/s {{noResponseKind}}", right=True))


# --- correctness ---
def status_match_pct():
    return (P("Status-code match % (includes missing source responses)", "Matching tuples divided by all tuples, "
                                                                         "including tuples without a source response.", unit="percent", mx=100)
            .q(MATCH_PCT, "Match %"))


def status_mismatches():
    e = ('label_replace(sum by (method, sourceStatusCode, targetStatusCode) (increase(tupleComparison_total{statusCodesMatch="false"}[$__interval])), '
         '"sourceStatusCode", "no source response", "sourceStatusCode", "")')
    return (P("Status-code mismatches per graph period (source → target)", "Mismatched tuples counted in each "
                                                                           "graph interval by method and source → "
                                                                           "target status-code class (stacked); "
                                                                           "tuples without a source response are "
                                                                           "labelled as such.",
              unit="short", stacked=True)
            .q(e, "{{method}} {{sourceStatusCode}} → {{targetStatusCode}}"))


def status_matches():
    return (P("Status-code matches per second", "Matching tuples per second by method and status-code class (stacked).", unit="ops", stacked=True)
            .q(f'sum by (method, targetStatusCode) (rate(tupleComparison_total{{statusCodesMatch="true"}}[{RI}]))', "{{method}} {{targetStatusCode}}"))


# Per request pair, for tuples whose status codes matched, the replayer records both sides' spans from the request's
# last byte to the final response's first byte and to its last byte. Histograms can't hold the signed difference, so
# tupleResponseDurationDifference holds its magnitude with targetSlower, and tupleResponseDurationRatio holds
# max(1 ms, target) / max(1 ms, source) in log-spaced buckets around 1.
SRC_FIRST, TGT_FIRST = "tupleSourceFirstByteDuration_milliseconds", "tupleTargetFirstByteDuration_milliseconds"
SRC_TOTAL, TGT_TOTAL = "tupleSourceResponseDuration_milliseconds", "tupleTargetResponseDuration_milliseconds"
DIFF = "tupleResponseDurationDifference_milliseconds"
RATIO = "tupleResponseDurationRatio"
SPANS = [("firstByte", "To first byte"), ("total", "To response complete")]
COMPARE = (" Values are per request pair, for requests whose status codes matched; the target is measured under "
           "replay load and speedup.")


def signed_mean_difference(span):
    """The exact mean of target − source: slower magnitudes less the others, over every pair."""
    def total(slower):
        return f"(sum(rate({DIFF}_sum{{span=\"{span}\",targetSlower=\"{slower}\"}}[{RI}])) or vector(0))"
    return f"({total('true')} - {total('false')}) / sum(rate({DIFF}_count{{span=\"{span}\"}}[{RI}]))"


def ratio_pct(quantile, span):
    return (f"100 * (histogram_quantile({quantile}, sum by (le) (rate({RATIO}_bucket{{span=\"{span}\"}}[{RI}]))) "
            "- 1)")


def response_times():
    return (P("Response time, source vs target (mean ms)", "Time from the request's last byte to the first byte "
              "and to the last byte of the final response, on each side, plus p95 of the complete response, from "
              "buckets topped at 2 minutes." + COMPARE, unit="ms")
            .q(mean(SRC_FIRST), "Source: request → first byte")
            .q(mean(TGT_FIRST), "Target: request → first byte")
            .q(mean(SRC_TOTAL), "Source: request → response complete")
            .q(mean(TGT_TOTAL), "Target: request → response complete")
            .q(p95(SRC_TOTAL), "Source: response complete p95")
            .q(p95(TGT_TOTAL), "Target: response complete p95"))


def response_time_diff():
    p = P("Response time difference, target − source (mean ms)", "Mean of each pair's target − source; positive "
          "means the target is slower. The p95 lines are of the difference's magnitude, split by whether the target "
          "was slower (a tie counts as not slower)." + COMPARE, unit="ms", mn=None)
    for span, label in SPANS:
        p.q(signed_mean_difference(span), label)
    p.q(f'histogram_quantile(0.95, sum by (le, targetSlower) (rate({DIFF}_bucket{{span="total"}}[{RI}])))',
        "To response complete: p95 |target − source|, target slower={{targetSlower}}")
    return p


def response_time_pct():
    p = P("Response time difference, target vs source (%)", "Median (p50) over pairs of target / source − 1, with p10 "
          "and p90; positive means the target is slower. The ratio floors each side's duration at 1 ms, so "
          "sub-millisecond responses don't produce extreme ratios. Percentiles interpolate within log-spaced "
          "buckets from 0.01 to 100." + COMPARE, unit="percent", mn=None)
    for span, label in SPANS:
        p.q(ratio_pct(0.5, span), f"{label} p50")
        p.q(ratio_pct(0.1, span), f"{label} p10")
        p.q(ratio_pct(0.9, span), f"{label} p90")
    return p


# --- tuples ---
def tuple_flow():
    return (P("Tuple durability (S3)", "Tuple writes accepted and made durable per second; right axis: writes "
                                       "pending (fleet total)." + STALE, unit="ops", runit="short")
            .q(rate("tupleDurabilityWritesAccepted_writes_total"), "Accepted/s")
            .q(rate("tupleDurabilityWritesDurable_writes_total"), "Durable/s")
            .q("sum(tupleDurabilityWritesPending_writes)", "Pending (fleet)", right=True))


def tuple_latency():
    return (P("Tuple durability latency (seconds)", "Windowed mean and p95 tuple write latency, and the oldest "
                                                    "context still comparing results (pending tuple) on the hottest "
                                                    "process." + TOPB, unit="s")
            .q(mean("tupleDurabilityWriteLatency_milliseconds", 1000), "Mean write latency")
            .q(p95("tupleDurabilityWriteLatency_milliseconds", 1000), "p95 write latency")
            .q('max(replayOldestContextAgeByActivity_milliseconds{activity="comparingResults"}) / 1000', "Oldest pending tuple (hottest "
                                                                                                         "pod)"))


def tuple_errors():
    p = P("Tuple / S3 faults (per minute)", "Tuple and S3 fault counter increases per minute (rate × 60); zero "
                                            "when a counter has never incremented.", unit="short")
    for m, lab in [("tupleSinkUploadRetryAttempts_retries_total", "S3 upload retries"),
                   ("tupleSinkUploadRecoveries_objects_total", "S3 upload recoveries"),
                   ("tupleDurabilityRetries_retries_total", "Outer tuple retries"),
                   ("tupleDurabilityWritesCancelled_writes_total", "Writes cancelled"),
                   ("tupleDurabilityTerminalFailures_writes_total", "Terminal failures")]:
        p.q(per_min(m), lab)
    p.q("sum(tupleSinkUploadsRetrying_objects) or vector(0)", "S3 objects awaiting retry (now)")
    return p


def resources_owned():
    return (P("Replayer owned payload memory (fleet MiB)", "Fleet total of payload bytes owned by replayer resources, "
                                                           "by resource type." + STALE, unit="bytes")
            .q("sum by (resourceType) (resourceOwnedBytes_bytes)", "{{resourceType}}"))


def fatal():
    p = P("Replayer invariant and fatal failures (per minute)", "Fatal / safety counter increases per minute "
                                                                "(rate × 60); zero when a counter has never incremented.", unit="short")
    for m, lab in [("replayFatalFailures_failures_total", "Fatal failures"),
                   ("replayIntakeCaptureProtocolViolations_violations_total", "Capture protocol violations"),
                   ("resourceOwnershipInvariantFailures_failures_total", "Ownership invariant failures"),
                   ("replayIntakeStaleGenerationInputsIgnored_inputs_total", "Stale-generation inputs ignored")]:
        p.q(per_min(m), lab)
    return p


def pods(container):
    return f"count(count by (pod) (container_memory_working_set_bytes{{{cadv(container)}}}))"


def fleet_proxy():
    return P("Capture proxy pods and source domain nodes", "Capture proxy pods with a cAdvisor sample. Source domain "
                                                           "node counts are CloudWatch-only (AWS/ES Nodes).",
             unit="short").q(pods("proxy"), "Proxy pods running")


def fleet_kafka():
    return P("Kafka broker pods", "Kafka broker pods with a cAdvisor sample.", unit="short").q(
        pods("kafka"), "Broker pods running")


def fleet_replayer():
    return P("Replayer pods", "Replayer pods with a cAdvisor sample.", unit="short").q(
        pods("replayer"), "Replayer pods running")


NOTE = ("**How to read this dashboard.** Prometheus twin of the CloudWatch *capture-replay* dashboard, same layout. "
        "Rates are fleet totals per second. Gauges marked *fleet* are sums over per-process series (pod_uuid); "
        "*hottest pod* is the largest single process. Per-process series from restarted pods linger up to 5 minutes "
        "(exporter metric_expiration), so fleet gauge sums can briefly over-count after churn. Durations show the "
        "windowed mean; p95 lines come from buckets topped at 10 s. MiB = 1,048,576 bytes. AWS/ES domain and NLB "
        "panels are CloudWatch-only. Proxy panels are empty when the capture proxy is scaled to 0.")


def layout_grid(ps, y):
    sk = skews()
    grid = [
        ("Throughput", [proxy_publish(), kafka_consume(), intake_framing(), target_connections()]),
        ("Backlog", [proxy_connections(), kafka_backlog(), intake_population(), target_permits()]),
        ("Latency / age", [proxy_latency(), kafka_commit_heads(), intake_age(), target_latency()]),
        ("Skew across pods (p10 / p50 / p90 / max)", sk),
        ("Faults", [proxy_errors(), kafka_rebalance(), fatal(), None]),
        ("Compute", [cadvisor("Capture proxy CPU and memory", "proxy"), kafka_brokers(),
                     cadvisor("Replayer CPU and memory", "replayer"), kafka_poll()]),
        ("Fleet size", [fleet_proxy(), fleet_kafka(), fleet_replayer(), "target-nodes"]),
    ]
    for gi, (name, cells) in enumerate(grid):
        ps.append(row(f"{name} — Capture proxy | Kafka → replayer consumer | Replayer intake | Replayer → target", y))
        y += 1
        if gi == 0:
            for i, h in enumerate(["Capture proxy", "Kafka → replayer consumer", "Replayer intake", "Replayer → target"]):
                ps.append(header(f"### {h}", i * 6, y, 6))
            y += 1
        # The backlog panels carry the longest legends.
        h = 8 if name == "Backlog" else 7
        for i, c in enumerate(cells):
            if c is None:
                ps.append(text("Target domain health (TARGET_DOMAIN)",
                               CW_ONLY.format(extra=", IndexingLatency", which="target"), i * 6, y, 6, h))
            elif c == "target-nodes":
                ps.append(text("Target domain nodes", "This panel exists only on the CloudWatch dashboard: it reads "
                               "the AWS/ES Nodes metric for the target domain, which is not exported to Prometheus.",
                               i * 6, y, 6, h))
            else:
                ps.append(c.build(i * 6, y, 6, h))
        y += h
    return y


def layout():
    ps, y = [], 0
    ps.append(text("", NOTE, 0, y, 24, 2))
    y += 2
    x = 0
    for p, wd in zip(kpis(), [3, 4, 3, 3, 3, 4]):
        ps.append(p.build(x, y, wd, 3))
        x += wd
    ps.append(kpi_target_text(x, y, 4, 3))
    y += 3
    ps.append(row("Response comparisons", y))
    y += 1
    for i, b in enumerate([status_match_pct, status_mismatches, status_matches]):
        ps.append(b().build(i * 8, y, 8, 6))
    y += 6
    for i, b in enumerate([response_times, response_time_diff, response_time_pct]):
        ps.append(b().build(i * 8, y, 8, 6))
    y += 6
    ps.append(row("End-to-end flow", y))
    y += 1
    for i, b in enumerate([flow_requests, flow_bytes, flow_lag]):
        ps.append(b().build(i * 8, y, 8, 7))
    y += 7
    y = layout_grid(ps, y)
    ps.append(row("Result tuples", y))
    y += 1
    for i, b in enumerate([tuple_flow, tuple_latency, tuple_errors]):
        ps.append(b().build(i * 8, y, 8, 6))
    y += 6
    ps.append(row("Source cluster and replayer internals", y))
    y += 1
    ps.append(text("Source domain health (SOURCE_DOMAIN)", CW_ONLY.format(extra="", which="source"), 0, y, 6, 7))
    for i, b in enumerate([contexts_by_activity, ops_by_type, resources_owned], 1):
        ps.append(b().build(i * 6, y, 6, 7))
    y += 7
    ps.append(cadvisor("OTel collector (telemetry pipeline) CPU and memory", "otel-collector").build(0, y, 8, 7))
    ps.append(flow_eta().build(8, y, 8, 7))
    ps.append(intake_demand().build(16, y, 8, 7))
    return ps


TEMPLATING = json.loads(r'''
{"list": [
  {"name": "DS_PROMETHEUS", "type": "datasource", "query": "prometheus", "current": {}},
  {"name": "catchup_floor_seconds", "type": "custom", "label": "Catch-up lag floor",
   "query": "1 second : 1,5 seconds : 5,1 minute : 60",
   "current": {"selected": true, "text": "5 seconds", "value": "5"},
   "options": [{"selected": false, "text": "1 second", "value": "1"}, {"selected": true, "text": "5 seconds", "value": "5"},
               {"selected": false, "text": "1 minute", "value": "60"}],
   "includeAll": false, "multi": false},
  {"name": "namespace", "type": "query", "label": "Namespace", "datasource": {"type": "prometheus", "uid": "${DS_PROMETHEUS}"},
   "definition": "label_values(container_cpu_usage_seconds_total{container=\"replayer\"}, namespace)",
   "query": {"query": "label_values(container_cpu_usage_seconds_total{container=\"replayer\"}, namespace)",
             "refId": "PrometheusVariableQueryEditor-VariableQuery"},
   "current": {"selected": true, "text": "ma", "value": "ma"}, "includeAll": false, "multi": false, "refresh": 1}
]}''')


def main(out):
    panels = layout()
    for i, p in enumerate(panels, 1):
        p["id"] = i
    dash = {"id": None, "uid": "capture-replay-observability", "title": "Capture and Replay Observability",
            "tags": ["capture", "replay", "kubernetes"], "timezone": "browser", "schemaVersion": 39, "version": 4,
            "refresh": "15s", "time": {"from": "now-6h", "to": "now"}, "panels": panels, "templating": TEMPLATING}
    body = json.dumps(dash, indent=2, ensure_ascii=False)
    # Helm would treat {{ }} as template actions; Grafana legend placeholders are unicode-escaped instead.
    body = body.replace("{{", "\\u007b\\u007b").replace("}}", "\\u007d\\u007d")
    json.loads(body)
    indented = "\n".join(("    " + line) if line else line for line in body.splitlines())
    head = ("{{- if .Values.metrics.enabled }}\napiVersion: v1\nkind: ConfigMap\nmetadata:\n"
            "  name: capture-replay-observability-dashboard\n  labels:\n    grafana_dashboard: \"1\"\ndata:\n"
            "  capture-replay-observability.json: |\n")
    open(out, "w").write(head + indented + "\n{{- end }}\n")
    print(out, len(panels), "panels")


if __name__ == "__main__":
    main(sys.argv[1])
