import {WorkflowBuilder} from "@/schemas/workflowBuilder";
import {
    CommonWorkflowParameters, makeRequiredImageParametersForKeys
} from "@/workflowTemplates/commonWorkflowTemplates";
import {typeToken} from "@/schemas/parameterSchemas";
import {z} from "zod/index";
import {expr} from "@/schemas/expression";
import {SNAPSHOT_MIGRATION_CONFIG, TARGET_CLUSTER_CONFIG, UNKNOWN} from "@/workflowTemplates/userSchemas";

export const DocumentBulkLoad = WorkflowBuilder.create({
    k8sResourceName: "DocumentBulkLoad",
    serviceAccountName: "argo-workflow-executor"
})

    .addParams(CommonWorkflowParameters)


    .addTemplate("deleteReplicaSet", t=>t
        .addRequiredInput("name", typeToken<string>())
        .addResourceTask(b=>b
            .setDefinition({action: "delete", flags: ["--ignore-not-found"],
                 manifest: {
                    "apiVersion": "apps/v1",
                    "kind": "ReplicaSet",
                    "metadata": {
                        "name": expr.concat(expr.literal("bulk-loader-"), b.inputs.name)
                    }
                }
            })
        ))

    .addTemplate("waitForCompletion", t=>t
        .addRequiredInput("configContents", typeToken<z.infer<typeof UNKNOWN>>())
        .addRequiredInput("sessionName", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addSteps(s=>s
            //.addStep("checkRfsCompletion", )
        )
    )

    .addTemplate("runBulkLoadFromConfig", t=>t
        .addRequiredInput("targetConfig", typeToken<z.infer<typeof TARGET_CLUSTER_CONFIG>>())
        .addRequiredInput("snapshotConfig", typeToken<z.infer<typeof SNAPSHOT_MIGRATION_CONFIG>>())
        .addRequiredInput("sessionName", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["ReindexFromSnapshot"]))

        .addSteps(sb=>sb)
    )

    .getFullScope();