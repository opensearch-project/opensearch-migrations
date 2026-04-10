import kafkaBrokerConfigMetadata from "../generated/kafkaBrokerConfigSchema.v4.2.0.metadata.json";
import kafkaBrokerConfigSchema from "../generated/kafkaBrokerConfigSchema.v4.2.0.schema.json";
import {
    STRIMZI_DOCUMENTED_EXCEPTION_EXACT_KEYS,
    STRIMZI_DOCUMENTED_FORBIDDEN_EXACT_KEYS,
    STRIMZI_DOCUMENTED_FORBIDDEN_PREFIXES,
    isDocumentedStrimziExceptionListenerKey,
} from "./kafkaBrokerConfigRules";

type JsonObject = Record<string, unknown>;

function isObject(value: unknown): value is JsonObject {
    return value !== null && typeof value === "object" && !Array.isArray(value);
}

function endsWithInstancePath(instancePath: string, suffix: string[]) {
    const segments = instancePath.split("/").filter(Boolean);
    if (segments.length < suffix.length) {
        return false;
    }
    return suffix.every((segment, index) => segments[segments.length - suffix.length + index] === segment);
}

export function cloneKafkaBrokerConfigSchema(): JsonObject {
    return JSON.parse(JSON.stringify(kafkaBrokerConfigSchema));
}

export function getKafkaBrokerConfigSchemaMetadata() {
    return kafkaBrokerConfigMetadata;
}

export function isWorkflowManagedKafkaBrokerConfigPath(instancePath: string) {
    return endsWithInstancePath(instancePath, ["autoCreate", "clusterSpecOverrides", "kafka", "config"]);
}

export function classifyKafkaBrokerConfigKey(key: string): "disallowed-by-strimzi" | "unknown" {
    if (STRIMZI_DOCUMENTED_EXCEPTION_EXACT_KEYS.has(key) || isDocumentedStrimziExceptionListenerKey(key)) {
        return "unknown";
    }

    if (STRIMZI_DOCUMENTED_FORBIDDEN_EXACT_KEYS.has(key)) {
        return "disallowed-by-strimzi";
    }

    if (STRIMZI_DOCUMENTED_FORBIDDEN_PREFIXES.some(prefix => key.startsWith(prefix))) {
        return "disallowed-by-strimzi";
    }

    return "unknown";
}

function replaceKafkaConfigSchemaInNode(node: unknown): boolean {
    if (Array.isArray(node)) {
        return node.some(replaceKafkaConfigSchemaInNode);
    }
    if (!isObject(node)) {
        return false;
    }

    const properties = isObject(node.properties) ? node.properties : undefined;
    if (properties && "config" in properties && "listeners" in properties) {
        properties.config = {
            $ref: "#/$defs/Kafka420BrokerConfig",
            description: "Kafka 4.2.0 broker configuration validated against the pinned Kafka catalog and Strimzi-managed key restrictions.",
        };
        return true;
    }

    return Object.values(node).some(replaceKafkaConfigSchemaInNode);
}

export function injectKafkaBrokerConfigSchema(defs: Record<string, unknown>) {
    defs.Kafka420BrokerConfig = cloneKafkaBrokerConfigSchema();
    const replaced = Object.values(defs).some(replaceKafkaConfigSchemaInNode);
    if (!replaced) {
        throw new Error("Unable to locate the Strimzi Kafka broker config schema to replace with the pinned Kafka 4.2.0 schema");
    }
}
