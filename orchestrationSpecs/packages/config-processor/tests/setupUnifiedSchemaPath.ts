import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import {
    buildUnifiedSchema,
    UNIFIED_SCHEMA_PATH_ENV,
} from "@opensearch-migrations/schemas";

const strimziFixturePath = path.resolve(
    __dirname,
    "..",
    "..",
    "schemas",
    "tests",
    "fixtures",
    "strimzi",
    "minimal-openapi.json"
);
const outputPath = path.join(os.tmpdir(), "orchestrationSpecs-config-processor-test-unified-schema.json");
const {schema} = buildUnifiedSchema({strimziSchemaPath: strimziFixturePath});

fs.writeFileSync(outputPath, JSON.stringify(schema, null, 2) + "\n");
process.env[UNIFIED_SCHEMA_PATH_ENV] = outputPath;
