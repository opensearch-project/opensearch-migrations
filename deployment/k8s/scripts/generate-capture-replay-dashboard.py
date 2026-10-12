"""Generate the capture/replay CloudWatch dashboard installed by the migrationAssistantWithArgo chart.

usage: generate-capture-replay-dashboard.py columns <out.json>

The chart's files/cloudwatch-dashboards/capture-replay-dashboard.json is this script's output with the
domain-name placeholders blanked, so edit here and regenerate rather than hand-editing the JSON.

Semantics this generator encodes (verified against dev2 on 2026-10-11):
  * Application metrics drop service.instance.id, so every pod writes one shared series.
    Pods export every 1 s (RootOtelContext PeriodicMetricReader), so for gauges
    Sum/PERIOD = time-averaged fleet total, Maximum = hottest pod, SampleCount/PERIOD = pods reporting.
  * Counters are delta (cumulativetodelta): Sum/PERIOD = fleet rate.
  * Histograms arrive with Min = Max = 0, so only Average (Sum/Count) is meaningful.
  * cAdvisor counters are scraped every 5 s and delta-converted by awsemf:
    Sum/PERIOD = fleet cores, Maximum/5 = hottest pod cores.
"""
import json
import sys

NS = "OpenSearchMigrations"
Q = "MA_QUALIFIER"
R = "placeholder-region"


class W:
    """One metric widget; collects metric rows and returns a dashboard widget dict."""

    def __init__(self, title, view="timeSeries", stacked=False, yleft=None, yright=None, period=60, stat="Sum"):
        self.title, self.view, self.stacked, self.period, self.stat = title, view, stacked, period, stat
        self.rows, self.n, self.yleft, self.yright = [], 0, yleft, yright

    def _id(self, p):
        self.n += 1
        return f"{p}{self.n}"

    def m(self, name, stat="Sum", lib="replayer", dims=None, **opts):
        """Hidden raw metric; returns its id."""
        i = self._id("m")
        row = [NS, name] + (["OTelLib", lib] if lib else []) + ["qualifier", Q]
        for k, v in (dims or {}).items():
            row += [k, v]
        self.rows.append(row + [dict({"id": i, "stat": stat, "visible": False}, **opts)])
        return i

    def raw(self, ns, name, dims, stat, **opts):
        i = self._id("m")
        row = [ns, name]
        for k, v in dims.items():
            row += [k, v]
        self.rows.append(row + [dict({"id": i, "stat": stat, "visible": False}, **opts)])
        return i

    def e(self, expr, label, visible=True, **opts):
        i = self._id("e")
        self.rows.append([dict({"expression": expr, "label": label, "id": i, "visible": visible}, **opts)])
        return i

    # --- semantic helpers -------------------------------------------------
    def rate(self, name, label, lib="replayer", dims=None, scale=1, **opts):
        s = self.m(name, "Sum", lib, dims)
        return self.e(f"FILL({s}, 0) / PERIOD({s})" + (f" / {scale}" if scale != 1 else ""), label, **opts)

    def gauge(self, name, label, lib="replayer", fleet=True, hottest=True, **opts):
        out = []
        if fleet:
            s = self.m(name, "Sum", lib)
            out.append(self.e(f"{s} / PERIOD({s})", f"{label} (fleet)", **opts))
        if hottest:
            mx = self.m(name, "Maximum", lib)
            out.append(self.e(mx, f"{label} (hottest pod)", **opts))
        return out

    def avg(self, name, label, lib="replayer", scale=1, **opts):
        a = self.m(name, "Average", lib)
        return self.e(a if scale == 1 else f"{a} / {scale}", label, **opts)

    def search_rate(self, name, dim, label, lib="replayer", extra="", **opts):
        q = (f"SEARCH('{{{NS},OTelLib,qualifier,{dim}}} MetricName=\"{name}\" OTelLib=\"{lib}\" "
             f"qualifier=\"{Q}\"{extra}', 'Sum', 60)")
        return self.e(f"{q} / 60", label, **opts)

    def widget(self, x, y, w, h):
        p = {"title": self.title, "view": self.view, "stacked": self.stacked, "region": R,
             "period": self.period, "stat": self.stat, "metrics": self.rows}
        yaxis = {}
        if self.yleft:
            yaxis["left"] = self.yleft
        if self.yright:
            yaxis["right"] = self.yright
        if yaxis:
            p["yAxis"] = yaxis
        if self.view == "singleValue":
            p["sparkline"] = True
            p.pop("stacked")
        return {"type": "metric", "x": x, "y": y, "width": w, "height": h, "properties": p}


def text(md, x, y, w, h=1):
    return {"type": "text", "x": x, "y": y, "width": w, "height": h, "properties": {"markdown": md, "background": "transparent"}}


