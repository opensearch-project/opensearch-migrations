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

    it("inherits the snapshot repo's region/endpoint when the user explicitly chose the bucket", () => {
        const out = resolveFailedDocumentStreamS3(
            {failedDocumentStreamS3Bucket: "user-bucket"},
            {awsRegion: "eu-west-1", endpoint: "https://repo.example"},
            {defaultS3Region: "us-east-1", defaultS3Endpoint: "https://dep.example"}
        );
        expect(out.failedDocumentStreamS3Bucket).toBe("user-bucket");
        expect(out.failedDocumentStreamS3Region).toBe("eu-west-1");
        expect(out.failedDocumentStreamS3Endpoint).toBe("https://repo.example");
    });

    it("uses the deployment region/endpoint (NOT the snapshot repo's) when the bucket falls back to the deployment default", () => {
        const out = resolveFailedDocumentStreamS3(
            {}, // no user bucket
            {awsRegion: "eu-west-1", endpoint: "https://repo.example"},
            {defaultS3Bucket: "dep-bucket", defaultS3Region: "us-east-1", defaultS3Endpoint: "https://dep.example"}
        );
        // bucket came from deployment default -> region/endpoint must track the deployment, not the repo
        expect(out.failedDocumentStreamS3Bucket).toBe("dep-bucket");
        expect(out.failedDocumentStreamS3Region).toBe("us-east-1");
        expect(out.failedDocumentStreamS3Endpoint).toBe("https://dep.example");
    });

    it("disables the stream (all undefined) when no bucket resolves anywhere", () => {
        const out = resolveFailedDocumentStreamS3({}, undefined, {});
        expect(out).toEqual({
            failedDocumentStreamS3Bucket: undefined,
            failedDocumentStreamS3Region: undefined,
            failedDocumentStreamS3Endpoint: undefined,
        });
    });

    it("treats empty/whitespace strings as absent (e.g. a missing ConfigMap key)", () => {
        const out = resolveFailedDocumentStreamS3(
            {failedDocumentStreamS3Bucket: "   "},
            undefined,
            {defaultS3Bucket: "dep-bucket", defaultS3Region: "us-east-1"}
        );
        expect(out.failedDocumentStreamS3Bucket).toBe("dep-bucket");
        expect(out.failedDocumentStreamS3Region).toBe("us-east-1");
    });

    it("throws when a bucket resolves but no region can be determined", () => {
        expect(() =>
            resolveFailedDocumentStreamS3({failedDocumentStreamS3Bucket: "user-bucket"}, undefined, {})
        ).toThrow(/no region could be determined/);
    });
});
