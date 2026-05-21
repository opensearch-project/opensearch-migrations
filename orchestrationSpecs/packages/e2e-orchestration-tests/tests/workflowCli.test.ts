import {
    WorkflowCli,
    WorkflowCliError,
    WorkflowCliRunner,
    buildWorkflowCliRunner,
} from "../src/workflowCli";
import { KubectlRunner } from "../src/k8sClient";

function recordingRunner(): {
    runner: WorkflowCliRunner;
    calls: { args: readonly string[]; input?: string }[];
    respond: (stdout?: string, stderr?: string, exitCode?: number) => void;
} {
    const calls: { args: readonly string[]; input?: string }[] = [];
    let next: { stdout: string; stderr: string; exitCode: number } = {
        stdout: "",
        stderr: "",
        exitCode: 0,
    };
    return {
        runner: (args, opts) => {
            calls.push({ args: [...args], input: opts?.input });
            return next;
        },
        calls,
        respond: (stdout = "", stderr = "", exitCode = 0) => {
            next = { stdout, stderr, exitCode };
        },
    };
}

function recordingKubectl(): {
    kubectl: KubectlRunner;
    calls: { args: readonly string[]; input?: string }[];
} {
    const calls: { args: readonly string[]; input?: string }[] = [];
    return {
        kubectl: (args, opts) => {
            calls.push({ args: [...args], input: opts?.input });
            return { stdout: "", stderr: "", exitCode: 0 };
        },
        calls,
    };
}

