# Kafka Offloader
This offloader will act as a Kafka Producer for offloading captured traffic logs to the configured Kafka cluster as defined by the settings in the provided Kafka producer properties file. 

### Sample Kafka Producer Properties File
```
# Kafka brokers
bootstrap.servers = localhost:9092

client.id = KafkaLoggingProducer

# Serializer Kafka producer will use for key of a record sent to Kafka Topic
key.serializer = org.apache.kafka.common.serialization.StringSerializer

# Serializer Kafka producer will use for value of a record sent to Kafka Topic
value.serializer = org.apache.kafka.common.serialization.ByteArraySerializer

# --- Additional setup to use AWS MSK IAM library for communication with an AWS MSK cluster
# Sets up TLS for encryption and SASL for authN.
#security.protocol = SASL_SSL

# Identifies the SASL mechanism to use.
#sasl.mechanism = AWS_MSK_IAM

# Binds SASL client implementation.
#sasl.jaas.config = software.amazon.msk.auth.iam.IAMLoginModule required;

# Encapsulates constructing a SigV4 signature based on extracted credentials.
# The SASL client bound by "sasl.jaas.config" invokes this class.
#sasl.client.callback.handler.class = software.amazon.msk.auth.iam.IAMClientCallbackHandler
```

### Development

Resources for making testing/development easier

#### Setting up a development Kafka cluster

For quick startup of Kafka, you can run it inside a docker container.  Pull the Apache Kafka image and run it.

```
docker pull apache/kafka:3.9.1
docker run -p 9092:9092 apache/kafka:3.9.1
```

More information can be found at https://kafka.apache.org/quickstart, including commands to test and query a running
Kafka cluster.

#### Tests

There is also a [Testcontainer](https://java.testcontainers.org/modules/kafka/) that is used
for several tests within the migrations repo.  See 
[KafkaTrafficCaptureSourceLongTermTest](../trafficReplayer/src/test/java/org/opensearch/migrations/replay/kafka/KafkaTrafficCaptureSourceLongTermTest.java) and 
[KafkaConfigurationCaptureProxyTest](../trafficCaptureProxyServer/src/test/java/org/opensearch/migrations/trafficcapture/proxyserver/KafkaConfigurationCaptureProxyTest.java) 
for examples.  These containers can start up within several seconds and the resulting tests can be simpler to 
maintain than to rely upon mocking.