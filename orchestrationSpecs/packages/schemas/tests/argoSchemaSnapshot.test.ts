import {
    ARGO_MIGRATION_CONFIG,
    OVERALL_MIGRATION_CONFIG, zodSchemaToJsonSchema
} from "../src";
import {z} from "zod";

describe('test schemas matches expected', () => {
    it("argo schema matches expected", () => {
        const schema = zodSchemaToJsonSchema(z.array(ARGO_MIGRATION_CONFIG))
        // let the test name be the snapshot key
        expect(JSON.stringify(schema, null, 2)).toMatchSnapshot();
    });

    it("argo user matches expected", () => {
        const schema = zodSchemaToJsonSchema(OVERALL_MIGRATION_CONFIG)
        // let the test name be the snapshot key
        expect(JSON.stringify(schema, null, 2)).toMatchSnapshot();
    });
});
