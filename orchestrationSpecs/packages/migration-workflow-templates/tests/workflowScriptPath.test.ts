import {renderWorkflowTemplate} from "@opensearch-migrations/argo-workflow-builders";

import {CreateSnapshot} from "../src/workflowTemplates/createSnapshot";
import {DocumentBulkLoad} from "../src/workflowTemplates/documentBulkLoad";
import {FullMigration} from "../src/workflowTemplates/fullMigration";
import {MigrationConsole} from "../src/workflowTemplates/migrationConsole";
import {TestMigrationWithWorkflowCli} from "../src/workflowTemplates/testMigrationWithWorkflowCli";
import {WORKFLOW_SCRIPTS_ROOT_ENV} from "../src/workflowTemplates/commonUtils/workflowParameters";

const WORKFLOW_SCRIPTS_ROOT = "{{workflow.parameters.workflowScriptsRoot}}";

type RenderedWorkflowTemplate = ReturnType<typeof renderWorkflowTemplate>;

function getTemplateArgs(rendered: RenderedWorkflowTemplate, scriptName: string): string[] {
    const template = rendered.spec.templates.find(t =>
        t.container?.args?.some((arg: unknown) => typeof arg === "string" && arg.includes(scriptName))
    );
    expect(template).toBeDefined();
    expect(template?.container?.args).toBeDefined();
    return template!.container!.args as string[];
}

function getTemplateEnv(rendered: RenderedWorkflowTemplate, scriptName: string): Record<string, string> {
    const template = rendered.spec.templates.find(t =>
        t.container?.args?.some((arg: unknown) => typeof arg === "string" && arg.includes(scriptName))
    );
    expect(template).toBeDefined();
    return Object.fromEntries((template!.container!.env ?? []).map((envVar: {name: string; value?: string}) => [envVar.name, envVar.value ?? ""]));
}

describe("workflow script path rendering", () => {
    test.each([
        {
            workflowName: "full-migration-with-workflow-cli",
            rendered: renderWorkflowTemplate(TestMigrationWithWorkflowCli),
            scriptName: "configureAndSubmitWorkflow.sh"
        },
        {
            workflowName: "full-migration-with-workflow-cli",
            rendered: renderWorkflowTemplate(TestMigrationWithWorkflowCli),
            scriptName: "monitorWorkflow.sh"
        },
        {
            workflowName: "full-migration-with-workflow-cli",
            rendered: renderWorkflowTemplate(TestMigrationWithWorkflowCli),
            scriptName: "evaluateWorkflowResult.sh"
        },
        {
            workflowName: "migration-console",
            rendered: renderWorkflowTemplate(MigrationConsole),
            scriptName: "runMigrationConsoleCommand.sh"
        },
        {
            workflowName: "full-migration",
            rendered: renderWorkflowTemplate(FullMigration),
            scriptName: "addApprovalGateOwnerReferences.sh"
        },
        {
            workflowName: "full-migration",
            rendered: renderWorkflowTemplate(FullMigration),
            scriptName: "cleanupApprovalGates.sh"
        },
        {
            workflowName: "document-bulk-load",
            rendered: renderWorkflowTemplate(DocumentBulkLoad),
            scriptName: "applyRfsMonitorCronJob.sh"
        },
        {
            workflowName: "create-snapshot",
            rendered: renderWorkflowTemplate(CreateSnapshot),
            scriptName: "applySnapshotMonitorCronJob.sh"
        },
    ])("$workflowName renders $scriptName without expression bleedthrough", ({rendered, scriptName}) => {
        const args = getTemplateArgs(rendered, scriptName);
        const env = getTemplateEnv(rendered, scriptName);

        expect(args).toEqual([`exec "\${${WORKFLOW_SCRIPTS_ROOT_ENV}}/${scriptName}"`]);
        expect(args[0]).not.toContain("{{");
        expect(env[WORKFLOW_SCRIPTS_ROOT_ENV]).toBe(WORKFLOW_SCRIPTS_ROOT);
    });
});
