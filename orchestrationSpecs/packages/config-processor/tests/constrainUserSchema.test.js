"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
var schemas_1 = require("@opensearch-migrations/schemas");
var constrainUserSchema_1 = require("../src/constrainUserSchema");
var yaml_1 = require("yaml");
var SIMPLE_TARGET_ONLY_CONFIG = "{\n  sourceClusters: {\n    \"source1\": {\n      \"endpoint\": \"https://source:9200\",\n    }\n  },\n  targetClusters: {\n    \"target1\": {\n      \"endpoint\": \"https://target:9200\",\n      \"authConfig\": {\n        \"basic\": {\n          \"secretName\": \"target1-creds\",\n          \"username\": \"admin\",\n          \"password\": \"admin\"\n        }                   \n      }\n    }\n  },\n  migrationConfigs: [\n    {\n      \"fromSource\": \"source1\",\n      \"toTarget\": \"target1\"\n    }\n  ]\n}";
describe("paramsFns runtime validation", function () {
    it("simple transform matches expected", function () {
        var lockedZod = (0, constrainUserSchema_1.makeLockedConfigSchema)((0, yaml_1.parse)(SIMPLE_TARGET_ONLY_CONFIG), schemas_1.OVERALL_MIGRATION_CONFIG);
        var lockedSchema = (0, schemas_1.zodSchemaToJsonSchema)(lockedZod);
        expect(lockedSchema).toMatchSnapshot();
    });
});
