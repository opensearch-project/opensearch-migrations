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

    it("allows live capture traffic without a replayer", () => {
        const result = OVERALL_MIGRATION_CONFIG.safeParse({
            sourceClusters: {
                source: {
                    endpoint: "https://source.example.com:9200",
                    version: "ES 7.10.2",
                },
            },
            targetClusters: {},
            snapshotMigrationConfigs: [],
            traffic: {
                proxies: {
                    capture: {
                        source: "source",
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
