import {
    DEFAULT_RESOURCES,
    USER_METADATA_WORKFLOW_OPTIONS,
} from "../src";

describe("metadata migration resources", () => {
    it("uses the Java migration console resources when omitted", () => {
        const parsed = USER_METADATA_WORKFLOW_OPTIONS.parse({});

        expect(parsed.resources).toEqual(DEFAULT_RESOURCES.JAVA_MIGRATION_CONSOLE_CLI);
    });

    it("deep-merges partial overrides with the defaults", () => {
        const parsed = USER_METADATA_WORKFLOW_OPTIONS.parse({
            resources: {
                limits: {
                    cpu: "750m",
                },
            },
        });

        expect(parsed.resources).toEqual({
            limits: {
                cpu: "750m",
                memory: "1800Mi",
            },
            requests: {
                cpu: "500m",
                memory: "1800Mi",
            },
        });
    });

    it("preserves optional ephemeral storage overrides", () => {
        const parsed = USER_METADATA_WORKFLOW_OPTIONS.parse({
            resources: {
                limits: {
                    "ephemeral-storage": "2Gi",
                },
                requests: {
                    "ephemeral-storage": "1Gi",
                },
            },
        });

        expect(parsed.resources).toEqual({
            limits: {
                cpu: "500m",
                memory: "1800Mi",
                "ephemeral-storage": "2Gi",
            },
            requests: {
                cpu: "500m",
                memory: "1800Mi",
                "ephemeral-storage": "1Gi",
            },
        });
    });

});
