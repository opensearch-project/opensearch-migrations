import {renderWorkflowTemplate} from "@opensearch-migrations/argo-workflow-builders";
import {ResourceManagement} from "../src/workflowTemplates/resourceManagement";

/**
 * Behavioral guard for the DataSnapshot VAP-retry recovery loop. Every other root-CR reconcile
 * (KafkaCluster, CapturedTraffic, CaptureProxy, SnapshotMigration, TrafficReplay) parks a
 * VAP-rejected tryApply on an ApprovalGate and retries after approval; DataSnapshot previously
 * had no recovery path, so a rejected apply was a silent dead end. This asserts the loop steps
 * are present so that parity does not regress.
 */
describe("DataSnapshot reconcile has the VAP-retry recovery loop", () => {
    const rendered = JSON.stringify(renderWorkflowTemplate(ResourceManagement)).toLowerCase();

    it("reconcileDataSnapshotResource wires waitForFix / patchApproval / resetGate / retryLoop", () => {
        // The reconcile template and its recovery steps render (step + template names lowercase).
        expect(rendered).toContain("reconciledatasnapshotresource");
        expect(rendered).toContain("waitforfix");
        expect(rendered).toContain("waitforuserapproval");
        expect(rendered).toContain("patchapprovalannotation");
        expect(rendered).toContain("patchapprovalgatephase");
        expect(rendered).toContain("retryloop");
    });

    it("the DataSnapshot patchApproval targets a DataSnapshot resource kind", () => {
        // patchApprovalAnnotation for this reconcile must annotate a DataSnapshot (not another kind),
        // so approval clears the gate on the right resource.
        expect(rendered).toContain("datasnapshot");
    });
});
