import {WorkflowBuilder} from "@/schemas/workflowBuilder";
import {CommonWorkflowParameters, s3ConfigParam} from "@/workflowTemplates/commonWorkflowTemplates";
import {typeToken} from "@/schemas/parameterSchemas";
import {z} from "zod/index";
import {CLUSTER_CONFIG} from "@/workflowTemplates/userSchemas";
import { literal } from "@/schemas/expression";
import {SNAPSHOT_MIGRATION_CONFIG} from "@/workflowTemplates/userSchemas"

export const CreateOrGetSnapshot = WorkflowBuilder.create({
    k8sResourceName: "CreateOrGetSnapshot",
    serviceAccountName: "argo-workflow-executor"
})

    .addParams(CommonWorkflowParameters)


    .addTemplate("createOrGetSnapshot", t=>t
        .addRequiredInput("sourceName", typeToken<string>())
        .addRequiredInput("sourceConfig", typeToken<z.infer<typeof CLUSTER_CONFIG>>())
        .addInputsFromRecord(s3ConfigParam)
        .addSteps(sb=>sb)
         .addExpressionOutput("snapshotConfig", literal({
            
         } as z.infer<typeof SNAPSHOT_MIGRATION_CONFIG>)))

    .getFullScope();