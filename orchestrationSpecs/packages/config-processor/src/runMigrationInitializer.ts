import {ARGO_WORKFLOW_SCHEMA} from "@opensearch-migrations/schemas";
import {MigrationInitializer} from "./migrationInitializer";
import {MigrationConfigTransformer} from "./migrationConfigTransformer";
import {parse} from "yaml";
import {Console} from "console";

global.console = new Console({
    stdout: process.stderr,
    stderr: process.stderr
});

const COMMAND_LINE_HELP_MESSAGE = `
Usage: initialize-workflow [options] [input-file]

Initialize migration workflow in etcd.  
When the configuration is provided in the user-schema with the --user-config option, 
that configuration will first be validated and transformed before doing the initialization.
The transformed user configuration will also be output to stdout.  

Arguments:
  --user-config <file>         (stdin: '-') User-specified YAML/JSON configuration file ('-' for stdin)
  --transformed-config <file>  (stdin: '-') Workflow-ready YAML/JSON configuration file (output of MigrationConfigTransformer)
  --etcd-endpoints <urls>      Comma-separated etcd endpoints (env: ETCD_ENDPOINTS)
  --unique-run-nonce <string>  Value to disambiguate workflow instances for snapshot names, keys, etc (env: UNIQUE_RUN_NONCE)
  --etcd-user <user>           Username for etcd authentication (env: ETCD_USER)
  --etcd-password <pass>       Password for etcd authentication (env: ETCD_PASSWORD)

Options:
  --skip-initialize            Only do transformation of user-config. Does no processing if passed a transformed config.            
  --silent                     Suppress outputting the workflow configuration to stdout            
  
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
    let etcdEndpoints = process.env.ETCD_ENDPOINTS;
    let etcdUser = process.env.ETCD_USER;
    let etcdPassword = process.env.ETCD_PASSWORD;
    let uniqueRunNonce = process.env.UNIQUE_RUN_NONCE;
    let userConfigFile = process.env.USER_WORKFLOW_CONFIGURATION;
    let workflowConfigFile = process.env.TRANSFORMED_WORKFLOW_CONFIGURATION;
    let skipInitialize = false;
    let silent = false;

    for (let i = 0; i < args.length; i++) {
        const arg = args[i];

        if (arg === '--etcd-endpoints' && i + 1 < args.length) {
            etcdEndpoints = args[++i];
        } else if (arg === '--etcd-user' && i + 1 < args.length) {
            etcdUser = args[++i];
        } else if (arg === '--etcd-password' && i + 1 < args.length) {
            etcdPassword = args[++i];
        } else if (arg === '--unique-run-nonce' && i + 1 < args.length) {
            uniqueRunNonce = args[++i];
        } else if (arg === '--user-config' && i + 1 < args.length) {
            userConfigFile = args[++i];
        } else if (arg === '--transformed-config' && i + 1 < args.length) {
            workflowConfigFile = args[++i];
        } else if (arg === '--skip-initialize') {
            skipInitialize = true;
        } else if (arg === '--silent') {
            silent = true;
        }
    }

    // Verify required etcd configuration values are set
    const missingVars: string[] = [];

    if (!skipInitialize) {
        if (!etcdEndpoints) {
            missingVars.push('ETCD_ENDPOINTS (or --etcd-endpoints)');
        }
        if (!uniqueRunNonce) {
            missingVars.push('UNIQUE_RUN_NONCE (or --unique-run-nonce)');
        }
    }

    if (missingVars.length > 0) {
        console.error('Error: Missing required configuration values:');
        missingVars.forEach(varName => {
            console.error(`  - ${varName}`);
        });
        console.error('\nPlease provide these via environment variables or command line arguments.');
        console.error('Run with --help for usage information.');
        process.exit(2);
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

        if (!skipInitialize) {
            const initializer = new MigrationInitializer({
                    endpoints: [etcdEndpoints as string],
                    ...(!etcdUser || !etcdPassword ? {} : {
                        auth: {
                            username: etcdUser as string,
                            password: etcdPassword as string
                        }
                    })
                },
                uniqueRunNonce as string
            );

            try {
                await initializer.initializeWorkflow(workflows);
            } finally {
                await initializer.close();
            }
        }

        // PRINT THE TRANSFORMED RESULTS SO THAT THE WORKFLOW CAN BE CREATED FROM THEM!
        if (!silent) {
            process.stdout.write(JSON.stringify(workflows, null, 2));
        }
    } catch (error) {
        if (error instanceof SyntaxError) {
            console.error('JSON parsing error:', error.message);
            process.exit(1);
        } else if (error instanceof Error) {
            console.error('Error:', error.message);
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
