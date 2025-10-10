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