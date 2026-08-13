import {
    renderWorkflowTemplate,
} from "@opensearch-migrations/argo-workflow-builders";

import {SetupCapture} from "../src/workflowTemplates/setupCapture";


describe("capture proxy setup failure propagation", () => {
    const rendered = renderWorkflowTemplate(SetupCapture) as any;
    const templates: any[] = rendered.spec?.templates ?? [];

    it("patches CaptureProxy status and then fails the owning workflow", () => {
        const reconcile = templates.find(
            template => template.name === "reconcilecapturetopicandproxy"
        );
        expect(reconcile).toBeDefined();
        const steps = reconcile.steps.flat();
        const setupProxy = steps.find(
            (step: any) => step.name === "setupProxy"
        );
        const configuredProxy = steps.find(
            (step: any) => step.name === "setupProxyWithConfiguredKafka"
        );
        const patchError = steps.find(
            (step: any) => step.name === "patchCaptureProxyError"
        );
        const failAfterPatch = steps.find(
            (step: any) => step.name === "failAfterProxyError"
        );

        expect(setupProxy.continueOn).toEqual({
            failed: true,
            error: true,
        });
        expect(configuredProxy.continueOn).toEqual({
            failed: true,
            error: true,
        });
        expect(patchError.when).toContain("Failed");
        expect(patchError.when).toContain("Error");
        expect(failAfterPatch.template).toBe("failcaptureproxysetup");
        expect(failAfterPatch.when).toEqual(patchError.when);
    });

    it("uses an explicit non-zero exit after the error status patch", () => {
        const failure = templates.find(
            template => template.name === "failcaptureproxysetup"
        );

        expect(failure).toBeDefined();
        expect(failure.container.args.join("\n")).toContain("exit 1");
    });
});
