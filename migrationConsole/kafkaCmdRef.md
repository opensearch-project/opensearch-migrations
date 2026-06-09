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

#### `console kafka describe-consumer-group` (with TIME LAG section)

The migration console wraps the native describe with a per-partition
**TIME LAG** section, showing how stale each partition's consumer position is
in wall-clock time — useful when message-count lag (`LAG = LOG-END-OFFSET -
CURRENT-OFFSET`) doesn't tell you whether the group is 50ms or 50min behind.

Invocation (inside the migration-console pod, EKS or local):

```shell
console kafka describe-consumer-group logging-group-default
```

The native `kafka-consumer-groups.sh --describe` table is preserved verbatim,
followed by a section like:

```
TOPIC  PARTITION  PROBED-OFFSET  TIMESTAMP (UTC)         TIME-LAG  NOTE
t1     0          100            2024-01-02T03:04:05Z    8m12s
t1     1          199            2024-01-02T03:11:50Z    27s       caught up; showing last record
t1     2          -              -                       -         partition empty
```

Row semantics:

- **Active-lag** partitions probe `CURRENT-OFFSET` (the next record the group
  is about to consume) so `TIME-LAG` is the age of the oldest unprocessed
  record.
- **Caught-up** partitions (`LAG == 0`) probe `LOG-END-OFFSET - 1` so the row
  still surfaces the age of the last produced record; annotated `caught up;
  showing last record`.
- **Empty** partitions (`LOG-END-OFFSET == 0`) skip the probe and render
  `partition empty`.
- Records without a timestamp (`timestamp.type=NoTimestampType`) surface as
  `record has no timestamp`; per-partition probe failures (timeout, parse
  error, missing tool) render `-` in the affected columns and never abort the
  rest of the table.

If the consumer group has not yet committed any offsets (e.g. the replayer
joined the group but auto-commit hasn't fired), the section falls back to:

```
PARTITION TIME LAG
  (no committed offsets parsed from the describe output above; nothing to probe)
```

In the EKS CDC integ tests, `log_kafka_consumer_group_state` polls briefly
for the first auto-commit before invoking describe, so the labelled
`[replay-start]` / `[replay-end]` snapshots in the test log capture the
full time-lag table rather than the empty-group fallback.

Delete a particular Consumer Group (Requires Consumer Group to be empty to perform)
```shell
./kafka-consumer-groups.sh --bootstrap-server "$MIGRATION_KAFKA_BROKER_ENDPOINTS" --timeout 100000 --delete --group logging-group-default
```

Reset a Consumer Group offset to latest (Requires Consumer Group to not be active to perform)
More options for different types of reset [here](https://docs.cloudera.com/runtime/7.2.8/kafka-managing/topics/kafka-manage-cli-cgroups.html#pnavId2)
```shell
./kafka-consumer-groups.sh --bootstrap-server "$MIGRATION_KAFKA_BROKER_ENDPOINTS" --timeout 100000 --reset-offsets --to-latest --topic logging-traffic-topic --group logging-group-default --execute
```