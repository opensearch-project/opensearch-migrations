import fs from "node:fs";
import path from "node:path";
import https from "node:https";
import {GenericContainer, StartedTestContainer, Wait} from "testcontainers";
import {isStrimziAllowedKafkaBrokerConfigKey} from "./kafkaBrokerConfigRules";

/*
 * This generator intentionally combines multiple upstream sources, and the
 * distinction matters because they do different jobs in the final schema.
 *
 * See the end of this documentation for more information on how to regenerate
 * the set of forbidden keys from Strimzi when upgrading Strimzi.
 *
 * Source 1: live Kafka container output
 * - We start a real Kafka 4.2.0 broker in Testcontainers.
 * - We run `kafka-configs.sh --describe --all` against that broker.
 * - The parsed output gives us the authoritative set of broker config keys that
 *   the running Kafka image actually reports.
 *
 * Source 2: Kafka web docs
 * - In parallel, we fetch the official Kafka 4.2 broker-config reference page.
 * - We parse that HTML into a map keyed by broker config name.
 * - For each key, the docs supply the metadata the container output does not
 *   give us in a structured form for schema generation: description, value
 *   type, default value, valid-values text, importance, and update mode.
 *
 * How those Kafka sources are combined:
 * - The live container is the source of truth for "which keys exist".
 * - The web docs are the source of truth for "what each key means".
 * - `buildSchema()` effectively performs an inner join:
 *   for every key seen from the live broker, look up the matching web-doc entry
 *   and build the JSON Schema property from that metadata.
 * - If the broker exposes a key that is missing from the web docs, generation
 *   fails fast. That is intentional, because a silently incomplete join would
 *   produce a misleading schema.
 * - The inverse mismatch is tolerated: if the docs page mentions extra keys that
 *   the live broker does not expose, those doc-only entries are ignored because
 *   the emitted schema is anchored to the actual container-reported key set.
 *
 * So the Kafka portion is not "container or docs"; it is:
 *   live broker key inventory + web-doc metadata for those same keys
 *
 * Source 3: Strimzi policy
 * - After the Kafka-derived schema surface is assembled, Strimzi policy is
 *   applied as a filter.
 * - Strimzi's `Kafka.spec.kafka.config` docs describe which Kafka broker
 *   configs are operator-managed and therefore not user-settable in a
 *   Strimzi-managed cluster.
 * - That information is not derivable from Kafka itself, because the blocked
 *   keys come from operator policy rather than from Kafka's own broker config
 *   definitions.
 *
 * The "special" Strimzi treatment in this generator therefore does NOT mean we
 * hand-curate the broker config catalog. The catalog still comes from Kafka.
 * Strimzi only removes or preserves entries from that Kafka-derived set
 * according to its documented restrictions and exceptions.
 *
 * In execution-order terms:
 * 1. Read broker keys from the live Kafka container.
 * 2. Read broker metadata from the Kafka web docs.
 * 3. Join those Kafka sources by config key.
 * 4. Filter the joined result through Strimzi policy.
 *
 * In other words:
 *   final schema = (live Kafka key set + Kafka doc metadata) filtered by Strimzi policy
 *
 * The actual Strimzi policy constants live separately in
 * `kafkaBrokerConfigRules.ts` so future readers can see the separation clearly:
 * Kafka-derived definitions vs operator-specific restrictions.
 *
 * Maintenance/upkeep:
 * - Updating Kafka alone should become mostly mechanical: bump the Kafka image
 *   and docs target, then regenerate.
 * - But because the Kafka result is a join between container output and the
 *   docs page, Kafka upgrades can still fail if the HTML shape changes or if the
 *   docs and image drift far enough apart that a broker key no longer has a
 *   matching docs entry.
 * - Updating Strimzi is more involved because this file depends on a policy
 *   layer that that must be interpreted from Strimzi documentation that describes
 *   which keys are managed by Strimzi and forbidden to be specified in the user-config.
 * - So each Strimzi upgrade still needs a person to review the documented
 *   forbidden prefixes, exact blocked keys, and exception list in
 *   `kafkaBrokerConfigRules.ts`.
 * - That means this design carries ongoing maintenance cost and some upgrade
 *   risk.
 */

const KAFKA_VERSION = "4.2.0";
const KAFKA_IMAGE = `apache/kafka:${KAFKA_VERSION}`;
const KAFKA_DOCS_URL = "https://kafka.apache.org/42/configuration/broker-configs/";
const OUTPUT_DIR = path.resolve(__dirname, "..", "generated");
const SCHEMA_OUTPUT_PATH = path.join(OUTPUT_DIR, `kafkaBrokerConfigSchema.v${KAFKA_VERSION}.schema.json`);
const METADATA_OUTPUT_PATH = path.join(OUTPUT_DIR, `kafkaBrokerConfigSchema.v${KAFKA_VERSION}.metadata.json`);

type KafkaValueType = "list" | "int" | "boolean" | "string" | "long" | "short" | "password" | "double" | "class";

