"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
var src_1 = require("../src");
var zod_1 = require("zod");
describe('test schemas matches expected', function () {
    it("argo schema matches expected", function () {
        var schema = (0, src_1.zodSchemaToJsonSchema)(zod_1.z.array(src_1.PARAMETERIZED_MIGRATION_CONFIG));
        // let the test name be the snapshot key
        expect(JSON.stringify(schema, null, 2)).toMatchSnapshot();
    });
    it("argo user matches expected", function () {
        var schema = (0, src_1.zodSchemaToJsonSchema)(src_1.OVERALL_MIGRATION_CONFIG);
        // let the test name be the snapshot key
        expect(JSON.stringify(schema, null, 2)).toMatchSnapshot();
    });
});
