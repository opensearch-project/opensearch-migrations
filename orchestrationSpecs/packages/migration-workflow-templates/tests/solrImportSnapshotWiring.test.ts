import {renderWorkflowTemplate} from "@opensearch-migrations/argo-workflow-builders";
import {CreateSnapshot} from "../src/workflowTemplates/createSnapshot";
import {CreateOrGetSnapshot} from "../src/workflowTemplates/createOrGetSnapshot";

/**
 * Behavioral guards for the Solr externally-managed-snapshot IMPORT wiring in the workflow
 * templates. These assert the rendered Argo YAML directly (rather than only via the large
 * full-template snapshots) so a regression in the import branch fails loudly and legibly.
 */
describe("Solr import snapshot workflow wiring", () => {
    it("snapshotWorkflow gates the import branch (runImport + direct CR completion) on mode", () => {
        const rendered = JSON.stringify(renderWorkflowTemplate(CreateSnapshot));

        // runCreateSnapshot emits --mode/--source-type via the inline-JSON params; sourceType is
        // forwarded to Java, but workflow branching is controlled by createSnapshotConfig.mode.
        expect(rendered).toContain("sourceType");
        expect(rendered).toContain("createSnapshotConfig");
        expect(rendered).toContain("sprig.dig('mode', 'create', fromJSON(inputs.parameters.createSnapshotConfig)) == 'import'");
        // The import-only steps exist and are gated on mode=import.
        expect(rendered).toContain("runImport");
        expect(rendered).toContain("markSnapshotImported");
        // The import path marks the CR done directly (no monitor cronjob) via patchDataSnapshotCompleted
        // (template names render lowercased in the Argo YAML).
        expect(rendered).toContain("patchdatasnapshotcompleted");
        // Import steps fire when mode=import; create-path steps when mode is anything else/default.
        expect(rendered).toContain("{{=sprig.dig('mode', 'create', fromJSON(inputs.parameters.createSnapshotConfig)) == 'import'}}");
        expect(rendered).toContain("{{=!(sprig.dig('mode', 'create', fromJSON(inputs.parameters.createSnapshotConfig)) == 'import')}}");
        // The create-path completion-wait still exists.
        expect(rendered).toContain("waitindefinitelyfordatasnapshot");
    });

    it("createOrGetSnapshot drives sourceType=solr and the verbatim external name on the import path", () => {
        const rendered = JSON.stringify(renderWorkflowTemplate(CreateOrGetSnapshot));

        // The import-external-snapshot name input is threaded through.
        expect(rendered).toContain("importExternalSnapshotName");
        // sourceType is passed to the snapshotWorkflow step (ternary: "solr" when importing, else "").
        expect(rendered).toContain("sourceType");
        expect(rendered).toContain("solr");
    });
});
