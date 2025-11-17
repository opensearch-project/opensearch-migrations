import { z, ZodTypeAny } from 'zod';
import {parse} from "yaml";
import * as fs from "node:fs";
import {OVERALL_MIGRATION_CONFIG, zodSchemaToJsonSchema} from "@opensearch-migrations/schemas";
import {Console} from "console";
import {parseUserConfig} from "./userConfigReader";
import {makeLockedConfigSchema} from "./constrainUserSchema";

global.console = new Console({
    stdout: process.stderr,
    stderr: process.stderr
});

const COMMAND_LINE_HELP_MESSAGE = `Usage: format-approvals [input-file|-]

Convert a user configuration into the approval configmap, pulling all of the approval flags
from the configuration and denormalizing them for each approval step so that the workflow
can do a simple check for each approval (suspension) step.
`

export async function main() {
    const args = process.argv.slice(2);

    if (args.length === 0) {
        console.error("Error: no args provided.");
        console.error(COMMAND_LINE_HELP_MESSAGE);
        process.exit(2);
    }

    let data
    try {
        data = await parseUserConfig(args[0]);
    } catch (error) {
        console.error('Error loading YAML:', error);
        console.error(COMMAND_LINE_HELP_MESSAGE);
        process.exit(3);
    }


    // process.stdout.write(JSON.stringify(output, null, 2));
}

if (require.main === module) {
    main().catch((error) => {
        console.error('Unhandled error:', error);
        process.exit(1);
    });
}
