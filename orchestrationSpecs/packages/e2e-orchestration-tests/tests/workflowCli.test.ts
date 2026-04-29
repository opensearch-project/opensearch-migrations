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

    it("builds 'submit' with namespace and optional wait", () => {
        const r = recordingRunner();
        const cli = new WorkflowCli({ runner: r.runner, namespace: "ma" });
        cli.submit();
        cli.submit({ wait: true, timeoutSeconds: 300, workflowName: "my-wf" });
        expect(r.calls[0].args).toEqual(["submit", "--namespace", "ma"]);
        expect(r.calls[1].args).toEqual([
            "submit",
            "--namespace",
            "ma",
            "--wait",
            "--timeout",
            "300",
            "--workflow-name",
            "my-wf",
        ]);
    });

    it("builds 'approve' with the given pattern and namespace", () => {
        const r = recordingRunner();
        const cli = new WorkflowCli({ runner: r.runner, namespace: "ma" });
        cli.approve("*.evaluateMetadata");
        expect(r.calls[0].args).toEqual([
            "approve",
            "*.evaluateMetadata",
            "--namespace",
            "ma",
        ]);
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
        runner(["approve", "*.evaluateMetadata", "--namespace", "ma"]);
        expect(kc.calls[0].args).toEqual([
            "exec",
            "-n",
            "ma",
            "migration-console-0",
            "--",
            "workflow",
            "approve",
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
