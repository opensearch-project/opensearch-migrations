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