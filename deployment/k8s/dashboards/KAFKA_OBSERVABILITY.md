# Kafka observability

Migration Assistant monitors only the Strimzi Kafka clusters that it creates
and manages. External Kafka clusters and Amazon MSK are outside this feature's
scope.

## Storage metrics and dashboards

The Prometheus/Grafana capture/replay dashboard shows:

- minimum, unweighted average, and maximum disk utilization across Kafka PVCs;
- disk utilization per PVC;
- available and provisioned bytes per PVC;
- **Estimated time until full** from the available-space trend over the
  configured lookback (six hours by default);
- inode utilization; and
- the kubelet/CSI abnormal-volume-health signal.

Utilization is defined consistently as:

```text
100 * (1 - available_bytes / capacity_bytes)
```

Maximum utilization is the primary cluster-level capacity-risk signal.
The CloudWatch capture/replay dashboard shows:

- capacity-weighted fleet disk utilization;
- free space for every Kafka PVC in GiB;
- fleet free, used, and capacity totals in GiB;
- capacity-weighted fleet inode utilization; and
- each PVC's kubelet/CSI abnormal-volume-health signal.

No Kafka PVC selection is required. The panels cover the complete
Migration Assistant-managed Kafka cluster. Per-volume legends are retained
only where they expose free-space imbalance or identify an abnormal volume.

CloudWatch `SEARCH` expressions return arrays of time series and cannot divide
the available array by the capacity array element-by-element. Fleet
utilization therefore reduces both arrays to totals before applying
`100 * (1 - available / capacity)`. The storage panels use conservative
five-minute windows and repeat-fill short per-volume scrape gaps before fleet
reduction, so an incomplete current minute does not look like capacity
disappeared.

The current dashboard represents the one managed Kafka cluster associated
with the Migration Assistant installation. If a future installation needs to
switch among multiple managed clusters, the selector should use a stable
migration, proxy, or Kafka-cluster identity carried by the metrics—not an
individual PVC.

Missing inode or health series mean the kubelet or CSI driver did not publish
those metrics. The dashboards do not convert missing series to zero usage or a
healthy value.

Prometheus and the Grafana dashboard are available whenever the standard
Migration Assistant metrics pipeline is enabled. They do not require
Alertmanager.

On EKS, the same metrics are also exported automatically to the
`OpenSearchMigrations` CloudWatch namespace whenever the standard metrics
pipeline is enabled. The CloudWatch metric identity is exactly:

```text
EKSClusterName, qualifier, namespace, persistentvolumeclaim
```

`pod`, `node`, and `device` are intentionally not CloudWatch dimensions for
PVC capacity metrics.

## How Kafka PVCs are identified

Prometheus does not classify a claim as Kafka merely because its name starts
with `data-`. Kubelet volume metrics are joined on `namespace` and
`persistentvolumeclaim` with `kube_persistentvolumeclaim_labels`. The matching
PVC must carry both allowlisted Strimzi labels:

- `strimzi.io/cluster`
- `strimzi.io/pool-name`

The Grafana Kafka PVC variable uses the same Strimzi-label source.

Migration Assistant currently creates one node pool named `dual-role`, whose
roles are both `controller` and `broker`. A separate controller-only storage
case is therefore inapplicable to the current managed topology. If separate
controller-only pools are added later, the selector must also consume broker
role information before this assumption can be removed.

CloudWatch cannot perform the Kubernetes label join. Its collector therefore
filters the Migration Assistant namespace using the current Strimzi
`dual-role` PVC naming forms:

```text
data-<cluster>-dual-role-<broker>
data-<volume>-<cluster>-dual-role-<broker>
```

This naming assumption applies only to Migration Assistant-managed Kafka and
must be revisited if the managed node-pool topology changes.

## Global storage policy

The global policy is configured with Helm percentages of capacity remaining
free:

```yaml
kafkaObservability:
  storage:
    warningFreePercent: 20
    criticalFreePercent: 10
    inodeFreePercent: 10
    forecastLookback: 6h
    projectedFillWarningHorizonHours: 24
    abnormalVolumeHealthAlerting: true
```

