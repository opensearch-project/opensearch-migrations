# Kafka Observability Plan

Working branch: `codex/kafka-observability`

## Decisions

- Scope this work to Kafka clusters managed by Migration Assistant. External
  Kafka and MSK observability are not part of this effort.
- Define free percentage as `available bytes / capacity bytes`.
- Define utilization as `100 * (1 - free percentage)`. Use this definition
  consistently in dashboards and alerts.
- Configure warning and critical capacity thresholds globally through Helm as
  percentages of capacity that remain free. Prometheus and CloudWatch should
  consume the same policy.
- Keep the policy global for now. A future workflow or Kafka-cluster schema can
  override it alongside an individual broker configuration.
- Show minimum, unweighted average, and maximum utilization across Kafka PVCs.
  Maximum utilization is the primary cluster-level capacity-risk signal.
- Label the forecast **Estimated time until full**. It uses the linear
  available-space trend from the previous six hours and is advisory; fixed
  free-space thresholds remain the primary alerts.
- Identify Prometheus Kafka PVCs by joining kubelet volume metrics with
  `kube_persistentvolumeclaim_labels` carrying the Strimzi cluster and pool
  labels.
- For the current Migration Assistant-managed `dual-role` node pool, identify
  CloudWatch Kafka PVCs by namespace and the Strimzi-generated PVC naming
  convention. Revisit this if Migration Assistant adds separate persistent
  controller-only pools.
- Use these CloudWatch dimensions:
  `EKSClusterName`, `qualifier`, `namespace`, and
  `persistentvolumeclaim`.
- Do not expose individual Kafka PVC selection as a customer dashboard
  control. The current dashboard covers the complete managed Kafka cluster.
  If multiple managed clusters later require selection, use a stable
  migration, proxy, or Kafka-cluster identity after verified metric
  enrichment.
- On EKS, export Kafka PVC metrics whenever the existing Migration Assistant
  metrics pipeline is enabled. Do not require a separate Kafka-PVC export
  switch.
- Keep `node`, `pod`, and `device` out of PVC capacity metrics.
- Keep Alertmanager disabled by default.
- Render Kafka storage alert rules only when both Alertmanager and Kafka
  storage alerts are explicitly enabled. Dashboard panels and metric
  collection do not depend on Alertmanager.
- Publish one customer-facing Kafka observability guide. Do not retain
  separate Prometheus and CloudWatch guides unless customers still have to
  perform materially different backend-specific setup.

## 1. Normalize metric semantics and PVC selection

- [x] Change every utilization query to use
  `100 * (1 - available bytes / capacity bytes)`.
- [x] Keep the summary series:
  - minimum utilization across Kafka PVCs
  - unweighted average utilization across Kafka PVCs
  - maximum utilization across Kafka PVCs
- [x] Allowlist the PVC labels required from kube-state-metrics:
  - `strimzi.io/cluster`
  - `strimzi.io/pool-name`
- [x] Select Migration Assistant-managed Kafka PVCs in Prometheus by joining
  kubelet volume metrics with the allowlisted Strimzi labels.
- [x] Stop using `data-*` alone as the Prometheus definition of a Kafka PVC.
- [x] Document the current `dual-role` naming assumption used by the
  CloudWatch selector.

## 2. Test Kafka PVC identification

- [x] Add a focused test for a single-volume Kafka broker PVC.
- [x] Add a focused test for multiple JBOD volumes belonging to one broker.
- [x] Add a focused test for multiple Kafka brokers.
- [x] Add a test showing that an unrelated `data-*` PVC is excluded.
- [x] Add a test showing that controller-only storage would be excluded or
  explicitly document why the current `dual-role` scope makes it inapplicable.

## 3. Finish the Prometheus dashboard

- [x] Add minimum, average, and maximum disk-utilization panels.
- [x] Add per-PVC utilization.
- [x] Add free and provisioned bytes per PVC.
- [x] Add **Estimated time until full** with a six-hour lookback explanation.
- [x] Add inode utilization and CSI abnormal-health status.
- [x] Add a multi-select Kafka PVC dashboard variable.
- [x] Replace the dashboard's name-only PVC variable with Strimzi-label-based
  selection.
