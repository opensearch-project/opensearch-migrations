import {describe, expect, it, jest} from "@jest/globals";
import {parse} from "yaml";
import {
    DEFAULT_SUBMISSION_PREFLIGHT_CONCURRENCY,
    preflightSubmissionResources,
    SubmissionAdmissionClient,
    SubmissionCommandResult,
    SubmissionPreflightResource,
} from "../src/submissionPreflight";

const impossibleResource: SubmissionPreflightResource = {
    manifest: {
        apiVersion: "migrations.opensearch.org/v1alpha1",
        kind: "CapturedTraffic",
        metadata: {name: "p2-topic"},
        spec: {sourceLabel: "new-source"},
    },
    policyResource: {
        apiVersion: "migrations.opensearch.org/v1alpha1",
        kind: "CapturedTraffic",
        name: "p2-topic",
        parameters: {sourceLabel: "new-source"},
    },
};

function result(
    status: number,
    stdout = "",
    stderr = "",
): SubmissionCommandResult {
    return {status, stdout, stderr};
}

describe("submission preflight", () => {
    it("uses live metadata for an existing-resource dry run", async () => {
        const generated = {
            ...impossibleResource,
            manifest: {
                ...impossibleResource.manifest,
                metadata: {
                    name: "p2-topic",
                    resourceVersion: "0",
                    uid: "generated-placeholder",
                },
            },
        } as SubmissionPreflightResource;
        const run = jest.fn(
            (args: string[], input?: string): SubmissionCommandResult => {
                if (args[0] === "get") {
                    return result(0, JSON.stringify({
                        ...generated.manifest,
                        metadata: {
                            name: "p2-topic",
                            namespace: "ma",
                            resourceVersion: "12",
                            uid: "live-uid",
                        },
                        status: {phase: "Ready"},
                    }));
                }
                const candidate = parse(input ?? "");
                expect(args[0]).toBe("replace");
                expect(candidate.metadata.resourceVersion).toBe("12");
                expect(candidate.metadata.uid).toBe("live-uid");
                return result(0);
            },
        );

        const report = await preflightSubmissionResources(
            [generated],
            {namespace: "ma", run},
        );

        expect(report.allowed).toBe(true);
        expect(report.issues).toEqual([]);
    });

    it("uses create dry run without generated server metadata when absent", async () => {
        const generated = {
            ...impossibleResource,
            manifest: {
                ...impossibleResource.manifest,
                metadata: {
                    name: "p2-topic",
                    resourceVersion: "0",
                    uid: "generated-placeholder",
                },
            },
        } as SubmissionPreflightResource;
        const run = jest.fn(
            (args: string[], input?: string): SubmissionCommandResult => {
                if (args[0] === "get") {
                    return result(1, "", "NotFound");
                }
                const candidate = parse(input ?? "");
                expect(args[0]).toBe("create");
                expect(candidate.metadata.resourceVersion).toBeUndefined();
                expect(candidate.metadata.uid).toBeUndefined();
                return result(0);
            },
        );

        const report = await preflightSubmissionResources(
            [generated],
            {namespace: "ma", run},
        );

        expect(report.allowed).toBe(true);
        expect(report.issues).toEqual([]);
    });

    it("blocks a proven impossible update and returns a structured reset target", async () => {
        const run = jest.fn<(args: string[], input?: string) => SubmissionCommandResult>()
            .mockReturnValueOnce(result(0, JSON.stringify({
                apiVersion: "migrations.opensearch.org/v1alpha1",
                kind: "CapturedTraffic",
                metadata: {name: "p2-topic", resourceVersion: "12"},
                spec: {sourceLabel: "old-source"},
                status: {phase: "Ready"},
            })))
            .mockReturnValueOnce(result(
                1,
                "",
                "Impossible: sourceLabel cannot be changed. Delete and recreate.",
            ));

        const report = await preflightSubmissionResources(
            [impossibleResource],
            {namespace: "ma", run},
        );

        expect(report.allowed).toBe(false);
        expect(report.issues).toContainEqual(expect.objectContaining({
            classification: "recreate-required",
            kind: "CapturedTraffic",
            name: "p2-topic",
            resourceId: "resource:capturedtraffics:p2-topic",
            resetTargetId: "reset:capturedtraffics:p2-topic",
        }));
    });

    it("reports approval-gated updates without blocking submission", async () => {
        const resource: SubmissionPreflightResource = {
            manifest: {
                apiVersion: "migrations.opensearch.org/v1alpha1",
                kind: "TrafficReplay",
                metadata: {name: "replay"},
                spec: {tupleMaxFileSizeMb: 256},
            },
            policyResource: {
                apiVersion: "migrations.opensearch.org/v1alpha1",
                kind: "TrafficReplay",
                name: "replay",
                parameters: {tupleMaxFileSizeMb: 256},
            },
        };
        const run = jest.fn<(args: string[], input?: string) => SubmissionCommandResult>()
            .mockReturnValueOnce(result(0, JSON.stringify({
                ...resource.manifest,
                metadata: {name: "replay", resourceVersion: "4"},
                spec: {tupleMaxFileSizeMb: 128},
                status: {phase: "Ready"},
            })))
            .mockReturnValueOnce(result(
                1,
                "",
                "Gated changes detected. Create an ApprovalGate to approve this update.",
            ));

        const report = await preflightSubmissionResources(
            [resource],
            {namespace: "ma", run},
        );

        expect(report.allowed).toBe(true);
        expect(report.issues).toContainEqual(expect.objectContaining({
            classification: "approval-required",
            blocking: false,
        }));
    });

    it("treats admission unavailability as a non-blocking warning", async () => {
        const run = jest.fn<(args: string[], input?: string) => SubmissionCommandResult>()
            .mockReturnValue(result(1, "", "Unable to connect to the server"));

        const report = await preflightSubmissionResources(
            [impossibleResource],
            {namespace: "ma", run},
        );

        expect(report.allowed).toBe(true);
        expect(report.issues).toContainEqual(expect.objectContaining({
            classification: "warning",
            blocking: false,
        }));
    });

    it("does not block state-dependent admission-policy failures", async () => {
        const stateDependentResource: SubmissionPreflightResource = {
            ...impossibleResource,
            manifest: {
                ...impossibleResource.manifest,
                spec: {sourceLabel: "old-source"},
            },
            policyResource: {
                ...impossibleResource.policyResource!,
                parameters: {sourceLabel: "old-source"},
            },
        };
        const run = jest.fn<(args: string[], input?: string) => SubmissionCommandResult>()
            .mockReturnValueOnce(result(0, JSON.stringify({
                ...stateDependentResource.manifest,
                metadata: {name: "p2-topic", resourceVersion: "12"},
                spec: {sourceLabel: "old-source"},
                status: {phase: "Ready"},
            })))
            .mockReturnValueOnce(result(
                1,
                "",
                "ValidatingAdmissionPolicy denied request: spec.phase must be Ready",
            ));

        const report = await preflightSubmissionResources(
            [stateDependentResource],
            {namespace: "ma", run},
        );

        expect(report.allowed).toBe(true);
        expect(report.issues[0]).toEqual(expect.objectContaining({
            classification: "warning",
        }));
    });

    it("classifies structured Kubernetes API errors", async () => {
        const client: SubmissionAdmissionClient = {
            read: async () => ({
                ...impossibleResource.manifest,
                metadata: {
                    name: "p2-topic",
                    resourceVersion: "12",
                },
                spec: {sourceLabel: "old-source"},
                status: {phase: "Ready"},
            }),
            dryRun: async () => {
                throw {
                    code: 422,
                    body: {
                        message: (
                            "Impossible: sourceLabel cannot be changed. "
                            + "Delete and recreate."
                        ),
                    },
                };
            },
        };

        const report = await preflightSubmissionResources(
            [impossibleResource],
            {namespace: "ma", client},
        );

        expect(report.allowed).toBe(false);
        expect(report.issues).toContainEqual(expect.objectContaining({
            classification: "recreate-required",
            resetTargetId: "reset:capturedtraffics:p2-topic",
        }));
    });

    it("bounds resource concurrency and preserves issue order", async () => {
        const resources = Array.from(
            {length: 6},
            (_, index): SubmissionPreflightResource => ({
                manifest: {
                    apiVersion: "migrations.opensearch.org/v1alpha1",
                    kind: "CapturedTraffic",
                    metadata: {name: `traffic-${index}`},
                    spec: {sourceLabel: `source-${index}`},
                },
            }),
        );
        let active = 0;
        let maximumActive = 0;
        const client: SubmissionAdmissionClient = {
            read: async () => {
                active += 1;
                maximumActive = Math.max(maximumActive, active);
                return undefined;
            },
            dryRun: async candidate => {
                const index = Number(candidate.metadata.name.split("-")[1]);
                await new Promise(resolve => setTimeout(
                    resolve,
                    (resources.length - index) * 2,
                ));
                active -= 1;
                throw new Error(
                    `spec.sourceLabel: Required value for ${candidate.metadata.name}`,
                );
            },
        };

        const report = await preflightSubmissionResources(
            resources,
            {client, concurrency: 2},
        );

        expect(maximumActive).toBe(2);
        expect(report.issues.map(item => item.name)).toEqual(
            resources.map(item => item.manifest.metadata.name),
        );
    });

    it("uses the default resource concurrency bound", async () => {
        const resources = Array.from(
            {length: DEFAULT_SUBMISSION_PREFLIGHT_CONCURRENCY + 2},
            (_, index): SubmissionPreflightResource => ({
                manifest: {
                    apiVersion: "migrations.opensearch.org/v1alpha1",
                    kind: "CapturedTraffic",
                    metadata: {name: `traffic-${index}`},
                    spec: {sourceLabel: `source-${index}`},
                },
            }),
        );
        let active = 0;
        let maximumActive = 0;
        let releaseReads: (() => void) | undefined;
        const readsBlocked = new Promise<void>(resolve => {
            releaseReads = resolve;
        });
        const client: SubmissionAdmissionClient = {
            read: async () => {
                active += 1;
                maximumActive = Math.max(maximumActive, active);
                if (active === DEFAULT_SUBMISSION_PREFLIGHT_CONCURRENCY) {
                    releaseReads?.();
                }
                await readsBlocked;
                return undefined;
            },
            dryRun: async () => {
                active -= 1;
            },
        };

        const report = await preflightSubmissionResources(resources, {client});

        expect(maximumActive).toBe(DEFAULT_SUBMISSION_PREFLIGHT_CONCURRENCY);
        expect(report.allowed).toBe(true);
    });
});
