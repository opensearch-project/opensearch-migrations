import {BaseExpression, configMapKey, defineParam, expr, InputParamDef, typeToken} from "@opensearch-migrations/argo-workflow-builders";

export const DEFAULT_WORKFLOW_SCRIPTS_ROOT = "/root/workflows/.workflowScripts";

export const CommonWorkflowParameters = {
    s3SnapshotConfigMap: defineParam({expression: "s3-snapshot-config"}),
    gcsSnapshotConfigMap: defineParam({expression: "gcs-snapshot-config"}),
    imageConfigMapName: defineParam({expression: "migration-image-config"}),
    migrationRunNumber: defineParam({expression: "0"}),
    workflowScriptsRoot: defineParam({expression: DEFAULT_WORKFLOW_SCRIPTS_ROOT})
} as const;

export const PROVIDER_CONFIG_MAP_NAME = "provider-config";

// Target cloud provider ("aws" | "gcp" | "azure"), used to select cloud-specific
// behavior such as the capture proxy's internal load-balancer annotation.
//
// This MUST be resolved as a TEMPLATE INPUT (via addInputsFromRecord on the entry
// template), NOT as a global workflow argument: Argo v4 does not evaluate
// valueFrom.configMapKeyRef on a Workflow's global spec.arguments when the workflow
// is submitted via workflowTemplateRef (the console CLI path) — it takes the static
// `value` fallback. configMapKeyRef IS honored on template `inputs`. This mirrors how
// image params resolve (defaultImagesMap): resolve at the top template's inputs, then
// thread the resolved value down the step chain via selectInputsForRegister.
//
// The expression ("aws") is a build-time fallback. The provider-config ConfigMap is
// rendered unconditionally by Helm with the cloudProvider key always present, because
// Argo v4 errors on a missing configMapKeyRef key even with optional:true.
export function cloudProviderResolvedInput(): { cloudProvider: InputParamDef<string, false> } {
    return {
        cloudProvider: defineParam({
            from: configMapKey(PROVIDER_CONFIG_MAP_NAME, "cloudProvider"),
            type: typeToken<string>(),
            expression: "aws",
        }),
    };
}

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
