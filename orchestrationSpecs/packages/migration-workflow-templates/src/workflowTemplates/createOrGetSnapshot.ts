import {z} from "zod";
import {CreateSnapshot} from "./createSnapshot";
import {ARGO_CREATE_SNAPSHOT_OPTIONS, SNAPSHOT_NAME_CONFIG} from "@opensearch-migrations/schemas";
import {
    COMPLETE_SNAPSHOT_CONFIG,
    CREATE_SNAPSHOT_OPTIONS,
    DYNAMIC_SNAPSHOT_CONFIG, NAMED_SOURCE_CLUSTER_CONFIG_WITHOUT_SNAPSHOT_INFO
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
        .addRequiredInput("sourceLabel", typeToken<string>())
        .addRequiredInput("snapshotNameConfig", typeToken<z.infer<typeof SNAPSHOT_NAME_CONFIG>>())
        .addRequiredInput("snapshotPrefix", typeToken<string>())
        .addRequiredInput("uniqueRunNonce", typeToken<string>())

        .addSteps(b => b
            .addStepGroup(c => c))
        .addExpressionOutput("snapshotName", b=>
            expr.ternary(
                expr.hasKey(expr.deserializeRecord(b.inputs.snapshotNameConfig), "createSnapshotConfig"),
                expr.concatWith("_",
                    b.inputs.sourceLabel,
                    b.inputs.snapshotPrefix,
                    b.inputs.uniqueRunNonce
                ),
                expr.getLoose(expr.deserializeRecord(b.inputs.snapshotNameConfig), "externallyManagedSnapshotName"))
        )
        .addExpressionOutput("autoCreate", b=>
            expr.hasKey(expr.deserializeRecord(b.inputs.snapshotNameConfig), "createSnapshotConfig")
        )
    )


    .addTemplate("createOrGetSnapshot", t => t
        .addRequiredInput("createSnapshotConfig", typeToken<z.infer<typeof ARGO_CREATE_SNAPSHOT_OPTIONS>>())
        .addRequiredInput("sourceConfig", typeToken<z.infer<typeof NAMED_SOURCE_CLUSTER_CONFIG_WITHOUT_SNAPSHOT_INFO>>())
        .addRequiredInput("snapshotConfig", typeToken<z.infer<typeof DYNAMIC_SNAPSHOT_CONFIG>>())
        .addRequiredInput("snapshotPrefix", typeToken<string>())
        .addRequiredInput("targetLabel", typeToken<string>())
        .addRequiredInput("uniqueRunNonce", typeToken<string>())
        .addRequiredInput("semaphoreConfigMapName", typeToken<string>())
        .addRequiredInput("semaphoreKey", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))

        .addSteps(b => b
            .addStep("getSnapshotName", INTERNAL, "getSnapshotName", c=>c.register({
                    ...selectInputsForRegister(b, c),
                    sourceLabel: expr.get(expr.deserializeRecord(b.inputs.sourceConfig), "label"),
                    snapshotNameConfig: expr.serialize(
                        expr.get(expr.deserializeRecord(b.inputs.snapshotConfig), "config")) as any,
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
                            label: expr.jsonPathStrict(b.inputs.snapshotConfig, "label"),
                        })
                    ),
                    semaphoreConfigMapName: b.inputs.semaphoreConfigMapName,
                    semaphoreKey: b.inputs.semaphoreKey
                }), {
                    when: tasks => tasks.getSnapshotName.outputs.autoCreate
                }
            )
        )
        .addExpressionOutput("snapshotConfig", c =>
            expr.serialize(expr.makeDict({
                snapshotName: c.steps.getSnapshotName.outputs.snapshotName,
                repoConfig: expr.get(expr.deserializeRecord(c.inputs.snapshotConfig), "repoConfig"),
                label: expr.get(expr.deserializeRecord(c.inputs.snapshotConfig), "label")
            })))
    )

    .getFullScope();
