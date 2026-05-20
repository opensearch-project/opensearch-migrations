import {renderWorkflowTemplate} from "@opensearch-migrations/argo-workflow-builders";

import {CreateSnapshot} from "../src/workflowTemplates/createSnapshot";
import {DocumentBulkLoad} from "../src/workflowTemplates/documentBulkLoad";
import {FullMigration} from "../src/workflowTemplates/fullMigration";
import {MigrationConsole} from "../src/workflowTemplates/migrationConsole";
import {TestMigrationWithWorkflowCli} from "../src/workflowTemplates/testMigrationWithWorkflowCli";
import {
    DEFAULT_WORKFLOW_SCRIPTS_ROOT,
    WORKFLOW_SCRIPTS_ROOT_ENV
} from "../src/workflowTemplates/commonUtils/workflowParameters";

const WORKFLOW_SCRIPTS_ROOT = "{{workflow.parameters.workflowScriptsRoot}}";
const TEMPLATE_INPUT_WORKFLOW_SCRIPTS_ROOT = "{{inputs.parameters.workflowScriptsRoot}}";

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

function getTemplateInputs(rendered: RenderedWorkflowTemplate, templateName: string): Record<string, string> {
    const template = rendered.spec.templates.find(t => t.name === templateName);
    expect(template).toBeDefined();
    return Object.fromEntries((template?.inputs?.parameters ?? []).map((param: {name: string; value?: string}) => [param.name, param.value ?? ""]));
}

describe("workflow script path rendering", () => {
    test.each([
        {
            workflowName: "full-migration-with-workflow-cli",
            rendered: renderWorkflowTemplate(TestMigrationWithWorkflowCli),
            scriptName: "configureAndSubmitWorkflow.sh",
            expectedScriptRootValue: TEMPLATE_INPUT_WORKFLOW_SCRIPTS_ROOT,
        },
        {
            workflowName: "full-migration-with-workflow-cli",
            rendered: renderWorkflowTemplate(TestMigrationWithWorkflowCli),
            scriptName: "monitorWorkflow.sh",
            expectedScriptRootValue: TEMPLATE_INPUT_WORKFLOW_SCRIPTS_ROOT,
        },
        {
            workflowName: "full-migration-with-workflow-cli",
            rendered: renderWorkflowTemplate(TestMigrationWithWorkflowCli),
            scriptName: "evaluateWorkflowResult.sh",
            expectedScriptRootValue: TEMPLATE_INPUT_WORKFLOW_SCRIPTS_ROOT,
        },
        {
            workflowName: "migration-console",
            rendered: renderWorkflowTemplate(MigrationConsole),
            scriptName: "runMigrationConsoleCommand.sh",
            expectedScriptRootValue: WORKFLOW_SCRIPTS_ROOT,
        },
        {
            workflowName: "full-migration",
            rendered: renderWorkflowTemplate(FullMigration),
            scriptName: "addApprovalGateOwnerReferences.sh",
            expectedScriptRootValue: WORKFLOW_SCRIPTS_ROOT,
        },
        {
            workflowName: "full-migration",
            rendered: renderWorkflowTemplate(FullMigration),
            scriptName: "cleanupApprovalGates.sh",
            expectedScriptRootValue: WORKFLOW_SCRIPTS_ROOT,
        },
        {
            workflowName: "document-bulk-load",
            rendered: renderWorkflowTemplate(DocumentBulkLoad),
            scriptName: "applyRfsMonitorCronJob.sh",
            expectedScriptRootValue: WORKFLOW_SCRIPTS_ROOT,
        },
        {
            workflowName: "create-snapshot",
            rendered: renderWorkflowTemplate(CreateSnapshot),
            scriptName: "applySnapshotMonitorCronJob.sh",
            expectedScriptRootValue: WORKFLOW_SCRIPTS_ROOT,
        },
    ])("$workflowName renders $scriptName without expression bleedthrough", ({rendered, scriptName, expectedScriptRootValue}) => {
        const args = getTemplateArgs(rendered, scriptName);
        const env = getTemplateEnv(rendered, scriptName);

        expect(args).toEqual([`exec "\${${WORKFLOW_SCRIPTS_ROOT_ENV}}/${scriptName}"`]);
        expect(args[0]).not.toContain("{{");
        expect(env[WORKFLOW_SCRIPTS_ROOT_ENV]).toBe(expectedScriptRootValue);
    });

    it("defaults workflowScriptsRoot inside the referenced workflow template scope", () => {
        const rendered = renderWorkflowTemplate(TestMigrationWithWorkflowCli);

        expect(getTemplateInputs(rendered, "configureandsubmitworkflow").workflowScriptsRoot).toBe(DEFAULT_WORKFLOW_SCRIPTS_ROOT);
        expect(getTemplateInputs(rendered, "monitorworkflow").workflowScriptsRoot).toBe(DEFAULT_WORKFLOW_SCRIPTS_ROOT);
        expect(getTemplateInputs(rendered, "evaluateworkflowresult").workflowScriptsRoot).toBe(DEFAULT_WORKFLOW_SCRIPTS_ROOT);
    });
});
