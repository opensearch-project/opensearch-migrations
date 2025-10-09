import {WorkflowBuilder} from "@/argoWorkflowBuilders/models/workflowBuilder";
import {
    CommonWorkflowParameters,
    makeRequiredImageParametersForKeys
} from "@/workflowTemplates/commonWorkflowTemplates";
import {z} from "zod";
import {CLUSTER_CONFIG, COMPLETE_SNAPSHOT_CONFIG, CONSOLE_SERVICES_CONFIG_FILE} from "@/workflowTemplates/userSchemas";
import {MigrationConsole} from "@/workflowTemplates/migrationConsole";
import {INTERNAL} from "@/argoWorkflowBuilders/models/taskBuilder";
import {MISSING_FIELD} from "@/argoWorkflowBuilders/models/plainObject";
import {selectInputsForRegister} from "@/argoWorkflowBuilders/models/parameterConversions";
import {typeToken} from "@/argoWorkflowBuilders/models/sharedTypes";

export const CreateSnapshot = WorkflowBuilder.create({
    k8sResourceName: "create-snapshot",
    parallelism: 100,
    serviceAccountName: "argo-workflow-executor"
})

    .addParams(CommonWorkflowParameters)


    .addTemplate("checkSnapshotStatus", t => t
        .addRequiredInput("configContents", typeToken<z.infer<typeof CONSOLE_SERVICES_CONFIG_FILE>>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addSteps(b => b
            .addStep("checkSnapshotCompletion", MigrationConsole, "runMigrationCommand", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    command: "set -e && [ \"$(console --config-file=/config/migration_services.yaml snapshot status)\" = \"SUCCESS\" ] && exit 0 || exit 1"
                }))
        )
        .addRetryParameters({
            limit: "200", retryPolicy: "Always",
            backoff: {duration: "5", factor: "2", maxDuration: "300"}
        })
    )


    .addTemplate("snapshotWorkflow", t => t
        .addRequiredInput("sourceConfig", typeToken<z.infer<typeof CLUSTER_CONFIG>>())
        .addRequiredInput("snapshotConfig", typeToken<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>())
        .addRequiredInput("indices", typeToken<string[]>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))

        .addSteps(b => b
            .addStep("getConsoleConfig", MigrationConsole, "getConsoleConfig", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    kafkaInfo: MISSING_FIELD,
                    targetConfig: MISSING_FIELD
                }))

            .addStep("createSnapshot", MigrationConsole, "runMigrationCommand", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    configContents: c.steps.getConsoleConfig.outputs.configContents,
                    command: "" +
                        "set -e && \n" +
                        "console --config-file=/config/migration_services.yaml -v snapshot delete --acknowledge-risk ;\n" +
                        "console --config-file=/config/migration_services.yaml -v snapshot create\n"
                }))

            .addStep("checkSnapshotStatus", INTERNAL, "checkSnapshotStatus", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    configContents: c.steps.getConsoleConfig.outputs.configContents
                }))
        )
        .addExpressionOutput("snapshotConfig", b => b.inputs.snapshotConfig)
    )


    .getFullScope();
