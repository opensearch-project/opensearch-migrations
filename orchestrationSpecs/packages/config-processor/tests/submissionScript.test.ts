import {afterEach, describe, expect, it} from "@jest/globals";
import {spawnSync} from "child_process";
import {
    chmodSync,
    existsSync,
    mkdtempSync,
    readFileSync,
    rmSync,
    writeFileSync,
} from "fs";
import {tmpdir} from "os";
import * as path from "path";


const tempDirectories: string[] = [];

afterEach(() => {
    for (const directory of tempDirectories.splice(0)) {
        rmSync(directory, {recursive: true, force: true});
    }
});

function executable(file: string, contents: string): void {
    writeFileSync(file, contents);
    chmodSync(file, 0o755);
}

function runSubmission(
    workflowName?: string,
    options: {
        preflightReport?: Record<string, unknown>;
        prepareOnly?: boolean;
        namespace?: string;
    } = {},
) {
    const directory = mkdtempSync(path.join(tmpdir(), "workflow-submit-script-"));
    tempDirectories.push(directory);
    const bin = path.join(directory, "bin");
    const config = path.join(directory, "config.yaml");
    const manifest = path.join(directory, "workflow.yaml");
    const initializerArgs = path.join(directory, "initializer-args.txt");
    const operations = path.join(directory, "operations.txt");
    const preflightArgs = path.join(directory, "preflight-args.txt");
    const preflightReport = path.join(directory, "preflight-report.json");
    const initializerCount = path.join(directory, "initializer-count.txt");
    const preparedDir = path.join(directory, "prepared");
    require("fs").mkdirSync(bin);
    writeFileSync(config, "sourceClusters: {}\n");

    const initializer = path.join(bin, "initialize");
    executable(initializer, `#!/bin/bash
set -euo pipefail
printf 'initialize\\n' >> "$INITIALIZER_COUNT_FILE"
printf '%s\\n' "$@" > "$INITIALIZER_ARGS_FILE"
output_dir=""
while [[ $# -gt 0 ]]; do
    if [[ "$1" == "--output-dir" ]]; then
        output_dir="$2"
        shift 2
    else
        shift
    fi
done
printf '{}\\n' > "$output_dir/workflowMigration.config.yaml"
printf '{"allowed":true,"checkedResources":0,"issues":[]}\\n' > "$output_dir/submissionPreflight.json"
`);
executable(path.join(bin, "preflight"), `#!/bin/bash
set -euo pipefail
printf 'preflight\\n' >> "$OPERATIONS_FILE"
printf '%s\\n' "$@" > "$PREFLIGHT_ARGS_FILE"
report_path=""
while [[ $# -gt 0 ]]; do
    if [[ "$1" == "--output" ]]; then
        report_path="$2"
        shift 2
    else
        shift
    fi
done
cp "$PREFLIGHT_REPORT_FILE" "$report_path"
`);
    executable(path.join(bin, "kubectl"), `#!/bin/bash
set -euo pipefail
printf 'kubectl %s\\n' "$*" >> "$OPERATIONS_FILE"
cat > "$KUBECTL_MANIFEST_FILE"
name="$(awk '/^  name: / {print $2; exit}' "$KUBECTL_MANIFEST_FILE")"
echo "workflow.argoproj.io/$name created"
`);
    writeFileSync(preflightReport, `${JSON.stringify(
        options.preflightReport ?? {
            formatVersion: 1,
            allowed: true,
            checkedResources: 0,
            issues: [],
        },
    )}\n`);

    const script = path.resolve(
        __dirname,
        "../scripts/createMigrationWorkflowFromUserConfiguration.sh",
    );
    const args = [config];
    if (workflowName) args.push("--workflow-name", workflowName);
    if (options.namespace) args.push("--namespace", options.namespace);
    if (options.prepareOnly) {
        args.push("--prepare-only", preparedDir);
    }
    const environment = {
        ...process.env,
        INITIALIZE_CMD: initializer,
        INITIALIZER_ARGS_FILE: initializerArgs,
        INITIALIZER_COUNT_FILE: initializerCount,
        KUBECTL_MANIFEST_FILE: manifest,
        OPERATIONS_FILE: operations,
        PREFLIGHT_CMD: path.join(bin, "preflight"),
        PREFLIGHT_ARGS_FILE: preflightArgs,
        PREFLIGHT_REPORT_FILE: preflightReport,
        NODEJS: process.execPath,
        PATH: `${bin}:${process.env.PATH ?? ""}`,
    };
    const result = spawnSync(script, args, {
        encoding: "utf8",
        env: environment,
    });
    return {
        result,
        manifest: existsSync(manifest) ? readFileSync(manifest, "utf8") : "",
        initializerArgs: (existsSync(initializerArgs)
            ? readFileSync(initializerArgs, "utf8")
            : "")
            .trim()
            .split("\n"),
        operations: existsSync(operations)
            ? readFileSync(operations, "utf8").trim().split("\n")
            : [],
        preflightArgs: existsSync(preflightArgs)
            ? readFileSync(preflightArgs, "utf8").trim().split("\n")
            : [],
        initializerCount,
        preparedDir,
        config,
        script,
        environment,
    };
}

