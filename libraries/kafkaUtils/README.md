# Kafka Utils

This package contains utilities for working with Kafka in relation to the Migration Assistant.



`deployment/k8s/charts/sharedResources/baseKafkaCluster/templates/configuration.yaml`

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

To run: 
```shell
./gradlew :libraries:KafkaUtils:run --args="--inputFile '/Users/my-user/Downloads/kafka_export_from_migration_console_1745351831.proto.gz' --kafkaConnection localhost:9092"
```
