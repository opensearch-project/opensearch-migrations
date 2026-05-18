import {
    generateMigrationCrdsYaml,
    generateValidatingAdmissionPoliciesYaml,
    main as generateMigrationResourcesMain,
} from "../src/generateMigrationResources";
import {
    collectProjectedFields,
    collectRestrictedProjectedFields,
} from "../src/migrationResourceProjections";
import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";

describe("generated migration resources", () => {
    const crds = generateMigrationCrdsYaml();
    const vaps = generateValidatingAdmissionPoliciesYaml();

    test("generates CRDs for every projected resource field", () => {
        for (const field of collectProjectedFields()) {
            expect(crds).toContain(`${field.specPath[field.specPath.length - 1]}:`);
        }
    });

    test("projects safe user-visible fields into CRDs without VAP checks", () => {
        expect(crds).toContain("name: trafficreplays.migrations.opensearch.org");
        expect(crds).toContain("speedupFactor:");
        expect(vaps).not.toContain("speedupFactor");
    });

    test("adds schema-only fields that were missing from hand-written CRDs", () => {
        expect(crds).toContain("compressionEnabled:");
        expect(crds).toContain("includeGlobalState:");
        expect(crds).toContain("tupleMaxBufferSeconds:");
        expect(crds).toContain("tupleMaxFileSizeMb:");
        expect(crds).toContain("metadataMigrationEnableSourcelessMigrations:");
    });

    test("does not add dependency fields to approval gates", () => {
        const approvalGateCrd = crds.split("---")
            .find(document => document.includes("name: approvalgates.migrations.opensearch.org"));
        expect(approvalGateCrd).toBeDefined();
        expect(approvalGateCrd).not.toContain("dependsOn:");
    });

    test("generates VAP checks for every restricted projected field", () => {
        for (const field of collectRestrictedProjectedFields()) {
            expect(vaps).toContain(field.specPath.join("."));
        }
    });

    test("keeps gated VAP approval tied to the migration run number annotation", () => {
        expect(vaps).toContain("'migrations.opensearch.org/approved-during-run' in object.metadata.annotations");
        expect(vaps).toContain("'migrations.opensearch.org/run-number' in object.metadata.labels");
        expect(vaps).toContain(
            "object.metadata.annotations['migrations.opensearch.org/approved-during-run'] == object.metadata.labels['migrations.opensearch.org/run-number']"
        );
        expect(vaps).not.toContain("== &&");
    });

    test("preserves stricter invariants alongside gated checks", () => {
        expect(vaps).toContain("object.spec.partitions >= oldObject.spec.partitions");
        expect(vaps).toContain("Gated changes detected on CapturedTraffic fields: partitions, replicas, topicConfig");
    });

    test("generates lifecycle guard policies from resource projections", () => {
        expect(vaps).toContain("name: migrations-lock-on-complete-policy");
        expect(vaps).toContain("resources: [\"datasnapshots\", \"snapshotmigrations\"]");
        expect(vaps).toContain("name: migrations-deleting-phase-guard-policy");
        expect(vaps).toContain("resources: [\"kafkaclusters\", \"capturedtraffics\", \"captureproxies\", \"datasnapshots\", \"snapshotmigrations\", \"trafficreplays\"]");
        expect(crds).toContain("enum: [Initialized, Running, Completed, Deleting, Error]");
    });

    test("generates immutable MigrationRun history resources", () => {
        expect(crds).toContain("name: migrationruns.migrations.opensearch.org");
        expect(crds).toContain("kind: MigrationRun");
        expect(crds).toContain("shortNames: [mrun]");
        expect(crds).toContain("required: [workflowName, runNumber, timestamp, resolvedConfig]");
        expect(crds).toContain("rule: \"self == oldSelf\"");
        expect(crds).toContain("message: MigrationRun specs are historical records and are immutable after creation.");
        expect(crds).toContain("message: MigrationRun workflow status fields may only be set once.");
        expect(crds).toContain("jsonPath: .spec.workflowName");
        expect(crds).toContain("jsonPath: .status.workflowUid");
        expect(crds).toContain("workflowUid:");
        expect(crds).toContain("workflowCreationTimestamp:");
        expect(crds).toContain("resolvedConfig:");
        expect(crds).toContain("x-kubernetes-preserve-unknown-fields: true");

        expect(vaps).toContain("name: migrations-migrationrun-immutability-policy");
        expect(vaps).toContain("resources: [\"migrationruns\"]");
        expect(vaps).toContain("object.spec == oldObject.spec");
        expect(vaps).toContain("'migrations.opensearch.org/workflow-uid' in object.metadata.labels");
        expect(vaps).toContain("object.metadata.annotations == oldObject.metadata.annotations");
        expect(vaps).toContain("name: migrations-migrationrun-immutability-binding");
        expect(vaps).toContain("MigrationRun workflow UID label may only be set once.");
        expect(vaps).toContain("name: migrations-migrationrun-status-immutability-policy");
        expect(vaps).toContain("resources: [\"migrationruns/status\"]");
        expect(vaps).toContain("MigrationRun workflow status fields may only be set once.");
    });

    test("stages globally-scoped image-installable resources", () => {
        const outputDir = fs.mkdtempSync(path.join(os.tmpdir(), "migration-resources-"));
        generateMigrationResourcesMain(["--all", "--output-dir", outputDir]);

        const stagedCrds = fs.readFileSync(path.join(outputDir, "migrationCrds.yaml"), "utf8");
        const stagedVaps = fs.readFileSync(path.join(outputDir, "validatingAdmissionPolicies.yaml"), "utf8");

        expect(stagedCrds).toContain("kind: CustomResourceDefinition");
        expect(stagedVaps).toContain("migrations-trafficreplay-policy");
        expect(stagedVaps).not.toContain("namespaceSelector");
        expect(stagedVaps).not.toContain("{{");
    });
});
