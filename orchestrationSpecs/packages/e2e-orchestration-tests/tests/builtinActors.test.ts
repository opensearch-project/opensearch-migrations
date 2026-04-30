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

    it("create-basic-auth-secrets creates each source/target basic auth secret from the baseline config", async () => {
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

        const applied: string[] = [];
        const actor = builtinActors().find((a) => a.name === CREATE_BASIC_AUTH_SECRETS_ACTOR)!;
        try {
            await actor.run({
                baselineConfigPath,
                namespace: "ma",
                phase: "setup",
                workflowCli: new WorkflowCli({
                    namespace: "ma",
                    runner: () => ({ stdout: "", stderr: "", exitCode: 0 }),
                }),
                k8sClient: new K8sClient({
                    namespace: "ma",
                    runner: (_args, opts) => {
                        applied.push(opts?.input ?? "");
                        return { stdout: "", stderr: "", exitCode: 0 };
                    },
                }),
            });
        } finally {
            fs.rmSync(tmpDir, { recursive: true, force: true });
        }

        expect(applied).toHaveLength(2);
        expect(applied.join("\n")).toContain("name: source-creds");
        expect(applied.join("\n")).toContain("name: target-creds");
        expect(applied.join("\n")).toContain("username: admin");
        expect(applied.join("\n")).toContain("password: admin");
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
