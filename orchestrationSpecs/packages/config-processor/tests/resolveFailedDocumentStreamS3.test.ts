import {resolveFailedDocumentStreamS3} from "../src/migrationConfigTransformer";

describe("resolveFailedDocumentStreamS3", () => {
    it("passes through explicit user bucket/region/endpoint", () => {
        const out = resolveFailedDocumentStreamS3(
            {
                failedDocumentStreamS3Bucket: "user-bucket",
                failedDocumentStreamS3Region: "us-west-2",
                failedDocumentStreamS3Endpoint: "https://s3.example",
            },
            {awsRegion: "eu-west-1", endpoint: "https://repo.example"},
            {defaultS3Bucket: "dep-bucket", defaultS3Region: "us-east-1"}
        );
        expect(out).toEqual({
            failedDocumentStreamS3Bucket: "user-bucket",
            failedDocumentStreamS3Region: "us-west-2",
            failedDocumentStreamS3Endpoint: "https://s3.example",
        });
    });

    it("inherits the snapshot repo's region/endpoint ahead of the deployment defaults", () => {
        const out = resolveFailedDocumentStreamS3(
            {failedDocumentStreamS3Bucket: "user-bucket"},
            {awsRegion: "eu-west-1", endpoint: "https://repo.example"},
            {defaultS3Region: "us-east-1", defaultS3Endpoint: "https://dep.example"}
        );
        expect(out.failedDocumentStreamS3Bucket).toBe("user-bucket");
        expect(out.failedDocumentStreamS3Region).toBe("eu-west-1");
        expect(out.failedDocumentStreamS3Endpoint).toBe("https://repo.example");
    });

    it("falls back to the deployment region/endpoint when neither the user nor the repo supplies one", () => {
        const out = resolveFailedDocumentStreamS3(
            {failedDocumentStreamS3Bucket: "user-bucket"},
            undefined,
            {defaultS3Region: "us-east-1", defaultS3Endpoint: "https://dep.example"}
        );
        expect(out.failedDocumentStreamS3Region).toBe("us-east-1");
        expect(out.failedDocumentStreamS3Endpoint).toBe("https://dep.example");
    });

    it("disables the stream when the user names no bucket, even with a deployment default available", () => {
        // A deployment-provisioned default must not quietly turn the stream on.
        const out = resolveFailedDocumentStreamS3(
            {}, // no user bucket
            {awsRegion: "eu-west-1", endpoint: "https://repo.example"},
            {defaultS3Bucket: "dep-bucket", defaultS3Region: "us-east-1", defaultS3Endpoint: "https://dep.example"}
        );
        expect(out).toEqual({
            failedDocumentStreamS3Bucket: undefined,
            failedDocumentStreamS3Region: undefined,
            failedDocumentStreamS3Endpoint: undefined,
        });
    });

    it("disables the stream (all undefined) when nothing at all is configured", () => {
        const out = resolveFailedDocumentStreamS3({}, undefined, {});
        expect(out).toEqual({
            failedDocumentStreamS3Bucket: undefined,
            failedDocumentStreamS3Region: undefined,
            failedDocumentStreamS3Endpoint: undefined,
        });
    });

    it("clears an orphan region/endpoint when no bucket is set", () => {
        const out = resolveFailedDocumentStreamS3(
            {failedDocumentStreamS3Region: "us-west-2", failedDocumentStreamS3Endpoint: "https://s3.example"},
            undefined,
            {}
        );
        expect(out).toEqual({
            failedDocumentStreamS3Bucket: undefined,
            failedDocumentStreamS3Region: undefined,
            failedDocumentStreamS3Endpoint: undefined,
        });
    });

    it("treats an empty/whitespace bucket as absent, leaving the stream disabled", () => {
        const out = resolveFailedDocumentStreamS3(
            {failedDocumentStreamS3Bucket: "   "},
            undefined,
            {defaultS3Bucket: "dep-bucket", defaultS3Region: "us-east-1"}
        );
        expect(out.failedDocumentStreamS3Bucket).toBeUndefined();
        expect(out.failedDocumentStreamS3Region).toBeUndefined();
    });

    it("throws when a bucket is set but no region can be determined", () => {
        expect(() =>
            resolveFailedDocumentStreamS3({failedDocumentStreamS3Bucket: "user-bucket"}, undefined, {})
        ).toThrow(/no region could be determined/);
    });
});
