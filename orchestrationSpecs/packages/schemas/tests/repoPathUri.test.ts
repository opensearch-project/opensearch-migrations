import { REPO_CONFIG } from "../src";

/**
 * Direct tests for the repoPathUri pattern. The fixture-driven suites in userSchema.valid.test.ts
 * and userSchema.invalid.test.ts cover whole configs, so they only ever exercise one URI per
 * fixture. This pattern is the only place storage-account and container naming rules are enforced
 * anywhere in the stack (AzureUri on the Java side splits the URI without validating either name),
 * so the boundaries are worth pinning down here rather than through full configs.
 */
describe("repoPathUri", () => {
    const accepts = (uri: string) => REPO_CONFIG.safeParse({ repoPathUri: uri }).success;

    describe("azure://", () => {
        it.each([
            ["azure://myaccount/mycontainer", "account and container"],
            ["azure://myaccount/mycontainer/snapshots", "with a path"],
            ["azure://myaccount/mycontainer/nested/snapshot/path", "with a nested path"],
            ["azure://acc/abc", "minimum-length account and container"],
            [`azure://${"a".repeat(24)}/mycontainer`, "maximum-length account"],
            [`azure://myaccount/${"c".repeat(63)}`, "maximum-length container"],
            ["azure://account1/my-container-1", "digits and interior hyphens"],
            [`azure://myaccount/a${"-b".repeat(31)}`, "alternating hyphens up to the length limit"],
        ])("accepts %s (%s)", (uri) => {
            expect(accepts(uri)).toBe(true);
        });

        it.each([
            ["azure://myaccount", "no container"],
            ["azure://myaccount/", "empty container"],
            ["azure://ab/mycontainer", "account shorter than 3 characters"],
            [`azure://${"a".repeat(25)}/mycontainer`, "account longer than 24 characters"],
            ["azure://MyAccount/mycontainer", "uppercase in the account"],
            ["azure://my-account/mycontainer", "hyphen in the account"],
            ["azure://myaccount/ab", "container shorter than 3 characters"],
            [`azure://myaccount/${"c".repeat(64)}`, "container longer than 63 characters"],
            ["azure://myaccount/MyContainer", "uppercase in the container"],
            ["azure://myaccount/-mycontainer", "leading hyphen in the container"],
            ["azure://myaccount/mycontainer-", "trailing hyphen in the container"],
            ["azure://myaccount/my--container", "consecutive hyphens in the container"],
            ["azure://myaccount/my_container", "underscore in the container"],
        ])("rejects %s (%s)", (uri) => {
            expect(accepts(uri)).toBe(false);
        });
    });

    // Guards against a change to the azure:// alternative loosening or breaking its neighbours,
    // since all three share one pattern.
    describe("other schemes", () => {
        it.each([
            "s3://my-bucket",
            "s3://my-bucket/snapshots",
            "gs://my-bucket/snapshots",
            "gs://my.dotted.bucket.name/snapshots",
        ])("accepts %s", (uri) => {
            expect(accepts(uri)).toBe(true);
        });

        it.each([
            "azure:/myaccount/mycontainer",
            "abs://myaccount/mycontainer",
            "s3://MyBucket",
            "/local/path",
            "",
        ])("rejects %s", (uri) => {
            expect(accepts(uri)).toBe(false);
        });
    });
});
