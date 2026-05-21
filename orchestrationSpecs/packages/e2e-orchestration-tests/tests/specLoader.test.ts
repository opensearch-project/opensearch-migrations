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
fixtures:
  basicAuthCredentials:
    bySecretName:
      source-creds:
        usernameEnv: E2E_SOURCE_BASIC_AUTH_USERNAME
        passwordEnv: E2E_SOURCE_BASIC_AUTH_PASSWORD
      target-creds:
        usernameEnv: E2E_TARGET_BASIC_AUTH_USERNAME
        passwordEnv: E2E_TARGET_BASIC_AUTH_PASSWORD
  poisonPills:
    byName:
      datasnapshot-bad-repo-endpoint:
        subject: datasnapshot:source-snap1
        strategy: config-value
        expectedCollateral: [captureproxy:capture-proxy]
        poison:
          path: sourceClusters.source.snapshotInfo.repos.default.endpoint
          value: "http://does-not-exist.ma.svc.cluster.local:4566"
        restore:
          path: sourceClusters.source.snapshotInfo.repos.default.endpoint
          value: "localstack://localstack:4566"
      snapshotmigration-bad-target-auth:
        subject: snapshotmigration:source-target-snap1-migration-0
        strategy: basic-auth-credentials
        expectedCollateral: [trafficreplay:capture-proxy-target-replay1]
        secretName: target-creds
        poison:
          usernameEnv: E2E_BAD_TARGET_BASIC_AUTH_USERNAME
          passwordEnv: E2E_BAD_TARGET_BASIC_AUTH_PASSWORD
        restore:
          usernameEnv: E2E_TARGET_BASIC_AUTH_USERNAME
          passwordEnv: E2E_TARGET_BASIC_AUTH_PASSWORD
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
        expect(spec.fixtures).toEqual({});
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
        expect(spec.fixtures.basicAuthCredentials?.bySecretName["source-creds"]).toEqual({
            usernameEnv: "E2E_SOURCE_BASIC_AUTH_USERNAME",
            passwordEnv: "E2E_SOURCE_BASIC_AUTH_PASSWORD",
        });
        expect(spec.fixtures.basicAuthCredentials?.bySecretName["target-creds"]).toEqual({
            usernameEnv: "E2E_TARGET_BASIC_AUTH_USERNAME",
            passwordEnv: "E2E_TARGET_BASIC_AUTH_PASSWORD",
        });
        expect(spec.fixtures.poisonPills?.byName["datasnapshot-bad-repo-endpoint"]).toMatchObject({
            subject: "datasnapshot:source-snap1",
            strategy: "config-value",
            poison: {
                path: "sourceClusters.source.snapshotInfo.repos.default.endpoint",
            },
        });
        expect(spec.fixtures.poisonPills?.byName["snapshotmigration-bad-target-auth"]).toMatchObject({
            subject: "snapshotmigration:source-target-snap1-migration-0",
            strategy: "basic-auth-credentials",
            secretName: "target-creds",
        });
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
