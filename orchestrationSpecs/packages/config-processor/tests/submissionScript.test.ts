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

function runSubmission(workflowName?: string) {
    const directory = mkdtempSync(path.join(tmpdir(), "workflow-submit-script-"));
    tempDirectories.push(directory);
    const bin = path.join(directory, "bin");
    const config = path.join(directory, "config.yaml");
    const manifest = path.join(directory, "workflow.yaml");
    const initializerArgs = path.join(directory, "initializer-args.txt");
    require("fs").mkdirSync(bin);
    writeFileSync(config, "sourceClusters: {}\n");

    const initializer = path.join(bin, "initialize");
    executable(initializer, `#!/bin/bash
set -euo pipefail
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
`);
    executable(path.join(bin, "kubectl"), `#!/bin/bash
set -euo pipefail
cat > "$KUBECTL_MANIFEST_FILE"
name="$(awk '/^  name: / {print $2; exit}' "$KUBECTL_MANIFEST_FILE")"
echo "workflow.argoproj.io/$name created"
`);

    const script = path.resolve(
        __dirname,
        "../scripts/createMigrationWorkflowFromUserConfiguration.sh",
    );
    const args = [config];
    if (workflowName) args.push("--workflow-name", workflowName);
    const result = spawnSync(script, args, {
        encoding: "utf8",
        env: {
            ...process.env,
            INITIALIZE_CMD: initializer,
            INITIALIZER_ARGS_FILE: initializerArgs,
            KUBECTL_MANIFEST_FILE: manifest,
            NODEJS: process.execPath,
            PATH: `${bin}:${process.env.PATH ?? ""}`,
        },
    });
    return {
        result,
        manifest: existsSync(manifest) ? readFileSync(manifest, "utf8") : "",
        initializerArgs: (existsSync(initializerArgs)
            ? readFileSync(initializerArgs, "utf8")
            : "")
            .trim()
            .split("\n"),
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
    });
});
