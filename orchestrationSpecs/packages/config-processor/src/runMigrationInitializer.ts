import {ARGO_WORKFLOW_SCHEMA} from "@opensearch-migrations/schemas";
import {MigrationInitializer} from "./migrationInitializer";
import {MigrationConfigTransformer} from "./migrationConfigTransformer";
import {parse} from "yaml";
import {Console} from "console";
import {formatInputValidationError, InputValidationError} from "./streamSchemaTransformer";

global.console = new Console({
    stdout: process.stderr,
    stderr: process.stderr
});

const COMMAND_LINE_HELP_MESSAGE = `
Usage: initialize-workflow [options] [input-file]

Initialize migration workflow in etcd.  
When the configuration is provided in the user-schema with the --user-config option, 
that configuration will first be validated and transformed before doing the initialization.

Arguments:
  --user-config <file>         (stdin: '-') User-specified YAML/JSON configuration file ('-' for stdin)
  --transformed-config <file>  (stdin: '-') Workflow-ready YAML/JSON configuration file (output of MigrationConfigTransformer)
  --output-dir <dir>           Directory to write output files (workflowMigration.config.yaml, approvalConfigMaps.yaml, concurrencyConfigMaps.yaml)

  -h, --help               Show this help message
`;

async function parseInput(inputFile: string) {
    if (inputFile === '-') { // stdin
        const chunks: Buffer[] = [];
        for await (const chunk of process.stdin) {
            chunks.push(chunk);
        }
        const data = Buffer.concat(chunks).toString('utf-8');
        return parse(data);
    } else { // file
        const fs = await import('fs/promises');
        const data = await fs.readFile(inputFile, 'utf-8');
        return parse(data);
    }
}

export async function main() {
    const args = process.argv.slice(2);

    if (args.includes('--help') || args.includes('-h')) {
        process.stderr.write(COMMAND_LINE_HELP_MESSAGE);
        process.exit(1);
    }

    // Parse command line arguments
    let userConfigFile = process.env.USER_WORKFLOW_CONFIGURATION;
    let workflowConfigFile = process.env.TRANSFORMED_WORKFLOW_CONFIGURATION;
    let outputDir: string | undefined;

    for (let i = 0; i < args.length; i++) {
        const arg = args[i];

        if (arg === '--user-config' && i + 1 < args.length) {
            userConfigFile = args[++i];
        } else if (arg === '--transformed-config' && i + 1 < args.length) {
            workflowConfigFile = args[++i];
        } else if (arg === '--output-dir' && i + 1 < args.length) {
            outputDir = args[++i];
        }
    }

    // Verify that either configJson or inputFile is provided
    if (!userConfigFile && !workflowConfigFile) {
        console.error('Error: No configuration source provided.');
        console.error('\nYou must provide either --user-config or --transformed-config');
        process.stderr.write(COMMAND_LINE_HELP_MESSAGE);
        process.exit(3);
    } else if (userConfigFile && workflowConfigFile) {
        console.error('Error: Multiple configuration sources provided.');
        console.error('\nYou must provide ONE of--user-config or --transformed-config');
        process.stderr.write(COMMAND_LINE_HELP_MESSAGE);
        process.exit(4);
    }

    try {
        const workflows: ARGO_WORKFLOW_SCHEMA = await (async ()=>{
            if (userConfigFile) {
                const processor = new MigrationConfigTransformer();
                const userConfig = await parseInput(userConfigFile) as any;
                return processor.processFromObject(userConfig);
            } else if (workflowConfigFile) {
                return parseInput(workflowConfigFile) as any;
            } else {
                throw new Error("Neither userConfigFile nor workflowConfigFile found");
            }
        }) ();

        // Generate output files
        if (outputDir) {
            const initializer = new MigrationInitializer();
            await initializer.generateOutputFiles(workflows, outputDir, userConfigFile ? await parseInput(userConfigFile) : null);
        }

        // Output transformed workflow to stdout if no output directory specified - ignoring auxiliary configmap values
        if (!outputDir) {
            process.stdout.write(JSON.stringify(workflows, null, 2));
        }
    } catch (error) {
        if (error instanceof InputValidationError) {
            console.error('Validation error:');
            console.error(formatInputValidationError(error));
            process.exit(1);
        } else if (error instanceof SyntaxError) {
            console.error('JSON parsing error:', error.message);
            process.exit(1);
        } else if (error instanceof Error) {
            console.error('Error:', error);
            process.exit(1);
        } else {
            console.error('Unknown error:', error);
            process.exit(1);
        }
    }
}

// Run if executed directly
if (require.main === module && !process.env.SUPPRESS_AUTO_LOAD) {
    main().catch(error => {
        console.error('Fatal error:', error);
        process.exit(1);
    });
}
