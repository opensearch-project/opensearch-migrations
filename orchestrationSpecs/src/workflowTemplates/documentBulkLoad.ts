import {WorkflowBuilder} from "@/schemas/workflowBuilder";
import {
    CommonWorkflowParameters,
    makeImageParametersForKeys,
    s3ConfigParam
} from "@/workflowTemplates/commonWorkflowTemplates";
import {defineRequiredParam, typeToken} from "@/schemas/parameterSchemas";
import {z} from "zod/index";
import {CLUSTER_CONFIG, SNAPSHOT_MIGRATION_CONFIG, TARGET_CLUSTER_CONFIG} from "@/workflowTemplates/userSchemas";

export const DocumentBulkLoad = WorkflowBuilder.create({
    k8sResourceName: "DocumentBulkLoad",
    serviceAccountName: "argo-workflow-executor"
})

    .addParams(CommonWorkflowParameters)


    .addTemplate("runBulkLoadFromConfig", t=>t
        .addRequiredInput("targetConfig", typeToken<z.infer<typeof TARGET_CLUSTER_CONFIG>>())
        .addRequiredInput("snapshotConfig", typeToken<z.infer<typeof SNAPSHOT_MIGRATION_CONFIG>>())
        .addRequiredInput("sessionName", typeToken<string>())
        .addInputsFromRecord(makeImageParametersForKeys(["ReindexFromSnapshot"]))

        .addSteps(sb=>sb)
    )

    .getFullScope();