- [x] Add warning and critical threshold colors using the global free-space
  percentage policy.
- [x] Regenerate the dashboard from its source generator.
- [x] Pass the dashboard Helm wiring smoke test after the changes.

## 4. Add opt-in Prometheus storage alerts

- [x] Add a global Migration Assistant Helm policy for:
  - warning free-space percentage
  - critical free-space percentage
  - inode free-space percentage
  - forecast lookback duration
  - projected-fill warning horizon
  - abnormal-volume-health alerting
- [x] Add a separate Helm switch for Kafka Prometheus storage alerts.
- [x] Keep Alertmanager disabled by default.
- [x] Keep Kafka storage alerts disabled by default.
- [x] Render Kafka storage alert rules only when both Alertmanager and Kafka
  storage alerts are enabled.
- [x] Add rules for:
  - warning and critical low free space
  - projected fill within the configured warning horizon
  - inode exhaustion
  - abnormal volume health
- [x] Validate Helm values so percentage thresholds are between 0 and 100 and
  the critical free-space percentage is lower than the warning percentage.
- [x] Keep Alertmanager receivers and credentials customer-supplied.
- [x] Add Helm-render tests for defaults, both enablement gates, thresholds,
  and generated Prometheus expressions.

## 5. Validate Prometheus and prepare the shared customer guide

- [x] Draft the current-state common, Prometheus, and CloudWatch notes.
- [x] Do not publish the standalone Prometheus guide as the final customer
  experience.
- [x] Fold the Prometheus behavior into one concise Kafka observability guide.
- [x] In the shared guide, document:
  - metrics and dashboard availability
  - the global percentage policy
  - opt-in storage alerts
  - that dashboard metrics do not require Alertmanager
  - Alertmanager receiver configuration only where customer action is needed
- [ ] Run a kind integration test for PVC selection and alert rules.
  - Partial validation on 2026-11-12: a local kind Prometheus instance
    discovered the allowlisted Strimzi labels, excluded an unlabeled
    `data-*` PVC, and loaded all rendered alert rules with healthy
    evaluations. The kind `local-path` provisioner emitted no
    `kubelet_volume_stats_*` series, so the live metric join remains
    unverified.
- [ ] Validate against a running Kafka cluster with increasing disk use.
- [ ] Validate behavior when Kafka retention reduces disk use.
- [ ] Confirm that missing CSI inode or health metrics produce missing series,
  not false healthy or zero-usage values.

## 6. Complete the CloudWatch experience

- [x] Add an `aws.eksClusterName` Helm value.
- [x] Pass the EKS cluster name already known by `aws-bootstrap.sh` to Helm.
- [x] Pass the EKS cluster name already resolved by the Rust deployment CLI to
  Helm.
- [x] Add `EKSClusterName` to the AWS metadata and OTel collector environment.
- [x] Add Kafka PVC collection to the standard EKS CloudWatch metrics
  configuration so it is enabled whenever the existing metrics pipeline is
  enabled.
- [x] Keep the PVC exporter configuration composable so customers do not have
  to replace the complete collector configuration.
- [x] Export only the required `kubelet_volume_stats_*` metrics.
- [x] Export detailed PVC series using:
  `EKSClusterName`, `qualifier`, `namespace`, and
  `persistentvolumeclaim`.
- [x] Add renderable CloudWatch panels for:
  - capacity-weighted fleet utilization
  - free space by PVC in GiB
  - fleet free, used, and capacity values in GiB
  - capacity-weighted fleet inode utilization
  - per-PVC abnormal volume health
- [x] Keep the CloudWatch storage section cluster-wide without a Kafka PVC
  selector.
- [ ] Add exact dynamic minimum, unweighted average, and maximum utilization
  across Kafka PVCs in CloudWatch.
  - EKS validation on 2026-11-12 found that `SEARCH` returns arrays of time
    series and CloudWatch rejects element-wise
    `SEARCH(available) / SEARCH(capacity)`. Capacity-weighted fleet
    utilization is implemented without that invalid operation. A
    backend-derived per-PVC utilization metric or another verified pairing
    mechanism is still required for the exact dynamic minimum, unweighted
    average, and maximum.