def tuple_search(match=None):
    """Both tupleComparison schemas: with sourceStatusCode and without it (source response missing)."""
    m = f" statusCodesMatch=\"{match}\"" if match else ""
    with_src = (f"SEARCH('{{{NS},OTelLib,method,qualifier,sourceStatusCode,statusCodesMatch,targetStatusCode}} "
                f"MetricName=\"tupleComparison\" OTelLib=\"replayer\" qualifier=\"{Q}\"{m}', 'Sum', 60)")
    no_src = (f"SEARCH('{{{NS},OTelLib,method,qualifier,statusCodesMatch,targetStatusCode}} "
              f"MetricName=\"tupleComparison\" OTelLib=\"replayer\" qualifier=\"{Q}\"{m}', 'Sum', 60)")
    return with_src, no_src


def es(w, metric, stat, label, domain_var="TARGET_DOMAIN", scale=None, **opts):
    q = f"SEARCH('{{AWS/ES,ClientId,DomainName}} MetricName=\"{metric}\" DomainName=\"{domain_var}\"', '{stat}', 60)"
    expr = f"MAX({q})" if stat in ("Maximum", "Average") else f"SUM({q})"
    if scale:
        expr = f"{expr} / {scale}"
    return w.e(expr, label, **opts)


MIB = 1048576

# ----------------------------------------------------------------------------------------------
# Widget catalogue.  Each entry builds a W; layouts below only decide placement.
# ----------------------------------------------------------------------------------------------


def kpis():
    out = []
    w = W("Captured requests/s", "singleValue")
    w.rate("fullRequest", "Captured req/s", lib="captureProxy")
    out.append(w)
    w = W("Replayed tuples/s", "singleValue")
    a, b = tuple_search()
    w.e(f"(SUM({a}) + FILL(SUM({b}), 0)) / 60", "Replayed tuples/s")
    out.append(w)
    w = W("Mean replay lag (s)", "singleValue")
    w.avg("lagBetweenSourceAndTargetRequests", "Mean lag (s)", scale=1000)
    out.append(w)
    w = W("Actual speedup (5 min)", "singleValue", period=300)
    lag = w.m("lagBetweenSourceAndTargetRequests", "Average", period=300)
    s = w.e(f"{lag} / 1000", "lag s", visible=False)
    w.e(f"IF(DIFF_TIME({s}) > PERIOD({s}), 0/0, 1 - RATE({s}))", "Speedup")
    out.append(w)
    w = W("Status-code match %", "singleValue")
    a, b = tuple_search()
    ma, _ = tuple_search("true")
    w.e(f"100 * SUM({ma}) / (SUM({a}) + FILL(SUM({b}), 0))", "Match %")
    out.append(w)
    w = W("Pods reporting", "singleValue")
    r = w.m("replayActiveContexts", "SampleCount")
    w.e(f"{r} / PERIOD({r})", "Replayers")
    p = w.m("captureConnectionsActive", "SampleCount", lib="captureProxy")
    w.e(f"FILL({p}, 0) / PERIOD({p})", "Proxies")
    out.append(w)
    w = W("Target CPU / JVM pressure (max node %)", "singleValue")
    es(w, "CPUUtilization", "Maximum", "CPU %")
    es(w, "JVMMemoryPressure", "Maximum", "JVM %")
    out.append(w)
    return out


def flow_requests():
    w = W("Requests through the pipeline (per second)")
    w.rate("fullRequest", "Proxy requests captured/s", lib="captureProxy")
    w.e(f"SUM(SEARCH('{{{NS},OTelLib,qualifier,recordKind}} MetricName=\"capturePublishingRecordsAccepted\" OTelLib=\"captureProxy\" qualifier=\"{Q}\"', 'Sum', 60)) / 60",
        "Proxy Kafka records published/s")
    w.rate("kafkaSourceRecordsRead", "Replayer Kafka records read/s")
    w.rate("targetTransactionCount", "Replayer target transactions/s")
    a, b = tuple_search()
    w.e(f"(SUM({a}) + FILL(SUM({b}), 0)) / 60", "Tuples compared/s")
    return w


def flow_bytes():
    w = W("Bytes through the pipeline (MiB/s)")
    w.e(f"SUM(SEARCH('{{AWS/NetworkELB,LoadBalancer}} MetricName=\"ProcessedBytes\" capturep', 'Sum', 60)) / 60 / {MIB}",
        "Capture NLB processed (both directions)")
    w.rate("bytesRead", "Proxy request bytes (client → proxy)", lib="captureProxy", scale=MIB)
    w.rate("bytesWritten", "Proxy response bytes (proxy → client)", lib="captureProxy", scale=MIB)
    w.e(f"SUM(SEARCH('{{{NS},OTelLib,qualifier,recordKind}} MetricName=\"capturePublishingBytesAccepted\" OTelLib=\"captureProxy\" qualifier=\"{Q}\"', 'Sum', 60)) / 60 / {MIB}",
        "Proxy → Kafka published")
    w.rate("kafkaBytesRead", "Kafka → replayer read", scale=MIB)
    w.rate("bytesWrittenToTarget", "Replayer → target requests", scale=MIB)
    w.rate("bytesReadFromTarget", "Target → replayer responses", scale=MIB)
    return w


