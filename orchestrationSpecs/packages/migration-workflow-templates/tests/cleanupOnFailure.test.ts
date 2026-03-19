import { renderWorkflowTemplate } from "@opensearch-migrations/argo-workflow-builders";
import { DocumentBulkLoad } from "../src/workflowTemplates/documentBulkLoad";
import { RfsCoordinatorCluster } from "../src/workflowTemplates/rfsCoordinatorCluster";

function findTemplate(rendered: any, name: string) {
    return rendered.spec.templates.find((t: any) => t.name === name);
}

function findStep(steps: any[][], name: string) {
    for (const group of steps) {
        const found = group.find((s: any) => s.name === name);
        if (found) return found;
    }
    return undefined;
}

describe("documentBulkLoad cleanup-on-failure", () => {
    const rendered = renderWorkflowTemplate(DocumentBulkLoad);

    describe("runBulkLoad ensures stopHistoricalBackfill always runs", () => {
        const template = findTemplate(rendered, "runbulkload");

        it("has continueOn.failed on startHistoricalBackfillFromConfig", () => {
            const step = findStep(template.steps, "startHistoricalBackfillFromConfig");
            expect(step?.continueOn).toEqual({ failed: true });
        });

        it("gates setupWaitForCompletion on backfill creation succeeding", () => {
            const step = findStep(template.steps, "setupWaitForCompletion");
            expect(step?.when).toContain("startHistoricalBackfillFromConfig.status");
            expect(step?.when).toContain("Succeeded");
            expect(step?.continueOn).toEqual({ failed: true });
        });

        it("has continueOn.failed on waitForCompletion", () => {
            const step = findStep(template.steps, "waitForCompletion");
            expect(step?.continueOn).toEqual({ failed: true });
        });

        it("does not have continueOn on stopHistoricalBackfill (final cleanup step)", () => {
            const step = findStep(template.steps, "stopHistoricalBackfill");
            expect(step).toBeDefined();
            expect(step?.continueOn).toBeUndefined();
        });
    });

    describe("setupAndRunBulkLoad ensures cleanupRfsCoordinator always runs", () => {
        const template = findTemplate(rendered, "setupandrunbulkload");

        it("has continueOn.failed on createRfsCoordinator", () => {
            const step = findStep(template.steps, "createRfsCoordinator");
            expect(step?.continueOn).toEqual({ failed: true });
        });

        it("has continueOn.failed on runBulkLoad", () => {
            const step = findStep(template.steps, "runBulkLoad");
            expect(step?.continueOn).toEqual({ failed: true });
        });

        it("does not have continueOn on cleanupRfsCoordinator (final cleanup step)", () => {
            const step = findStep(template.steps, "cleanupRfsCoordinator");
            expect(step).toBeDefined();
            expect(step?.continueOn).toBeUndefined();
        });
    });
});

describe("rfsCoordinatorCluster cleanup-on-failure", () => {
    const rendered = renderWorkflowTemplate(RfsCoordinatorCluster);

    describe("deleteRfsCoordinator ensures all deletes run", () => {
        const template = findTemplate(rendered, "deleterfscoordinator");

        it("has continueOn.failed on deleteStatefulSet", () => {
            const step = findStep(template.steps, "deleteStatefulSet");
            expect(step?.continueOn).toEqual({ failed: true });
        });

        it("has continueOn.failed on deleteService", () => {
            const step = findStep(template.steps, "deleteService");
            expect(step?.continueOn).toEqual({ failed: true });
        });

        it("does not have continueOn on deleteSecret (final cleanup step)", () => {
            const step = findStep(template.steps, "deleteSecret");
            expect(step).toBeDefined();
            expect(step?.continueOn).toBeUndefined();
        });
    });
});
