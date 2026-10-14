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

    it("requires snapshot migrations to configure metadata or document backfill work", () => {
        const result = OVERALL_MIGRATION_CONFIG.safeParse({
            sourceClusters: {
                foo: {
                    endpoint: "https://foo.example.com:9200",
                    version: "ES 7.10.2",
                    snapshotInfo: {
                        snapshots: {
                            snap: {
                                repoName: "",
                                config: {externallyManagedSnapshotName: "snap"},
                            },
                        },
                    },
                },
            },
            targetClusters: {
                target: {
                    endpoint: "https://target.example.com:9200",
                },
            },
            snapshotMigrationConfigs: [{
                fromSource: "foo",
                toTarget: "target",
                fromSnapshot: "snap",
                slices: {"slice-0": {}},
            }],
        });

        expect(result.success).toBe(false);
        if (!result.success) {
            expect(result.error.issues).toEqual(expect.arrayContaining([
                expect.objectContaining({
                    message: "At least one of metadataMigrationConfig or documentBackfillConfig must be provided",
                    path: ["snapshotMigrationConfigs", 0],
                }),
            ]));
        }
    });

    test.each([
        ["topic defaults", {}, 2],
        ["explicit topic configuration", {partitions: 1}, 1],
    ])("rejects proxy replicas plus rollout capacity above %s", (
        _name,
        specOverrides,
        expectedPartitionCount,
    ) => {
        const result = OVERALL_MIGRATION_CONFIG.safeParse({
            sourceClusters: {
                source: {
                    version: "ES 7.10.2",
                    endpoint: "http://source:9200",
                },
            },
            targetClusters: {},
            snapshotMigrationConfigs: [],
            traffic: {
                kafkaClusters: {
                    default: {
                        autoCreate: {},
                        topics: {
                            capture: {specOverrides},
                        },
                    },
                },
                proxies: {
                    proxy1: {
                        source: "source",
                        kafka: "default",
                        kafkaTopic: "capture",
                        proxyConfig: {
                            listenPort: 9201,
                            podReplicas: 2,
                        },
                    },
                },
                replayers: {},
            },
        });

        expect(result.success).toBe(false);
        if (!result.success) {
            expect(result.error.issues).toContainEqual(expect.objectContaining({
                message: expect.stringContaining(
                    `has ${expectedPartitionCount} partition(s), but capture proxy 'proxy1' with 2 pod replica(s) requires at least 3 partition(s)`,
                ),
                path: [
                    "traffic",
                    "kafkaClusters",
                    "default",
                    "topics",
                    "capture",
                    "specOverrides",
                    "partitions",
                ],
            }));
        }
    });

    test.each([
        ["live capture", "CreateTime", "LogAppendTime", {
            proxies: {
                proxy1: {
                    source: "source",
                    kafka: "default",
                    kafkaTopic: "capture",
                    proxyConfig: {listenPort: 9201},
                },
            },
            s3Sources: {},
        }, "capture"],
        ["BYOC import", "LogAppendTime", "CreateTime", {
            proxies: {},
            s3Sources: {
                dump1: {
                    s3Uri: "s3://traffic-bucket/captures/one.proto.gz",
                    awsRegion: "us-east-1",
                    sourceLabel: "archived-source",
                    kafka: "default",
                    kafkaTopic: "dump",
                },
            },
        }, "dump"],
    ])("rejects explicit %s topic timestamp conflicts", (
        _name,
        configuredTimestampType,
        requiredTimestampType,
        trafficEntries,
        topicName,
    ) => {
        const result = OVERALL_MIGRATION_CONFIG.safeParse({
            sourceClusters: {
                source: {
                    version: "ES 7.10.2",
                    endpoint: "http://source:9200",
                },
            },
            targetClusters: {},
            snapshotMigrationConfigs: [],
            traffic: {
                kafkaClusters: {
                    default: {
                        autoCreate: {},
                        topics: {
                            [topicName]: {
                                specOverrides: {
                                    config: {
                                        "message.timestamp.type": configuredTimestampType,
                                    },
                                },
                            },
                        },
                    },
                },
                ...trafficEntries,
                replayers: {},
            },
        });

        expect(result.success).toBe(false);
        if (!result.success) {
            expect(result.error.issues).toContainEqual(expect.objectContaining({
                message: expect.stringContaining(
                    `'message.timestamp.type' to be '${requiredTimestampType}'`,
                ),
                path: [
                    "traffic",
                    "kafkaClusters",
                    "default",
                    "topics",
                    topicName,
                    "specOverrides",
                    "config",
                    "message.timestamp.type",
                ],
            }));
        }
    });
});
