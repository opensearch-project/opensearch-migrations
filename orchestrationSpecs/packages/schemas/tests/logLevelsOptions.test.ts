import {USER_METADATA_OPTIONS, USER_PROXY_OPTIONS, USER_REPLAYER_OPTIONS, USER_RFS_OPTIONS} from "../src/userSchemas";

describe("logLevels options", () => {
    const rfsBase = {maxShardSizeBytes: 1024, disableFailedDocumentStream: true};

    test("RFS accepts all three loggers", () => {
        const parsed = USER_RFS_OPTIONS.parse({
            ...rfsBase,
            logLevels: {root: "debug", bulkRequestTrace: "trace", failedRequests: "info"},
        });
        expect(parsed.logLevels).toEqual({root: "debug", bulkRequestTrace: "trace", failedRequests: "info"});
    });

    test("RFS leaves logLevels unset by default so the workflow's defaults apply", () => {
        expect(USER_RFS_OPTIONS.parse(rfsBase).logLevels).toBeUndefined();
    });

    test.each([
        ["RFS", USER_RFS_OPTIONS, rfsBase],
        ["metadata", USER_METADATA_OPTIONS, {}],
        ["replayer", USER_REPLAYER_OPTIONS, {}],
        ["proxy", USER_PROXY_OPTIONS, {listenPort: 9201}],
    ])("%s rejects an unknown level", (_name, schema, base) => {
        expect(schema.safeParse({...base, logLevels: {root: "verbose"}}).success).toBe(false);
    });

    test.each([
        ["metadata", USER_METADATA_OPTIONS, {}],
        ["replayer", USER_REPLAYER_OPTIONS, {}],
        ["proxy", USER_PROXY_OPTIONS, {listenPort: 9201}],
    ])("%s accepts a root level", (_name, schema, base) => {
        expect(schema.safeParse({...base, logLevels: {root: "debug"}}).success).toBe(true);
    });
});
