import {ARGO_MIGRATION_CONFIG_PRE_ENRICH} from "@opensearch-migrations/schemas";
import {parse} from "yaml";
import {Console} from "console";
import * as fs from "fs/promises";
import {MigrationConfigTransformer} from "./migrationConfigTransformer";
import {buildResolvedMigrationResources} from "./resolvedMigrationResources";

global.console = new Console({
    stdout: process.stderr,
    stderr: process.stderr,
});

const COMMAND_LINE_HELP_MESSAGE = `
Usage: resolveMigrationResources [options]

Resolve migration resources from either the user workflow config or the workflow-ready Argo config.

Arguments:
  --user-config <file>         (stdin: '-') User-specified YAML/JSON configuration file
  --transformed-config <file>  (stdin: '-') Workflow-ready YAML/JSON configuration file
  --workflow-name <name>       Optional workflow name to include in the artifact
  --output <file>              Optional output file. Defaults to stdout.
  --include-parameter-policies Include debug field policy metadata for each resource parameter

  -h, --help                   Show this help message
`;

async function parseInput(inputFile: string) {
    if (inputFile === "-") {
        const chunks: Buffer[] = [];
        for await (const chunk of process.stdin) {
            chunks.push(chunk);
        }
        return parse(Buffer.concat(chunks).toString("utf-8"));
    }

    return parse(await fs.readFile(inputFile, "utf-8"));
}

export async function main(args = process.argv.slice(2)) {
    if (args.includes("--help") || args.includes("-h")) {
        process.stderr.write(COMMAND_LINE_HELP_MESSAGE);
        process.exit(1);
    }

    let userConfigFile: string | undefined;
    let workflowConfigFile: string | undefined;
    let workflowName: string | undefined;
    let outputFile: string | undefined;
    let includeParameterPolicies = false;

    for (let i = 0; i < args.length; i++) {
        const arg = args[i];
        if (arg === "--user-config" && i + 1 < args.length) {
            userConfigFile = args[++i];
        } else if (arg === "--transformed-config" && i + 1 < args.length) {
            workflowConfigFile = args[++i];
        } else if (arg === "--workflow-name" && i + 1 < args.length) {
            workflowName = args[++i];
        } else if (arg === "--output" && i + 1 < args.length) {
            outputFile = args[++i];
        } else if (arg === "--include-parameter-policies") {
            includeParameterPolicies = true;
        } else {
            console.error(`Error: unknown arg: \`${arg}\`.`);
            process.stderr.write(COMMAND_LINE_HELP_MESSAGE);
            process.exit(5);
        }
    }

    if (!userConfigFile && !workflowConfigFile) {
        console.error("Error: No configuration source provided.");
        process.stderr.write(COMMAND_LINE_HELP_MESSAGE);
        process.exit(3);
    }
    if (userConfigFile && workflowConfigFile) {
        console.error("Error: Multiple configuration sources provided.");
        process.stderr.write(COMMAND_LINE_HELP_MESSAGE);
        process.exit(4);
    }

    const workflowConfig = userConfigFile
        ? await new MigrationConfigTransformer().processFromObject(await parseInput(userConfigFile))
        : ARGO_MIGRATION_CONFIG_PRE_ENRICH.parse(await parseInput(workflowConfigFile!));
    const contents = JSON.stringify(
        buildResolvedMigrationResources(workflowConfig, workflowName, {includeParameterPolicies}),
        null,
        2
    );

    if (outputFile) {
        await fs.writeFile(outputFile, contents);
    } else {
        process.stdout.write(contents);
    }
}

if (require.main === module && !process.env.SUPPRESS_AUTO_LOAD) {
    main().catch(error => {
        console.error("Fatal error:", error);
        process.exit(1);
    });
}
