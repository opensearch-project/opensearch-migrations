import { WorkflowCommandOrchestrator } from "../src/workflowTemplates/workflowCommandOrchestrator";

describe("WorkflowCommandOrchestrator workflow template", () => {
    it("matches snapshot (structure regression guard)", () => {
        // This will create a __snapshots__/ file the first time you run it.
        // Future changes to the DSL structure will cause a diff, which is
        // exactly what we want when editing this workflow.
        expect(WorkflowCommandOrchestrator).toMatchSnapshot();
    });
});
