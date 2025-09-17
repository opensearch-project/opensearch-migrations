import {WorkflowBuilder} from "@/schemas/workflowBuilder";
import {
    CommonWorkflowParameters,
    makeRequiredImageParametersForKeys
} from "@/workflowTemplates/commonWorkflowTemplates";
import {z} from "zod/index";
import {CLUSTER_CONFIG, DYNAMIC_SNAPSHOT_CONFIG} from "@/workflowTemplates/userSchemas";
import {expr, literal} from "@/schemas/expression";
import {CreateSnapshot} from "@/workflowTemplates/createSnapshot";
import {getAcceptedRegisterKeys, selectInputsForKeys} from "@/schemas/parameterConversions";
import {typeToken} from "@/schemas/sharedTypes";

export const CreateOrGetSnapshot = WorkflowBuilder.create({
    k8sResourceName: "CreateOrGetSnapshot",
    serviceAccountName: "argo-workflow-executor"
})

    .addParams(CommonWorkflowParameters)


    .addTemplate("createOrGetSnapshot", t=>t
        .addRequiredInput("autocreateSnapshotName", typeToken<string>())
        .addRequiredInput("indices", typeToken<string[]>())
        .addRequiredInput("sourceConfig", typeToken<z.infer<typeof CLUSTER_CONFIG>>())
        .addRequiredInput("snapshotConfig", typeToken<z.infer<typeof DYNAMIC_SNAPSHOT_CONFIG>>())
        .addOptionalInput("alreadyDefinedName",
                c=>
                    expr.nullCoalesce(expr.jsonPathLoose(c.inputParameters.snapshotConfig, "snapshotName"), ""))
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addSteps(b=>b
            .addStep("createSnapshot", CreateSnapshot, "snapshotWorkflow", c=>c
                .register({
                    ...selectInputsForKeys(b, getAcceptedRegisterKeys(c)),
                    snapshotConfig: expr.makeDict({
                        repoConfig: expr.jsonPathLoose(b.inputs.snapshotConfig, "repoConfig"),
                        snapshotName: b.inputs.autocreateSnapshotName
                    }),
                }), {when: expr.equals(b.inputs.alreadyDefinedName, literal(""))})
        )
         .addExpressionOutput("snapshotConfig",
                 c=> c.steps.createSnapshot.outputs.snapshotConfig))


    .getFullScope();