describe("workflow submission script", () => {
    it.each([
        ["the default", undefined, "migration-workflow"],
        ["a requested", "team-migration", "team-migration"],
    ])("uses %s workflow name consistently", (
        _description,
        requested,
        expected,
    ) => {
        const output = runSubmission(requested);

        expect(output.result.status).toBe(0);
        expect(output.result.stderr).toBe("");
        expect(output.manifest).toContain(`  name: ${expected}\n`);
        expect(output.manifest).toContain(
            `migrations.opensearch.org/workflow-name: "${expected}"`,
        );
        const workflowNameIndexes = output.initializerArgs
            .map((argument, index) => argument === "--workflow-name" ? index : -1)
            .filter(index => index >= 0);
        expect(workflowNameIndexes).toHaveLength(1);
        expect(output.initializerArgs[workflowNameIndexes[0] + 1]).toBe(expected);
        expect(output.operations[0]).toBe("preflight");
        expect(output.operations[1]).toContain("kubectl create");
    });

    it("does not mutate Kubernetes when preflight blocks submission", () => {
        const output = runSubmission(undefined, {
            preflightReport: {
                formatVersion: 1,
                allowed: false,
                checkedResources: 1,
                issues: [{
                    kind: "CapturedTraffic",
                    name: "p2-topic",
                    classification: "recreate-required",
                    blocking: true,
                    message: "sourceLabel cannot be changed",
                    source: "projection-policy",
                }],
            },
        });

        expect(output.result.status).toBe(2);
        expect(output.operations).toEqual(["preflight"]);
        expect(output.manifest).toBe("");
    });

    it("commits a prepared bundle without running initialization again", () => {
        const prepared = runSubmission(undefined, {prepareOnly: true});

        expect(prepared.result.status).toBe(0);
        expect(prepared.operations).toEqual(["preflight"]);
        const committed = spawnSync(
            prepared.script,
            [
                prepared.config,
                "--commit-prepared",
                prepared.preparedDir,
            ],
            {
                encoding: "utf8",
                env: prepared.environment,
            },
        );

        expect(committed.status).toBe(0);
        expect(readFileSync(prepared.initializerCount, "utf8")
            .trim().split("\n")).toEqual(["initialize"]);
        expect(readFileSync(
            prepared.environment.OPERATIONS_FILE,
            "utf8",
        ).trim().split("\n")).toEqual([
            "preflight",
            "kubectl create -f -",
        ]);
    });

    it("uses the requested namespace for preflight and workflow creation", () => {
        const output = runSubmission("team-migration", {
            namespace: "migration-team",
        });

        expect(output.result.status).toBe(0);
        const namespaceIndex = output.preflightArgs.indexOf("--namespace");
        expect(namespaceIndex).toBeGreaterThanOrEqual(0);
        expect(output.preflightArgs[namespaceIndex + 1]).toBe("migration-team");
        expect(output.operations).toEqual([
            "preflight",
            "kubectl --namespace migration-team create -f -",
        ]);
    });
});
