import { WorkflowCommandOrchestrator } from "../src/workflowTemplates/workflowCommandOrchestrator";
import { AllWorkflowTemplates } from "../src/workflowTemplates/allWorkflowTemplates";

describe("WorkflowCommandOrchestrator workflow template", () => {
    it("is exported and serializable", () => {
        // sanity check: export exists and is an object
        expect(WorkflowCommandOrchestrator).toBeDefined();
        expect(typeof WorkflowCommandOrchestrator).toBe("object");

        // ensure it can be JSON-serialized (required for YAML generation)
        const serialized = JSON.stringify(WorkflowCommandOrchestrator);
        expect(serialized).toContain("workflow-command-orchestrator");
    });

    it("defines a main entrypoint that calls workflow CLI commands", () => {
        const serialized = JSON.stringify(WorkflowCommandOrchestrator);

        // expect the logical name to be present
        expect(serialized).toContain("workflow-command-orchestrator");

        // expect the key template names to show up somewhere
        expect(serialized).toContain("configureWorkflow");
        expect(serialized).toContain("submitWorkflow");
        expect(serialized).toContain("monitorWorkflow");

        // expect the embedded scripts to call the workflow CLI
        expect(serialized).toContain("workflow configure");
        expect(serialized).toContain("workflow submit");
        expect(serialized).toContain("workflow status");
    });

    it("is included in AllWorkflowTemplates", () => {
        // The AllWorkflowTemplates aggregator is what makeTemplates.ts uses,
        // so we want to be sure this new workflow is part of it.
        const found = AllWorkflowTemplates.some((tpl: any) => {
            // avoid relying on exact property names by stringifying.
            try {
                const s = JSON.stringify(tpl);
                return s.includes("workflow-command-orchestrator");
            } catch {
                return false;
            }
        });

        expect(found).toBe(true);
    });

    it("matches snapshot (structure regression guard)", () => {
        // This will create a __snapshots__/ file the first time you run it.
        // Future changes to the DSL structure will cause a diff, which is
        // exactly what we want when editing this workflow.
        expect(WorkflowCommandOrchestrator).toMatchSnapshot();
    });
});
