/**
 * Built-in actors.
 *
 * The runner includes one real setup actor for local live runs
 * (`create-basic-auth-secrets`) plus stubs for design-doc cleanup
 * actors that are not implemented yet.
 *
 * The cleanup actors are intentionally stubs that:
 *   1. logs a diagnostic via the caller-supplied logger, and
 *   2. throws a `NotImplementedActorError`, so the runner records a
 *      diagnostic and (for teardown) moves on to the next actor.
 *
 * Making those stubs throw means a test that claims to have torn down
 * target indices does not silently succeed — the snapshot records
 * "teardown actor 'delete-target-indices' failed: not implemented".
 * That's honest signalling for live runs.
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
            `actor '${name}' is a placeholder and has no implementation yet`,
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

export interface BasicAuthCredentials {
    username: string;
    password: string;
}

export const CREATE_BASIC_AUTH_SECRETS_ACTOR = "create-basic-auth-secrets";

function createBasicAuthSecretsActor(): Actor {
    return {
        name: CREATE_BASIC_AUTH_SECRETS_ACTOR,
        run: async (ctx: ActorContext) => {
            const rawConfig = parseYaml(
                fs.readFileSync(ctx.baselineConfigPath, "utf8"),
            ) as unknown;
            for (const secretName of scrapeBasicAuthSecretNames(rawConfig)) {
                ctx.workflowCli.createOrUpdateCredentialsStdin(
                    secretName,
                    credentialsForSecret(ctx, secretName),
                );
            }
        },
    };
}

function credentialsForSecret(
    ctx: ActorContext,
    secretName: string,
): BasicAuthCredentials {
    const fixture = ctx.spec.fixtures.basicAuthCredentials;
    if (!fixture) {
        throw new Error(
            `${CREATE_BASIC_AUTH_SECRETS_ACTOR} requires fixtures.basicAuthCredentials in the scenario spec`,
        );
    }
    const source = fixture.bySecretName[secretName];
    if (!source) {
        throw new Error(
            `${CREATE_BASIC_AUTH_SECRETS_ACTOR} has no env mapping for '${secretName}'; ` +
                "set fixtures.basicAuthCredentials.bySecretName",
        );
    }
    return {
        username: requireEnv(source.usernameEnv, secretName, "username"),
        password: requireEnv(source.passwordEnv, secretName, "password"),
    };
}

function requireEnv(envName: string, secretName: string, field: "username" | "password"): string {
    const value = process.env[envName];
    if (!value) {
        throw new Error(
            `${CREATE_BASIC_AUTH_SECRETS_ACTOR} expected ${field} for '${secretName}' in env var ${envName}`,
        );
    }
    return value;
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
