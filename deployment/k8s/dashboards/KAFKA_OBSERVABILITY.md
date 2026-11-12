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

## CloudWatch alarms and SNS

CloudWatch alarms are disabled by default. Enabling them creates warning and
critical ACK `MetricAlarm` resources that group Metrics Insights inputs by
`persistentvolumeclaim`, calculate remaining free percentage, and evaluate
the global hard percentage thresholds:

```yaml
kafkaObservability:
  cloudWatchAlarms:
    enabled: true
    snsActionArns:
      - arn:aws:sns:us-east-1:123456789012:migration-kafka-alerts
```

SNS topics and subscriptions are customer-supplied. The Helm release manages
alarm creation, update, and deletion; an EKS deployment is still required to
validate the live ACK lifecycle, contributor behavior, and notifications.
A CloudWatch fill forecast is not configured initially.

## Opt-in broker JMX health

Broker JMX metrics are disabled by default. Enable the rules and broker
`PodMonitor` with:

```yaml
kafkaObservability:
  brokerMetrics:
    enabled: true
```

Managed Kafka resources reference the shipped JMX exporter ConfigMap through
`spec.kafka.metricsConfig`. When the switch is off, the ConfigMap has no
rules. When it is on, Grafana panels and gated Prometheus alert rules cover:

- under-replicated and offline partitions;
- ISR expansion and shrink events;
- active-controller and KRaft quorum health;
- produce/fetch request errors and throttling;
- request-handler saturation; and
- JVM heap, garbage collection, CPU, and process uptime.

The JMX rules do not export topic or partition labels. Migration Assistant does
not expose Strimzi `kafkaExporter` through the workflow schema initially:
broker health is supplied by JMX, while omitting exporter topic/partition
series keeps default cardinality bounded to migration-relevant signals.