describe("WorkflowCli subcommands", () => {
    it("builds 'configure edit --stdin' with YAML input", () => {
        const r = recordingRunner();
        const cli = new WorkflowCli({ runner: r.runner, namespace: "ma" });
        cli.configureEditStdin("foo: bar\n");
        expect(r.calls[0].args).toEqual(["configure", "edit", "--stdin"]);
        expect(r.calls[0].input).toBe("foo: bar\n");
    });

    it("creates HTTP Basic credentials with stdin", () => {
        const r = recordingRunner();
        const cli = new WorkflowCli({ runner: r.runner, namespace: "ma" });
        cli.createCredentialsStdin("source-creds", {
            username: "admin",
            password: "secret",
        });
        expect(r.calls[0].args).toEqual([
            "configure",
            "credentials",
            "create",
            "--stdin",
            "source-creds",
        ]);
        expect(r.calls[0].input).toBe("admin:secret\n");
    });

    it("updates HTTP Basic credentials when create reports an existing managed credential", () => {
        const calls: { args: readonly string[]; input?: string }[] = [];
        const cli = new WorkflowCli({
            namespace: "ma",
            runner: (args, opts) => {
                calls.push({ args: [...args], input: opts?.input });
                if (calls.length === 1) {
                    return {
                        stdout: "",
                        stderr: "Error: Managed HTTP Basic credentials already exist: source-creds",
                        exitCode: 1,
                    };
                }
                return { stdout: "", stderr: "", exitCode: 0 };
            },
        });

        cli.createOrUpdateCredentialsStdin("source-creds", {
            username: "admin",
            password: "secret",
        });

        expect(calls.map((c) => c.args)).toEqual([
            ["configure", "credentials", "create", "--stdin", "source-creds"],
            ["configure", "credentials", "update", "--stdin", "source-creds"],
        ]);
        expect(calls[1].input).toBe("admin:secret\n");
    });

    it("builds 'submit' with namespace and optional wait", () => {
        const r = recordingRunner();
        const cli = new WorkflowCli({ runner: r.runner, namespace: "ma" });
        cli.submit();
        cli.submit({ wait: true, timeoutSeconds: 300 });
        expect(r.calls[0].args).toEqual(["submit", "--namespace", "ma"]);
        expect(r.calls[1].args).toEqual([
            "submit",
            "--namespace",
            "ma",
            "--wait",
            "--timeout",
            "300",
        ]);
    });

    it("builds approve for exact gate names", () => {
        const r = recordingRunner();
        const cli = new WorkflowCli({ runner: r.runner, namespace: "ma" });
        cli.approve("source.target.snap1.migration-0.evaluatemetadata");
        expect(r.calls[0].args).toEqual([
            "approve",
            "step",
            "source.target.snap1.migration-0.evaluatemetadata",
            "--namespace",
            "ma",
        ]);
    });

    it("builds approve for glob-style patterns", () => {
        const r = recordingRunner();
        const cli = new WorkflowCli({ runner: r.runner, namespace: "ma" });
        cli.approve("*.evaluateMetadata");
        expect(r.calls[0].args).toEqual([
            "approve",
            "step",
            "*.evaluateMetadata",
            "--namespace",
            "ma",
        ]);
    });

    it("treats no pending structural step gates as idempotent success", () => {
        const r = recordingRunner();
        r.respond("No gates are currently being waited on by the workflow.\n", "command terminated with exit code 1", 1);
        const cli = new WorkflowCli({ runner: r.runner, namespace: "ma" });
        const result = cli.approve("*.evaluateMetadata");
        expect(result.exitCode).toBe(0);
        expect(result.stdout).toContain("No gates are currently");
    });

    it("treats no matching pending gates as idempotent success", () => {
        const r = recordingRunner();
        r.respond("No pending gates match ('*.documentbackfill',).\nAvailable pending gates:\n  - default.vapretry\n", "command terminated with exit code 1", 1);
        const cli = new WorkflowCli({ runner: r.runner, namespace: "ma" });
        const result = cli.approve("*.documentbackfill");
        expect(result.exitCode).toBe(0);
        expect(result.stdout).toContain("No pending gates match");
    });

    it("builds categorized change and retry approval commands", () => {
        const r = recordingRunner();
        const cli = new WorkflowCli({ runner: r.runner, namespace: "ma" });
        cli.approveChange("captureproxy.capture-proxy");
        cli.approveRetry("snapshotmigration.source-target-snap1-migration-0");
        expect(r.calls.map((c) => c.args)).toEqual([
            ["approve", "change", "captureproxy.capture-proxy", "--namespace", "ma"],
            ["approve", "retry", "snapshotmigration.source-target-snap1-migration-0", "--namespace", "ma"],
        ]);
    });

    it("lists categorized approval gates", () => {
        const r = recordingRunner();
        const cli = new WorkflowCli({ runner: r.runner, namespace: "ma" });
        cli.listApprovalGates("retry");
        expect(r.calls[0].args).toEqual([
            "approve",
            "retry",
            "--list",
            "--namespace",
            "ma",
        ]);
    });

    it("can treat retry prerequisite failures as expected non-advancement", () => {
        const r = recordingRunner();
        r.respond(
            "",
            "Cannot approve retry gates. These resources still exist:\n  SnapshotMigration/source-target-snap1-migration-0\n",
            1,
        );
        const cli = new WorkflowCli({ runner: r.runner, namespace: "ma" });
        const result = cli.approveRetry("snapshotmigration.source-target-snap1-migration-0", {
            allowPrereqFailure: true,
        });
        expect(result.exitCode).toBe(0);
    });

    it("builds 'reset' with all documented flags in order", () => {
        const r = recordingRunner();
        const cli = new WorkflowCli({ runner: r.runner, namespace: "ma" });
        cli.reset({ all: true, cascade: true, includeProxies: true, deleteStorage: true });
        expect(r.calls[0].args).toEqual([
            "reset",
            "--all",
            "--cascade",
            "--include-proxies",
            "--delete-storage",
            "--namespace",
            "ma",
        ]);
    });

    it("builds 'reset' with a path when provided", () => {
        const r = recordingRunner();
        const cli = new WorkflowCli({ runner: r.runner, namespace: "ma" });
        cli.reset({ path: "captureproxy:source-proxy" });
        expect(r.calls[0].args).toEqual([
            "reset",
            "captureproxy:source-proxy",
            "--namespace",
            "ma",
        ]);
    });

    it("throws WorkflowCliError on non-zero exit", () => {
        const r = recordingRunner();
        r.respond("", "boom", 1);
        const cli = new WorkflowCli({ runner: r.runner, namespace: "ma" });
        expect(() => cli.submit()).toThrow(WorkflowCliError);
    });
});

describe("buildWorkflowCliRunner kubectl-exec", () => {
    it("wraps every command in kubectl exec against the given pod", () => {
        const kc = recordingKubectl();
        const runner = buildWorkflowCliRunner({
            mode: "kubectl-exec",
            namespace: "ma",
            pod: "migration-console-0",
            kubectl: kc.kubectl,
        });
        runner(["approve", "step", "*.evaluateMetadata", "--namespace", "ma"]);
        expect(kc.calls[0].args).toEqual([
            "exec",
            "-n",
            "ma",
            "migration-console-0",
            "--",
            "workflow",
            "approve",
            "step",
            "*.evaluateMetadata",
            "--namespace",
            "ma",
        ]);
    });

    it("adds -i to the kubectl args when the workflow command expects stdin", () => {
        const kc = recordingKubectl();
        const runner = buildWorkflowCliRunner({
            mode: "kubectl-exec",
            namespace: "ma",
            pod: "migration-console-0",
            kubectl: kc.kubectl,
        });
        runner(["configure", "edit", "--stdin"], { input: "foo: bar\n" });
        expect(kc.calls[0].args[0]).toBe("exec");
        expect(kc.calls[0].args).toContain("-i");
        expect(kc.calls[0].input).toBe("foo: bar\n");
    });
});
