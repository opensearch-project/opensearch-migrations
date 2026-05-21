/**
 * workflowCli — wraps the migration-console `workflow` CLI.
 *
 * The CLI is a Python app that runs inside the `migration-console-0`
 * pod. Two invocation modes are supported:
 *
 *   - `local`: `workflow <subcommand>` — assumes the CLI is on PATH
 *     (what the migrationConsole integ tests do).
 *   - `kubectl-exec`: `kubectl exec -n <ns> <pod> -- workflow
 *     <subcommand>` — matches the `configureAndSubmit.sh` helper
 *     shipped with migration-workflow-templates.
 *
 * The wrapper exposes just the subset the test framework currently
 * needs: `configureEditStdin`, `submit`, `approve`, and `reset`.
 */

import {
    KubectlRunner,
    KubectlRunOptions,
    defaultKubectlRunner,
} from "./k8sClient";
import { spawnSync } from "node:child_process";

export class WorkflowCliError extends Error {
    constructor(
        message: string,
        public readonly exitCode: number | null,
        public readonly stdout: string,
        public readonly stderr: string,
    ) {
        super(message);
        this.name = "WorkflowCliError";
    }
}

export interface WorkflowCliRunResult {
    stdout: string;
    stderr: string;
    exitCode: number;
}

export type ApprovalGateCategory = "step" | "change" | "retry";

export type WorkflowCliRunner = (
    workflowArgs: readonly string[],
    opts?: KubectlRunOptions,
) => WorkflowCliRunResult;

export interface LocalCliOptions {
    mode: "local";
    /** Extra environment variables to set on the child. */
    env?: Record<string, string>;
}

export interface KubectlExecCliOptions {
    mode: "kubectl-exec";
    namespace: string;
    pod: string;
    /** Optional kubectl runner for tests. */
    kubectl?: KubectlRunner;
}

export type WorkflowCliOptions = LocalCliOptions | KubectlExecCliOptions;

const DEFAULT_TIMEOUT_MS = 10 * 60 * 1000; // 10 minutes

/**
 * Build a `WorkflowCliRunner` configured for either mode. Tests can
 * also supply their own runner directly to `WorkflowCli` without going
 * through this factory.
 */
export function buildWorkflowCliRunner(opts: WorkflowCliOptions): WorkflowCliRunner {
    if (opts.mode === "local") {
        return (args, runOpts) => {
            const res = spawnSync("workflow", [...args], {
                encoding: "utf8",
                timeout: runOpts?.timeoutMs ?? DEFAULT_TIMEOUT_MS,
                input: runOpts?.input,
                env: opts.env ? { ...process.env, ...opts.env } : process.env,
            });
            if (res.error) {
                throw new WorkflowCliError(
                    `'workflow' CLI not runnable locally: ${res.error.message}`,
                    null,
                    res.stdout ?? "",
                    res.stderr ?? "",
                );
            }
            return {
                stdout: res.stdout ?? "",
                stderr: res.stderr ?? "",
                exitCode: res.status ?? 0,
            };
        };
    }

    const kubectl = opts.kubectl ?? defaultKubectlRunner;
    return (args, runOpts) => {
        const execArgs = [
            "exec",
            ...(runOpts?.input != null ? ["-i"] : []),
            "-n",
            opts.namespace,
            opts.pod,
            "--",
            "workflow",
            ...args,
        ];
        const res = kubectl(execArgs, {
            timeoutMs: runOpts?.timeoutMs ?? DEFAULT_TIMEOUT_MS,
            input: runOpts?.input,
        });
        return {
            stdout: res.stdout,
            stderr: res.stderr,
            exitCode: res.exitCode,
        };
    };
}

export interface WorkflowCliCtorOptions {
    runner: WorkflowCliRunner;
    /** Default namespace passed to every subcommand. */
    namespace: string;
    /** Defaults to 10 minutes. */
    defaultTimeoutMs?: number;
}

export class WorkflowCli {
    private readonly runner: WorkflowCliRunner;
    private readonly namespace: string;
    private readonly defaultTimeoutMs: number;

    constructor(opts: WorkflowCliCtorOptions) {
        this.runner = opts.runner;
        this.namespace = opts.namespace;
        this.defaultTimeoutMs = opts.defaultTimeoutMs ?? DEFAULT_TIMEOUT_MS;
    }

    /** `workflow configure edit --stdin` — writes the given YAML. */
    configureEditStdin(yaml: string, timeoutMs?: number): WorkflowCliRunResult {
        return this.must(
            ["configure", "edit", "--stdin"],
            { input: yaml, timeoutMs: timeoutMs ?? this.defaultTimeoutMs },
        );
    }

    /**
     * Create workflow-managed HTTP Basic credentials. `workflow
     * configure edit --stdin` and `workflow submit` only count
     * credentials created through this managed Secret path.
     */
    createCredentialsStdin(
        name: string,
        creds: { username: string; password: string },
    ): WorkflowCliRunResult {
        return this.must(
            ["configure", "credentials", "create", "--stdin", name],
            { input: formatBasicAuthCredentials(creds), timeoutMs: this.defaultTimeoutMs },
        );
    }