def flow_lag():
    w = W("Replay lag, schedule slip and speedup", period=60,
          yleft={"label": "seconds", "showUnits": False}, yright={"label": "speedup ×", "showUnits": False})
    lag = w.m("lagBetweenSourceAndTargetRequests", "Average")
    s = w.e(f"{lag} / 1000", "Mean replay lag (s)")
    w.avg("scheduleLag", "Mean schedule slip (s)", scale=1000)
    w.e("TIME_SERIES(CATCHUP_FLOOR_SECONDS)", "Catch-up floor (s)", color="#7f7f7f")
    lag5 = w.m("lagBetweenSourceAndTargetRequests", "Average", period=300)
    s5 = w.e(f"{lag5} / 1000", "lag 5m", visible=False)
    w.e(f"IF(DIFF_TIME({s5}) > PERIOD({s5}), 0/0, 1 - RATE({s5}))", "Actual speedup (5 min)", yAxis="right")
    return w


def flow_eta():
    w = W("Catch-up ETA to floor (hours)", period=300)
    lag = w.m("lagBetweenSourceAndTargetRequests", "Average", period=300)
    s = w.e(f"{lag} / 1000", "lag s", visible=False)
    red = w.e(f"-1 * RATE({s})", "lag reduction s/s", visible=False)
    w.e(f"IF({s} > CATCHUP_FLOOR_SECONDS, IF({red} > 0.01, (({s} - CATCHUP_FLOOR_SECONDS) / 3600) / {red}, 0/0), 0/0)",
        "Hours to floor at current reduction rate")
    return w


# --- capture proxy ---------------------------------------------------------------------------
def proxy_publish():
    w = W("Proxy → Kafka publishing", yright={"label": "awaiting ack", "showUnits": False})
    sr = lambda n, st: f"SUM(SEARCH('{{{NS},OTelLib,qualifier,recordKind}} MetricName=\"{n}\" OTelLib=\"captureProxy\" qualifier=\"{Q}\"', '{st}', 60))"
    w.e(f"{sr('capturePublishingRecordsAccepted', 'Sum')} / 60", "Records accepted/s")
    w.e(f"SUM(SEARCH('{{{NS},OTelLib,outcome,qualifier,recordKind}} MetricName=\"capturePublishingCompletions\" OTelLib=\"captureProxy\" qualifier=\"{Q}\" outcome=\"acknowledged\"', 'Sum', 60)) / 60", "Acknowledged/s")
    w.e(f"{sr('capturePublishingRecordsAwaitingAcknowledgement', 'Sum')} / 60", "Records awaiting ack (fleet)", yAxis="right")
    w.e(f"{sr('capturePublishingRecordsAwaitingAcknowledgement', 'Maximum')}", "Records awaiting ack (hottest pod)", yAxis="right")
    return w


def proxy_latency():
    w = W("Proxy latency (mean ms)")
    w.avg("gatheringRequestDuration", "Gather request", lib="captureProxy")
    w.avg("gatheringResponseDuration", "Gather response (source latency)", lib="captureProxy")
    w.avg("kafkaCommitDuration", "Kafka publish → ack", lib="captureProxy")
    w.avg("captureConnectionDuration", "Client connection lifetime", lib="captureProxy", visible=False)
    return w


def proxy_connections():
    w = W("Proxy connections and writers")
    w.gauge("captureConnectionsActive", "Client connections", lib="captureProxy")
    w.gauge("captureWritersActive", "Active writers", lib="captureProxy", hottest=False)
    w.gauge("captureWritersDraining", "Draining writers", lib="captureProxy", hottest=False)
    w.search_rate("captureConnectionCloses", "reason", "Closes/s ${PROP('Dim.reason')}", lib="captureProxy")
    return w


