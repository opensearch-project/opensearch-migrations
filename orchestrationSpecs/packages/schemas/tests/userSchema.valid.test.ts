import { KAFKA_TOPIC_CONFIG, OVERALL_MIGRATION_CONFIG } from "../src";
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

    it("allows live capture traffic without a replayer", () => {
        const result = OVERALL_MIGRATION_CONFIG.safeParse({
            sourceClusters: {
                source: {
                    endpoint: "https://source.example.com:9200",
                    version: "ES 7.10.2",
                },
            },
            targetClusters: {},
            traffic: {
                kafkaClusters: {
                    default: {
                        autoCreate: {},
                        topics: {capture: {}},
                    },
                },
                proxies: {
                    capture: {
                        source: "source",
                        kafka: "default",
                        kafkaTopic: "capture",
                        proxyConfig: {
                            listenPort: 9201,
                        },
                    },
                },
            },
        });

        if (!result.success) {
            throw new Error(result.error.issues.map(i => `${i.path.join(".")}: ${i.message}`).join("\n"));
        }
        expect(result.data.traffic?.replayers).toEqual({});
        expect(result.data.snapshotMigrationConfigs).toEqual([]);
    });

    it("rejects multiple proxies producing to one topic but allows multiple replayers", () => {
        const config: any = {
            sourceClusters: {
                first: {
                    endpoint: "https://first.example.com:9200",
                    version: "ES 7.10.2",
                },
                second: {
                    endpoint: "https://second.example.com:9200",
                    version: "ES 7.10.2",
                },
            },
            targetClusters: {
                target: {
                    endpoint: "https://target.example.com:9200",
                },
            },
            traffic: {
                kafkaClusters: {
                    shared: {
                        autoCreate: {},
                        topics: {
                            capture: {},
                        },
                    },
                },
                proxies: {
                    first: {
                        source: "first",
                        kafka: "shared",
                        kafkaTopic: "capture",
                        proxyConfig: {listenPort: 9201},
                    },
                    second: {
                        source: "second",
                        kafka: "shared",
                        kafkaTopic: "capture",
                        proxyConfig: {listenPort: 9202},
                    },
                },
                replayers: {
                    "first-reader": {
                        fromCapturedTraffic: "first",
                        toTarget: "target",
                    },
                    "second-reader": {
                        fromCapturedTraffic: "first",
                        toTarget: "target",
                    },
                },
            },
        };

        const duplicateProducer = OVERALL_MIGRATION_CONFIG.safeParse(config);
        expect(duplicateProducer.success).toBe(false);
        if (!duplicateProducer.success) {
            expect(duplicateProducer.error.issues).toContainEqual(
                expect.objectContaining({
                    path: ["traffic", "proxies", "second", "kafkaTopic"],
                    message: expect.stringContaining(
                        "Each (kafka cluster, topic) tuple must have at most one producer.",
                    ),
                }),
            );
        }

        config.traffic.kafkaClusters.shared.topics.second = {};
        config.traffic.proxies.second.kafkaTopic = "second";
        expect(OVERALL_MIGRATION_CONFIG.safeParse(config).success).toBe(true);
    });

    it("requires positive integer partition and replica topic overrides", () => {
        for (const [field, value] of [
            ["partitions", 0],
            ["partitions", 1.5],
            ["replicas", 0],
            ["replicas", -1],
        ] as const) {
            const result = KAFKA_TOPIC_CONFIG.safeParse({
                specOverrides: {
                    [field]: value,
                },
            });
            expect(result.success).toBe(false);
            if (!result.success) {
                expect(result.error.issues).toContainEqual(
                    expect.objectContaining({
                        path: ["specOverrides", field],
                        message: `${field} must be an integer greater than or equal to 1.`,
                    }),
                );
            }
        }

        expect(KAFKA_TOPIC_CONFIG.safeParse({
            specOverrides: {
                partitions: 1,
                replicas: 1,
            },
        }).success).toBe(true);
    });

    it("rejects replay names that cannot become Kubernetes resource names", () => {
        const result = OVERALL_MIGRATION_CONFIG.safeParse({
            sourceClusters: {
                source: {
                    endpoint: "https://source.example.com:9200",
                    version: "ES 7.10.2",
                },
            },
            targetClusters: {
                target: {
                    endpoint: "https://target.example.com:9200",
                },
            },
            traffic: {
                replayers: {
                    sourceTarget: {
                        fromCapturedTraffic: "capture",
                        toTarget: "target",
                    },
                },
            },
        });

        expect(result.success).toBe(false);
        if (!result.success) {
            expect(result.error.issues.map(issue => issue.path.join("."))).toContain("traffic.replayers.sourceTarget");
        }
    });
});
