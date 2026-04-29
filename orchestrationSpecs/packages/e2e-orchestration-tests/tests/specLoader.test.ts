import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";

import { SpecLoadError, loadScenarioSpec, parseScenarioSpec } from "../src/specLoader";

const MIN_SPEC_YAML = `
baseConfig: ./baseline.wf.yaml
matrix:
  subject: proxy:capture-proxy
`;

const FULL_SPEC_YAML = `
baseConfig: ../../baseline.wf.yaml
phaseCompletionTimeoutSeconds: 1200
matrix:
  subject: proxy:capture-proxy
  select:
    - changeClass: safe
      patterns: [subject-change]
    - changeClass: gated
      patterns: [subject-gated-change]
      response: approve
    - changeClass: impossible
      patterns: [subject-impossible-change]
      response: reset-then-approve
lifecycle:
  setup: [delete-target-indices]
  teardown: [delete-target-indices, delete-source-snapshots]
approvalGates:
  - approvePattern: "*.evaluateMetadata"
  - approvePattern: "*.migrateMetadata"
    validations: [compare-indices]
`;

describe("parseScenarioSpec", () => {
    it("parses a minimal spec and applies defaults", () => {
        const { spec, resolvedBaseConfigPath } = parseScenarioSpec(
            MIN_SPEC_YAML,
            "/tmp/specs",
        );
        expect(spec.baseConfig).toBe("./baseline.wf.yaml");
        expect(spec.phaseCompletionTimeoutSeconds).toBe(600);
        expect(spec.matrix.subject).toBe("proxy:capture-proxy");
        expect(spec.matrix.select).toBeUndefined();
        expect(spec.lifecycle).toEqual({ setup: [], teardown: [] });
        expect(spec.approvalGates).toEqual([]);
        expect(resolvedBaseConfigPath).toBe(path.resolve("/tmp/specs/baseline.wf.yaml"));
    });

    it("parses a full spec with selectors, gates, and lifecycle", () => {
        const { spec } = parseScenarioSpec(FULL_SPEC_YAML, "/tmp/specs");
        expect(spec.phaseCompletionTimeoutSeconds).toBe(1200);
        expect(spec.matrix.select).toHaveLength(3);
        expect(spec.matrix.select![1]).toEqual({
            changeClass: "gated",
            patterns: ["subject-gated-change"],
            response: "approve",
        });
        expect(spec.lifecycle.teardown).toEqual([
            "delete-target-indices",
            "delete-source-snapshots",
        ]);
        expect(spec.approvalGates[0].validations).toEqual([]);
        expect(spec.approvalGates[1].validations).toEqual(["compare-indices"]);
    });

    it("rejects unknown top-level keys (strict schema)", () => {
        expect(() =>
            parseScenarioSpec(
                MIN_SPEC_YAML + "\nunexpectedKey: 42\n",
                "/tmp/specs",
            ),
        ).toThrow(SpecLoadError);
    });

    it("rejects an invalid subject ComponentId", () => {
        const bad = `
baseConfig: ./x.yaml
matrix:
  subject: "NotAComponentId"
`;
        expect(() => parseScenarioSpec(bad, "/tmp/specs")).toThrow(SpecLoadError);
    });

    it("reports the failing path in the error message", () => {
        const bad = `
baseConfig: ./x.yaml
matrix:
  subject: proxy:capture-proxy
  select:
    - changeClass: not-a-class
      patterns: [subject-change]
`;
        try {
            parseScenarioSpec(bad, "/tmp/specs");
            fail("expected SpecLoadError");
        } catch (e) {
            expect(e).toBeInstanceOf(SpecLoadError);
            expect((e as SpecLoadError).message).toMatch(/matrix\.select\.0\.changeClass/);
        }
    });

    it("rejects non-YAML input", () => {
        expect(() => parseScenarioSpec("::this is not: yaml: either:\n", "/tmp/specs"))
            .toThrow(SpecLoadError);
    });
});

describe("loadScenarioSpec", () => {
    let tmpDir: string;
    beforeEach(() => {
        tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "e2e-spec-"));
    });
    afterEach(() => {
        fs.rmSync(tmpDir, { recursive: true, force: true });
    });

    it("reads a spec from disk and resolves baseConfig relative to the spec dir", () => {
        const specPath = path.join(tmpDir, "nested", "spec.test.yaml");
        fs.mkdirSync(path.dirname(specPath), { recursive: true });
        fs.writeFileSync(specPath, MIN_SPEC_YAML, "utf8");
        const loaded = loadScenarioSpec(specPath);
        expect(loaded.specDir).toBe(path.dirname(specPath));
        expect(loaded.resolvedBaseConfigPath).toBe(
            path.resolve(path.dirname(specPath), "baseline.wf.yaml"),
        );
    });

    it("throws a readable error when the file does not exist", () => {
        const missing = path.join(tmpDir, "does-not-exist.yaml");
        try {
            loadScenarioSpec(missing);
            fail("expected SpecLoadError");
        } catch (e) {
            expect(e).toBeInstanceOf(SpecLoadError);
            expect((e as SpecLoadError).specPath).toBe(path.resolve(missing));
        }
    });
});
