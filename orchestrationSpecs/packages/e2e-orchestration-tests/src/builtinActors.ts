/**
 * Built-in actors.
 *
 * The first-slice runner includes one real setup actor for local live
 * runs (`create-basic-auth-secrets`) plus stubs for design-doc cleanup
 * actors that are not implemented yet.
 *
 * The cleanup actors are intentionally stubs that:
 *   1. logs a diagnostic via the caller-supplied logger, and
 *   2. throws a `NotImplementedActorError`, so the runner records a
 *      diagnostic and (for teardown) moves on to the next actor.
 *
 * Making those stubs throw means a test that claims to have torn down
 * target indices does not silently succeed — the snapshot records
 * "teardown actor 'delete-target-indices' failed: not implemented in
 * first slice". That's honest signalling for the first live runs.
 *
 * When the real implementations land, replace each stub in this file
 * (or register a full-featured actor under the same name via
 * `extraActors` in `RunFromSpecOptions`).
 */

import * as fs from "node:fs";

import { parse as parseYaml } from "yaml";

import { Actor, ActorContext } from "./actors";

export class NotImplementedActorError extends Error {
    constructor(name: string) {
        super(
            `actor '${name}' is a first-slice stub and has no implementation yet`,
        );
        this.name = "NotImplementedActorError";
    }
}

function stub(name: string): Actor {
    return {
        name,
        run: async () => {
            throw new NotImplementedActorError(name);
        },
    };
}

export interface BasicAuthSecretDefaults {
    username: string;
    password: string;
}

export const DEFAULT_BASIC_AUTH_SECRET_CREDS: BasicAuthSecretDefaults = {
    username: "admin",
    password: "admin",
};

export const CREATE_BASIC_AUTH_SECRETS_ACTOR = "create-basic-auth-secrets";

function createBasicAuthSecretsActor(
    creds: BasicAuthSecretDefaults = DEFAULT_BASIC_AUTH_SECRET_CREDS,
): Actor {
    return {
        name: CREATE_BASIC_AUTH_SECRETS_ACTOR,
        run: async (ctx: ActorContext) => {
            const rawConfig = parseYaml(
                fs.readFileSync(ctx.baselineConfigPath, "utf8"),
            ) as unknown;
            for (const secretName of scrapeBasicAuthSecretNames(rawConfig)) {
                ctx.k8sClient.applyBasicAuthSecret(secretName, creds);
            }
        },
    };
}

function scrapeBasicAuthSecretNames(rawConfig: unknown): string[] {
    const config = asRecord(rawConfig);
    const names = new Set<string>();
    for (const clusterMap of [
        asRecord(config["sourceClusters"]),
        asRecord(config["targetClusters"]),
    ]) {
        for (const cluster of Object.values(clusterMap)) {
            const basic = asRecord(asRecord(asRecord(cluster)["authConfig"])["basic"]);
            const secretName = basic["secretName"];
            if (typeof secretName === "string" && secretName.length > 0) {
                names.add(secretName);
            }
        }
    }
    return [...names].sort();
}

function asRecord(v: unknown): Record<string, unknown> {
    return v && typeof v === "object" && !Array.isArray(v)
        ? (v as Record<string, unknown>)
        : {};
}

/**
 * Names the design doc's example specs reference. Keeping the list
 * small — additions should be justified by at least one spec in the
 * repo or a reviewed mutator that needs the hook.
 */
export const BUILTIN_ACTOR_NAMES: readonly string[] = [
    CREATE_BASIC_AUTH_SECRETS_ACTOR,
    "delete-target-indices",
    "delete-source-snapshots",
] as const;

export function builtinActors(): Actor[] {
    return [
        createBasicAuthSecretsActor(),
        stub("delete-target-indices"),
        stub("delete-source-snapshots"),
    ];
}
