/**
 * k8sClient — thin wrapper over `kubectl` for CRD reads.
 *
 * This module intentionally shells out to `kubectl` rather than using
 * `@kubernetes/client-node`. Reasons:
 *
 *   - the migration framework's own CLI (`workflow`) does the same,
 *     so matching that surface keeps us close to production use;
 *   - it inherits whatever auth context the user already has
 *     (kubeconfig, AWS SSO, IAM Authenticator, etc.);
 *   - it avoids pulling a heavy dependency into a package whose main
 *     job is test orchestration, not general cluster I/O.
 *
 * All commands are invoked with `spawnSync` and array args to avoid
 * shell interpolation of resource names.
 */

import { spawnSync, SpawnSyncReturns } from "node:child_process";
import { stringify as stringifyYaml } from "yaml";

import { ComponentId } from "./types";

export class KubectlError extends Error {
    constructor(
        message: string,
        public readonly exitCode: number | null,
        public readonly stderr: string,
    ) {
        super(message);
        this.name = "KubectlError";
    }
}

export interface KubectlRunResult {
    stdout: string;
    stderr: string;
    exitCode: number;
}

/**
 * How to launch kubectl. Injected so tests can substitute a fake. The
 * real implementation calls `spawnSync('kubectl', args, ...)`.
 */
export type KubectlRunner = (args: readonly string[], opts?: KubectlRunOptions) => KubectlRunResult;

export interface KubectlRunOptions {
    /** Milliseconds; kills kubectl if exceeded. */
    timeoutMs?: number;
    /** Optional stdin payload. */
    input?: string;
}

export const defaultKubectlRunner: KubectlRunner = (args, opts) => {
    const res: SpawnSyncReturns<string> = spawnSync("kubectl", [...args], {
        encoding: "utf8",
        timeout: opts?.timeoutMs,
        input: opts?.input,
    });
    if (res.error) {
        throw new KubectlError(
            `kubectl invocation failed: ${res.error.message}`,
            null,
            res.stderr ?? "",
        );
    }
    return {
        stdout: res.stdout ?? "",
        stderr: res.stderr ?? "",
        exitCode: res.status ?? 0,
    };
};

export interface K8sClientOptions {
    namespace: string;
    runner?: KubectlRunner;
    /** Extra args appended to every kubectl invocation (e.g. --context=...). */
    extraArgs?: readonly string[];
}

/**
 * A thin kubectl-backed client for the CRDs this framework cares about.
 * Only read methods are exposed; mutations (approve, reset, submit) go
 * through the `workflow` CLI, not kubectl.
 */
export class K8sClient {
    private readonly runner: KubectlRunner;
    private readonly namespace: string;
    private readonly extraArgs: readonly string[];

    constructor(opts: K8sClientOptions) {
        this.namespace = opts.namespace;
        this.runner = opts.runner ?? defaultKubectlRunner;
        this.extraArgs = opts.extraArgs ?? [];
    }

    /**
     * `kubectl get <plural> -n <ns> -o json` → parsed JSON. Throws on
     * non-zero exit.
     */
    getAll(plural: string): { items: Record<string, unknown>[] } {
        return this.runJson(["get", plural, "-n", this.namespace, "-o", "json"]) as {
            items: Record<string, unknown>[];
        };
    }

    /**
     * Read one resource by name. Returns null if not found (kubectl exits
     * with NotFound in stderr).
     */
    getOne(plural: string, name: string): Record<string, unknown> | null {
        const res = this.runner(
            ["get", plural, name, "-n", this.namespace, "-o", "json", ...this.extraArgs],
        );
        if (res.exitCode !== 0) {
            if (/not\s*found/i.test(res.stderr)) return null;
            throw new KubectlError(
                `kubectl get ${plural}/${name} failed`,
                res.exitCode,
                res.stderr,
            );
        }
        return JSON.parse(res.stdout) as Record<string, unknown>;
    }

