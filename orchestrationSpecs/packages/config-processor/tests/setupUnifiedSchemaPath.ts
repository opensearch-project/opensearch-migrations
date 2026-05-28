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
const workerId = process.env.JEST_WORKER_ID ?? String(process.pid);
const outputPath = path.join(
    os.tmpdir(),
    `orchestrationSpecs-config-processor-test-unified-schema-${workerId}-${process.pid}.json`
);
const tempPath = `${outputPath}.tmp`;
const {schema} = buildUnifiedSchema({strimziSchemaPath: strimziFixturePath});

fs.writeFileSync(tempPath, JSON.stringify(schema, null, 2) + "\n");
fs.renameSync(tempPath, outputPath);
process.env[UNIFIED_SCHEMA_PATH_ENV] = outputPath;
