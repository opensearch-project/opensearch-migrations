# Kafka Offloader
This offloader will act as a Kafka Producer for offloading captured traffic logs to the configured Kafka cluster as defined by the settings in the provided Kafka producer properties file. 

### Sample Kafka Producer Properties File
```
# Kafka brokers
bootstrap.servers = localhost:9092

client.id = KafkaLoggingProducer

# Serializer for key of Producer record map
key.serializer = org.apache.kafka.common.serialization.StringSerializer

# Serializer for value of Producer record map
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

### Sample Docker Compose for Local Kafka Cluster
```
version: '3'
services:
  zookeeper:
    image: confluentinc/cp-zookeeper:7.3.2
    container_name: zookeeper
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181
      ZOOKEEPER_TICK_TIME: 2000

  broker:
    image: confluentinc/cp-kafka:7.3.2
    container_name: broker
    ports:
      - "9092:9092"
    depends_on:
      - zookeeper
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: 'zookeeper:2181'
      # Development values for easy debugging
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT,PLAINTEXT_INTERNAL:PLAINTEXT
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092,PLAINTEXT_INTERNAL://broker:29092
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
```