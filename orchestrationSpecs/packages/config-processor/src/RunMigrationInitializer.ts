import {ARGO_WORKFLOW_SCHEMA} from "@opensearch-migrations/schemas";
import {EtcdOptions, MigrationInitializer} from "./MigrationInitializer";

async function main() {
    const args = process.argv.slice(2);

    if (args.includes('--help') || args.includes('-h')) {
        console.log(`
Usage: initialize-workflow [options] [input-file]

Initialize migration workflow in etcd.

Arguments:
  input-file                Path to input JSON file ('-' for stdin)

Options:
  --config <json>           JSON configuration string

  --etcd-endpoints <urls>   Comma-separated etcd endpoints (env: ETCD_ENDPOINTS)
  --prefix <prefix>         Workflow prefix for etcd keys (env: PREFIX)
  --etcd-user <user>        Username for etcd authentication (env: ETCD_USER)
  --etcd-password <pass>    Password for etcd authentication (env: ETCD_PASSWORD)
  
  -h, --help               Show this help message
`);
        process.exit(0);
    }

    // Parse command line arguments
    let etcdEndpoints = process.env.ETCD_ENDPOINTS;
    let etcdUser = process.env.ETCD_USER;
    let etcdPassword = process.env.ETCD_PASSWORD;
    let prefix = process.env.PREFIX;
    let configJson = process.env.CONFIGURATION;
    let inputFile: string | null = null;

    for (let i = 0; i < args.length; i++) {
        const arg = args[i];

        if (arg === '--etcd-endpoints' && i + 1 < args.length) {
            etcdEndpoints = args[++i];
        } else if (arg === '--etcd-user' && i + 1 < args.length) {
            etcdUser = args[++i];
        } else if (arg === '--etcd-password' && i + 1 < args.length) {
            etcdPassword = args[++i];
        } else if (arg === '--prefix' && i + 1 < args.length) {
            prefix = args[++i];
        } else if (arg === '--config' && i + 1 < args.length) {
            configJson = args[++i];
        } else if (!arg.startsWith('--')) {
            inputFile = arg;
        }
    }

    // Verify required etcd configuration values are set
    const missingVars: string[] = [];

    if (!etcdEndpoints) {
        missingVars.push('ETCD_ENDPOINTS (or --etcd-endpoints)');
    }
    if (!etcdUser) {
        missingVars.push('ETCD_USER (or --etcd-user)');
    }
    if (!etcdPassword) {
        missingVars.push('ETCD_PASSWORD (or --etcd-password)');
    }
    if (!prefix) {
        missingVars.push('PREFIX (or --prefix)');
    }

    if (missingVars.length > 0) {
        console.error('Error: Missing required configuration values:');
        missingVars.forEach(varName => {
            console.error(`  - ${varName}`);
        });
        console.error('\nPlease provide these via environment variables or command line arguments.');
        console.error('Run with --help for usage information.');
        process.exit(1);
    }

    // Verify that either configJson or inputFile is provided
    if (!configJson && !inputFile) {
        console.error('Error: No configuration source provided.');
        console.error('\nYou must provide configuration via one of:');
        console.error('  - Input file path as argument');
        console.error('  - stdin by passing "-" as argument');
        console.error('  - --config flag with inline JSON');
        console.error('  - CONFIGURATION environment variable');
        console.error('\nRun with --help for usage information.');
        process.exit(1);
    }

    try {
        let workflows: ARGO_WORKFLOW_SCHEMA;

        // Load configuration from file, stdin, or command line
        if (inputFile) {
            if (inputFile === '-') {
                // Read from stdin
                const chunks: Buffer[] = [];
                for await (const chunk of process.stdin) {
                    chunks.push(chunk);
                }
                const data = Buffer.concat(chunks).toString('utf-8');
                workflows = JSON.parse(data);
            } else {
                // Read from file
                const fs = await import('fs/promises');
                const data = await fs.readFile(inputFile, 'utf-8');
                workflows = JSON.parse(data);
            }
        } else if (configJson) {
            workflows = JSON.parse(configJson);
        } else {
            console.error('Error: Must provide configuration via --config, input file, or stdin');
            console.error('Run with --help for usage information');
            process.exit(1);
        }

        const initializer = new MigrationInitializer({
                endpoints: [etcdEndpoints as string],
                username: etcdUser as string,
                password: etcdPassword as string
            },
            prefix as string
        );

        try {
            await initializer.initializeWorkflow(workflows);
        } finally {
            await initializer.close();
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
if (require.main === module) {
    main().catch(error => {
        console.error('Fatal error:', error);
        process.exit(1);
    });
}
