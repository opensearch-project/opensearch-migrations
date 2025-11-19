import {FullMigrationWithCli} from "../src/workflowTemplates/fullMigrationWithCli";
import {AllWorkflowTemplates} from "../src/workflowTemplates/allWorkflowTemplates";

describe("FullMigrationWithCli workflow template", () => {
    it("is exported and serializable", () => {
        expect(FullMigrationWithCli).toBeDefined();
        const serialized = JSON.stringify(FullMigrationWithCli);
        expect(serialized).toContain("full-migration-with-cli");
    });

    it("invokes WorkflowCommandOrchestrator", () => {
        const serialized = JSON.stringify(FullMigrationWithCli);
        expect(serialized).toContain("runMigrationViaWorkflowCli");
        expect(serialized).toContain("workflow-command-orchestrator");
    });

    it("is included in AllWorkflowTemplates", () => {
        const found = AllWorkflowTemplates.some(tpl => {
            try {
                return JSON.stringify(tpl).includes("full-migration-with-cli");
            } catch {
                return false;
            }
        });
        expect(found).toBe(true);
    });

    it("matches snapshot", () => {
        expect(FullMigrationWithCli).toMatchSnapshot();
    });
});
