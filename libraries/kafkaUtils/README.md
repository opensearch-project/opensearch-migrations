# Kafka Utils

This package contains utilities for working with Kafka in relation to the Migration Assistant.


## Setting up an environment to load Kafka records from an export

We currently do not include this library in our Docker images, so to load Kafka records from a compressed export file, this utility will need to be able to communicate with the desired kafka cluster. The following sections describe steps to setup an Migraiton Assistant environment that we load our Kafka export into. 

### Setting up a Kubernetes environment

We can follow the quickstart guide [here](../../deployment/k8s/quickstart.md) to setup a testing environment for K8s, but *FIRST* we should add an external listener for our Kafka cluster so we can run this utility locally and communicate with the kafka cluster

1. Modify the kafka cluster configuration found [here](../../deployment/k8s/charts/sharedResources/baseKafkaCluster/templates/configuration.yaml) to include this additional external listener:
```yaml
      - name: external
        port: 10092
        type: nodeport
        tls: false
        configuration:
          brokers:
            - broker: 0
              advertisedHost: localhost
              advertisedPort: 9092
```

2. In a separate shell, set up a port forward so that the kafka broker can be reached at `localhost:9092`

```shell
kubectl -n ma port-forward svc/captured-traffic-broker-external-0 9092:10092
```

3. Follow the quickstart guide [here](../../deployment/k8s/quickstart.md) to set up the Migration Assistant environment



## Running the utility with Gradle
The following command can be modified to execute with a given kafka export input file and kafka broker endpoint
```shell
./gradlew :libraries:KafkaUtils:run --args="--inputFile '<INPUT_FILE_PATH>' --kafkaConnection localhost:9092"
```
