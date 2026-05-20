import {BaseExpression, defineParam, expr} from "@opensearch-migrations/argo-workflow-builders";

export const CommonWorkflowParameters = {
    s3SnapshotConfigMap: defineParam({expression: "s3-snapshot-config"}),
    imageConfigMapName: defineParam({expression: "migration-image-config"}),
    approvalConfigMapName: defineParam({expression: "approval-config"}),
    workflowScriptsRoot: defineParam({expression: "/root/workflows/.workflowScripts"})
} as const;

export function workflowScriptPath(
    workflowScriptsRoot: BaseExpression<string>,
    scriptName: string
): BaseExpression<string> {
    return expr.concat(workflowScriptsRoot, expr.literal(`/${scriptName}`));
}

export function workflowIdentityEnvVars() {
    return {
        WORKFLOW_NAME: expr.getWorkflowValue("name"),
        WORKFLOW_UID: expr.getWorkflowValue("uid"),
        PARENT_WORKFLOW_NAME: expr.getWorkflowValue("name"),
        PARENT_WORKFLOW_UID: expr.getWorkflowValue("uid")
    } as const;
}
