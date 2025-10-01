import {WorkflowBuilder} from "@/argoWorkflowBuilders/models/workflowBuilder";
import {
    CommonWorkflowParameters,
    makeRequiredImageParametersForKeys
} from "@/workflowTemplates/commonWorkflowTemplates";
import {MigrationConsole} from "@/workflowTemplates/migrationConsole";
import {z} from "zod";
import {
    CLUSTER_CONFIG,
    COMPLETE_SNAPSHOT_CONFIG,
    METADATA_OPTIONS, REPLAYER_OPTIONS,
    TARGET_CLUSTER_CONFIG
} from "@/workflowTemplates/userSchemas";
import {MISSING_FIELD} from "@/argoWorkflowBuilders/models/plainObject";
import {
    selectInputsFieldsAsExpressionRecord,
    selectInputsForRegister
} from "@/argoWorkflowBuilders/models/parameterConversions";
import {typeToken} from "@/argoWorkflowBuilders/models/sharedTypes";
import expr from "@/argoWorkflowBuilders/models/expression";
import {K8sActionVerb} from "@/argoWorkflowBuilders/models/k8sResourceBuilder";
import {TemplateBuilder} from "@/argoWorkflowBuilders/models/templateBuilder";
import {INTERNAL} from "@/argoWorkflowBuilders/models/taskBuilder";
import {inputsToEnvVars, inputsToEnvVarsList, remapRecordNames, transformZodObjectToParams} from "@/utils";
import {defineRequiredParam} from "@/argoWorkflowBuilders/models/parameterSchemas";

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
