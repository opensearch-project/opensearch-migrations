import {
    CommonWorkflowParameters,
    makeRequiredImageParametersForKeys
} from "./commonWorkflowTemplates";
import {z} from "zod";
import {
    CLUSTER_CONFIG,
    COMPLETE_SNAPSHOT_CONFIG,
    METADATA_OPTIONS,
    TARGET_CLUSTER_CONFIG
} from "@opensearch-migrations/schemas";
import {
    defineRequiredParam,
    inputsToEnvVars,
    INTERNAL,
    selectInputsFieldsAsExpressionRecord,
    selectInputsForRegister,
    transformZodObjectToParams,
    typeToken,
    WorkflowBuilder
} from "@opensearch-migrations/argo-workflow-builders";

const COMMON_METADATA_PARAMETERS = {
    snapshotConfig: defineRequiredParam<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>({ description:
            "Snapshot storage details (region, endpoint, etc)"}),
    sourceConfig: defineRequiredParam<z.infer<typeof CLUSTER_CONFIG>>(),
    targetConfig: defineRequiredParam<z.infer<typeof TARGET_CLUSTER_CONFIG>>(),
    useLocalStack: defineRequiredParam<boolean>({description: "Only used for local testing" }),
    ...makeRequiredImageParametersForKeys(["MigrationConsole"])
};

export const MetadataMigration = WorkflowBuilder.create({
    k8sResourceName: "metadata-migration",
    serviceAccountName: "argo-workflow-executor"
})

    .addParams(CommonWorkflowParameters)


    .addTemplate("runMetadata", t=>t
        .addRequiredInput("commandMode", typeToken<"evaluate"|"migrate">())
        .addInputsFromRecord(COMMON_METADATA_PARAMETERS)
        .addInputsFromRecord(transformZodObjectToParams(METADATA_OPTIONS))

        .addContainer(b=>b
            .addImageInfo(b.inputs.imageMigrationConsoleLocation, b.inputs.imageMigrationConsolePullPolicy)
            .addEnvVarsFromRecord(inputsToEnvVars(b.inputs,
                // {
                //   ...remapRecordNames(b.inputs.metadataMigrationConfig, {}),
                // },
                "METADATA_", "_CMD_LINE_ARG"))
            .addCommand(["/root/metadataMigration/bin/MetadataMigration", b.inputs.commandMode])
        )
    )


    .addSuspendTemplate("approveEvaluate")
    .addSuspendTemplate("approveMigrate")


    .addTemplate("migrateMetaData", t => t
        .addRequiredInput("metadataMigrationConfig", typeToken<z.infer<typeof METADATA_OPTIONS>>())
        .addInputsFromRecord(COMMON_METADATA_PARAMETERS)
        .addSteps(b => b
            .addStep("metadataEvaluate", INTERNAL, "runMetadata", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    ...selectInputsFieldsAsExpressionRecord(b.inputs.metadataMigrationConfig, c),
                    commandMode: "evaluate"
                })
            )
            .addStep("approveEvaluate", INTERNAL, "approveEvaluate")
            .addStep("metadataMigrate", INTERNAL, "runMetadata", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    ...selectInputsFieldsAsExpressionRecord(b.inputs.metadataMigrationConfig, c),
                    commandMode: "migrate"
                })
            )
            .addStep("approveMigrate", INTERNAL, "approveMigrate")
        )
    )


    .getFullScope();
