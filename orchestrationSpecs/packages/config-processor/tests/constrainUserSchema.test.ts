import {OVERALL_MIGRATION_CONFIG, zodSchemaToJsonSchema} from "@opensearch-migrations/schemas";
import {makeLockedConfigSchema} from "../src/constrainUserSchema";
import {parse} from "yaml";

const SIMPLE_TARGET_ONLY_CONFIG = `{
  sourceClusters: {
    "source1": {
      "endpoint": "https://source:9200",
    }
  },
  targetClusters: {
    "target1": {
      "endpoint": "https://target:9200",
      "authConfig": {
        "basic": {
          "secretName": "target1-creds",
          "username": "admin",
          "password": "admin"
        }                   
      }
    }
  },
  migrationConfigs: [
    {
      "fromSource": "source1",
      "toTarget": "target1"
    }
  ]
}`;

describe("paramsFns runtime validation", () => {
    it("simple transform matches expected", () => {
        const lockedZod = makeLockedConfigSchema(parse(SIMPLE_TARGET_ONLY_CONFIG), OVERALL_MIGRATION_CONFIG);
        const lockedSchema = zodSchemaToJsonSchema(lockedZod as any);
        expect(lockedSchema).toMatchSnapshot();
    });
});