/**
 * Poison-pill state control helpers.
 *
 * A poison pill is not the mutation under test. It is a reversible
 * fixture that keeps one subject resource short of completion so the
 * runner can submit the real mutator while that subject is still
 * in-progress. Keep these mechanics explicit in specs so coverage
 * output can separate state-control collateral from behavior assertions.
 */

import { WorkflowCli } from "./workflowCli";
import {
    ComponentId,
    PoisonPillSpec,
    ScenarioSpec,
} from "./types";

export type PoisonPillMode = "poison" | "restore";

export interface ResolvedPoisonPill {
    name: string;
    spec: PoisonPillSpec;
}

export function resolvePoisonPill(
    spec: ScenarioSpec,
    name: string,
    subject: ComponentId,
): ResolvedPoisonPill {
    const pill = spec.fixtures.poisonPills?.byName[name];
    if (!pill) {
        throw new Error(`unknown poisonPill '${name}'`);
    }
    if (pill.subject !== subject) {
        throw new Error(
            `poisonPill '${name}' targets '${pill.subject}', not subject '${subject}'`,
        );
    }
    return { name, spec: pill };
}

/**
 * Return a cloned config with a config-value poison or restore value
 * applied. Non-config poison pills do not affect YAML and return a
 * clone of the input unchanged.
 */
export function applyPoisonPillToConfig(
    config: unknown,
    pill: PoisonPillSpec,
    mode: PoisonPillMode,
): unknown {
    const cloned = structuredClone(config);
    if (pill.strategy !== "config-value") return cloned;

    const op = mode === "poison" ? pill.poison : pill.restore;
    setPathValue(cloned, op.path, op.value);
    return cloned;
}

/**
 * Apply side-effect poison pills. Config-value pills are represented
 * directly in the submitted YAML, so this only performs work for
 * credential-based state controls.
 */
export function applyPoisonPillSideEffect(
    workflowCli: WorkflowCli,
    pill: ResolvedPoisonPill,
    mode: PoisonPillMode,
): void {
    if (pill.spec.strategy !== "basic-auth-credentials") return;
    const source = mode === "poison" ? pill.spec.poison : pill.spec.restore;
    workflowCli.createOrUpdateCredentialsStdin(
        pill.spec.secretName,
        {
            username: requireEnv(source.usernameEnv, pill.name, "username", mode),
            password: requireEnv(source.passwordEnv, pill.name, "password", mode),
        },
    );
}

function requireEnv(
    envName: string,
    poisonPillName: string,
    field: "username" | "password",
    mode: PoisonPillMode,
): string {
    const value = process.env[envName];
    if (!value) {
        throw new Error(
            `poisonPill '${poisonPillName}' ${mode} expected ${field} in env var ${envName}`,
        );
    }
    return value;
}

function setPathValue(root: unknown, path: string, value: unknown): void {
    const parts = path.split(".").filter((p) => p.length > 0);
    if (parts.length === 0) {
        throw new Error("config-value poison path must not be empty");
    }

    let cursor = root as unknown;
    for (let i = 0; i < parts.length - 1; i += 1) {
        cursor = descend(cursor, parts[i], path);
    }
    const leaf = parts[parts.length - 1];
    if (Array.isArray(cursor)) {
        const index = numericIndex(leaf, path);
        cursor[index] = value;
        return;
    }
    if (!isRecord(cursor)) {
        throw new Error(`cannot set '${path}': parent '${parts.slice(0, -1).join(".")}' is not an object`);
    }
    cursor[leaf] = value;
}

function descend(value: unknown, part: string, fullPath: string): unknown {
    if (Array.isArray(value)) {
        const index = numericIndex(part, fullPath);
        const next = value[index];
        if (next === undefined) {
            throw new Error(`cannot set '${fullPath}': array index ${index} is missing`);
        }
        return next;
    }
    if (!isRecord(value)) {
        throw new Error(`cannot set '${fullPath}': '${part}' is not reachable`);
    }
    const next = value[part];
    if (next === undefined) {
        throw new Error(`cannot set '${fullPath}': key '${part}' is missing`);
    }
    return next;
}

function numericIndex(part: string, fullPath: string): number {
    const index = Number(part);
    if (!Number.isInteger(index) || index < 0) {
        throw new Error(`cannot set '${fullPath}': '${part}' is not an array index`);
    }
    return index;
}

function isRecord(value: unknown): value is Record<string, unknown> {
    return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}
