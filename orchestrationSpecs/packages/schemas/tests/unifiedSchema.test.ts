import path from "node:path";
import os from "node:os";
import fs from "node:fs";
import childProcess from "node:child_process";
import Ajv from "ajv";
import {
    ALLOW_FALLBACK_UNIFIED_SCHEMA_ENV,
    buildUnifiedSchema,
    loadUnifiedSchema,
    UNIFIED_SCHEMA_PATH_ENV,
} from "../src";

const strimziFixturePath = path.resolve(__dirname, "fixtures", "strimzi", "minimal-openapi.json");

function getAutoCreateProperties(schema: any) {
    return schema.properties.traffic.properties.kafkaClusters.additionalProperties.anyOf
        .find((branch: any) => branch.properties?.autoCreate)
        .properties.autoCreate.properties;
}

function getTopicProperties(schema: any) {
    return schema.properties.traffic.properties.kafkaClusters.additionalProperties.anyOf
        .find((branch: any) => branch.properties?.autoCreate)
        .properties.topics.additionalProperties.properties;
}

function resolveRef(schema: any, root: any): any {
    if (!schema?.$ref?.startsWith("#/")) {
        return schema;
    }
    return schema.$ref
        .slice(2)
        .split("/")
        .reduce((current: any, part: string) => current?.[part], root);
}

function getNodePoolProperties(schema: any) {
    return resolveRef((schema as any).$defs.StrimziKafkaNodePoolSpec, schema).properties;
}

function getPersistentStorageProperties(schema: any) {
    return (schema as any).$defs["io.strimzi.api.kafka.model.storage.PersistentClaimStorage"].properties;
}

const validConfig = {
    sourceClusters: {
        source: {
            version: "ES 7.10.2",
            endpoint: "http://source:9200",
        },
    },
    targetClusters: {
        target: {
            endpoint: "http://target:9200",
        },
    },
    snapshotMigrationConfigs: [],
    traffic: {
        kafkaClusters: {
            default: {
                autoCreate: {
                    auth: {
                        type: "scram-sha-512",
                    },
                    clusterSpecOverrides: {
                        kafka: {
                            config: {
                                "offsets.topic.replication.factor": 1,
                                "compression.type": "producer",
                            },
                            listeners: [
                                {
                                    name: "external",
                                    port: 9094,
                                    type: "loadbalancer",
                                    tls: true,
                                    authentication: {
                                        type: "scram-sha-512",
                                    },
                                },
                            ],
                        },
                        entityOperator: {
                            topicOperator: {},
                            userOperator: {},
                        },
                    },
                    nodePoolSpecOverrides: {
                        replicas: 3,
                        roles: ["broker", "controller"],
                        storage: {
                            type: "jbod",
                            volumes: [
                                {
                                    id: 0,
                                    type: "persistent-claim",
                                    size: "100Gi",
                                },
                            ],
                        },
                    },
                },
                topics: {
                    capture: {
                        specOverrides: {
                        partitions: 12,
                        replicas: 2,
                        config: {
                            "cleanup.policy": "compact",
                        },
                    },
                    },
                },
            },
        },
    },
};

