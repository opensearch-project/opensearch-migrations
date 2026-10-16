import { OVERALL_MIGRATION_CONFIG } from "../src";
import * as fs from "fs";
import * as path from "path";

const FIXTURES_DIR = path.join(__dirname, "fixtures/invalid");

const fixtures = fs.readdirSync(FIXTURES_DIR).filter(f => f.endsWith(".json"));

describe("invalid configs fail validation", () => {
    test.each(fixtures)("%s", (file) => {
        const data = JSON.parse(fs.readFileSync(path.join(FIXTURES_DIR, file), "utf-8"));
        const result = OVERALL_MIGRATION_CONFIG.safeParse(data);
        expect(result.success).toBe(false);
    });

    test.each([
        ["implicit workflow-managed Kafka defaults", undefined, 2],
        ["explicit workflow-managed Kafka configuration", {
            default: {
                autoCreate: {
                    topicSpecOverrides: {
                        partitions: 1
                    }
                }
            }
        }, 1],
    ])("rejects proxy replicas plus rollout capacity above topic partitions with %s",
        (_name, kafkaClusterConfiguration, expectedPartitionCount = 1) => {
        const data = {
            sourceClusters: {
                source: {
                    version: "ES 7.10.2",
                    endpoint: "http://source:9200"
                }
            },
            targetClusters: {
                target: {
                    endpoint: "http://target:9200"
                }
            },
            snapshotMigrationConfigs: [],
            kafkaClusterConfiguration,
            traffic: {
                proxies: {
                    proxy1: {
                        source: "source",
                        proxyConfig: {
                            listenPort: 9201,
                            podReplicas: 2
                        }
                    }
                },
                replayers: {}
            }
        };

        const result = OVERALL_MIGRATION_CONFIG.safeParse(data);

        expect(result.success).toBe(false);
        if (!result.success) {
            expect(result.error.issues).toContainEqual(expect.objectContaining({
                message: expect.stringContaining(
                    `has ${expectedPartitionCount} partition(s), but capture proxy 'proxy1' with 2 pod replica(s) requires at least 3 partition(s)`
                ),
                path: [
                    "kafkaClusterConfiguration",
                    "default",
                    "autoCreate",
                    "topicSpecOverrides",
                    "partitions"
                ]
            }));
        }
    });

    test.each([
        ["live capture", "CreateTime", "LogAppendTime", {
            proxies: {
                proxy1: {
                    source: "source",
                    proxyConfig: {
                        listenPort: 9201,
                    },
                },
            },
            replayers: {},
        }],
        ["BYOC import", "LogAppendTime", "CreateTime", {
            s3Sources: {
                dump1: {
                    s3Uri: "s3://traffic-bucket/captures/one.proto.gz",
                    awsRegion: "us-east-1",
                    sourceLabel: "archived-source",
                },
            },
            replayers: {},
        }],
    ])("rejects explicit %s topic timestamp conflicts", (
        _name,
        configuredTimestampType,
        requiredTimestampType,
        traffic
    ) => {
        const result = OVERALL_MIGRATION_CONFIG.safeParse({
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
                        topicSpecOverrides: {
                            config: {
                                "message.timestamp.type": configuredTimestampType,
                            },
                        },
                    },
                },
            },
            traffic,
        });

        expect(result.success).toBe(false);
        if (!result.success) {
            expect(result.error.issues).toContainEqual(expect.objectContaining({
                message: expect.stringContaining(
                    `'message.timestamp.type' to be '${requiredTimestampType}'`
                ),
                path: [
                    "kafkaClusterConfiguration",
                    "default",
                    "autoCreate",
                    "topicSpecOverrides",
                    "config",
                    "message.timestamp.type",
                ],
            }));
        }
    });
});
