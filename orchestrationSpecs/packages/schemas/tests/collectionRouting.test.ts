import { OVERALL_MIGRATION_CONFIG } from "../src";
import * as fs from "fs";
import * as path from "path";

function loadFixture(dir: "valid" | "invalid", name: string) {
    return JSON.parse(fs.readFileSync(path.join(__dirname, "fixtures", dir, name), "utf-8"));
}

function issueMessages(data: unknown): string[] {
    const result = OVERALL_MIGRATION_CONFIG.safeParse(data);
    expect(result.success).toBe(false);
    return result.success ? [] : result.error.issues.map(issue => issue.message);
}

describe("collection-routed targets", () => {
    test.each(["collection-routed-target.json", "collection-routed-target-regex-only.json"])("%s is valid", (file) => {
        const result = OVERALL_MIGRATION_CONFIG.safeParse(loadFixture("valid", file));
        expect(result.success).toBe(true);
    });

    test.each([
        ["collection-routed-missing-routing.json", "needs at least one staticCollectionRouting or regexCollectionRouting entry"],
        ["collection-routing-empty.json", "needs at least one staticCollectionRouting or regexCollectionRouting entry"],
        ["collection-routing-on-unrouted-target.json", "is not collectionRouted"],
        ["collection-routed-without-aoss-sigv4.json", "requires sigv4 authConfig with service 'aoss'"],
        ["collection-routing-duplicate-static.json", "lists source index 'shared-config' more than once"],
        ["collection-routing-blank-collection.json", "must not be blank"],
    ])("%s is rejected", (file, expected) => {
        const messages = issueMessages(loadFixture("invalid", file));
        expect(messages.some(message => message.includes(expected))).toBe(true);
    });

    test("replayer targeting a routed target is rejected", () => {
        const config = loadFixture("valid", "traffic.json");
        config.targetClusters.target = {
            endpoint: "https://123456789012.aoss.us-east-1.on.aws",
            authConfig: {sigv4: {region: "us-east-1", service: "aoss"}},
            collectionRouted: true,
        };
        const messages = issueMessages(config);
        expect(messages.some(message => message.includes("does not support collection-routed targets"))).toBe(true);
    });
});