    /**
     * Create or update an Opaque Secret with basic-auth keys. Uses
     * `kubectl apply -f -` so repeated setup runs are idempotent.
     */
    applyBasicAuthSecret(
        name: string,
        creds: { username: string; password: string },
    ): void {
        const manifest = stringifyYaml({
            apiVersion: "v1",
            kind: "Secret",
            metadata: {
                name,
                namespace: this.namespace,
            },
            type: "Opaque",
            stringData: {
                username: creds.username,
                password: creds.password,
            },
        });
        const res = this.runner(["apply", "-f", "-", ...this.extraArgs], {
            input: manifest,
        });
        if (res.exitCode !== 0) {
            throw new KubectlError(
                `kubectl apply secret/${name} failed`,
                res.exitCode,
                res.stderr,
            );
        }
    }

    /**
     * Delete a Kubernetes resource and wait for deletion to complete.
     * `--ignore-not-found` keeps cleanup idempotent across failed or
     * partially completed runs.
     */
    deleteResourceAndWait(
        resource: string,
        name: string,
        timeoutSeconds = 60,
    ): void {
        const res = this.runner(
            [
                "delete",
                resource,
                name,
                "-n",
                this.namespace,
                "--ignore-not-found",
                "--wait=true",
                `--timeout=${timeoutSeconds}s`,
                ...this.extraArgs,
            ],
        );
        if (res.exitCode !== 0) {
            throw new KubectlError(
                `kubectl delete ${resource}/${name} failed`,
                res.exitCode,
                res.stderr,
            );
        }
    }

    private runJson(args: readonly string[]): unknown {
        const res = this.runner([...args, ...this.extraArgs]);
        if (res.exitCode !== 0) {
            throw new KubectlError(
                `kubectl ${args.join(" ")} failed`,
                res.exitCode,
                res.stderr,
            );
        }
        try {
            return JSON.parse(res.stdout);
        } catch (e) {
            throw new KubectlError(
                `kubectl ${args.join(" ")} stdout is not valid JSON: ${(e as Error).message}`,
                res.exitCode,
                res.stderr,
            );
        }
    }
}

/**
 * Extract the fields the framework reads from a CRD item, with defensive
 * parsing. Unknown shapes return undefined rather than throwing.
 */
export interface CrdObservation {
    componentId: ComponentId;
    phase: string;
    uid?: string;
    generation?: number;
    /** spec.dependsOn, if present. */
    dependsOn?: string[];
    /** The raw resource for diagnostic capture. */
    raw: unknown;
}

export function extractCrdObservation(
    plural: string,
    item: Record<string, unknown>,
): CrdObservation | null {
    const meta = (item["metadata"] as Record<string, unknown> | undefined) ?? {};
    const name = typeof meta["name"] === "string" ? meta["name"] : undefined;
    if (!name) return null;

    const status = (item["status"] as Record<string, unknown> | undefined) ?? {};
    const spec = (item["spec"] as Record<string, unknown> | undefined) ?? {};
    const kind = pluralToKind(plural);
    const componentId = `${kind}:${name}` as ComponentId;
    const dependsOnRaw = spec["dependsOn"];
    const dependsOn =
        Array.isArray(dependsOnRaw) && dependsOnRaw.every((x) => typeof x === "string")
            ? (dependsOnRaw as string[])
            : undefined;

    return {
        componentId,
        phase: typeof status["phase"] === "string" ? (status["phase"] as string) : "Unknown",
        uid: typeof meta["uid"] === "string" ? (meta["uid"] as string) : undefined,
        generation:
            typeof meta["generation"] === "number" ? (meta["generation"] as number) : undefined,
        dependsOn,
        raw: item,
    };
}

/**
 * Map CRD plurals to their short kind used in component IDs. Keep this
 * aligned with the IDs produced by `componentTopologyResolver`.
 */
const PLURAL_TO_KIND: Record<string, string> = {
    kafkaclusters: "kafkacluster",
    capturedtraffics: "capturedtraffic",
    captureproxies: "captureproxy",
    datasnapshots: "datasnapshot",
    snapshotmigrations: "snapshotmigration",
    trafficreplays: "trafficreplay",
    approvalgates: "approvalgate",
};

function pluralToKind(plural: string): string {
    return PLURAL_TO_KIND[plural] ?? plural;
}

/** The CRD plurals the framework reads by default. */
export const MIGRATION_CRD_PLURALS: readonly string[] = [
    "kafkaclusters",
    "capturedtraffics",
    "captureproxies",
    "datasnapshots",
    "snapshotmigrations",
    "trafficreplays",
    "approvalgates",
];
