import {buildValidationElements, validateInputAgainstUnifiedSchema} from "../src/unifiedSchemaValidator";
import {formatInputValidationError, InputValidationError} from "../src/streamSchemaTransformer";
import {ErrorObject} from "ajv";

describe("unifiedSchemaValidator", () => {
    const baseUnifiedValidationInput = {
        sourceClusters: {
            source1: {
                endpoint: "https://elasticsearch-master-headless:9200",
                allowInsecure: true,
                version: "ES 7.10",
                authConfig: {
                    basic: {
                        secretName: "source1-creds"
                    }
                },
                snapshotInfo: {
                    repos: {
                        default: {
                            awsRegion: "us-east-2",
                            endpoint: "http://localhost:4566",
                            s3RepoPathUri: "s3://test-bucket"
                        }
                    },
                    snapshots: {
                        snap1: {
                            config: {
                                createSnapshotConfig: {}
                            },
                            repoName: "default"
                        }
                    }
                }
            }
        },
        targetClusters: {
            target1: {
                endpoint: "https://opensearch-cluster-master-headless:9200",
                allowInsecure: true,
                authConfig: {
                    basic: {
                        secretName: "target1-creds"
                    }
                }
            }
        },
        snapshotMigrationConfigs: [
            {
                fromSource: "source1",
                toTarget: "target1",
                perSnapshotConfig: {
                    snap1: [
                        {
                            label: "migration-0",
                            metadataMigrationConfig: {}
                        }
                    ]
                }
            }
        ],
        traffic: {
            proxies: {
                proxy1: {
                    source: "source1",
                    proxyConfig: {
                        listenPort: 9201
                    }
                }
            },
            replayers: {
                replay1: {
                    fromProxy: "proxy1",
                    toTarget: "target1"
                }
            }
        },
        kafkaClusterConfiguration: {
            default: {
                autoCreate: {}
            }
        }
    };

    it("suppresses union noise when a more specific Kafka broker config error exists", () => {
        const configWithBogusKafkaKey = {
            ...baseUnifiedValidationInput,
            kafkaClusterConfiguration: {
                default: {
                    autoCreate: {
                        clusterSpecOverrides: {
                            kafka: {
                                config: {
                                    "auto.create.topics.enable": false,
                                    "bogus.inner.key": true,
                                }
                            }
                        }
                    }
                }
            }
        };

        expect(() => validateInputAgainstUnifiedSchema(configWithBogusKafkaKey))
            .toThrow(/Kafka broker config 'bogus\.inner\.key' is not part of the pinned Kafka 4\.2\.0 broker config catalog/);

        expect(() => validateInputAgainstUnifiedSchema(configWithBogusKafkaKey))
            .not.toThrow(/must have required property 'existing'|must match a schema in anyOf/);
    });

    it("collapses a bare kafka cluster union failure into one clearer message", () => {
        const fakeAjvErrors: ErrorObject[] = [
            {
                keyword: "required",
                instancePath: "/kafkaClusterConfiguration/default",
                schemaPath: "#/properties/kafkaClusterConfiguration/additionalProperties/anyOf/0/required",
                params: {missingProperty: "autoCreate"},
                message: "must have required property 'autoCreate'",
            } as ErrorObject,
            {
                keyword: "required",
                instancePath: "/kafkaClusterConfiguration/default",
                schemaPath: "#/properties/kafkaClusterConfiguration/additionalProperties/anyOf/1/required",
                params: {missingProperty: "existing"},
                message: "must have required property 'existing'",
            } as ErrorObject,
            {
                keyword: "anyOf",
                instancePath: "/kafkaClusterConfiguration/default",
                schemaPath: "#/properties/kafkaClusterConfiguration/additionalProperties/anyOf",
                params: {},
                message: "must match a schema in anyOf",
            } as ErrorObject,
        ];

        const formatted = formatInputValidationError(
            new InputValidationError(buildValidationElements(fakeAjvErrors)),
            {singleLine: true}
        );

        expect(formatted).toMatch(/Kafka cluster configuration must define exactly one of 'existing' or 'autoCreate'/);
        expect(formatted).not.toMatch(/must have required property 'existing'|must have required property 'autoCreate'|must match a schema in anyOf/);
    });

    it("does not collapse unrelated ambiguous union failures outside kafka cluster config", () => {
        const fakeAjvErrors: ErrorObject[] = [
            {
                keyword: "required",
                instancePath: "/ambiguousUnion",
                schemaPath: "#/properties/ambiguousUnion/anyOf/0/required",
                params: {missingProperty: "xOnly"},
                message: "must have required property 'xOnly'",
            } as ErrorObject,
            {
                keyword: "required",
                instancePath: "/ambiguousUnion",
                schemaPath: "#/properties/ambiguousUnion/anyOf/1/required",
                params: {missingProperty: "yOnly"},
                message: "must have required property 'yOnly'",
            } as ErrorObject,
            {
                keyword: "anyOf",
                instancePath: "/ambiguousUnion",
                schemaPath: "#/properties/ambiguousUnion/anyOf",
                params: {},
                message: "must match a schema in anyOf",
            } as ErrorObject,
        ];

        const formatted = formatInputValidationError(
            new InputValidationError(buildValidationElements(fakeAjvErrors)),
            {singleLine: true}
        );

        expect(formatted).toMatch(/must have required property 'xOnly'/);
        expect(formatted).toMatch(/must have required property 'yOnly'/);
        expect(formatted).toMatch(/must match a schema in anyOf/);
        expect(formatted).not.toMatch(/Kafka cluster configuration must define exactly one of 'existing' or 'autoCreate'/);
    });
});
