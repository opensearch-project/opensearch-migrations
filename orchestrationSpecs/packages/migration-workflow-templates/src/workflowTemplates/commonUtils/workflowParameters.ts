import {BaseExpression, defineParam, expr} from "@opensearch-migrations/argo-workflow-builders";

export const DEFAULT_WORKFLOW_SCRIPTS_ROOT = "/root/workflows/.workflowScripts";

export const CommonWorkflowParameters = {
    s3SnapshotConfigMap: defineParam({expression: "s3-snapshot-config"}),
    gcsSnapshotConfigMap: defineParam({expression: "gcs-snapshot-config"}),
    imageConfigMapName: defineParam({expression: "migration-image-config"}),
    migrationRunNumber: defineParam({expression: "0"}),
    workflowScriptsRoot: defineParam({expression: DEFAULT_WORKFLOW_SCRIPTS_ROOT})
} as const;

export const WORKFLOW_SCRIPTS_ROOT_ENV = "WORKFLOW_SCRIPTS_ROOT";

export function workflowScriptRootEnvVars(
    workflowScriptsRoot: BaseExpression<string>,
) {
    return {
        [WORKFLOW_SCRIPTS_ROOT_ENV]: workflowScriptsRoot
    } as const;
}

export function workflowScriptCommand(
    scriptName: string
): string {
    return `exec "\${${WORKFLOW_SCRIPTS_ROOT_ENV}}/${scriptName}"`;
}

export function workflowIdentityEnvVars() {
    return {
        WORKFLOW_NAME: expr.getWorkflowValue("name"),
        WORKFLOW_UID: expr.getWorkflowValue("uid"),
        PARENT_WORKFLOW_NAME: expr.getWorkflowValue("name"),
        PARENT_WORKFLOW_UID: expr.getWorkflowValue("uid")
    } as const;
}
