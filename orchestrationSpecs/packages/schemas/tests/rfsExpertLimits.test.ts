import {isoDurationSeconds, USER_RFS_OPTIONS} from "../src/userSchemas";

const base = {maxShardSizeBytes: 1024, disableFailedDocumentStream: true};

describe("RFS expert limits", () => {
    test("defaults to a one-day lease cap", () => {
        const parsed = USER_RFS_OPTIONS.parse(base);
        expect(parsed.maxLeaseDuration).toBe("P1D");
    });

    test.each([
        ["PT1H", "PT1H"],
        ["PT10M", "P1D"],
        ["PT1H", "P2DT1H"],
    ])("accept initialLeaseDuration %s with maxLeaseDuration %s", (initialLeaseDuration, maxLeaseDuration) => {
        expect(USER_RFS_OPTIONS.safeParse({...base, initialLeaseDuration, maxLeaseDuration}).success).toBe(true);
    });

    test("rejects a max lease shorter than the initial lease", () => {
        const result = USER_RFS_OPTIONS.safeParse({...base, initialLeaseDuration: "PT2H", maxLeaseDuration: "PT1H"});
        expect(result.success).toBe(false);
        if (!result.success) {
            expect(result.error.issues).toEqual(expect.arrayContaining([
                expect.objectContaining({path: ["maxLeaseDuration"], message: expect.stringContaining("at least")})
            ]));
        }
    });

    test("compares against the default initial lease when only the max is set", () => {
        expect(USER_RFS_OPTIONS.safeParse({...base, maxLeaseDuration: "PT30M"}).success).toBe(false);
    });

    test("bulkResponseTimeout defaults to just over the 300 s front-end idle timeout", () => {
        expect(USER_RFS_OPTIONS.parse(base).bulkResponseTimeout).toBe("PT310S");
        expect(USER_RFS_OPTIONS.parse({...base, bulkResponseTimeout: "PT2M"}).bulkResponseTimeout).toBe("PT2M");
    });

    test.each([["PT0S"], ["-PT1M"], ["310"]])("rejects bulkResponseTimeout %s", (bulkResponseTimeout) => {
        expect(USER_RFS_OPTIONS.safeParse({...base, bulkResponseTimeout}).success).toBe(false);
    });

});

describe("isoDurationSeconds", () => {
    test.each([
        ["PT1H", 3600],
        ["P1D", 86400],
        ["P1DT2H3M4.5S", 93784.5],
        ["-PT1M", -60],
    ])("%s is %d seconds", (duration, seconds) => {
        expect(isoDurationSeconds(duration)).toBe(seconds);
    });

    test("is undefined for a non-duration", () => {
        expect(isoDurationSeconds("1 hour")).toBeUndefined();
    });
});
