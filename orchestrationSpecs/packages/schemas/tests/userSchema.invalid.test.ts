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
                perSnapshotConfig: {},
            }],
        });

        expect(result.success).toBe(false);
        if (!result.success) {
            expect(result.error.issues).toEqual(expect.arrayContaining([
                expect.objectContaining({
                    message: "At least one metadata migration or document backfill configuration is required.",
                    path: ["snapshotMigrationConfigs", 0, "perSnapshotConfig"],
                }),
            ]));
        }
    });
});