def proxy_errors():
    w = W("Proxy faults (per minute)")
    for n, lab in [("capturePublisherFailures", None), ("captureWritesRejected", "Writes rejected"),
                   ("captureHeartbeatDeadlineViolations", "Heartbeat deadline violations"),
                   ("gatheringRequestExceptionCount", "Request exceptions"),
                   ("gatheringResponseExceptionCount", "Source response exceptions"),
                   ("kafkaCommitExceptionCount", "Kafka commit exceptions")]:
        if lab is None:
            w.e(f"SUM(SEARCH('{{{NS},OTelLib,qualifier,reason}} MetricName=\"{n}\" OTelLib=\"captureProxy\" qualifier=\"{Q}\"', 'Sum', 60))",
                "Publisher failures")
        else:
            s = w.m(n, "Sum", "captureProxy")
            w.e(f"FILL({s}, 0)", lab)
    w.e(f"SUM(SEARCH('{{{NS},OTelLib,outcome,qualifier,recordKind}} MetricName=\"capturePublishingCompletions\" OTelLib=\"captureProxy\" qualifier=\"{Q}\" outcome=\"failed\"', 'Sum', 60))",
        "Failed publications")
    w.e(f"SUM(SEARCH('{{{NS},OTelLib,captureActivationId,mode,processId,qualifier,sourceForwardingContinued}} MetricName=\"captureGapActive\" OTelLib=\"captureProxy\" qualifier=\"{Q}\"', 'Maximum', 60))",
        "Processes with an active capture gap")
    return w


def cadvisor(title, container):
    # cAdvisor deltas arrive lumpy (scrapes are dropped as duplicates and re-delivered), so per-minute
    # CPU swings several-fold; 5-minute buckets track Prometheus rate(...[5m]) within a few percent.
    w = W(title, period=300, yright={"label": "MiB", "showUnits": False})
    c = w.raw(NS, "container_cpu_usage_seconds_total", {"container": container, "namespace": "ma"}, "Sum", period=300)
    w.e(f"{c} / PERIOD({c})", "CPU cores (fleet, 5 min)")
    ma = w.raw(NS, "container_memory_working_set_bytes", {"container": container, "namespace": "ma"}, "Average")
    w.e(f"{ma} / {MIB}", "Working set MiB (avg pod)", yAxis="right")
    mm = w.raw(NS, "container_memory_working_set_bytes", {"container": container, "namespace": "ma"}, "Maximum")
    w.e(f"{mm} / {MIB}", "Working set MiB (hottest pod)", yAxis="right")
    return w


# --- kafka -----------------------------------------------------------------------------------
def kafka_consume():
    w = W("Replayer consumer: read vs committed (records/s)")
    w.rate("kafkaSourceRecordsRead", "Read/s")
    w.rate("kafkaSourceRecordsCommitted", "Committed/s")
    w.search_rate("kafkaSourceCommitResolutions", "outcome", "Commit resolutions/s ${PROP('Dim.outcome')}")
    return w


def kafka_outstanding():
    w = W("Records held by the replayer (uncommitted)", yright={"label": "ms", "showUnits": False})
    w.gauge("kafkaSourceRecordsOutstanding", "Records outstanding")
    w.e(w.m("replayOldestKafkaRecordAge", "Maximum"), "Oldest held record age ms (hottest pod)", yAxis="right")
    u = w.m("replayKafkaCommitUncertainPartitions", "Maximum")
    w.e(f"FILL({u}, 0)", "Commit-uncertain partitions (hottest pod)")
    return w


def kafka_rebalance():
    w = W("Consumer-group churn (per minute)")
    for n, lab in [("rebalanceCallbackCount", "Rebalance callbacks"),
                   ("kafkaSourceGenerationsRetired", "Generations retired"),
                   ("kafkaSourceRevocationsReachingDeadline", "Revocations reaching deadline"),
                   ("kafkaSourceRecordsCancelled", "Records cancelled")]:
        s = w.m(n, "Sum")
        w.e(f"FILL({s}, 0)", lab)
    w.e(f"SUM(SEARCH('{{{NS},OTelLib,cause,qualifier}} MetricName=\"kafkaSourceRecordsAbandonedAtRevocation\" OTelLib=\"replayer\" qualifier=\"{Q}\"', 'Sum', 60))",
        "Records abandoned at revocation")
    u = w.m("replayKafkaCommitUncertainPartitions", "Sum")
    w.e(f"FILL({u}, 0) / PERIOD({u})", "Commit-uncertain partitions (fleet)")
    return w


def kafka_poll():
    w = W("Kafka poll and commit timing (mean ms)")
    w.avg("kafkaPollDuration", "Poll")
    w.avg("commitDuration", "Commit")
    w.avg("rebalanceCallbackDuration", "Rebalance callback")
    return w


def kafka_backlog():
    w = W("Kafka backlog behind the replayers (records)", yright={"label": "partitions", "showUnits": False})
    lag = w.m("kafkaSourceConsumerLag", "Sum")
    w.e(f"{lag} / PERIOD({lag})", "Unread lag (fleet)")
    held = w.m("kafkaSourceRecordsOutstanding", "Sum")
    # CloudWatch math treats an absent series as contributing nothing, so without the guard a replayer that
    # does not report lag would draw held records alone under the backlog label.
    reporting = w.m("kafkaSourceConsumerLag", "SampleCount")
    w.e(f"IF({reporting} > 0, ({lag} + {held}) / PERIOD({lag}))", "Uncommitted backlog = unread + held (fleet)")
    w.e(w.m("kafkaSourceMaxPartitionLag", "Maximum"), "Largest single-partition lag")
    unk = w.m("kafkaSourceLagUnknownPartitions", "Sum")
    w.e(f"{unk} / PERIOD({unk})", "Partitions with unknown lag", yAxis="right")
    return w


