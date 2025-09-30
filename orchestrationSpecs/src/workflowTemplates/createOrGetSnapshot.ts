import {WorkflowBuilder} from "@/argoWorkflowBuilders/models/workflowBuilder";
import {
    CommonWorkflowParameters,
    makeRequiredImageParametersForKeys
} from "@/workflowTemplates/commonWorkflowTemplates";
import {z} from "zod";
import {CLUSTER_CONFIG, COMPLETE_SNAPSHOT_CONFIG, DYNAMIC_SNAPSHOT_CONFIG} from "@/workflowTemplates/userSchemas";
import {expr} from "@/argoWorkflowBuilders/models/expression";
import {CreateSnapshot} from "@/workflowTemplates/createSnapshot";
import {getAcceptedRegisterKeys, selectInputsForKeys} from "@/argoWorkflowBuilders/models/parameterConversions";
import {typeToken} from "@/argoWorkflowBuilders/models/sharedTypes";

export const CreateOrGetSnapshot = WorkflowBuilder.create({
    k8sResourceName: "create-or-get-snapshot",
    serviceAccountName: "argo-workflow-executor"
})

    .addParams(CommonWorkflowParameters)


    .addTemplate("createOrGetSnapshot", t => t
        .addRequiredInput("autocreateSnapshotName", typeToken<string>())
        .addRequiredInput("indices", typeToken<string[]>())
        .addRequiredInput("sourceConfig", typeToken<z.infer<typeof CLUSTER_CONFIG>>())
        .addRequiredInput("snapshotConfig", typeToken<z.infer<typeof DYNAMIC_SNAPSHOT_CONFIG>>())
        .addOptionalInput("alreadyDefinedName", c =>
            expr.dig(expr.deserializeRecord(c.inputParameters.snapshotConfig), ["snapshotName"], ""))
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addSteps(b => b
            .addStep("createSnapshot", CreateSnapshot, "snapshotWorkflow", c => c
                .register({
                    ...selectInputsForKeys(b, getAcceptedRegisterKeys(c)),
                    snapshotConfig: expr.serialize(
                        expr.makeDict({
                            repoConfig: expr.jsonPathStrict(b.inputs.snapshotConfig, "repoConfig"),
                            snapshotName: expr.toLowerCase(b.inputs.autocreateSnapshotName)
                        }),
                    )
                }), {when: expr.equals(b.inputs.alreadyDefinedName, expr.literal("")) })
        )
        .addExpressionOutput("snapshotConfig", c =>
            expr.ternary(
                expr.equals(c.steps.createSnapshot.status, "Skipped"),
                expr.cast(c.inputs.snapshotConfig).to<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>(),
                expr.cast(c.steps.createSnapshot.outputs.snapshotConfig).to<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>()))
    )

    .getFullScope();