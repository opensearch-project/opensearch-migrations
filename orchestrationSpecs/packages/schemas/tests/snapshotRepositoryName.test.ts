import {
    ELASTICSEARCH_SNAPSHOT_INFO,
    REPO_CONFIG as SNAPSHOT_REPO_CONFIG,
    SNAPSHOT_REPOSITORY_NAME,
    SNAPSHOT_REPOSITORY_NAME_MESSAGE,
} from "../src/userSchemas";

const REPO_CONFIG = {
    repoPathUri: "s3://somebucket",
};

const SNAPSHOT_CONFIG = {
    config: {
        createSnapshotConfig: {},
    },
    repoName: "migration-repository",
};

describe("Elasticsearch/OpenSearch snapshot repository names", () => {
    it.each([
        "migration-repository",
        "snapshots_prod",
        "Repo.1",
        "repo:name+1",
    ])("accepts %s", (repositoryName) => {
        expect(SNAPSHOT_REPOSITORY_NAME.safeParse(repositoryName).success).toBe(true);
    });

    it.each([
        "",
        "r a",
        "repo\\name",
        "repo/name",
        "repo*name",
        "repo?name",
        "repo\"name",
        "repo<name",
        "repo>name",
        "repo|name",
        "repo,name",
        "repo#name",
    ])("rejects %j", (repositoryName) => {
        const result = SNAPSHOT_REPOSITORY_NAME.safeParse(repositoryName);

        expect(result.success).toBe(false);
        if (!result.success) {
            expect(result.error.issues[0]?.message).toBe(SNAPSHOT_REPOSITORY_NAME_MESSAGE);
        }
    });

    it("validates repository map keys", () => {
        const result = ELASTICSEARCH_SNAPSHOT_INFO.safeParse({
            repos: {
                "r a": REPO_CONFIG,
            },
            snapshots: {
                snapshot: SNAPSHOT_CONFIG,
            },
        });

        expect(result.success).toBe(false);
        if (!result.success) {
            expect(result.error.issues).toEqual(expect.arrayContaining([
                expect.objectContaining({
                    code: "invalid_key",
                    path: ["repos", "r a"],
                    issues: expect.arrayContaining([
                        expect.objectContaining({
                            message: SNAPSHOT_REPOSITORY_NAME_MESSAGE,
                        }),
                    ]),
                }),
            ]));
        }
    });

    it("validates snapshot repository references", () => {
        const result = ELASTICSEARCH_SNAPSHOT_INFO.safeParse({
            repos: {
                "migration-repository": REPO_CONFIG,
            },
            snapshots: {
                snapshot: {
                    ...SNAPSHOT_CONFIG,
                    repoName: "r a",
                },
            },
        });

        expect(result.success).toBe(false);
        if (!result.success) {
            expect(result.error.issues).toEqual(expect.arrayContaining([
                expect.objectContaining({
                    message: SNAPSHOT_REPOSITORY_NAME_MESSAGE,
                    path: ["snapshots", "snapshot", "repoName"],
                }),
            ]));
        }
    });
});

describe("snapshot repository regions", () => {
    it("requires an AWS region for S3 repositories", () => {
        const result = SNAPSHOT_REPO_CONFIG.safeParse({
            repoPathUri: "s3://somebucket/snapshots",
        });

        expect(result.success).toBe(false);
        if (!result.success) {
            expect(result.error.issues).toEqual(expect.arrayContaining([
                expect.objectContaining({
                    message: "AWS region is required for s3:// snapshot repositories.",
                    path: ["awsRegion"],
                }),
            ]));
        }
    });

    it("does not require an AWS region for GCS repositories", () => {
        expect(SNAPSHOT_REPO_CONFIG.safeParse({
            repoPathUri: "gs://somebucket/snapshots",
        }).success).toBe(true);
    });
});