def kafka_commit_heads():
    w = W("Commit-head age (seconds since broker append)", yright={"label": "partitions", "showUnits": False})
    w.e(f"{w.m('kafkaSourceOldestCommitHeadAge', 'Maximum')} / 1000", "Oldest head (any pod)")
    w.e(f"{w.m('kafkaSourceOldestCommitHeadAge', 'p50')} / 1000", "Oldest head per pod (p50 across pods)")
    st = w.m("kafkaSourceStaleCommitHeadPartitions", "Sum")
    w.e(f"{st} / PERIOD({st})", "Partitions with head held > 5 min (fleet)", yAxis="right")
    return w


def kafka_brokers():
    w = W("Kafka broker CPU and disk (EBS) throughput", period=300, yright={"label": "MiB/s", "showUnits": False})
    c = w.raw(NS, "container_cpu_usage_seconds_total", {"container": "kafka", "namespace": "ma"}, "Sum", period=300)
    w.e(f"{c} / PERIOD({c})", "CPU cores (fleet, 5 min)")
    disk = "{OpenSearchMigrations,container,device,namespace,pod} container=\"kafka\" MetricName=\"%s\""
    w.e(f"SUM(SEARCH('{disk % 'container_fs_writes_bytes_total'}', 'Sum', 300)) / 300 / {MIB}", "Disk writes (fleet)", yAxis="right")
    w.e(f"SUM(SEARCH('{disk % 'container_fs_reads_bytes_total'}', 'Sum', 300)) / 300 / {MIB}", "Disk reads (fleet)", yAxis="right")
    w.e(f"MAX(SEARCH('{disk % 'container_fs_writes_bytes_total'}', 'Sum', 300)) / 300 / {MIB}", "Disk writes (busiest volume)", yAxis="right")
    return w


def pct(w, name, label, lib="replayer", scale=1):
    for p in ("p10", "p50", "p90", "Maximum"):
        i = w.m(name, p, lib)
        w.e(i if scale == 1 else f"{i} / {scale}", f"{label} {p.replace('Maximum', 'max')}")


def skew_proxy():
    w = W("Proxy skew: client connections per pod")
    pct(w, "captureConnectionsActive", "", lib="captureProxy")
    return w


def skew_kafka():
    w = W("Replayer skew: partitions owned per pod")
    pct(w, "kafkaSourceAssignedPartitions", "")
    return w


def skew_replayer():
    w = W("Replayer skew: Kafka records read per pod-second")
    pct(w, "kafkaSourceRecordsRead", "")
    return w


def skew_target():
    w = W("Replayer skew: active target permits per pod")
    pct(w, "targetAttemptPermitsActive", "")
    return w


# --- replayer intake / scheduling --------------------------------------------------------------
def intake_population():
    w = W("Replayer in-flight work (fleet total)")
    w.gauge("replayActiveContexts", "Active contexts", hottest=False)
    w.gauge("replayActiveOwnerOperations", "Owner operations", hottest=False)
    w.gauge("replayLongRunningOwnerOperations", "Long-running owner operations", hottest=False)
    w.gauge("replayIntakeActiveRecordTrackers", "Record trackers", hottest=False)
    w.gauge("replayIntakeRetryReadyRequestSupply", "Retry-ready request supply", hottest=False)
    return w


def intake_demand():
    w = W("Intake demand (backpressure)", stacked=True)
    w.search_rate("replayIntakeDemandEvaluations", "demandState", "${PROP('Dim.demandState')}/s")
    return w


def intake_framing():
    w = W("Source response framing (per second)")
    w.rate("replayIntakeRequestsReconstituted", "Requests reconstituted")
    w.rate("replayIntakeResponsesProvenComplete", "Responses proven complete")
    w.rate("replayIntakeResponsesUnprovenComplete", "Responses unproven complete")
    w.search_rate("replayIntakeResponsesIncomplete", "incompleteReason", "Incomplete ${PROP('Dim.incompleteReason')}")
    w.rate("replayIntakeSourceConnectionsExpired", "Source connections expired")
    return w


