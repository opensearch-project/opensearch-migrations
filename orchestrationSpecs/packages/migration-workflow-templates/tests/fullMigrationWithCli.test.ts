import {FullMigrationWithCli} from "../src/workflowTemplates/workflowCommandOrchestrator";

describe("FullMigrationWithCli workflow template", () => {
    it("matches snapshot", () => {
        expect(FullMigrationWithCli).toMatchSnapshot();
    });
});
