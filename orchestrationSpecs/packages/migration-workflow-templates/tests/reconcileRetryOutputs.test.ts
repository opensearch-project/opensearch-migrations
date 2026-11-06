import {renderWorkflowTemplate} from "@opensearch-migrations/argo-workflow-builders";
import {ResourceManagement} from "../src/workflowTemplates/resourceManagement";

/**
 * A reconcile template that recovers from a VAP denial does so in its self-recursive retryLoop step, so its
 * outputs must fall back to retryLoop's. Otherwise a recovered apply reports empty outputs (including the
 * SnapshotMigration UID used for ownerReferences).
 */
describe("reconcile templates with a VAP retry loop", () => {
    const templates: any[] = (renderWorkflowTemplate(ResourceManagement) as any).spec.templates;
    const withRetryLoop = templates.filter(template =>
        (template.steps ?? []).flat().some((step: any) => step.name === "retryLoop"));

    test("exist", () => {
        expect(withRetryLoop.length).toBeGreaterThanOrEqual(6);
    });

    test.each(withRetryLoop.map(template => [template.name, template]))(
        "%s takes its outputs from retryLoop when the first apply failed", (_name, template) => {
            const outputs: any[] = template.outputs?.parameters ?? [];
            expect(outputs.length).toBeGreaterThan(0);
            for (const output of outputs) {
                const expression: string = output.valueFrom?.expression ?? "";
                if (expression.includes("steps.tryApply.outputs")) {
                    expect(expression).toContain(`steps.retryLoop.outputs.parameters.${output.name}`);
                }
            }
        });

    // Argo requeues a step whose `when` names a missing output instead of skipping it, so reading a failed apply's
    // outputs there leaves the workflow waiting forever and never reaches waitForFix.
    test.each(withRetryLoop.map(template => [template.name, template]))(
        "%s doesn't read a step's outputs in a when if that step may fail", (_name, template) => {
            const steps: any[] = (template.steps ?? []).flat();
            const mayFail = steps.filter(step => step.continueOn?.failed).map(step => step.name);
            expect(mayFail).toContain("tryApply");
            for (const step of steps) {
                for (const name of mayFail) {
                    expect(step.when ?? "").not.toContain(`steps.${name}.outputs`);
                }
            }
        });
});
