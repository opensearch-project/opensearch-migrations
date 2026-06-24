import {
    applyPoisonPillSideEffect,
    applyPoisonPillToConfig,
    resolvePoisonPill,
} from "../src/poisonPills";
import { WorkflowCli } from "../src/workflowCli";
import { ComponentId, ScenarioSpec } from "../src/types";

function spec(): ScenarioSpec {
    return {
        baseConfig: "./baseline.wf.yaml",
        phaseCompletionTimeoutSeconds: 600,
        matrix: { subject: "datasnapshot:source-snap1" },
        lifecycle: { setup: [], teardown: [] },
        approvalGates: [],
        fixtures: {
            poisonPills: {
                byName: {
                    "bad-repo-endpoint": {
                        subject: "datasnapshot:source-snap1" as ComponentId,
                        strategy: "config-value",
                        expectedCollateral: ["captureproxy:capture-proxy" as ComponentId],
                        poison: {
                            path: "sourceClusters.source.snapshotInfo.repos.default.endpoint",
                            value: "http://does-not-exist:4566",
                        },
                        restore: {
                            path: "sourceClusters.source.snapshotInfo.repos.default.endpoint",
                            value: "localstack://localstack:4566",
                        },
                    },
                    "bad-target-auth": {
                        subject: "snapshotmigration:source-target-snap1-migration-0" as ComponentId,
                        strategy: "basic-auth-credentials",
                        expectedCollateral: [],
                        secretName: "target-creds",
                        poison: {
                            usernameEnv: "E2E_BAD_TARGET_USERNAME",
                            passwordEnv: "E2E_BAD_TARGET_PASSWORD",
                        },
                        restore: {
                            usernameEnv: "E2E_TARGET_USERNAME",
                            passwordEnv: "E2E_TARGET_PASSWORD",
                        },
                    },
                },
            },
        },
    };
}

describe("poison pill helpers", () => {
    it("applies and removes a config-value poison without mutating the input object", () => {
        const pill = resolvePoisonPill(
            spec(),
            "bad-repo-endpoint",
            "datasnapshot:source-snap1" as ComponentId,
        ).spec;
        const baseline = {
            sourceClusters: {
                source: {
                    snapshotInfo: {
                        repos: {
                            default: {
                                endpoint: "localstack://localstack:4566",
                            },
                        },
                    },
                },
            },
        };

        const poisoned = applyPoisonPillToConfig(baseline, pill, "poison") as typeof baseline;
        const restored = applyPoisonPillToConfig(poisoned, pill, "restore") as typeof baseline;

        expect(baseline.sourceClusters.source.snapshotInfo.repos.default.endpoint)
            .toBe("localstack://localstack:4566");
        expect(poisoned.sourceClusters.source.snapshotInfo.repos.default.endpoint)
            .toBe("http://does-not-exist:4566");
        expect(restored.sourceClusters.source.snapshotInfo.repos.default.endpoint)
            .toBe("localstack://localstack:4566");
    });

    it("applies credential poison and restore through workflow-managed credentials", () => {
        const oldEnv = { ...process.env };
        process.env.E2E_BAD_TARGET_USERNAME = "bad-user";
        process.env.E2E_BAD_TARGET_PASSWORD = "bad-pass";
        process.env.E2E_TARGET_USERNAME = "good-user";
        process.env.E2E_TARGET_PASSWORD = "good-pass";

        const calls: Array<{ args: readonly string[]; input?: string }> = [];
        const workflowCli = new WorkflowCli({
            namespace: "ma",
            runner: (args, opts) => {
                calls.push({ args: [...args], input: opts?.input });
                return { stdout: "", stderr: "", exitCode: 0 };
            },
        });
        const pill = resolvePoisonPill(
            spec(),
            "bad-target-auth",
            "snapshotmigration:source-target-snap1-migration-0" as ComponentId,
        );

        try {
            applyPoisonPillSideEffect(workflowCli, pill, "poison");
            applyPoisonPillSideEffect(workflowCli, pill, "restore");
        } finally {
            process.env = oldEnv;
        }

        expect(calls).toEqual([
            {
                args: ["configure", "credentials", "create", "--stdin", "target-creds"],
                input: "bad-user:bad-pass\n",
            },
            {
                args: ["configure", "credentials", "create", "--stdin", "target-creds"],
                input: "good-user:good-pass\n",
            },
        ]);
    });
});