The critical free-space percentage must be lower than the warning percentage,
and all percentages must be between 0 and 100. Grafana and CloudWatch convert
the free-space thresholds to utilization colors; Prometheus and CloudWatch
alarms compare remaining free percentage directly.

## Prometheus storage alerts

Alertmanager and Kafka storage alerts are both disabled by default. Rules are
rendered only when both switches are enabled:

```yaml
kafkaObservability:
  prometheusAlerts:
    enabled: true

charts:
  kube-prometheus-stack:
    values:
      alertmanager:
        enabled: true
```

The rules cover warning and critical low free space, projected fill within the
configured horizon, low free inodes, and abnormal volume health. Dashboard
metrics continue to work when Alertmanager is disabled.

Migration Assistant does not create notification receivers or receiver
credentials. Configure the underlying `kube-prometheus-stack` Alertmanager
route and receivers for the customer's email, Slack, PagerDuty, or other
destination, and keep credentials in a customer-managed Kubernetes Secret.

## Disabling CloudWatch metrics and dashboards

CloudWatch export and the ACK-managed dashboards are enabled by default on
EKS. Customers who cannot use CloudWatch or do not want to publish custom
metrics can disable both while retaining Prometheus and Grafana:

```yaml
metrics:
  cloudWatch:
    enabled: false
```

This setting suppresses all CloudWatch metric exporters and removes the
Capture Replay and Reindex From Snapshot dashboard resources on upgrade. It
does not disable the ACK CloudWatch controller itself; customers can disable
the `ack-cloudwatch-controller` conditional package independently when they do
not need it for other resources.

## Broker JMX health

Broker JMX metrics are enabled by default for Migration Assistant-managed
Kafka. Prometheus scrapes the broker `PodMonitor`; on EKS, the standard metrics
collector also scrapes each local broker once per minute and exports the same
bounded metric set to CloudWatch. Grafana and CloudWatch panels cover:

- under-replicated and offline partitions;
- ISR expansion and shrink events;
- active-controller and KRaft quorum health;
- produce/fetch request errors and throttling;
- combined broker/controller request-handler utilization; and
- JVM heap, garbage collection, CPU, and process uptime.

The managed Kafka 4.0 deployment uses G1 garbage collection. The CloudWatch GC
panel names the three G1 collectors explicitly; a customer-supplied JVM
collector override requires corresponding dashboard changes.

Kafka 4.0 combined-role brokers expose `RequestHandlerAvgIdlePercent` as the
sum of the broker and controller request-handler pool idle fractions. The
Grafana and CloudWatch utilization panels and the
`MigrationAssistantKafkaRequestHandlersSaturated` alert divide that value by two
before converting it to busy percent. This keeps the displayed value on a 0–100% scale for the
Migration Assistant-managed dual-role broker pool.

Collection can be disabled explicitly:

```yaml
kafkaObservability:
  brokerMetrics:
    enabled: false
```

Managed Kafka resources reference the shipped JMX exporter ConfigMap through
`spec.kafka.metricsConfig`. When collection is disabled, the ConfigMap has no
rules and the broker `PodMonitor` is removed.

When collection is enabled, the exporter restricts its JMX query to the Kafka
and JVM object names used by the published metric rules. This avoids
enumerating unrelated broker MBeans on both the Prometheus and CloudWatch
scrape paths.

Broker alerts remain disabled independently. They render only when
Alertmanager, Kafka Prometheus alerts, and broker alerts are all enabled:

```yaml
kafkaObservability:
  prometheusAlerts:
    enabled: true
  brokerMetrics:
    alertsEnabled: true
charts:
  kube-prometheus-stack:
    values:
      alertmanager:
        enabled: true
```

The JMX rules restrict request metrics to produce and consumer/follower fetch
traffic and do not export topic or partition labels. CloudWatch uses
`EKSClusterName`, `qualifier`, `namespace`, `kafka_cluster`, and `pod`, adding
`request`, `error`, or `collector` only to the metrics that require them.
Migration Assistant does not expose Strimzi `kafkaExporter` through the
workflow schema initially; broker health is supplied by JMX while default
cardinality remains bounded.
