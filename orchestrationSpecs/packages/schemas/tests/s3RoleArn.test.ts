import {REPO_CONFIG} from "../src/userSchemas";

const PARTITIONS = ["aws", "aws-cn", "aws-us-gov", "aws-iso", "aws-iso-b", "aws-iso-e", "aws-iso-f", "aws-eusc"];

function parsesRoleArn(s3RoleArn: string) {
    return REPO_CONFIG.safeParse({repoPathUri: "s3://example-bucket/path", s3RoleArn}).success;
}

describe("s3RoleArn validation", () => {
    test("accepts an empty value", () => {
        expect(parsesRoleArn("")).toBe(true);
    });

    describe.each(PARTITIONS)("partition %s", (partition) => {
        test.each([
            ["no path", "role/SnapshotRole"],
            ["one-segment path", "role/service-role/SnapshotRole"],
            ["multi-segment path", "role/a/b-c/d_e/SnapshotRole"],
            ["name with every allowed punctuation character", "role/path/Name+=,.@_-1"],
        ])("accepts a role ARN with %s", (_label, resource) => {
            expect(parsesRoleArn(`arn:${partition}:iam::123456789012:${resource}`)).toBe(true);
        });
    });

    test.each([
        ["a user ARN", "arn:aws:iam::123456789012:user/SnapshotUser"],
        ["a group ARN", "arn:aws:iam::123456789012:group/Admins"],
        ["a policy ARN", "arn:aws:iam::123456789012:policy/SnapshotPolicy"],
        ["a missing role name", "arn:aws:iam::123456789012:role/"],
        ["a path with no role name", "arn:aws:iam::123456789012:role/service-role/"],
        ["an empty path segment", "arn:aws:iam::123456789012:role//SnapshotRole"],
        ["an 11-digit account", "arn:aws:iam::12345678901:role/SnapshotRole"],
        ["a region in an IAM ARN", "arn:aws:iam:us-east-1:123456789012:role/SnapshotRole"],
        ["a non-IAM service", "arn:aws:s3::123456789012:role/SnapshotRole"],
        ["an unknown partition prefix", "arn:awscn:iam::123456789012:role/SnapshotRole"],
        ["a trailing-dash partition", "arn:aws-:iam::123456789012:role/SnapshotRole"],
        ["an uppercase partition", "arn:AWS:iam::123456789012:role/SnapshotRole"],
        ["a space in the role name", "arn:aws:iam::123456789012:role/Snapshot Role"],
    ])("rejects %s", (_label, arn) => {
        expect(parsesRoleArn(arn)).toBe(false);
    });
});