    /** Update an existing workflow-managed HTTP Basic credential. */
    updateCredentialsStdin(
        name: string,
        creds: { username: string; password: string },
    ): WorkflowCliRunResult {
        return this.must(
            ["configure", "credentials", "update", "--stdin", name],
            { input: formatBasicAuthCredentials(creds), timeoutMs: this.defaultTimeoutMs },
        );
    }

    /**
     * Idempotent credential setup for e2e fixtures: create first, then
     * update if the managed credential already exists.
     */
    createOrUpdateCredentialsStdin(
        name: string,
        creds: { username: string; password: string },
    ): WorkflowCliRunResult {
        try {
            return this.createCredentialsStdin(name, creds);
        } catch (e) {
            const err = e as Partial<WorkflowCliError>;
            const output = `${err.message ?? ""}\n${err.stdout ?? ""}\n${err.stderr ?? ""}`;
            if (/Managed HTTP Basic credentials already exist/i.test(output)) {
                return this.updateCredentialsStdin(name, creds);
            }
            throw e;
        }
    }

    /** `workflow submit --namespace <ns> [--wait --timeout <t>]`. */
    submit(opts: { wait?: boolean; timeoutSeconds?: number } = {}): WorkflowCliRunResult {
        const args = ["submit", "--namespace", this.namespace];
        if (opts.wait) args.push("--wait");
        if (opts.timeoutSeconds != null) args.push("--timeout", String(opts.timeoutSeconds));
        return this.must(args, { timeoutMs: this.defaultTimeoutMs });
    }

    /** `workflow approve <category> --list --namespace <ns>`. */
    listApprovalGates(category: ApprovalGateCategory): WorkflowCliRunResult {
        return this.must(["approve", category, "--list", "--namespace", this.namespace]);
    }

    /** Backwards-compatible default: approve structural step gates. */
    approve(pattern: string): WorkflowCliRunResult {
        return this.approveStep(pattern);
    }

    approveStep(pattern: string): WorkflowCliRunResult {
        return this.approveGate("step", pattern);
    }

    approveChange(pattern: string): WorkflowCliRunResult {
        return this.approveGate("change", pattern);
    }

    approveRetry(
        pattern: string,
        opts: { allowPrereqFailure?: boolean } = {},
    ): WorkflowCliRunResult {
        return this.approveGate("retry", pattern, opts);
    }

    private approveGate(
        category: ApprovalGateCategory,
        pattern: string,
        opts: { allowPrereqFailure?: boolean } = {},
    ): WorkflowCliRunResult {
        const args = ["approve", category, pattern, "--namespace", this.namespace];
        const res = this.runner(args, { timeoutMs: this.defaultTimeoutMs });
        const combinedOutput = `${res.stdout}\n${res.stderr}`;
        if (
            res.exitCode !== 0 &&
            /(No pending steps found|No steps are currently being waited on|No gated changes are currently being waited on|No retry gates are currently being waited on|No gates are currently being waited on|No pending gates match|No gates match)/i.test(combinedOutput)
        ) {
            return { ...res, exitCode: 0 };
        }
        if (
            opts.allowPrereqFailure &&
            res.exitCode !== 0 &&
            /Cannot approve retry gates\. These resources still exist/i.test(combinedOutput)
        ) {
            return { ...res, exitCode: 0 };
        }
        if (res.exitCode !== 0) {
            throw new WorkflowCliError(
                `workflow ${args.join(" ")} exited ${res.exitCode}`,
                res.exitCode,
                res.stdout,
                res.stderr,
            );
        }
        return res;
    }

    /**
     * `workflow reset [...] --namespace <ns>`. Mirrors the flags the
     * Python CLI exposes.
     */
    reset(opts: {
        all?: boolean;
        cascade?: boolean;
        includeProxies?: boolean;
        deleteStorage?: boolean;
        path?: string;
    }): WorkflowCliRunResult {
        const args = ["reset"];
        if (opts.path) args.push(opts.path);
        if (opts.all) args.push("--all");
        if (opts.cascade) args.push("--cascade");
        if (opts.includeProxies) args.push("--include-proxies");
        if (opts.deleteStorage) args.push("--delete-storage");
        args.push("--namespace", this.namespace);
        return this.must(args);
    }

    private must(args: readonly string[], runOpts?: KubectlRunOptions): WorkflowCliRunResult {
        const res = this.runner(args, runOpts);
        if (res.exitCode !== 0) {
            throw new WorkflowCliError(
                `workflow ${args.join(" ")} exited ${res.exitCode}`,
                res.exitCode,
                res.stdout,
                res.stderr,
            );
        }
        return res;
    }
}

function formatBasicAuthCredentials(creds: { username: string; password: string }): string {
    return `${creds.username}:${creds.password}\n`;
}
