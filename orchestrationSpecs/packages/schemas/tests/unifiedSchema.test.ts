import path from "node:path";
import Ajv from "ajv";
import {buildUnifiedSchema} from "../src";

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
                            "log.message.format.version": "3.7",
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
        const ajv = new Ajv({allErrors: true, strict: false});
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
});