def intake_age():
    w = W("Oldest work (hottest pod, seconds)")
    w.e(f"{w.m('replayOldestContextAge', 'Maximum')} / 1000", "Oldest context")
    w.e(f"{w.m('replayOldestKafkaRecordAge', 'Maximum')} / 1000", "Oldest Kafka record")
    w.e(f"MAX(SEARCH('{{{NS},OTelLib,activity,qualifier}} MetricName=\"replayOldestContextAgeByActivity\" OTelLib=\"replayer\" qualifier=\"{Q}\"', 'Maximum', 60)) / 1000",
        "Oldest by any activity")
    return w


def contexts_by_activity():
    w = W("Active contexts by activity (fleet total)")
    w.e(f"SEARCH('{{{NS},OTelLib,activity,qualifier}} MetricName=\"replayActiveContextsByActivity\" OTelLib=\"replayer\" qualifier=\"{Q}\"', 'Sum', 60) / 60",
        "${PROP('Dim.activity')}")
    return w


def ops_by_type():
    w = W("Owner operations by type (fleet total)")
    w.e(f"SEARCH('{{{NS},OTelLib,operationType,qualifier}} MetricName=\"replayActiveOwnerOperationsByType\" OTelLib=\"replayer\" qualifier=\"{Q}\"', 'Sum', 60) / 60",
        "${PROP('Dim.operationType')}")
    return w


# --- target ------------------------------------------------------------------------------------
def target_latency():
    w = W("Replayer → target latency (mean seconds)")
    w.avg("targetTransactionDuration", "Target transaction", scale=1000)
    w.avg("requestConnectingDuration", "Connect", scale=1000)
    w.avg("requestSendingDuration", "Send", scale=1000)
    w.avg("waitingForResponseDuration", "Wait for response", scale=1000)
    w.avg("receivingResponseDuration", "Receive response", scale=1000)
    return w


def target_connections():
    w = W("Target connections", yright={"label": "per second", "showUnits": False})
    w.gauge("activeTargetConnections", "Open target connections", hottest=False)
    w.rate("connectionsOpened", "Opened/s", yAxis="right")
    w.rate("connectionsClosedCount", "Closed/s", yAxis="right")
    w.rate("requestConnectingExceptionCount", "Connect exceptions/s", yAxis="right")
    w.rate("nonRetryableConnectionFailures", "Non-retryable failures/s", yAxis="right")
    return w


def target_permits():
    w = W("Target permits and retries", yright={"label": "per second", "showUnits": False})
    w.gauge("targetAttemptPermitsActive", "Permits active", hottest=False)
    w.gauge("targetAttemptPermitAcquisitionsPending", "Permits pending", hottest=False)
    w.rate("numRetriedRequests", "Retried requests/s", yAxis="right")
    w.rate("targetRetriesExhausted", "Retries exhausted/s", yAxis="right")
    w.search_rate("targetAttemptsWithoutResponse", "noResponseKind", "No response/s ${PROP('Dim.noResponseKind')}", yAxis="right")
    return w


def target_domain():
    w = W("Target domain health (TARGET_DOMAIN)", yright={"label": "count", "showUnits": False})
    es(w, "CPUUtilization", "Maximum", "CPU % (max node)")
    es(w, "JVMMemoryPressure", "Maximum", "JVM pressure % (max node)")
    es(w, "ThreadpoolWriteQueue", "Maximum", "Write queue (max node)", yAxis="right")
    es(w, "ThreadpoolWriteRejected", "Sum", "Write rejected", yAxis="right")
    es(w, "5xx", "Sum", "5xx", yAxis="right")
    es(w, "IndexingLatency", "Average", "Indexing latency ms (max node)", visible=False)
    return w


def source_domain():
    w = W("Source domain health (SOURCE_DOMAIN)", yright={"label": "count", "showUnits": False})
    es(w, "CPUUtilization", "Maximum", "CPU % (max node)", domain_var="SOURCE_DOMAIN")
    es(w, "JVMMemoryPressure", "Maximum", "JVM pressure % (max node)", domain_var="SOURCE_DOMAIN")
    es(w, "ThreadpoolWriteQueue", "Maximum", "Write queue (max node)", domain_var="SOURCE_DOMAIN", yAxis="right")
    es(w, "ThreadpoolWriteRejected", "Sum", "Write rejected", domain_var="SOURCE_DOMAIN", yAxis="right")
    es(w, "5xx", "Sum", "5xx", domain_var="SOURCE_DOMAIN", yAxis="right")
    return w


# --- correctness -----------------------------------------------------------------------------
def status_match_pct():
    w = W("Status-code match % (includes missing source responses)", yleft={"min": 0, "max": 100})
    a, b = tuple_search()
    ma, _ = tuple_search("true")
    w.e(f"100 * SUM({ma}) / (SUM({a}) + FILL(SUM({b}), 0))", "Match %")
    return w


