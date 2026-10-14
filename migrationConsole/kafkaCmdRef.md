## Kafka Sample Commands

Sample commands to be run from the kafka/bin directory

### AWS

When running these commands in an AWS environment, the following piece should be added to the end of the commands to allow IAM communication with MSK
```shell
--command-config ../../aws/msk-iam-auth.properties
```

### Kubernetes / Amazon EKS

When running on a kubernetes cluster the command configuration is mounted onto the file system, add the following argument
```shell
--command-config /opt/kafka-config/kafka.properties
```

### Topics

List all Topics
```shell
./kafka-topics.sh --bootstrap-server "$MIGRATION_KAFKA_BROKER_ENDPOINTS" --list
```

Create a Topic
```shell
./kafka-topics.sh --bootstrap-server "$MIGRATION_KAFKA_BROKER_ENDPOINTS" --create --replication-factor 1 --topic test-topic
```

Delete a Topic
```shell
./kafka-topics.sh --bootstrap-server "$MIGRATION_KAFKA_BROKER_ENDPOINTS" --delete --topic test-topic
```

### Consumer Groups

List all Consumer Groups
```shell
./kafka-consumer-groups.sh --bootstrap-server "$MIGRATION_KAFKA_BROKER_ENDPOINTS" --timeout 100000 --list
```

Describe all Consumer Groups
```shell
./kafka-consumer-groups.sh --bootstrap-server "$MIGRATION_KAFKA_BROKER_ENDPOINTS" --timeout 100000 --describe --all-groups
```

Describe a particular Consumer Group
```shell
./kafka-consumer-groups.sh --bootstrap-server "$MIGRATION_KAFKA_BROKER_ENDPOINTS" --timeout 100000 --describe --group logging-group-default
```

#### `console kafka describe-consumer-group` (with replay watermark)

The migration console preserves the native consumer-group table and appends a
per-partition **REPLAY WATERMARK** section. The native `LAG` column remains the
authoritative offset-distance signal. The added section shows the timestamp
span between the last contiguously and durably replayed record and the newest
timestamped record in the partition.

Invocation (inside the migration-console pod, EKS or local):

```shell
console kafka describe-consumer-group logging-group-default
```

The appended section looks like:

```
REPLAY WATERMARK (record timestamps; not authoritative Kafka backlog drain time)
TOPIC  PARTITION  COMMITTED-OFFSET  OFFSET-LAG  REPLAYED-THROUGH (UTC)  LATEST-RECORD (UTC)  TEMPORAL-BACKLOG  NOTE
t1     0          101               49          2024-01-02T03:04:05Z   2024-01-02T03:12:17Z   8m12s
t1     1          200               0           2024-01-02T03:11:50Z   2024-01-02T03:11:50Z   0s                caught up by offset
```

Row semantics:

- The replayer commits Kafka's normal next-read offset together with the record
  timestamp of the last record in the durably replayed contiguous prefix.
- `REPLAYED-THROUGH` comes from that committed offset metadata.
- `LATEST-RECORD` comes from Kafka's batched maximum-timestamp lookup.
- `TEMPORAL-BACKLOG = LATEST-RECORD - REPLAYED-THROUGH` while offset lag is
  nonzero. It is zero when the partition is caught up by offset.
- Missing or malformed watermark metadata renders `-` rather than inventing a
  reassuring value. This is expected until a replayer with watermark support
  makes its first commit.

The query uses Kafka Admin metadata APIs only. It starts no per-partition
consumer JVMs and does not fetch captured-traffic values. For a workflow-managed
live-capture topic, record timestamps are broker-assigned `LogAppendTime`, so the
timestamp span is meaningful broker time. It is still not the age of the exact
next unprocessed record and is not authoritative Kafka backlog drain time.

If the consumer group has no committed offsets, the replay-watermark section
states that directly. Failure of the supplemental query never hides a successful
native consumer-group description.

Delete a particular Consumer Group (Requires Consumer Group to be empty to perform)
```shell
./kafka-consumer-groups.sh --bootstrap-server "$MIGRATION_KAFKA_BROKER_ENDPOINTS" --timeout 100000 --delete --group logging-group-default
```

Reset a Consumer Group offset to latest (Requires Consumer Group to not be active to perform)
More options for different types of reset [here](https://docs.cloudera.com/runtime/7.2.8/kafka-managing/topics/kafka-manage-cli-cgroups.html#pnavId2)
```shell
./kafka-consumer-groups.sh --bootstrap-server "$MIGRATION_KAFKA_BROKER_ENDPOINTS" --timeout 100000 --reset-offsets --to-latest --topic logging-traffic-topic --group logging-group-default --execute
```

### Importing Captured Traffic from S3

Streams a `kafkaExport.sh`-produced archive (gzipped, base64-encoded TrafficStream
records) onto a Kafka topic. The export and import are symmetric — every record
loaded matches the bytes of the original capture, so the replayer reads it the
same way as live-captured traffic.

The topic must already exist (the import does not create it).

Using the `kafkaImport.sh` helper (recommended — handles MSK/EKS auth detection):
```shell
/root/kafka-tools/kafkaImport.sh \
    --topic "$TOPIC" \
    --s3-uri "s3://your-bucket/kafka_export_from_migration_console_<ts>.proto.gz"
```

Or the underlying recipe directly:
```shell
aws s3 cp "$S3_URI" - \
  | gunzip \
  | /root/kafkaUtils/bin/kafkaUtils \
      --stdin \
      --topicName "$TOPIC" \
      --kafkaBrokers "$MIGRATION_KAFKA_BROKER_ENDPOINTS"
```

Importing from a local file instead of S3:
```shell
/root/kafka-tools/kafkaImport.sh \
    --topic "$TOPIC" \
    --input-file /shared-logs-output/kafka_export_from_migration_console_<ts>.proto.gz
```
