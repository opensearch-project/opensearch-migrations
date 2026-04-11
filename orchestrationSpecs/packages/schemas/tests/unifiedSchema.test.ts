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
    kafkaClusterConfiguration: {
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
                topicSpecOverrides: {
                    partitions: 12,
                    replicas: 2,
                    config: {
                        "cleanup.policy": "compact",
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

        expect(JSON.stringify({
            mode: schema["x-orchestration-specs-strimzi-schema-mode"],
            autoCreate: (schema as any).properties.kafkaClusterConfiguration.additionalProperties.anyOf[0]
                .properties.autoCreate.properties,
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

        const invalidConfig = {
            ...validConfig,
            kafkaClusterConfiguration: {
                default: {
                    autoCreate: {
                        ...validConfig.kafkaClusterConfiguration.default.autoCreate,
                        nodePoolSpecOverrides: {
                            ...validConfig.kafkaClusterConfiguration.default.autoCreate.nodePoolSpecOverrides,
                            roles: ["broker", "invalid-role"],
                        },
                        topicSpecOverrides: {
                            ...validConfig.kafkaClusterConfiguration.default.autoCreate.topicSpecOverrides,
                            config: {
                                "cleanup.policy": "not-allowed",
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
            expect.stringMatching(/topicSpecOverrides.*cleanup\.policy/),
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
