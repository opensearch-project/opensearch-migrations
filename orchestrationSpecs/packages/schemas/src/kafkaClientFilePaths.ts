// Shared by config-processor and workflow-template packages so rendered Kafka
// client property files point at the same paths the workflow mounts.
export const KAFKA_AUTH_CONFIG_MOUNT_PATH = "/config/kafka-auth";
export const KAFKA_CLIENT_PROPERTIES_FILE_NAME = "client.properties";
export const KAFKA_AUTH_CONFIG_FILE_PATH =
    `${KAFKA_AUTH_CONFIG_MOUNT_PATH}/${KAFKA_CLIENT_PROPERTIES_FILE_NAME}`;

export const KAFKA_CA_MOUNT_PATH = "/config/kafka-ca";
export const KAFKA_CA_CERT_FILE_NAME = "ca.crt";
export const KAFKA_CA_CERT_FILE_PATH = `${KAFKA_CA_MOUNT_PATH}/${KAFKA_CA_CERT_FILE_NAME}`;
