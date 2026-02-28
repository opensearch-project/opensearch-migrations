import { submitProbe } from "../infra/probeHelper.js";
import { ParitySpec, reportContractResult } from "../infra/parityHelper.js";

describe("Record Operations - sprig.dict creates object", () => {
  const spec: ParitySpec = {
    category: "Record Operations",
    name: "sprig.dict creates object",
    argoExpression: "toJson(sprig.dict('a', '1', 'b', '2'))",
  };

  describe("ArgoYaml", () => {
    test("raw expression produces expected result", async () => {
      const result = await submitProbe({ expression: spec.argoExpression });
      expect(result.phase).toBe("Succeeded");
      expect(JSON.parse(result.globalOutputs.result)).toEqual({ a: "1", b: "2" });
      reportContractResult(spec, result);
    });
  });
});

describe.skip("Record Operations - deferred contract cases", () => {
  test("sprig.merge combines objects", () => {});
  test("sprig.omit removes keys", () => {});
  test("sprig.dig with default for missing key", () => {});
  test("sprig.dig extracts nested value", () => {});
  test("keys returns array of keys", () => {});
  test("'in' operator checks key existence true", () => {});
  test("'in' operator checks key existence false", () => {});
  test("bracket notation for key access", () => {});
  test("bracket notation with special characters in key", () => {});
});
