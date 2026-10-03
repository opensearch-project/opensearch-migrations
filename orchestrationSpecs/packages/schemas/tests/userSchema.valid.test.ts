import { OVERALL_MIGRATION_CONFIG } from "../src";
import * as fs from "fs";
import * as path from "path";

const FIXTURES_DIR = path.join(__dirname, "fixtures/valid");

const fixtures = fs.readdirSync(FIXTURES_DIR).filter(f => f.endsWith(".json"));

describe("valid configs parse successfully", () => {
    test.each(fixtures)("%s", (file) => {
        const data = JSON.parse(fs.readFileSync(path.join(FIXTURES_DIR, file), "utf-8"));
        const result = OVERALL_MIGRATION_CONFIG.safeParse(data);
        if (!result.success) {
            throw new Error(result.error.issues.map(i => `${i.path.join(".")}: ${i.message}`).join("\n"));
        }
        expect(result.success).toBe(true);
    });

    test("no-capture proxies do not require Kafka rollout capacity", () => {
        const result = OVERALL_MIGRATION_CONFIG.safeParse({
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
            kafkaClusterConfiguration: {
                default: {
                    autoCreate: {
                        topicSpecOverrides: {
                            partitions: 1
                        }
                    }
                }
            },
            traffic: {
                proxies: {
                    proxy1: {
                        source: "source",
                        proxyConfig: {
                            listenPort: 9201,
                            podReplicas: 2,
                            noCapture: true
                        }
                    }
                },
                replayers: {}
            }
        });

        expect(result.success).toBe(true);
        if (!result.success) {
            expect(result.error.issues).not.toContainEqual(expect.objectContaining({
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
        ["live capture", "LogAppendTime", {
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
        ["BYOC import", "CreateTime", {
            s3Sources: {
                dump1: {
                    s3Uri: "s3://traffic-bucket/captures/one.proto.gz",
                    awsRegion: "us-east-1",
                    sourceLabel: "archived-source",
                },
            },
            replayers: {},
        }],
    ])("workflow-managed %s topics accept explicit %s", (_name, timestampType, traffic) => {
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
                                "message.timestamp.type": timestampType,
                            },
                        },
                    },
                },
            },
            traffic,
        });

        expect(result.success).toBe(true);
    });
});
