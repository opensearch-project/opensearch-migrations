import {renderWorkflowTemplate} from "@opensearch-migrations/argo-workflow-builders";
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
});