def status_mismatches():
    w = W("Status-code mismatches per second (source → target)", stacked=True)
    a, b = tuple_search("false")
    w.e(f"{a} / 60", "${PROP('Dim.method')} ${PROP('Dim.sourceStatusCode')} → ${PROP('Dim.targetStatusCode')}")
    w.e(f"{b} / 60", "${PROP('Dim.method')} no source response → ${PROP('Dim.targetStatusCode')}")
    return w


def status_matches():
    w = W("Status-code matches per second", stacked=True)
    a, _ = tuple_search("true")
    w.e(f"{a} / 60", "${PROP('Dim.method')} ${PROP('Dim.targetStatusCode')}")
    return w


# --- tuples ----------------------------------------------------------------------------------
def tuple_flow():
    w = W("Tuple durability (S3)", yright={"label": "pending", "showUnits": False})
    w.rate("tupleDurabilityWritesAccepted", "Accepted/s")
    w.rate("tupleDurabilityWritesDurable", "Durable/s")
    w.gauge("tupleDurabilityWritesPending", "Pending", hottest=False, yAxis="right")
    return w


def tuple_latency():
    w = W("Tuple durability latency (seconds)")
    w.avg("tupleDurabilityWriteLatency", "Mean write latency", scale=1000)
    w.e(f"MAX(SEARCH('{{{NS},OTelLib,activity,qualifier}} MetricName=\"replayOldestContextAgeByActivity\" OTelLib=\"replayer\" qualifier=\"{Q}\" activity=\"comparingResults\"', 'Maximum', 60)) / 1000",
        "Oldest pending tuple (hottest pod)")
    return w


def tuple_errors():
    w = W("Tuple / S3 faults (per minute)")
    for n, lab in [("tupleSinkUploadRetryAttempts", "S3 upload retries"), ("tupleSinkUploadRecoveries", "S3 upload recoveries"),
                   ("tupleDurabilityRetries", "Outer tuple retries"), ("tupleDurabilityWritesCancelled", "Writes cancelled")]:
        s = w.m(n, "Sum")
        w.e(f"FILL({s}, 0)", lab)
    w.e(f"SUM(SEARCH('{{{NS},OTelLib,failureReason,qualifier}} MetricName=\"tupleDurabilityTerminalFailures\" OTelLib=\"replayer\" qualifier=\"{Q}\"', 'Sum', 60))",
        "Terminal failures")
    r = w.m("tupleSinkUploadsRetrying", "Sum")
    w.e(f"FILL({r}, 0) / PERIOD({r})", "S3 objects awaiting retry (fleet, now)")
    return w


# --- process safety --------------------------------------------------------------------------
def resources_owned():
    w = W("Replayer owned payload memory (fleet MiB)")
    w.e(f"SEARCH('{{{NS},OTelLib,qualifier,resourceType}} MetricName=\"resourceOwnedBytes\" OTelLib=\"replayer\" qualifier=\"{Q}\"', 'Sum', 60) / 60 / {MIB}",
        "${PROP('Dim.resourceType')}")
    return w


def fatal():
    w = W("Replayer invariant and fatal failures (per minute)")
    w.e(f"SUM(SEARCH('{{{NS},OTelLib,qualifier,reason}} MetricName=\"replayFatalFailures\" OTelLib=\"replayer\" qualifier=\"{Q}\"', 'Sum', 60))", "Fatal failures")
    w.e(f"SUM(SEARCH('{{{NS},OTelLib,qualifier}} MetricName=\"replayIntakeCaptureProtocolViolations\" OTelLib=\"replayer\" qualifier=\"{Q}\"', 'Sum', 60))", "Capture protocol violations")
    w.e(f"SUM(SEARCH('{{{NS},OTelLib,qualifier,resourceType}} MetricName=\"resourceOwnershipInvariantFailures\" OTelLib=\"replayer\" qualifier=\"{Q}\"', 'Sum', 60))", "Ownership invariant failures")
    s = w.m("replayIntakeStaleGenerationInputsIgnored", "Sum", dims=None)
    w.e(f"FILL({s}, 0)", "Stale-generation inputs ignored")
    return w


def telemetry():
    return cadvisor("OTel collector (telemetry pipeline) CPU and memory", "otel-collector")


NOTE = ("**How to read this dashboard.** Rates are fleet totals per second. Gauges marked *fleet* are time-averaged "
        "fleet totals; *hottest pod* is the single largest pod sample. Durations are means only: histogram "
        "min/max/percentiles are not exported to CloudWatch. MiB = 1,048,576 bytes. *Pods reporting* is derived from "
        "the 1 s export interval; a drop means pods stopped exporting, not that work stopped. Proxy panels are empty "
        "when the capture proxy is scaled to 0.")


