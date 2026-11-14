import {USER_RFS_OPTIONS} from "../src/userSchemas";

describe("bulkRequestTraceMaxBytes", () => {
    const base = {maxShardSizeBytes: 1024, disableFailedDocumentStream: true};

    test("defaults to one MiB", () => {
        expect(USER_RFS_OPTIONS.parse(base).bulkRequestTraceMaxBytes).toBe(1024 * 1024);
    });

    test.each([[-1], [1.5]])("rejects %s", (value) => {
        expect(USER_RFS_OPTIONS.safeParse({...base, bulkRequestTraceMaxBytes: value}).success).toBe(false);
    });

    test.each([[0], [500]])("accepts %s", (value) => {
        expect(USER_RFS_OPTIONS.parse({...base, bulkRequestTraceMaxBytes: value}).bulkRequestTraceMaxBytes)
            .toBe(value);
    });
});
