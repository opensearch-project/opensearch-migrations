import {renderWorkflowTemplate} from "@opensearch-migrations/argo-workflow-builders";
import {ResourceManagement} from "../src/workflowTemplates/resourceManagement";

/**
 * Behavioral guards for spec.dependsOn on the terminal-resource apply manifests. The `workflow
 * reset` CLI builds its deletion DAG from spec.dependsOn read off live CRs, so DataSnapshot and
 * SnapshotMigration must write it via tryApply (their upsert/apply manifests). These assertions
 * pin the rendered Argo YAML so a regression that drops the edge fails loudly.
 */
describe("terminal-resource dependsOn is written by tryApply", () => {
    const rendered = JSON.stringify(renderWorkflowTemplate(ResourceManagement));

    it("DataSnapshot apply manifest emits spec.dependsOn from the resolved item name list", () => {
        // The transformer stamps the resolved proxy-setup names as a flat `dependsOn` list on the
        // snapshot item; the manifest reads it with a [] default.
        expect(rendered).toContain(
            "dependsOn: {{=sprig.dig('dependsOn', [], fromJSON(inputs.parameters.snapshotItemConfig))}}"
        );
    });

    it("SnapshotMigration apply manifest emits spec.dependsOn as [dataSnapshotResourceName] or []", () => {
        // A SnapshotMigration depends on its DataSnapshot when one exists (workflow-generated or Solr
        // import-prepare); an externally-managed ES/OS snapshot with no DataSnapshot has no edge.
        expect(rendered).toContain(
            "dependsOn: {{=(('dataSnapshotResourceName' in fromJSON(inputs.parameters.snapshotMigrationConfig)['snapshotNameResolution']) ? " +
            "([sprig.dig('dataSnapshotResourceName', '', fromJSON(inputs.parameters.snapshotMigrationConfig)['snapshotNameResolution'])]) : ([]))}}"
        );
    });
});
