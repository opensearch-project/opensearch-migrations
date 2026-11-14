import {USER_RFS_OPTIONS} from "../src/userSchemas";

describe("failed document stream choice", () => {
    test.each([
        ["a bucket", {failedDocumentStreamS3Bucket: "bucket"}],
        ["the opt-out", {disableFailedDocumentStream: true}],
        ["a bucket with the opt-out set false", {failedDocumentStreamS3Bucket: "bucket", disableFailedDocumentStream: false}],
    ])("accepts %s", (_name, data) => {
        expect(USER_RFS_OPTIONS.safeParse({maxShardSizeBytes: 1024, ...data}).success).toBe(true);
    });

    test.each([
        ["neither", {}, "failedDocumentStreamS3Bucket", "disableFailedDocumentStream: true"],
        ["a blank bucket", {failedDocumentStreamS3Bucket: "  "}, "failedDocumentStreamS3Bucket", "disableFailedDocumentStream: true"],
        ["both", {failedDocumentStreamS3Bucket: "bucket", disableFailedDocumentStream: true}, "disableFailedDocumentStream", "can't both be set"],
    ])("rejects %s", (_name, data, path, message) => {
        const result = USER_RFS_OPTIONS.safeParse({maxShardSizeBytes: 1024, ...data});
        expect(result.success).toBe(false);
        if (!result.success) {
            expect(result.error.issues).toEqual(expect.arrayContaining([
                expect.objectContaining({path: [path], message: expect.stringContaining(message)})
            ]));
        }
    });
});

describe("maxDocumentSizeBytes", () => {
    const base = {maxShardSizeBytes: 1024, disableFailedDocumentStream: true};

    test("is optional and has no default", () => {
        expect(USER_RFS_OPTIONS.parse(base).maxDocumentSizeBytes).toBeUndefined();
    });

    test.each([[0], [-1], [1.5]])("rejects %s", (value) => {
        expect(USER_RFS_OPTIONS.safeParse({...base, maxDocumentSizeBytes: value}).success).toBe(false);
    });

    test("accepts a positive integer", () => {
        expect(USER_RFS_OPTIONS.parse({...base, maxDocumentSizeBytes: 104857600}).maxDocumentSizeBytes).toBe(104857600);
    });
});
