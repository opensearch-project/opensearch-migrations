import path from "node:path";
import {
    ARGO_MIGRATION_CONFIG,
  buildUnifiedSchema,
    zodSchemaToJsonSchema
} from "../src";
import {z} from "zod";

const strimziFixturePath = path.resolve(__dirname, "fixtures", "strimzi", "minimal-openapi.json");

describe('test schemas matches expected', () => {
    it("argo schema matches expected", () => {
        const schema = zodSchemaToJsonSchema(z.array(ARGO_MIGRATION_CONFIG))
        // let the test name be the snapshot key
        expect(JSON.stringify(schema, null, 2)).toMatchSnapshot();
    });

    it("argo user matches expected", () => {
        const schema = buildUnifiedSchema({strimziSchemaPath: strimziFixturePath}).schema;
        // let the test name be the snapshot key
        expect(JSON.stringify(schema, null, 2)).toMatchSnapshot();
    });
});
