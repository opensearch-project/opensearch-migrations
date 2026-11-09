import { ARGO_METADATA_WORKFLOW_OPTION_KEYS, ARGO_RFS_WORKFLOW_OPTION_KEYS, OVERALL_MIGRATION_CONFIG } from "../src";
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

    // The tools treat a target as routed only when they receive the table, so it can't be held back as a workflow option
    test.each([
        ["metadata", ARGO_METADATA_WORKFLOW_OPTION_KEYS],
        ["document backfill", ARGO_RFS_WORKFLOW_OPTION_KEYS],
    ])("%s passes collectionRouting to its tool", (_, workflowOptionKeys) => {
        expect(workflowOptionKeys as readonly string[]).not.toContain("collectionRouting");
    });

    function configWithRegexRoute(sourceIndex: string, collection: string) {
        const config = loadFixture("valid", "collection-routed-target-regex-only.json");
        config.snapshotMigrationConfigs[0].perSnapshotConfig.snap1[0].regexCollectionRouting = [{sourceIndex, collection}];
        return config;
    }

    test.each([
        ["(.+)-\\d{4}", "$1"],
        ["(.+)-\\d{4}", "$0"],
        ["logs-(?<app>[a-z]+)-.*", "col-${app}"],
        ["(a)", "x\\$1"],
        ["logs-.*", "common"],
    ])("regex route %s to %s is valid", (sourceIndex, collection) => {
        const result = OVERALL_MIGRATION_CONFIG.safeParse(configWithRegexRoute(sourceIndex, collection));
        expect(result.success ? [] : result.error.issues.map(issue => issue.message)).toEqual([]);
    });

    // The same patterns that Java rejects, plus Java-only syntax that this check is too conservative for
    test.each([
        "[",
        "(unclosed",
        "*a",
        "a{2,1}",
        "abc\\",
        "(?<n>a)(?<n>b)",
        "(?<1bad>a)",
        "[z-a]",
        "(?i)logs-.*",
        "(?x)a b",
        "a++",
        "(?>abc)",
    ])("regex route sourceIndex %s is rejected", (sourceIndex) => {
        const messages = issueMessages(configWithRegexRoute(sourceIndex, "common"));
        expect(messages.some(message => message.includes("is not a valid regular expression"))).toBe(true);
    });

    // JavaScript accepts these, so only the Java check at migration start rejects them
    test.each(["logs-{", "a{,2}", "\\y", "[]"])("regex route sourceIndex %s is left for Java to reject", (sourceIndex) => {
        expect(OVERALL_MIGRATION_CONFIG.safeParse(configWithRegexRoute(sourceIndex, "common")).success).toBe(true);
    });

    // The same cases as ServerlessCollectionRoutingTest.invalidReplacements_areRejectedWhenParsed
    test.each([
        ["$2", "references undefined group 2"],
        ["${missing}", "references undefined group 'missing'"],
        ["${app", "has an unclosed '${'"],
        ["a$", "has '$' that is not a group reference"],
        ["$x", "has '$' that is not a group reference"],
        ["a\\", "ends with an unescaped backslash"],
    ])("regex route collection %s is rejected", (collection, expected) => {
        const messages = issueMessages(configWithRegexRoute("(?<app>[a-z]+)-\\d+", collection));
        expect(messages.some(message => message.includes(expected))).toBe(true);
    });

    function trafficConfigWithRoutedTarget(replayerConfig?: Record<string, unknown>) {
        const config = loadFixture("valid", "traffic.json");
        config.targetClusters.target = {
            endpoint: "https://123456789012.aoss.us-east-1.on.aws",
            authConfig: {sigv4: {region: "us-east-1", service: "aoss"}},
            collectionRouted: true,
        };
        config.traffic.replayers.replayer1.replayerConfig = replayerConfig;
        return config;
    }

    test.each([
        ["no transform", undefined],
        ["only a tuple transform", {tupleTransformerConfig: "[]"}],
        ["an empty requestTransforms list", {requestTransforms: []}],
    ])("replayer targeting a routed target with %s is rejected", (_, replayerConfig) => {
        const messages = issueMessages(trafficConfigWithRoutedTarget(replayerConfig));
        expect(messages.some(message => message.includes("needs a request transform"))).toBe(true);
    });

    test.each([
        ["transformerConfig", {transformerConfig: "[]"}],
        ["transformerConfigEncoded", {transformerConfigEncoded: "W10="}],
        ["transformerConfigFile", {transformerConfigFile: "/config/transform.json"}],
        ["a requestTransforms list", {requestTransforms: [{entryPoint: {javascript: "(m) => m"}}]}],
        ["a single requestTransforms entry", {requestTransforms: {entryPoint: {javascript: "(m) => m"}}}],
    ])("replayer targeting a routed target with %s is valid", (_, replayerConfig) => {
        const result = OVERALL_MIGRATION_CONFIG.safeParse(trafficConfigWithRoutedTarget(replayerConfig));
        expect(result.success ? [] : result.error.issues.map(issue => issue.message)).toEqual([]);
    });
});
