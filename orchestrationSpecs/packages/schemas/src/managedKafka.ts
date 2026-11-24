/**
 * Kafka version for workflow-managed (auto-created) Strimzi clusters. It is not user-configurable.
 *
 * Keep these in step when bumping:
 * - the Strimzi operator chart (`charts.strimzi-kafka-operator.version` in the migrationAssistantWithArgo
 *   values.yaml) must list this version as supported in its kafka-versions.yaml; Strimzi 1.3.x supports
 *   Kafka 4.2.0-4.3.2.
 * - the `quay.io/strimzi/kafka:<strimzi>-kafka-<version>` image in infra/mirror/private-ecr-manifest.yaml.
 * - the broker config catalog in generated/kafkaBrokerConfigSchema.v4.2.0.*.json (same minor line).
 */
export const MANAGED_KAFKA_VERSION = "4.2.2";

/**
 * KRaft metadata version for MANAGED_KAFKA_VERSION (the `metadata` value in Strimzi's kafka-versions.yaml).
 * When an existing cluster is upgraded, Strimzi first rolls the brokers on their current metadata version
 * and raises it to this value in a later reconciliation.
 */
export const MANAGED_KAFKA_METADATA_VERSION = "4.2-IV1";