def layout_columns():
    """Stage per column (proxy | Kafka | replayer | target); each row compares one signal type across stages."""
    ws, y = [], 0
    ws.append(text(NOTE, 0, y, 24, 2)); y += 2
    k = kpis()
    widths = [3, 4, 3, 3, 3, 4, 4]
    x = 0
    for w, wd in zip(k, widths):
        ws.append(w.widget(x, y, wd, 3)); x += wd
    y += 3
    ws.append(text("## End-to-end flow", 0, y, 24)); y += 1
    for i, b in enumerate([flow_requests, flow_bytes, flow_lag]):
        ws.append(b().widget(i * 8, y, 8, 7))
    y += 7
    ws.append(text("### Capture proxy", 0, y, 6)); ws.append(text("### Kafka → replayer consumer", 6, y, 6))
    ws.append(text("### Replayer intake", 12, y, 6)); ws.append(text("### Replayer → target", 18, y, 6)); y += 1
    grid = [
        ("Throughput", [proxy_publish, kafka_consume, intake_framing, target_connections]),
        ("Backlog", [proxy_connections, kafka_backlog, intake_population, target_permits]),
        ("Latency / age", [proxy_latency, kafka_commit_heads, intake_age, target_latency]),
        ("Skew across pods (p10 / p50 / p90 / max)", [skew_proxy, skew_kafka, skew_replayer, skew_target]),
        ("Faults", [proxy_errors, kafka_rebalance, fatal, target_domain]),
        ("Compute", [lambda: cadvisor("Capture proxy CPU and memory", "proxy"), kafka_brokers,
                     lambda: cadvisor("Replayer CPU and memory", "replayer"), kafka_poll]),
    ]
    for _, row in grid:
        for i, b in enumerate(row):
            ws.append(b().widget(i * 6, y, 6, 6))
        y += 6
    ws.append(text("## Correctness and result tuples", 0, y, 24)); y += 1
    for i, b in enumerate([status_match_pct, status_mismatches, status_matches]):
        ws.append(b().widget(i * 8, y, 8, 6))
    y += 6
    for i, b in enumerate([tuple_flow, tuple_latency, tuple_errors]):
        ws.append(b().widget(i * 8, y, 8, 6))
    y += 6
    ws.append(text("## Source cluster and replayer internals", 0, y, 24)); y += 1
    for i, b in enumerate([source_domain, contexts_by_activity, ops_by_type, resources_owned]):
        ws.append(b().widget(i * 6, y, 6, 6))
    y += 6
    for i, b in enumerate([telemetry, flow_eta, intake_demand]):
        ws.append(b().widget(i * 8, y, 8, 6))
    y += 6
    return ws


VARIABLES = [
    {"type": "property", "property": "region", "inputType": "input", "id": "REGION", "label": "Region",
     "defaultValue": "placeholder-region", "visible": False},
    {"type": "pattern", "pattern": "MA_STAGE", "inputType": "input", "id": "MA_STAGE", "label": "Migration Stage",
     "defaultValue": "placeholder-stage", "visible": False},
    {"type": "pattern", "pattern": "MA_QUALIFIER", "inputType": "input", "id": "MA_QUALIFIER", "label": "Migration Qualifier",
     "defaultValue": "placeholder-qualifier", "visible": False},
    {"type": "pattern", "pattern": "CATCHUP_FLOOR_SECONDS", "inputType": "select", "id": "CATCHUP_FLOOR_SECONDS",
     "label": "Catch-up lag floor", "defaultValue": "5",
     "values": [{"label": "1 second", "value": "1"}, {"label": "5 seconds", "value": "5"}, {"label": "1 minute", "value": "60"}],
     "visible": True},
    {"type": "pattern", "pattern": "TARGET_DOMAIN", "inputType": "select", "id": "TARGET_DOMAIN", "label": "Target domain",
     "search": "{AWS/ES,ClientId,DomainName} MetricName=\"CPUUtilization\"", "populateFrom": "DomainName",
     "defaultValue": "placeholder-target-domain", "visible": True},
    {"type": "pattern", "pattern": "SOURCE_DOMAIN", "inputType": "select", "id": "SOURCE_DOMAIN", "label": "Source domain",
     "search": "{AWS/ES,ClientId,DomainName} MetricName=\"CPUUtilization\"", "populateFrom": "DomainName",
     "defaultValue": "placeholder-source-domain", "visible": True},
]

if __name__ == "__main__":
    layout = {"columns": layout_columns}[sys.argv[1]]
    body = {"variables": VARIABLES, "widgets": layout()}
    text_out = json.dumps(body, indent=4, ensure_ascii=False)
    # Domain names are chosen per migration, so the installed dashboard leaves them for the operator to pick.
    text_out = text_out.replace('"placeholder-target-domain"', '""').replace('"placeholder-source-domain"', '""')
    open(sys.argv[2], "w").write(text_out + "\n")
    print(sys.argv[2], len(body["widgets"]), "widgets")
