import {
    selectInputsForRegister,
    typeToken,
    WorkflowBuilder
} from '@opensearch-migrations/argo-workflow-builders';
import {WorkflowCommandOrchestrator} from "./workflowCommandOrchestrator";
import {CommonWorkflowParameters} from "./commonUtils/workflowParameters";
import {makeRequiredImageParametersForKeys} from "./commonUtils/imageDefinitions";

export const FullMigrationWithCli = WorkflowBuilder.create({
    k8sResourceName: "full-migration-with-cli",
    parallelism: 1,
    serviceAccountName: "argo-workflow-executor"
})
    .addParams(CommonWorkflowParameters)
    .addTemplate("main", t => t
        .addRequiredInput("sourceClusterConfigJson", typeToken<string>())
        .addRequiredInput("targetClusterConfigJson", typeToken<string>())
        .addRequiredInput("snapshotConfigJson", typeToken<string>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addSteps(b => b
            .addStep("runMigrationViaWorkflowCli", WorkflowCommandOrchestrator, "main", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    sourceClusterConfigJson: b.inputs.sourceClusterConfigJson,
                    targetClusterConfigJson: b.inputs.targetClusterConfigJson,
                    snapshotConfigJson: b.inputs.snapshotConfigJson
                })
            )
        )
    )
    .setEntrypoint("main")
    .getFullScope();
