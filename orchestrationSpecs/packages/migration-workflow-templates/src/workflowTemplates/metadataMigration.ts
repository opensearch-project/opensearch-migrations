import {z} from "zod";
import {
    COMPLETE_SNAPSHOT_CONFIG,
    METADATA_OPTIONS,
    NAMED_SOURCE_CLUSTER_CONFIG,
    NAMED_TARGET_CLUSTER_CONFIG,
    S3_REPO_CONFIG
} from "@opensearch-migrations/schemas";
import {
    BaseExpression,
    defineRequiredParam,
    expr,
    INTERNAL,
    selectInputsForRegister,
    Serialized,
    typeToken,
    WorkflowBuilder
} from "@opensearch-migrations/argo-workflow-builders";

import {CommonWorkflowParameters} from "./commonUtils/workflowParameters";
import {makeRequiredImageParametersForKeys} from "./commonUtils/imageDefinitions";
import {makeTargetParamDict} from "./commonUtils/clusterSettingManipulators";
import {getHttpAuthSecretName} from "./commonUtils/clusterSettingManipulators";
import {getTargetHttpAuthCreds} from "./commonUtils/basicCredsGetters";

const COMMON_METADATA_PARAMETERS = {
    snapshotConfig: defineRequiredParam<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>({ description:
            "Snapshot storage details (region, endpoint, etc)"}),
    sourceConfig: defineRequiredParam<z.infer<typeof NAMED_SOURCE_CLUSTER_CONFIG>>(),
    targetConfig: defineRequiredParam<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>(),
    ...makeRequiredImageParametersForKeys(["MigrationConsole"])
};

export function makeRepoParamDict(
    repoConfig: BaseExpression<z.infer<typeof S3_REPO_CONFIG>>,
    includes3LocalDir: boolean) {
    return expr.makeDict({
        "s3Endpoint": expr.get(repoConfig, "endpoint"),
        "s3RepoUri": expr.get(repoConfig, "s3RepoPathUri"),
        "s3Region": expr.get(repoConfig, "awsRegion"),
        ...(includes3LocalDir ? { "s3LocalDir": expr.literal("/tmp") } : {})
    });
}

function makeParamsDict(
    sourceConfig: BaseExpression<Serialized<z.infer<typeof NAMED_SOURCE_CLUSTER_CONFIG>>>,
    targetConfig: BaseExpression<Serialized<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>>,
    snapshotConfig: BaseExpression<Serialized<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>>,
    options: BaseExpression<Serialized<z.infer<typeof METADATA_OPTIONS>>>
) {
    return expr.mergeDicts(
        expr.mergeDicts(
            makeTargetParamDict(targetConfig),
             // TODO - tighten the type on mergeDicts - it allowed this to go through w/out first calling fromJSON
            expr.omit(expr.deserializeRecord(options), "loggingConfigurationOverrideConfigMap")
        ),
        expr.mergeDicts(
            expr.makeDict({
                "snapshotName": expr.get(expr.deserializeRecord(snapshotConfig), "snapshotName"),
                "sourceVersion": expr.get(expr.deserializeRecord(sourceConfig), "version")
            }),
            makeRepoParamDict(expr.get(expr.deserializeRecord(snapshotConfig), "repoConfig"), true)
        )
    );
}

export const MetadataMigration = WorkflowBuilder.create({
    k8sResourceName: "metadata-migration",
    serviceAccountName: "argo-workflow-executor"
})

    .addParams(CommonWorkflowParameters)


    .addTemplate("runMetadata", t=>t
        .addRequiredInput("commandMode", typeToken<"evaluate"|"migrate">())
        .addInputsFromRecord(COMMON_METADATA_PARAMETERS)
        .addRequiredInput("metadataMigrationConfig", typeToken<z.infer<typeof METADATA_OPTIONS>>())

        .addContainer(b=>b
            .addImageInfo(b.inputs.imageMigrationConsoleLocation, b.inputs.imageMigrationConsolePullPolicy)
            .addCommand(["/root/metadataMigration/bin/MetadataMigration"])
            .addVolumesFromRecord({
                'test-creds':  {
                    configMap: {
                        name: expr.literal("localstack-test-creds"),
                        optional: true
                    },
                    mountPath: "/config/credentials",
                    readOnly: true
                }
            })
            .addEnvVar("AWS_SHARED_CREDENTIALS_FILE",
                expr.ternary(
                    expr.dig(expr.deserializeRecord(b.inputs.snapshotConfig), ["repoConfig", "useLocalStack"], false),
                    expr.literal("/config/credentials/configuration"),
                    expr.literal(""))
            )
            .addEnvVarsFromRecord(getTargetHttpAuthCreds(getHttpAuthSecretName(b.inputs.targetConfig)))
            .addArgs([
                b.inputs.commandMode,
                expr.literal("---INLINE-JSON"),
                expr.asString(expr.serialize(
                    makeParamsDict(b.inputs.sourceConfig, b.inputs.targetConfig, b.inputs.snapshotConfig, b.inputs.metadataMigrationConfig
                    )
                ))
            ])
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
                    commandMode: "evaluate"
                })
            )
            .addStep("approveEvaluate", INTERNAL, "approveEvaluate")
            .addStep("metadataMigrate", INTERNAL, "runMetadata", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    commandMode: "migrate"
                })
            )
            .addStep("approveMigrate", INTERNAL, "approveMigrate")
        )
    )


    .getFullScope();
