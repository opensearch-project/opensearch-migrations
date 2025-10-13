import {
    CommonWorkflowParameters,
    makeRequiredImageParametersForKeys
} from "./commonWorkflowTemplates";
import {z} from "zod";
import {
    CLUSTER_CONFIG,
    COMPLETE_SNAPSHOT_CONFIG, getZodKeys, HTTP_AUTH_BASIC,
    METADATA_OPTIONS, NAMED_SOURCE_CLUSTER_CONFIG, NAMED_TARGET_CLUSTER_CONFIG, S3_REPO_CONFIG,
    TARGET_CLUSTER_CONFIG
} from "@opensearch-migrations/schemas";
import {
    BaseExpression,
    defineRequiredParam, expr,
    inputsToEnvVars,
    INTERNAL,
    selectInputsFieldsAsExpressionRecord,
    selectInputsForRegister, Serialized,
    transformZodObjectToParams,
    typeToken,
    WorkflowBuilder
} from "@opensearch-migrations/argo-workflow-builders";

const COMMON_METADATA_PARAMETERS = {
    snapshotConfig: defineRequiredParam<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>({ description:
            "Snapshot storage details (region, endpoint, etc)"}),
    sourceConfig: defineRequiredParam<z.infer<typeof NAMED_SOURCE_CLUSTER_CONFIG>>(),
    targetConfig: defineRequiredParam<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>(),
    ...makeRequiredImageParametersForKeys(["MigrationConsole"])
};

export function makeAuthDict(targetConfig: BaseExpression<Serialized<z.infer<typeof TARGET_CLUSTER_CONFIG>>>) {
    // const safeAuthConfig = expr.stripUndefined(expr.get(expr.deserializeRecord(targetConfig), "authConfig"));
    const safeAuthConfig = (expr.getLoose(expr.deserializeRecord(targetConfig), "authConfig"));
    return expr.ternary(
        expr.hasKey(expr.deserializeRecord(targetConfig), "authConfig"),
        expr.ternary(
            expr.hasKey(safeAuthConfig, "basic"),
            expr.makeDict({
                "targetUsername": expr.getLoose(expr.getLoose(safeAuthConfig, "basic"), "username"),
                "targetPassword": expr.getLoose(expr.getLoose(safeAuthConfig, "basic"), "password")
            }),
            expr.ternary(
                expr.hasKey(safeAuthConfig, "sigv4"),
                expr.makeDict({
                    "targetAwsServiceSigningName": expr.getLoose(expr.getLoose(safeAuthConfig, "sigv4"), "service"),
                    "targetAwsRegion": expr.getLoose(expr.getLoose(safeAuthConfig, "sigv4"), "region")
                }),
                expr.ternary(
                    expr.hasKey(safeAuthConfig, "mtls"),
                    expr.makeDict({
                        "targetCaCert": expr.getLoose(expr.getLoose(safeAuthConfig, "mtls"), "caCert"),
                    }),
                    expr.literal({})
                )
            )
        ),
        expr.literal({}))
}

export function makeTargetParamDict(targetConfig: BaseExpression<Serialized<z.infer<typeof TARGET_CLUSTER_CONFIG>>>) {
    return expr.mergeDicts(
        makeAuthDict(targetConfig),
        expr.makeDict({
            "targetHost": expr.jsonPathStrict(targetConfig, "endpoint"),
            "targetInsecure": expr.dig(expr.deserializeRecord(targetConfig), ["allowInsecure"], false)
        })
    );
}

export function makeRepoParamDict(repoConfig: BaseExpression<z.infer<typeof S3_REPO_CONFIG>>) {
    return expr.makeDict({
        "s3Endpoint": expr.get(repoConfig, "endpoint"),
        "s3RepoUri": expr.get(repoConfig, "s3RepoPathUri"),
        "s3Region": expr.get(repoConfig, "aws_region"),
        "s3LocalDir": expr.literal("/tmp")
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
            makeRepoParamDict(expr.get(expr.deserializeRecord(snapshotConfig), "repoConfig"))
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
