import {WorkflowBuilder} from "@/schemas/workflowBuilder";
import {
    CommonWorkflowParameters,
    makeRequiredImageParametersForKeys
} from "@/workflowTemplates/commonWorkflowTemplates";
import {typeToken} from "@/schemas/parameterSchemas";
import {z} from "zod/index";
import {CLUSTER_CONFIG, CONSOLE_SERVICES_CONFIG_FILE, S3_CONFIG} from "@/workflowTemplates/userSchemas";
import {MigrationConsole} from "@/workflowTemplates/migrationConsole";
import {INTERNAL, selectInputsForKeys, selectInputsForRegister} from "@/schemas/taskBuilder";
import {MISSING_FIELD} from "@/schemas/plainObject";

export const CreateSnapshot = WorkflowBuilder.create({
    k8sResourceName: "FullMigration",
    parallelism: 100,
    serviceAccountName: "argo-workflow-executor"
})

    .addParams(CommonWorkflowParameters)


    .addTemplate("checkSnapshotStatus", t=>t
        .addRequiredInput("configContents", typeToken<z.infer<typeof CONSOLE_SERVICES_CONFIG_FILE>>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addSteps(b=>b
            .addStep("checkSnapshotCompletion", MigrationConsole, "runMigrationCommand", c=>
                c.register({
                    ...selectInputsForRegister(b,c),
                    command: "set -e && [ \"$(console --config-file=/config/migration_services.yaml snapshot status)\" = \"SUCCESS\" ] && exit 0 || exit 1"
                }))
        )
        .addRetryParameters({limit: "200", retryPolicy: "Always",
            backoff: { duration: "5", factor: "2", maxDuration: "300"}})
    )


    .addTemplate("snapshotWorkflow", t=>t
        .addRequiredInput("snapshotName", typeToken<string>())
        .addRequiredInput("sourceConfig", typeToken<z.infer<typeof CLUSTER_CONFIG>>())
        .addRequiredInput("s3Config", typeToken<z.infer<typeof S3_CONFIG>>())
        .addRequiredInput("indices", typeToken<string[]>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))

        .addSteps(b=>b
            .addStep("getConsoleConfig", MigrationConsole, "getConsoleConfig", c=>
               c.register({
                   ...selectInputsForRegister(b, c),
                   kafkaInfo: MISSING_FIELD,
                   targetConfig: MISSING_FIELD
               }))

            .addStep("", MigrationConsole, "runMigrationCommand", c=>
                c.register({
                    ...selectInputsForRegister(b, c),
                    configContents: c.steps.getConsoleConfig.outputs.configContents,
                    command: "" +
                        "set -e && \n" +
                        "console --config-file=/config/migration_services.yaml -v snapshot delete --acknowledge-risk ;\n" +
                        "console --config-file=/config/migration_services.yaml -v snapshot create\n"
                }))

            .addStep("checkSnapshotStatus", INTERNAL, "checkSnapshotStatus", c=>
                c.register({
                    ...selectInputsForRegister(b, c),
                    configContents: c.steps.getConsoleConfig.outputs.configContents
                }))
        )
    )


    .getFullScope();