describe("unified schema builder", () => {
    it("merges Strimzi definitions from a static OpenAPI fixture", () => {
        const {schema} = buildUnifiedSchema({
            strimziSchemaPath: strimziFixturePath,
        });
        const topicSpec = (schema as any).$defs.StrimziKafkaTopicSpec;

        expect(topicSpec.properties.topicName).toBeUndefined();
        expect(topicSpec.properties.partitions).toMatchObject({
            type: "integer",
            minimum: 1,
            default: 2,
            "x-essential": true,
            "x-effective-default": {
                label: "2",
                value: 2,
            },
        });
        expect(topicSpec.properties.partitions["x-expert"]).toBeUndefined();
        expect(topicSpec.properties.replicas).toMatchObject({
            type: "integer",
            minimum: 1,
            default: 3,
            "x-essential": true,
            "x-effective-default": {
                label: "3",
                value: 3,
            },
        });
        expect(topicSpec.properties.replicas["x-expert"]).toBeUndefined();
        expect(topicSpec.properties.config["x-expert"]).toBeUndefined();
        expect(topicSpec.properties.config["x-essential"]).toBe(true);
        expect(topicSpec.properties.config.properties["retention.ms"]).toMatchObject({
            type: "integer",
            default: 604800000,
            "x-essential": true,
            "x-effective-default": {
                label: "7 days",
                value: 604800000,
            },
        });
        expect(topicSpec.properties.config.properties["retention.ms"]["x-expert"]).toBeUndefined();
        expect(topicSpec.properties.config.properties["segment.bytes"]["x-expert"]).toBe(true);
        expect(getAutoCreateProperties(schema).auth["x-expert"]).toBe(true);
        expect(getAutoCreateProperties(schema).auth.description).toContain("over its TLS listener");
        expect(getAutoCreateProperties(schema).clusterSpecOverrides["x-expert"])
            .toBe(true);
        expect(getAutoCreateProperties(schema).nodePoolSpecOverrides).toMatchObject({
            "x-essential": true,
        });
        expect(getAutoCreateProperties(schema).nodePoolSpecOverrides["x-expert"])
            .toBeUndefined();
        expect(getTopicProperties(schema).specOverrides["x-expert"])
            .toBeUndefined();

        const nodePoolProperties = getNodePoolProperties(schema);
        expect(nodePoolProperties.replicas).toMatchObject({
            default: 3,
            "x-essential": true,
            "x-effective-default": {
                label: "3 brokers",
                value: 3,
            },
        });
        expect(nodePoolProperties.replicas["x-expert"]).toBeUndefined();
        expect(nodePoolProperties.storage["x-expert"]).toBeUndefined();
        expect(nodePoolProperties.storage["x-essential"]).toBe(true);
        expect(nodePoolProperties.resources["x-expert"]).toBeUndefined();
        expect(nodePoolProperties.resources["x-essential"]).toBe(true);
        expect(nodePoolProperties.resources.properties.requests["x-expert"]).toBeUndefined();
        expect(nodePoolProperties.resources.properties.limits["x-expert"]).toBeUndefined();
        expect(nodePoolProperties.resources.properties.claims["x-expert"]).toBe(true);
        expect(nodePoolProperties.roles["x-expert"]).toBe(true);
        expect(nodePoolProperties.template["x-expert"]).toBe(true);

        const storageProperties = getPersistentStorageProperties(schema);
        expect(storageProperties.type["x-expert"]).toBeUndefined();
        expect(storageProperties.size).toMatchObject({
            default: "2Gi",
            "x-essential": true,
            "x-effective-default": {
                label: "2Gi",
                value: "2Gi",
            },
        });
        expect(storageProperties.class["x-expert"]).toBeUndefined();
        expect(storageProperties.deleteClaim).toMatchObject({
            default: true,
            "x-essential": true,
            "x-effective-default": {
                label: "delete storage with removed Kafka nodes",
                value: true,
            },
        });
        expect(storageProperties.deleteClaim.description).toContain("not an automatic backup");
        expect(storageProperties.deleteClaim.description).toContain("replacement Strimzi Kafka resource");
        expect(storageProperties.deleteClaim.description).toContain("same logical cluster");
        expect(storageProperties.id["x-expert"]).toBe(true);
        expect((schema as any).$defs["io.strimzi.api.kafka.model.storage.JbodStorage"]
            .properties.type.default).toBeUndefined();

        expect(JSON.stringify({
            mode: schema["x-orchestration-specs-strimzi-schema-mode"],
            autoCreate: getAutoCreateProperties(schema),
            topic: getTopicProperties(schema),
            defs: {
                StrimziKafkaSpec: (schema as any).$defs.StrimziKafkaSpec,
                StrimziKafkaNodePoolSpec: (schema as any).$defs.StrimziKafkaNodePoolSpec,
                StrimziKafkaTopicSpec: (schema as any).$defs.StrimziKafkaTopicSpec,
                GenericKafkaListener: (schema as any).$defs["io.strimzi.api.kafka.model.GenericKafkaListener"],
            },
        }, null, 2)).toMatchSnapshot();
    });

    it("validates Strimzi override sections against the merged schema", () => {
        const {schema} = buildUnifiedSchema({
            strimziSchemaPath: strimziFixturePath,
        });
        const ajv = new Ajv(({allErrors: true, strict: false} as unknown) as ConstructorParameters<typeof Ajv>[0]);
        const validate = ajv.compile(schema);

        expect(validate(validConfig)).toBe(true);
        expect(validate.errors).toBeNull();

        for (const invalidSpecOverrides of [
            {partitions: 0},
            {partitions: 1.5},
            {replicas: 0},
            {replicas: 1.5},
        ]) {
            const invalidCountConfig = structuredClone(validConfig);
            invalidCountConfig.traffic.kafkaClusters.default.topics.capture.specOverrides = {
                ...invalidCountConfig.traffic.kafkaClusters.default.topics.capture.specOverrides,
                ...invalidSpecOverrides,
            };
            expect(validate(invalidCountConfig)).toBe(false);
        }

        const invalidConfig = {
            ...validConfig,
            traffic: {
                ...validConfig.traffic,
                kafkaClusters: {
                    default: {
                        autoCreate: {
                            ...validConfig.traffic.kafkaClusters.default.autoCreate,
                            nodePoolSpecOverrides: {
                                ...validConfig.traffic.kafkaClusters.default.autoCreate.nodePoolSpecOverrides,
                                roles: ["broker", "invalid-role"],
                            },
                        },
                        topics: {
                            capture: {
                                specOverrides: {
                                ...validConfig.traffic.kafkaClusters.default.topics.capture.specOverrides,
                                config: {
                                    "cleanup.policy": "not-allowed",
                                },
                            },
                        },
                        },
                    },
                },
            },
        };

        expect(validate(invalidConfig)).toBe(false);
        const errorPointers = (validate.errors ?? []).map(error =>
            (error as any).instancePath ?? (error as any).dataPath ?? ""
        );
        expect(errorPointers).toEqual(expect.arrayContaining([
            expect.stringMatching(/nodePoolSpecOverrides.*roles/),
            expect.stringMatching(/topics.*specOverrides.*cleanup\.policy/),
        ]));
    });

    it("does not probe the live cluster when an explicit schema path is configured", () => {
        const execSpy = jest.spyOn(childProcess, "execFileSync");
        const previousSchemaPath = process.env[UNIFIED_SCHEMA_PATH_ENV];
        const previousAllowFallback = process.env[ALLOW_FALLBACK_UNIFIED_SCHEMA_ENV];
        const explicitSchemaPath = path.join(os.tmpdir(), "orchestrationSpecs-unified-schema-test.json");

        try {
            const {schema} = buildUnifiedSchema({strimziSchemaPath: strimziFixturePath});
            fs.writeFileSync(explicitSchemaPath, JSON.stringify(schema, null, 2) + "\n");
            process.env[UNIFIED_SCHEMA_PATH_ENV] = explicitSchemaPath;
            delete process.env[ALLOW_FALLBACK_UNIFIED_SCHEMA_ENV];
            const loaded = loadUnifiedSchema();
            expect(loaded.source).toBe("file");
            expect(execSpy).not.toHaveBeenCalled();
        } finally {
            if (previousSchemaPath === undefined) {
                delete process.env[UNIFIED_SCHEMA_PATH_ENV];
            } else {
                process.env[UNIFIED_SCHEMA_PATH_ENV] = previousSchemaPath;
            }
            if (previousAllowFallback === undefined) {
                delete process.env[ALLOW_FALLBACK_UNIFIED_SCHEMA_ENV];
            } else {
                process.env[ALLOW_FALLBACK_UNIFIED_SCHEMA_ENV] = previousAllowFallback;
            }
            execSpy.mockRestore();
        }
    });
});
