import { renderWorkflowTemplate } from "@opensearch-migrations/argo-workflow-builders";
import { DocumentBulkLoad } from "../src/workflowTemplates/documentBulkLoad";
import { RfsCoordinatorCluster } from "../src/workflowTemplates/rfsCoordinatorCluster";
import { TestMigrationWithWorkflowCli } from "../src/workflowTemplates/testMigrationWithWorkflowCli";

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

    describe("runBulkLoad uses onExit to ensure stopHistoricalBackfill always runs", () => {
        const template = findTemplate(rendered, "runbulkload");

        it("has onExit pointing to stopHistoricalBackfill", () => {
            expect(template?.onExit).toBe("stophistoricalbackfill");
        });

        it("does not have continueOn on any steps (onExit handles cleanup)", () => {
            for (const group of template.steps) {
                for (const step of group) {
                    expect(step.continueOn).toBeUndefined();
                }
            }
        });

        it("does not include stopHistoricalBackfill as an inline step", () => {
            const step = findStep(template.steps, "stopHistoricalBackfill");
            expect(step).toBeUndefined();
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

describe("testMigrationWithWorkflowCli cleanup-on-failure", () => {
    const rendered = renderWorkflowTemplate(TestMigrationWithWorkflowCli);

    describe("main ensures deleteMigrationWorkflow always runs", () => {
        const template = findTemplate(rendered, "main");

        it("has continueOn.failed on configureAndSubmitWorkflow", () => {
            const step = findStep(template.steps, "configureAndSubmitWorkflow");
            expect(step?.continueOn).toEqual({ failed: true });
        });

        it("gates monitorWorkflow on configureAndSubmitWorkflow succeeding", () => {
            const step = findStep(template.steps, "monitorWorkflow");
            expect(step?.when).toContain("configureAndSubmitWorkflow.status");
            expect(step?.when).toContain("Succeeded");
            expect(step?.continueOn).toEqual({ failed: true });
        });

        it("gates evaluateWorkflowResult on monitorWorkflow succeeding", () => {
            const step = findStep(template.steps, "evaluateWorkflowResult");
            expect(step?.when).toContain("monitorWorkflow.status");
            expect(step?.when).toContain("Succeeded");
            expect(step?.continueOn).toEqual({ failed: true });
        });

        it("does not have continueOn on deleteMigrationWorkflow (final cleanup step)", () => {
            const step = findStep(template.steps, "deleteMigrationWorkflow");
            expect(step).toBeDefined();
            expect(step?.continueOn).toBeUndefined();
        });
    });
});
