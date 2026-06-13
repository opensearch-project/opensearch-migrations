/**
 * Shared test setup. Mirrors the config-processor's setupUnifiedSchemaPath
 * so that any test that resolves `@opensearch-migrations/schemas` via
 * the `UNIFIED_SCHEMA_PATH_ENV` indirection has a real schema on disk.
 */

import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";

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
    "minimal-openapi.json",
);
const outputPath = path.join(
    os.tmpdir(),
    "orchestrationSpecs-e2e-orchestration-tests-unified-schema.json",
);

const { schema } = buildUnifiedSchema({ strimziSchemaPath: strimziFixturePath });
fs.writeFileSync(outputPath, JSON.stringify(schema, null, 2) + "\n");
process.env[UNIFIED_SCHEMA_PATH_ENV] = outputPath;