interface KafkaDocEntry {
    key: string;
    description: string;
    type: KafkaValueType;
    defaultValue: string;
    validValues: string;
    importance: string;
    updateMode: string;
}

interface GeneratedSchemaMetadata {
    generatedAt: string;
    kafkaVersion: string;
    kafkaImage: string;
    kafkaDocsUrl: string;
    sourceCommand: string;
    totalKafkaBrokerConfigs: number;
    totalWorkflowManagedKafkaBrokerConfigs: number;
    filteredOutByStrimzi: string[];
    preservedByStrimziException: string[];
}

function fetchText(url: string): Promise<string> {
    return new Promise((resolve, reject) => {
        https.get(url, (response) => {
            if ((response.statusCode ?? 500) >= 400) {
                reject(new Error(`Failed to fetch ${url}: ${response.statusCode}`));
                return;
            }
            const chunks: Buffer[] = [];
            response.on("data", (chunk) => chunks.push(Buffer.from(chunk)));
            response.on("end", () => resolve(Buffer.concat(chunks).toString("utf-8")));
            response.on("error", reject);
        }).on("error", reject);
    });
}

function stripHtml(value: string) {
    return value
        .replace(/<br\s*\/?>/gi, "\n")
        .replace(/<[^>]+>/g, " ")
        .replace(/&quot;/g, "\"")
        .replace(/&#39;/g, "'")
        .replace(/&lt;/g, "<")
        .replace(/&gt;/g, ">")
        .replace(/&amp;/g, "&")
        .replace(/\s+/g, " ")
        .trim();
}

function parseKafkaDocs(html: string) {
    const entries = new Map<string, KafkaDocEntry>();
    const matches = html.matchAll(/<li><h4><a id=([^>]+)><\/a><a id=brokerconfigs_[^>]+href=[^>]+>([^<]+)<\/a><\/h4><p>([\s\S]*?)<\/p><table><tbody>([\s\S]*?)<\/tbody><\/table><\/li>/g);
    for (const match of matches) {
        const key = match[2];
        const rowMap = Object.fromEntries(
            [...match[4].matchAll(/<tr><th>([^<]+)<\/th><td>([\s\S]*?)<\/td><\/tr>/g)]
                .map(([, rawKey, rawValue]) => [rawKey.replace(/:$/, ""), stripHtml(rawValue)])
        );
        entries.set(key, {
            key,
            description: stripHtml(match[3]),
            type: (rowMap["Type"] || "string") as KafkaValueType,
            defaultValue: rowMap["Default"] || "",
            validValues: rowMap["Valid Values"] || "",
            importance: rowMap["Importance"] || "",
            updateMode: rowMap["Update Mode"] || "",
        });
    }
    return entries;
}

function parseBrokerConfigDescribeOutput(output: string) {
    const keys = new Set<string>();
    for (const line of output.split("\n")) {
        const trimmed = line.trim();
        if (!trimmed || trimmed.startsWith("Dynamic configs") || trimmed.startsWith("All configs")) {
            continue;
        }
        const equalsIndex = trimmed.indexOf("=");
        if (equalsIndex <= 0) {
            continue;
        }
        keys.add(trimmed.slice(0, equalsIndex).trim());
    }
    return keys;
}

function schemaForKafkaType(type: KafkaValueType): Record<string, unknown> {
    switch (type) {
        case "boolean":
            return {type: "boolean"};
        case "int":
        case "long":
        case "short":
            return {type: "integer"};
        case "double":
            return {type: "number"};
        case "list":
        case "string":
        case "password":
        case "class":
        default:
            return {type: "string"};
    }
}

function parseDefaultValue(raw: string, type: KafkaValueType): unknown {
    const normalized = raw.trim();
    if (!normalized || normalized === "null") {
        return undefined;
    }

    const firstToken = normalized.split(" ")[0];
    if (type === "boolean") {
        if (firstToken === "true") return true;
        if (firstToken === "false") return false;
        return undefined;
    }
    if (type === "int" || type === "long" || type === "short" || type === "double") {
        const value = Number(firstToken);
        return Number.isFinite(value) ? value : undefined;
    }
    return normalized;
}

function buildSchema(allKeys: Set<string>, docsByKey: Map<string, KafkaDocEntry>) {
    const filteredOutByStrimzi: string[] = [];
    const preservedByStrimziException: string[] = [];
    const properties: Record<string, unknown> = {};

    const keys = [...allKeys].sort();
    for (const key of keys) {
        const doc = docsByKey.get(key);
        if (!doc) {
            throw new Error(`Kafka docs metadata not found for broker config '${key}'`);
        }

        // This is the one place where Strimzi-specific policy influences the
        // Kafka-derived catalog. The rule set is intentionally isolated in
        // `kafkaBrokerConfigRules.ts` because these decisions come from
        // Strimzi's documentation for `Kafka.spec.kafka.config`, not from
        // Kafka's broker metadata or CRD shape.
        //
        // If Strimzi changes policy in a future release without changing the
        // CRD shape, regeneration alone will not catch that. A human still has
        // to review and update the Strimzi policy file.
        const allowedByStrimzi = isStrimziAllowedKafkaBrokerConfigKey(key);
        if (!allowedByStrimzi) {
            filteredOutByStrimzi.push(key);
            continue;
        }

        // These are not "manually allowed Kafka keys"; they are the subset of
        // Kafka-derived keys that Strimzi explicitly documents as exceptions to
        // its broader forbidden-prefix rules. This list exists only for
        // provenance/debugging in the generated metadata.
        if (key.startsWith("ssl.") || key.startsWith("controller.") || key.startsWith("cruise.control.metrics.topic") || key.startsWith("listener.name.")) {
            preservedByStrimziException.push(key);
        }

        const propertySchema: Record<string, unknown> = {
            ...schemaForKafkaType(doc.type),
            description: doc.description,
            "x-kafka-type": doc.type,
            "x-kafka-importance": doc.importance,
            "x-kafka-update-mode": doc.updateMode,
        };

        if (doc.validValues) {
            propertySchema["x-kafka-valid-values"] = doc.validValues;
        }

        const parsedDefault = parseDefaultValue(doc.defaultValue, doc.type);
        if (parsedDefault !== undefined) {
            propertySchema.default = parsedDefault;
        }

        properties[key] = propertySchema;
    }

    return {
        schema: {
            type: "object",
            additionalProperties: false,
            properties,
            description: "Kafka 4.2.0 broker configuration keys derived from a live Kafka 4.2.0 broker plus the official Kafka broker-config reference, filtered by Strimzi's documented operator-managed restrictions.",
        },
        filteredOutByStrimzi,
        preservedByStrimziException: [...new Set(preservedByStrimziException)].sort(),
    };
}

async function withKafkaContainer<T>(fn: (container: StartedTestContainer) => Promise<T>): Promise<T> {
    const container = await new GenericContainer(KAFKA_IMAGE)
        .withEnvironment({
            KAFKA_NODE_ID: "1",
            KAFKA_PROCESS_ROLES: "broker,controller",
            KAFKA_LISTENERS: "PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093",
            KAFKA_ADVERTISED_LISTENERS: "PLAINTEXT://localhost:9092",
            KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: "PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT",
            KAFKA_CONTROLLER_LISTENER_NAMES: "CONTROLLER",
            KAFKA_CONTROLLER_QUORUM_VOTERS: "1@localhost:9093",
        })
        .withWaitStrategy(Wait.forLogMessage("Kafka Server started"))
        .start();

    try {
        return await fn(container);
    } finally {
        await container.stop();
    }
}

async function readKafkaBrokerConfigKeysFromLiveBroker() {
    return withKafkaContainer(async (container) => {
        const command = [
            "/opt/kafka/bin/kafka-configs.sh",
            "--bootstrap-server",
            "localhost:9092",
            "--entity-type",
            "brokers",
            "--entity-name",
            "1",
            "--describe",
            "--all",
        ];
        const result = await container.exec(command);
        if (result.exitCode !== 0) {
            throw new Error(`kafka-configs.sh failed: ${result.output}`);
        }
        return {
            keys: parseBrokerConfigDescribeOutput(result.output),
            sourceCommand: command.join(" "),
        };
    });
}

export async function main() {
    const [docsHtml, brokerSnapshot] = await Promise.all([
        fetchText(KAFKA_DOCS_URL),
        readKafkaBrokerConfigKeysFromLiveBroker(),
    ]);

    const docsByKey = parseKafkaDocs(docsHtml);
    const {schema, filteredOutByStrimzi, preservedByStrimziException} = buildSchema(brokerSnapshot.keys, docsByKey);

    const metadata: GeneratedSchemaMetadata = {
        generatedAt: new Date().toISOString(),
        kafkaVersion: KAFKA_VERSION,
        kafkaImage: KAFKA_IMAGE,
        kafkaDocsUrl: KAFKA_DOCS_URL,
        sourceCommand: brokerSnapshot.sourceCommand,
        totalKafkaBrokerConfigs: brokerSnapshot.keys.size,
        totalWorkflowManagedKafkaBrokerConfigs: Object.keys((schema.properties ?? {}) as Record<string, unknown>).length,
        filteredOutByStrimzi,
        preservedByStrimziException,
    };

    fs.mkdirSync(OUTPUT_DIR, {recursive: true});
    fs.writeFileSync(SCHEMA_OUTPUT_PATH, JSON.stringify(schema, null, 2) + "\n");
    fs.writeFileSync(METADATA_OUTPUT_PATH, JSON.stringify(metadata, null, 2) + "\n");
    console.error(`Wrote Kafka broker config schema to ${SCHEMA_OUTPUT_PATH}`);
    console.error(`Wrote Kafka broker config metadata to ${METADATA_OUTPUT_PATH}`);
}

if (require.main === module && !process.env.SUPPRESS_AUTO_LOAD) {
    main().catch((error) => {
        console.error(error);
        process.exit(1);
    });
}
