import {renderWorkflowTemplate} from "@opensearch-migrations/argo-workflow-builders";
import {FullMigration} from "../src/workflowTemplates/fullMigration";
import {MetadataMigration} from "../src/workflowTemplates/metadataMigration";
import {ResourceManagement} from "../src/workflowTemplates/resourceManagement";

describe("source auth resource isolation", () => {
    const rendered = renderWorkflowTemplate(ResourceManagement) as any;
    const templates: any[] = rendered.spec?.templates ?? [];

    function manifest(templateName: string): string {
        const template = templates.find(candidate => candidate.name === templateName);
        expect(template?.resource?.manifest).toBeDefined();
        return template.resource.manifest as string;
    }

    it("does not project source auth into the pass-through CaptureProxy", () => {
        expect(manifest("upsertcaptureproxyresource")).not.toContain("sourceAuth");
    });

    it("does not project source auth into a migration that reads from a snapshot", () => {
        expect(manifest("upsertsnapshotmigrationresource")).not.toContain("sourceAuth");
    });

    it("keeps source auth on DataSnapshot, which connects directly to the source", () => {
        expect(manifest("upsertdatasnapshotresource")).toContain("sourceAuthBasicSecretName");
    });

    it("does not inject source credentials into metadata migration pods", () => {
        const metadataWorkflow = JSON.stringify(renderWorkflowTemplate(MetadataMigration));

        expect(metadataWorkflow).not.toContain("SOURCE_USERNAME");
        expect(metadataWorkflow).not.toContain("SOURCE_PASSWORD");
        expect(metadataWorkflow).not.toContain("sourceConfig");
        expect(metadataWorkflow).not.toContain("sourceEndpoint");
    });

    it("does not pass source connection inputs into the snapshot migration stages", () => {
        const fullMigration = renderWorkflowTemplate(FullMigration) as any;
        const migrateFromSnapshot = fullMigration.spec.templates.find(
            (template: any) => template.name === "migratefromsnapshot"
        );
        const migrateInputNames = migrateFromSnapshot.inputs.parameters.map(
            (parameter: any) => parameter.name
        );
        const runSingleSnapshotMigration = fullMigration.spec.templates.find(
            (template: any) => template.name === "runsinglesnapshotmigration"
        );
        const migrateStep = runSingleSnapshotMigration.steps
            .flat()
            .find((step: any) => step.name === "migrateFromSnapshot");
        const migrateArgumentNames = migrateStep.arguments.parameters.map(
            (parameter: any) => parameter.name
        );

        expect(migrateInputNames).not.toContain("sourceConfig");
        expect(migrateInputNames).not.toContain("sourceEndpoint");
        expect(migrateArgumentNames).not.toContain("sourceConfig");
        expect(migrateArgumentNames).not.toContain("sourceEndpoint");
    });
});