- [x] Initially use hard free-space thresholds for CloudWatch alarms; treat a
  derived CloudWatch fill forecast as optional follow-on work.
- [x] Prototype a Metrics Insights alarm grouped by
  `persistentvolumeclaim` so newly created PVCs are included automatically.
- [x] Reuse the global warning and critical free-space percentages for
  CloudWatch alarms.
- [x] Add Helm values for optional SNS action ARNs.
- [x] Add the ACK controller IAM permissions required to manage alarms.
- [ ] Verify alarm creation, update, and deletion with the Helm release.
- [ ] Verify metric cardinality and estimated custom-metric cost.
- [x] Update the shared Kafka observability guide with the automatic
  CloudWatch metrics and dashboard behavior.
- [x] Document CloudWatch alarm and SNS configuration only where customer
  action is needed.
- [x] Remove the complete OTel collector override appendix after PVC export is
  part of the standard EKS metrics pipeline.
- [x] Keep separate CloudWatch documentation only if manual setup remains
  after the product changes.
- [x] Add Helm-render and deployment-CLI tests for cluster-name propagation,
  dimensions, alarm defaults, and alarm configuration.
- [ ] Run an EKS validation and confirm dimensions, dashboard population,
  alarm lifecycle, and contributor notifications.
  - Partial validation on 2026-11-12 against
    `migration-eks-cluster-eop09-us-east-2`: the default-disabled alert
    configuration installed successfully, the broker `PodMonitor` reconciled,
    Alertmanager, `PrometheusRule`, and `MetricAlarm` resources remained
    absent, and the ACK-managed
    `MA-eop09-us-east-2-CaptureReplay` dashboard reached `Ready` with no AWS
    dashboard validation messages. The install also verified that the Kafka
    reconciliation hooks need only namespace-scoped `PodMonitor` and
    `PrometheusRule` access. A subsequent 160K benchmark exported 12 each of
    available, capacity, total-inode, and free-inode series with exactly the
    required dimensions. No abnormal-volume-health series was emitted by the
    EBS CSI/kubelet path, correctly leaving that signal missing rather than
    false healthy. Live rendering also exposed invalid array/array division in
    the original CloudWatch disk and inode panels. The replacement fleet and
    all-volume expressions returned live data, the replacement widgets
    rendered through `GetMetricWidgetImage`, and the updated ACK dashboard
    reached `Ready` without validation messages. Alarm lifecycle and
    contributor notifications remain unverified.

## 7. Add Kafka broker health

- [x] Add a JMX exporter rules ConfigMap enabled by default for managed Kafka.
- [x] Configure managed Kafka with `spec.kafka.metricsConfig`.
- [x] Add a broker `PodMonitor`.
- [x] Keep broker alerts independently disabled by default.
- [x] Add Prometheus panels and opt-in alerts for:
  - under-replicated and offline partitions
  - ISR expansion and shrink events
  - controller and quorum health
  - produce/fetch errors and throttling
  - request-handler saturation
  - JVM heap, garbage collection, and process health
- [x] Decide whether to expose Strimzi `kafkaExporter` through the workflow
  schema.
- [x] Keep topic-level metrics bounded to migration topics and avoid partition
  as a default CloudWatch dimension.
- [x] Export the bounded broker JMX metric set through the standard EKS
  collector with per-broker CloudWatch dimensions.
- [x] Add CloudWatch panels for replication, ISR, controller/quorum,
  produce/fetch health, request-handler saturation, and JVM health.
- [x] Validate at least ten minutes of broker JMX data on EKS.
  - On 2026-11-12, `migration-eks-cluster-eop09-us-east-2` exported 12
    per-broker series with the bounded `EKSClusterName`, `qualifier`,
    `namespace`, `kafka_cluster`, and `pod` dimensions. During
    `k6-ingest-connection-modes-prjkl`, CloudWatch returned complete CPU,
    request-handler, heap, GC, and under-replicated-partition expressions
    across 12 consecutive one-minute points from 06:29–06:40 UTC. The
    proxy-to-Kafka load marker supplied 11 points spanning exactly 600 seconds
    from 06:30–06:40 UTC while ramping to approximately 79.6K records/s. All
    12 brokers and 31 collectors remained ready, with no broker restarts or
    failed k6 runners.
