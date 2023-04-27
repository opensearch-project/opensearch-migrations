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

##### Setting up a development Kafka cluster

For quick startup of a Kafka cluster sample docker-compose files are placed in [dev-tools](../dev-tools/docker)

A Kafka instance can be started by running the following command in the proper directory
```
docker-compose up
```
This Kafka instance can then be cleaned up with the following command
```
docker-compose down -v
```