import {z} from "zod";
import {CreateSnapshot} from "./createSnapshot";
import {SNAPSHOT_NAME_CONFIG} from "@opensearch-migrations/schemas";
import {
    COMPLETE_SNAPSHOT_CONFIG,
    CREATE_SNAPSHOT_OPTIONS,
    DYNAMIC_SNAPSHOT_CONFIG, NAMED_SOURCE_CLUSTER_CONFIG
} from "@opensearch-migrations/schemas";
import {
    BaseExpression,
    expr,
    getAcceptedRegisterKeys,
    INTERNAL, makeDirectTypeProxy,
    selectInputsForKeys,
    selectInputsForRegister,
    typeToken,
    WorkflowBuilder
} from "@opensearch-migrations/argo-workflow-builders";

import {CommonWorkflowParameters} from "./commonUtils/workflowParameters";
import {makeRequiredImageParametersForKeys} from "./commonUtils/imageDefinitions";

export const CreateOrGetSnapshot = WorkflowBuilder.create({
    k8sResourceName: "create-or-get-snapshot",
    serviceAccountName: "argo-workflow-executor"
})

    .addParams(CommonWorkflowParameters)


    .addTemplate("getSnapshotName", t=>t
        .addRequiredInput("sourceName", typeToken<string>())
        .addRequiredInput("snapshotNameConfig", typeToken<z.infer<typeof SNAPSHOT_NAME_CONFIG>>())
        .addRequiredInput("uniqueRunNonce", typeToken<string>())

        .addSteps(b => b
            .addStepGroup(c => c))
        .addExpressionOutput("snapshotName", b=>
            expr.ternary(
                expr.hasKey(expr.deserializeRecord(b.inputs.snapshotNameConfig), "snapshotNamePrefix"),
                expr.concatWith("_",
                    b.inputs.sourceName,
                    expr.getLoose(expr.deserializeRecord(b.inputs.snapshotNameConfig), "snapshotNamePrefix"),
                    b.inputs.uniqueRunNonce
                ),
                expr.getLoose(expr.deserializeRecord(b.inputs.snapshotNameConfig), "externallyManagedSnapshot"))
        )
        .addExpressionOutput("autoCreate", b=>
            expr.hasKey(expr.deserializeRecord(b.inputs.snapshotNameConfig), "snapshotNamePrefix")
        )
    )


    .addTemplate("createOrGetSnapshot", t => t
        .addRequiredInput("createSnapshotConfig", typeToken<z.infer<typeof CREATE_SNAPSHOT_OPTIONS>>())
        .addRequiredInput("sourceConfig", typeToken<z.infer<typeof NAMED_SOURCE_CLUSTER_CONFIG>>())
        .addRequiredInput("snapshotConfig", typeToken<z.infer<typeof DYNAMIC_SNAPSHOT_CONFIG>>())
        .addRequiredInput("uniqueRunNonce", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))

        .addSteps(b => b
            .addStep("getSnapshotName", INTERNAL, "getSnapshotName", c=>c.register({
                    ...selectInputsForRegister(b, c),
                    sourceName: expr.get(expr.deserializeRecord(b.inputs.sourceConfig), "name"),
                    snapshotNameConfig: expr.serialize(
                        expr.get(expr.deserializeRecord(b.inputs.snapshotConfig), "snapshotNameConfig")) as any,
                })
            )
            .addStep("createSnapshot", CreateSnapshot, "snapshotWorkflow",
                c => c.register({
                    ...selectInputsForRegister(b, c),
                    ...selectInputsForKeys(b, getAcceptedRegisterKeys(c)),
                    snapshotConfig: expr.serialize(
                        expr.makeDict({
                            repoConfig: expr.jsonPathStrict(b.inputs.snapshotConfig, "repoConfig"),
                            snapshotName: expr.toLowerCase(c.steps.getSnapshotName.outputs.snapshotName),
                        })
                    ),
                }), {
                    when: tasks => tasks.getSnapshotName.outputs.autoCreate
                }
            )
        )
        .addExpressionOutput("snapshotConfig", c =>
            expr.serialize(expr.makeDict({
                snapshotName: c.steps.getSnapshotName.outputs.snapshotName,
                repoConfig: expr.get(expr.deserializeRecord(c.inputs.snapshotConfig), "repoConfig")
            })))
    )

    .getFullScope();
