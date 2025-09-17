import {WorkflowBuilder} from "@/schemas/workflowBuilder";
import {
    CommonWorkflowParameters,
    completeSnapshotConfigParam, makeRequiredImageParametersForKeys
} from "@/workflowTemplates/commonWorkflowTemplates";
import {MigrationConsole} from "@/workflowTemplates/migrationConsole";
import {z} from "zod/index";
import {CLUSTER_CONFIG, METADATA_OPTIONS, TARGET_CLUSTER_CONFIG} from "@/workflowTemplates/userSchemas";
import {MISSING_FIELD} from "@/schemas/plainObject";
import {selectInputsForRegister} from "@/schemas/parameterConversions";
import {typeToken} from "@/schemas/sharedTypes";

export const MetadataMigration = WorkflowBuilder.create({
    k8sResourceName: "MetadataMigration",
    serviceAccountName: "argo-workflow-executor"
})

    .addParams(CommonWorkflowParameters)


    .addTemplate("migrateMetaData", t=>t
        .addRequiredInput("metadataMigrationConfig", typeToken<z.infer<typeof METADATA_OPTIONS>>())
        .addRequiredInput("indices", typeToken<string[]>())
        .addInputsFromRecord(completeSnapshotConfigParam)
        .addRequiredInput("sourceConfig", typeToken<z.infer<typeof CLUSTER_CONFIG>>())
        .addRequiredInput("targetConfig", typeToken<z.infer<typeof TARGET_CLUSTER_CONFIG>>())
        .addRequiredInput("useLocalStack", typeToken<boolean>(), "Only used for local testing")
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))


        .addSteps(b=>b
            .addStep("migrateMetadataFromConfig", MigrationConsole, "getConsoleConfig", c=>
                c.register({
                    ...selectInputsForRegister(b, c),
                    kafkaInfo: MISSING_FIELD
                }))
            .addStep("runMetadataMigration", MigrationConsole, "runMigrationCommand", c=>
                c.register({
                    ...selectInputsForRegister(b, c),
                    // TODO - eventually funnel all of the options into the command -
                    // see selectInputsFieldsAsExpressionRecord for an easy way to route this
                    // (would need another helper template)
                    configContents: c.steps.migrateMetadataFromConfig.outputs.configContents,
                    command: "set -e && " +
                        "console --config-file=/config/migration_services.yaml -v metadata evaluate ; " +
                        "console --config-file=/config/migration_services.yaml -v metadata migrate"
                }))
        )
    )


    .getFullScope();
