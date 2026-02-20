# Kafka Utils

This package contains utilities for working with Kafka in relation to the Migration Assistant.


## Setting up an environment to load Kafka records from an export

We currently do not include this library in our Docker images, so to load Kafka records from a compressed export file, this utility will need to be able to communicate with the desired kafka cluster. The following sections describe steps to setup an Migraiton Assistant environment that we load our Kafka export into. 

### Setting up a Kubernetes environment

Run a simple single-node Kafka cluster and forward its port.

```bash
kubectl run kafka --image=apache/kafka:latest --port=9092 \
  --env="KAFKA_NODE_ID=1" \
  --env="KAFKA_PROCESS_ROLES=broker,controller" \
  --env="KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093" \
  --env="KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://kafka:9092" \
  --env="KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER" \
  --env="KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT" \
  --env="KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093" \
  --env="KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1" \
  --env="KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1" \
  --env="KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1" \
  --env="CLUSTER_ID=MkU3OEVBNTcwNTJENDM2Qk"

kubectl expose pod kafka --port=9092
```

```shell
kubectl -n ma port-forward svc/captured-traffic-broker-external-0 9092:10092
```

## Running the utility with Gradle
The following command can be modified to execute with a given kafka export input file and kafka broker endpoint
```shell
./gradlew :libraries:KafkaUtils:run --args="--inputFile '<INPUT_FILE_PATH>' --kafkaConnection localhost:9092"
```
