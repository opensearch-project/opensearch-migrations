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

    describe("setupAndRunBulkLoad delegates to internal template with onExit for coordinator cleanup", () => {
        const wrapper = findTemplate(rendered, "setupandrunbulkload");
        const internal = findTemplate(rendered, "setupandrunbulkloadinternal");

        it("wrapper delegates to setupAndRunBulkLoadInternal", () => {
            const step = findStep(wrapper.steps, "setupAndRunBulkLoadInternal");
            expect(step).toBeDefined();
        });

        it("internal template has onExit pointing to cleanupRfsCoordinator", () => {
            expect(internal?.onExit).toBe("cleanupRfsCoordinator".toLowerCase());
        });

        it("internal template has no continueOn on any steps", () => {
            for (const group of internal.steps) {
                for (const step of group) {
                    expect(step.continueOn).toBeUndefined();
                }
            }
        });
    });
});

describe("rfsCoordinatorCluster cleanup-on-failure", () => {
    const rendered = renderWorkflowTemplate(RfsCoordinatorCluster);

    describe("deleteRfsCoordinator delegates to internal template with onExit for secret cleanup", () => {
        const wrapper = findTemplate(rendered, "deleterfscoordinator");
        const internal = findTemplate(rendered, "deleterfscoordinatorresources");

        it("wrapper delegates to deleteRfsCoordinatorResources", () => {
            const step = findStep(wrapper.steps, "deleteRfsCoordinatorResources");
            expect(step).toBeDefined();
        });

        it("internal template has onExit pointing to deleteRfsCoordinatorSecret", () => {
            expect(internal?.onExit).toBe("deleterfscoordinatorsecret");
        });

        it("internal template has no continueOn on any steps", () => {
            for (const group of internal.steps) {
                for (const step of group) {
                    expect(step.continueOn).toBeUndefined();
                }
            }
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
