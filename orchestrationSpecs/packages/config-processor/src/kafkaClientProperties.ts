import {z} from "zod";
import {KAFKA_CA_CERT_FILE_PATH, KAFKA_CLIENT_CONFIG} from "@opensearch-migrations/schemas";

export type KafkaClientPropertyValue = string | number | boolean;
export type KafkaClientProperties = Record<string, KafkaClientPropertyValue>;
export type KafkaClientRole = "producer" | "consumer";

type KafkaClientConfig = z.infer<typeof KAFKA_CLIENT_CONFIG>;
type ProtectedKeyReasons = Map<string, string>;

// Protected keys are owned by the workflow or by the producer/consumer app contract.
// They are protected by rejecting user-provided file entries during config processing,
// before the rendered properties file is mounted into the Argo workflow.
function addAuthProtectedKeys(keys: ProtectedKeyReasons, kafkaConfig: KafkaClientConfig): void {
    if (kafkaConfig.authType !== "none" || kafkaConfig.enableMSKAuth) {
        keys.set("security.protocol", "auth");
        keys.set("sasl.mechanism", "auth");
        keys.set("sasl.jaas.config", "auth");
    }

    if (kafkaConfig.enableMSKAuth) {
        keys.set("sasl.client.callback.handler.class", "enableMSKAuth");
    }

    if (kafkaConfig.authType === "scram-sha-512" && kafkaConfig.caSecretName) {
        keys.set("ssl.truststore.type", "auth.caSecretName");
        keys.set("ssl.truststore.location", "auth.caSecretName");
    }
}

function protectedProducerKeys(kafkaConfig: KafkaClientConfig): ProtectedKeyReasons {
    const keys: ProtectedKeyReasons = new Map([
        ["bootstrap.servers", "kafkaConnection"],
        ["client.id", "proxyConfig.kafkaClientId"],
        ["key.serializer", "capture proxy producer serialization"],
        ["value.serializer", "capture proxy producer serialization"],
    ]);
    addAuthProtectedKeys(keys, kafkaConfig);
    return keys;
}

function protectedConsumerKeys(kafkaConfig: KafkaClientConfig): ProtectedKeyReasons {
    const keys: ProtectedKeyReasons = new Map([
        ["bootstrap.servers", "kafkaConnection"],
        ["group.id", "traffic replayer group id"],
        ["key.deserializer", "traffic replayer deserialization"],
        ["value.deserializer", "traffic replayer deserialization"],
        ["enable.auto.commit", "traffic replayer offset management"],
        ["partition.assignment.strategy", "traffic replayer partition assignment strategy"],
    ]);
    addAuthProtectedKeys(keys, kafkaConfig);
    return keys;
}

function protectedKeysForRole(role: KafkaClientRole, kafkaConfig: KafkaClientConfig): ProtectedKeyReasons {
    return role === "producer"
        ? protectedProducerKeys(kafkaConfig)
        : protectedConsumerKeys(kafkaConfig);
}

function validateProtectedKeys(
    role: KafkaClientRole,
    clusterKey: string,
    properties: Record<string, unknown>,
    protectedKeys: ProtectedKeyReasons
): void {
    for (const key of Object.keys(properties)) {
        const controlledBy = protectedKeys.get(key);
        if (controlledBy !== undefined) {
            throw new Error(
                `Kafka ${role} client property ` +
                `kafkaClusterConfiguration.${clusterKey}.existing.clientProperties.${role}.${key} ` +
                `is workflow-owned and must be configured through ${controlledBy}.`
            );
        }
    }
}

function assertKafkaClientPropertyNamesAndValues(
    role: KafkaClientRole,
    clusterKey: string,
    properties: Record<string, unknown>
): asserts properties is KafkaClientProperties {
    for (const [key, value] of Object.entries(properties)) {
        const path = `kafkaClusterConfiguration.${clusterKey}.existing.clientProperties.${role}.${key}`;
        if (key.length === 0) {
            throw new Error(`${path} must not use an empty Kafka client property name.`);
        }
        if (
            !["string", "number", "boolean"].includes(typeof value) ||
            (typeof value === "number" && !Number.isFinite(value))
        ) {
            throw new Error(`${path} must be a flat string, number, or boolean Kafka client property value.`);
        }
    }
}

export function workflowGeneratedKafkaClientProperties(
    kafkaConfig: KafkaClientConfig
): KafkaClientProperties {
    if (kafkaConfig.authType !== "scram-sha-512" || !kafkaConfig.caSecretName) {
        return {};
    }

    return {
        "ssl.truststore.type": "PEM",
        "ssl.truststore.location": KAFKA_CA_CERT_FILE_PATH,
    };
}

function stringifyKafkaClientPropertyValue(value: KafkaClientPropertyValue): string {
    return typeof value === "string" ? value : String(value);
}

function escapeJavaPropertiesPart(input: string, isKey: boolean): string {
    let out = "";
    for (let i = 0; i < input.length; i++) {
        const ch = input[i];
        const code = ch.charCodeAt(0);
        const isLeadingSpace = i === 0 && ch === " ";
        if (ch === "\\") out += "\\\\";
        else if (ch === "\n") out += "\\n";
        else if (ch === "\r") out += "\\r";
        else if (ch === "\t") out += "\\t";
        else if (ch === "\f") out += "\\f";
        else if (isLeadingSpace) out += "\\ ";
        else if (isKey && (ch === "=" || ch === ":" || ch === " ")) out += `\\${ch}`;
        else if (isKey && (ch === "#" || ch === "!")) out += `\\${ch}`;
        else if (!isKey && (i === 0 && (ch === "#" || ch === "!"))) out += `\\${ch}`;
        else if (code < 0x20 || code > 0x7e) out += `\\u${code.toString(16).padStart(4, "0")}`;
        else out += ch;
    }
    return out;
}

export function renderJavaProperties(properties: KafkaClientProperties): string {
    const keys = Object.keys(properties).sort();
    if (keys.length === 0) {
        return "";
    }

    return keys
        .map(key => [
            escapeJavaPropertiesPart(key, true),
            escapeJavaPropertiesPart(stringifyKafkaClientPropertyValue(properties[key]), false),
        ].join("="))
        .join("\n") + "\n";
}

export function buildKafkaClientPropertiesFileContent(args: {
    role: KafkaClientRole,
    clusterKey: string,
    kafkaConfig: KafkaClientConfig,
    userProperties?: KafkaClientProperties,
}): string {
    const userProperties = (args.userProperties ?? {}) as Record<string, unknown>;
    assertKafkaClientPropertyNamesAndValues(args.role, args.clusterKey, userProperties);
    validateProtectedKeys(
        args.role,
        args.clusterKey,
        userProperties,
        protectedKeysForRole(args.role, args.kafkaConfig)
    );

    return renderJavaProperties({
        ...workflowGeneratedKafkaClientProperties(args.kafkaConfig),
        ...userProperties,
    });
}
