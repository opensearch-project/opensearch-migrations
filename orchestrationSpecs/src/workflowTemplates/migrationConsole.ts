import {WorkflowBuilder} from "@/schemas/workflowBuilder";
import {
    CommonWorkflowParameters, makeRequiredImageParametersForKeys
} from "@/workflowTemplates/commonWorkflowTemplates";
import {typeToken} from "@/schemas/parameterSchemas";
import {z} from "zod/index";
import {expr} from "@/schemas/expression";
import {CLUSTER_CONFIG, SNAPSHOT_MIGRATION_CONFIG, TARGET_CLUSTER_CONFIG, UNKNOWN} from "@/workflowTemplates/userSchemas";

export const MigrationConsole = WorkflowBuilder.create({
    k8sResourceName: "MigrationConsole",
    serviceAccountName: "argo-workflow-executor"
})

    .addParams(CommonWorkflowParameters)


    .addTemplate("getConsoleConfig", b=>b
        .addRequiredInput("kafkaInfo", typeToken<z.infer<typeof UNKNOWN>>())
        .addRequiredInput("sourceCluster", typeToken<z.infer<typeof CLUSTER_CONFIG>>())
        .addRequiredInput("snapshotInfo", typeToken<z.infer<typeof UNKNOWN>>())
        .addRequiredInput("targetCluster", typeToken<z.infer<typeof TARGET_CLUSTER_CONFIG>>())
        .addSteps(s=>s)
        .addExpressionOutput("configContents", expr.concat(
            b.inputs.inputParameters
        ))
    )

    .addTemplate("runMigrationCommand", b=>b
        .
    )


    .getFullScope();