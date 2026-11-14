import {renderWorkflowTemplate} from "@opensearch-migrations/argo-workflow-builders";
import {collectRestrictedProjectedFields} from "@opensearch-migrations/schemas";
import {ResourceManagement} from "../src/workflowTemplates/resourceManagement";

/**
 * A gated or impossible field is enforced by a ValidatingAdmissionPolicy rule on the resource's spec. If the
 * workflow never writes the field into the spec, the rule always sees it absent and never fires, so the
 * restriction silently does nothing. Every restricted SnapshotMigration field must therefore be written by the
 * upsert manifest.
 */
describe("restricted SnapshotMigration fields reach the resource spec", () => {
    function upsertManifest(): string {
        const rendered = renderWorkflowTemplate(ResourceManagement) as any;
        const template = rendered.spec.templates.find((t: any) => t.name === "upsertsnapshotmigrationresource");
        expect(template?.resource?.manifest).toBeDefined();
        return template.resource.manifest as string;
    }

    const restricted = collectRestrictedProjectedFields()
        .filter(field => field.resourceKind === "SnapshotMigration");

    test("there are restricted fields to check", () => {
        expect(restricted.length).toBeGreaterThan(0);
    });

    test.each(restricted.map(field => [field.specPath.join("."), field.changeRestriction]))(
        "%s (%s) is written by the upsert manifest", (specPath) => {
            const topLevelKey = specPath.split(".")[0];
            expect(upsertManifest()).toMatch(new RegExp(`^\\s+${topLevelKey}:`, "m"));
        });
});
