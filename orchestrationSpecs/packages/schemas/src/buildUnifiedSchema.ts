import fs from "node:fs";
import path from "node:path";
import {buildUnifiedSchema, getFallbackUnifiedSchemaPath} from "./unifiedSchemaBuilder";

const HELP = `
Usage: build-unified-schema [--output <file>] [--strimzi-openapi <file>]

Build the unified migration configuration JSON schema.

Arguments:
  --output <file>           Write the schema to this path.
  --strimzi-openapi <file>  Use a Strimzi OpenAPI document to inline full Strimzi spec schemas.
`.trim();

export async function main() {
    const args = process.argv.slice(2);
    let outputPath = getFallbackUnifiedSchemaPath();
    let strimziSchemaPath: string | undefined;

    for (let i = 0; i < args.length; i++) {
        const arg = args[i];
        if (arg === "--help" || arg === "-h") {
            console.log(HELP);
            return;
        } else if (arg === "--output" && i + 1 < args.length) {
            outputPath = path.resolve(args[++i]);
        } else if (arg === "--strimzi-openapi" && i + 1 < args.length) {
            strimziSchemaPath = path.resolve(args[++i]);
        } else {
            throw new Error(`Unknown argument: ${arg}`);
        }
    }

    const unified = buildUnifiedSchema({
        strimziSchemaPath,
    });

    fs.mkdirSync(path.dirname(outputPath), {recursive: true});
    fs.writeFileSync(outputPath, JSON.stringify(unified.schema, null, 2) + "\n");
    console.error(`Wrote ${unified.mode} unified schema to ${outputPath}`);
}

if (require.main === module && !process.env.SUPPRESS_AUTO_LOAD) {
    main().catch((error) => {
        console.error(error);
        process.exit(1);
    });
}
