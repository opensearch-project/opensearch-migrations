import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";

import {
    BUILTIN_ACTOR_NAMES,
    CREATE_BASIC_AUTH_SECRETS_ACTOR,
    NotImplementedActorError,
    builtinActors,
} from "../src/builtinActors";
import { ActorRegistry } from "../src/actors";
import { WorkflowCli } from "../src/workflowCli";
import { K8sClient } from "../src/k8sClient";
import { ScenarioSpec } from "../src/types";

function specWithCredentialFixture(): ScenarioSpec {
    return {
        baseConfig: "/tmp/baseline.wf.yaml",
        phaseCompletionTimeoutSeconds: 5,
        matrix: { subject: "datasnapshot:source-snap1" },
        lifecycle: { setup: [], teardown: [] },
        approvalGates: [],
        fixtures: {
            basicAuthCredentials: {
                bySecretName: {
                    "source-creds": {
                        usernameEnv: "E2E_SOURCE_TEST_USERNAME",
                        passwordEnv: "E2E_SOURCE_TEST_PASSWORD",
                    },
                    "target-creds": {
                        usernameEnv: "E2E_TARGET_TEST_USERNAME",
                        passwordEnv: "E2E_TARGET_TEST_PASSWORD",
                    },
                },
            },
        },
    };
}

describe("builtinActors", () => {
    it("lists the actor names the design doc's example specs reference", () => {
        expect(BUILTIN_ACTOR_NAMES).toEqual(
            expect.arrayContaining([
                CREATE_BASIC_AUTH_SECRETS_ACTOR,
                "delete-target-indices",
                "delete-source-snapshots",
            ]),
        );
    });

    it("produces one actor per name, each with the matching name", () => {
        const actors = builtinActors();
        expect(actors.map((a) => a.name).sort()).toEqual([...BUILTIN_ACTOR_NAMES].sort());
    });

    it("cleanup stubs throw NotImplementedActorError when invoked", async () => {
        for (const actor of builtinActors().filter((a) => a.name !== CREATE_BASIC_AUTH_SECRETS_ACTOR)) {
            await expect(actor.run({} as never)).rejects.toBeInstanceOf(
                NotImplementedActorError,
            );
        }
    });

    it("create-basic-auth-secrets creates managed workflow credentials from spec-declared env vars", async () => {
        const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "builtin-actors-"));
        const baselineConfigPath = path.join(tmpDir, "baseline.wf.yaml");
        fs.writeFileSync(
            baselineConfigPath,
            `
sourceClusters:
  source:
    authConfig:
      basic:
        secretName: source-creds
targetClusters:
  target:
    authConfig:
      basic:
        secretName: target-creds
`,
            "utf8",
        );

        const previousSourceUsername = process.env.E2E_SOURCE_TEST_USERNAME;
        const previousSourcePassword = process.env.E2E_SOURCE_TEST_PASSWORD;
        const previousTargetUsername = process.env.E2E_TARGET_TEST_USERNAME;
        const previousTargetPassword = process.env.E2E_TARGET_TEST_PASSWORD;
        process.env.E2E_SOURCE_TEST_USERNAME = "source-admin";
        process.env.E2E_SOURCE_TEST_PASSWORD = "source-secret";
        process.env.E2E_TARGET_TEST_USERNAME = "target-admin";
        process.env.E2E_TARGET_TEST_PASSWORD = "target-secret";
        const workflowCalls: { args: readonly string[]; input?: string }[] = [];
        const actor = builtinActors().find((a) => a.name === CREATE_BASIC_AUTH_SECRETS_ACTOR)!;
        try {
            await actor.run({
                baselineConfigPath,
                namespace: "ma",
                phase: "setup",
                spec: specWithCredentialFixture(),
                workflowCli: new WorkflowCli({
                    namespace: "ma",
                    runner: (args, opts) => {
                        workflowCalls.push({ args, input: opts?.input });
                        return { stdout: "", stderr: "", exitCode: 0 };
                    },
                }),
                k8sClient: new K8sClient({
                    namespace: "ma",
                    runner: () => {
                        throw new Error("unexpected raw kubectl credential write");
                    },
                }),
            });
        } finally {
            restoreEnv("E2E_SOURCE_TEST_USERNAME", previousSourceUsername);
            restoreEnv("E2E_SOURCE_TEST_PASSWORD", previousSourcePassword);
            restoreEnv("E2E_TARGET_TEST_USERNAME", previousTargetUsername);
            restoreEnv("E2E_TARGET_TEST_PASSWORD", previousTargetPassword);
            fs.rmSync(tmpDir, { recursive: true, force: true });
        }

        expect(workflowCalls).toEqual([
            {
                args: ["configure", "credentials", "create", "--stdin", "source-creds"],
                input: "source-admin:source-secret\n",
            },
            {
                args: ["configure", "credentials", "create", "--stdin", "target-creds"],
                input: "target-admin:target-secret\n",
            },
        ]);
    });

    it("create-basic-auth-secrets fails clearly when fixture env vars are missing", async () => {
        const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "builtin-actors-"));
        const baselineConfigPath = path.join(tmpDir, "baseline.wf.yaml");
        fs.writeFileSync(
            baselineConfigPath,
            `
sourceClusters:
  source:
    authConfig:
      basic:
        secretName: source-creds
`,
            "utf8",
        );

        const previousUsername = process.env.E2E_SOURCE_TEST_USERNAME;
        const previousPassword = process.env.E2E_SOURCE_TEST_PASSWORD;
        delete process.env.E2E_SOURCE_TEST_USERNAME;
        delete process.env.E2E_SOURCE_TEST_PASSWORD;
        const actor = builtinActors().find((a) => a.name === CREATE_BASIC_AUTH_SECRETS_ACTOR)!;
        try {
            await expect(actor.run({
                baselineConfigPath,
                namespace: "ma",
                phase: "setup",
                spec: specWithCredentialFixture(),
                workflowCli: new WorkflowCli({
                    namespace: "ma",
                    runner: () => ({ stdout: "", stderr: "", exitCode: 0 }),
                }),
                k8sClient: new K8sClient({
                    namespace: "ma",
                    runner: () => ({ stdout: "", stderr: "", exitCode: 0 }),
                }),
            })).rejects.toThrow(/E2E_SOURCE_TEST_USERNAME/);
        } finally {
            restoreEnv("E2E_SOURCE_TEST_USERNAME", previousUsername);
            restoreEnv("E2E_SOURCE_TEST_PASSWORD", previousPassword);
            fs.rmSync(tmpDir, { recursive: true, force: true });
        }
    });

    it("registers cleanly into a fresh ActorRegistry", () => {
        const reg = new ActorRegistry();
        for (const a of builtinActors()) reg.register(a);
        for (const name of BUILTIN_ACTOR_NAMES) {
            expect(reg.has(name)).toBe(true);
        }
    });

    it("can be layered with extra actors without collision", () => {
        const reg = new ActorRegistry();
        for (const a of builtinActors()) reg.register(a);
        reg.register({ name: "extra-one", run: async () => {} });
        expect(reg.has("extra-one")).toBe(true);
        expect(reg.has("delete-target-indices")).toBe(true);
    });
});

function restoreEnv(name: string, value: string | undefined): void {
    if (value === undefined) {
        delete process.env[name];
    } else {
        process.env[name] = value;
    }
}
