import {z} from "zod";
import {
    COMPLETE_SNAPSHOT_CONFIG,
    DEFAULT_RESOURCES,
    METADATA_OPTIONS,
    NAMED_SOURCE_CLUSTER_CONFIG,
    NAMED_TARGET_CLUSTER_CONFIG,
    S3_REPO_CONFIG
} from "@opensearch-migrations/schemas";
import {
    BaseExpression, configMapKey,
    defineRequiredParam,
    expr, FunctionExpression, InputParameterSource, InputParametersRecord, InputParamsToExpressions,
    INTERNAL, PlainObject,
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
import {
    getApprovalMap,
    getSourceTargetPathAndSnapshotAndMigrationIndex
} from "./commonUtils/configContextPathConstructors";

const COMMON_METADATA_PARAMETERS = {
    snapshotConfig: defineRequiredParam<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>({ description:
            "Snapshot storage details (region, endpoint, etc)"}),
    sourceConfig: defineRequiredParam<z.infer<typeof NAMED_SOURCE_CLUSTER_CONFIG>>(),
    targetConfig: defineRequiredParam<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>(),
    migrationLabel: defineRequiredParam<string>(),
    ...makeRequiredImageParametersForKeys(["MigrationConsole"])
};

export function makeRepoParamDict(
    repoConfig: BaseExpression<z.infer<typeof S3_REPO_CONFIG>>,
    includes3LocalDir: boolean) {
    return expr.mergeDicts(
        expr.mergeDicts(
            expr.ternary(
                expr.isEmpty(expr.dig(repoConfig, ["endpoint"], (""))),
                expr.makeDict({}),
                expr.makeDict({"s3Endpoint": expr.getLoose(repoConfig, "endpoint")})),
            expr.ternary(
                expr.isEmpty(expr.dig(repoConfig, ["s3RoleArn"], (""))),
                expr.makeDict({}),
                expr.makeDict({"s3RoleArn": expr.getLoose(repoConfig, "s3RoleArn")}))
        ),
        expr.makeDict({
            "s3RepoUri": expr.get(repoConfig, "s3RepoPathUri"),
            "s3Region": expr.get(repoConfig, "awsRegion"),
            ...(includes3LocalDir ? { "s3LocalDir": expr.literal("/tmp") } : {})
        })
    );
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
            expr.omit(expr.deserializeRecord(options), "loggingConfigurationOverrideConfigMap", "jvmArgs")
        ),
        expr.mergeDicts(
            expr.makeDict({
                "snapshotName": expr.get(expr.deserializeRecord(snapshotConfig), "snapshotName"),
                "sourceVersion": expr.get(expr.deserializeRecord(sourceConfig), "version")
            }),
            makeRepoParamDict(
                expr.omit(expr.get(expr.deserializeRecord(snapshotConfig), "repoConfig"), "s3RoleArn"),
                true)
        )
    );
}

function makeApprovalCheck<
    IPR extends InputParamsToExpressions<typeof COMMON_METADATA_PARAMETERS, InputParameterSource>
>(
    inputs: IPR,
    skipApprovalMap: BaseExpression<any>,
    ...innerSkipFlags: string[]
) {
    return new FunctionExpression<boolean, any, any, "complicatedExpression">("sprig.dig", [
        ...getSourceTargetPathAndSnapshotAndMigrationIndex(inputs.sourceConfig,
            inputs.targetConfig,
            expr.jsonPathStrict(inputs.snapshotConfig, "label"),
            inputs.migrationLabel
        ),
        ...(innerSkipFlags !== undefined ? innerSkipFlags.map(f=>expr.literal(f)) : []),
        expr.literal(false),
        expr.deserializeRecord(skipApprovalMap)
    ]);
}

export const MetadataMigration = WorkflowBuilder.create({
    k8sResourceName: "metadata-migration",
    serviceAccountName: "argo-workflow-executor"
})

    .addParams(CommonWorkflowParameters)


    .addTemplate("runMetadata", t=>t
        .addRequiredInput("commandMode", typeToken<"evaluate"|"migrate">())
        .addRequiredInput("sourceConfig", typeToken<z.infer<typeof NAMED_SOURCE_CLUSTER_CONFIG>>())
        .addRequiredInput("targetConfig", typeToken<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>())
        .addRequiredInput("snapshotConfig", typeToken<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>())
        .addRequiredInput("metadataMigrationConfig", typeToken<z.infer<typeof METADATA_OPTIONS>>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addRequiredInput("sourceK8sLabel", typeToken<string>())
        .addRequiredInput("targetK8sLabel", typeToken<string>())
        .addRequiredInput("snapshotK8sLabel", typeToken<string>())
        .addRequiredInput("fromSnapshotMigrationK8sLabel", typeToken<string>())
        .addOptionalInput("taskK8sLabel", c => expr.ternary(
            expr.equals(c.inputParameters.commandMode, expr.literal("evaluate")),
            expr.literal("metadataEvaluate"),
            expr.literal("metadataMigrate")
        ))

        .addContainer(b=>b
            .addImageInfo(b.inputs.imageMigrationConsoleLocation, b.inputs.imageMigrationConsolePullPolicy)
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
            .addEnvVar("JDK_JAVA_OPTIONS",
                expr.dig(expr.deserializeRecord(b.inputs.metadataMigrationConfig), ["jvmArgs"], "")
            )
            .addEnvVarsFromRecord(getTargetHttpAuthCreds(getHttpAuthSecretName(b.inputs.targetConfig)))
            .addResources(DEFAULT_RESOURCES.JAVA_MIGRATION_CONSOLE_CLI)
            .addCommand(["/root/metadataMigration/bin/MetadataMigration"])
            .addArgs([
                b.inputs.commandMode,
                expr.literal("---INLINE-JSON"),
                expr.asString(expr.serialize(
                    makeParamsDict(b.inputs.sourceConfig, b.inputs.targetConfig, b.inputs.snapshotConfig, b.inputs.metadataMigrationConfig
                    )
                ))
            ])
            .addPodMetadata(({ inputs }) => ({
                labels: {
                    'migrations.opensearch.org/source': inputs.sourceK8sLabel,
                    'migrations.opensearch.org/target': inputs.targetK8sLabel,
                    'migrations.opensearch.org/snapshot': inputs.snapshotK8sLabel,
                    'migrations.opensearch.org/from-snapshot-migration': inputs.fromSnapshotMigrationK8sLabel,
                    'migrations.opensearch.org/task': inputs.taskK8sLabel
                }
            }))
        )
    )


    .addTemplate("approveEvaluate", t => t
        .addRequiredInput("name", typeToken<string>())
        .addSuspend()
    )
    .addTemplate("approveMigrate", t => t
        .addRequiredInput("name", typeToken<string>())
        .addSuspend()
    )


    .addTemplate("migrateMetaData", t => t
        .addRequiredInput("metadataMigrationConfig", typeToken<z.infer<typeof METADATA_OPTIONS>>())
        .addInputsFromRecord(COMMON_METADATA_PARAMETERS)
        .addInputsFromRecord(
            getApprovalMap(t.inputs.workflowParameters.approvalConfigMapName, typeToken<{}>()))
        .addOptionalInput("skipEvaluateApproval", c=>
            makeApprovalCheck(c.inputParameters, c.inputParameters.skipApprovalMap, "evaluateMetadata"))
        .addOptionalInput("skipMigrateApproval", c=>
            makeApprovalCheck(c.inputParameters, c.inputParameters.skipApprovalMap, "migrateMetadata"))
        .addOptionalInput("approvalNamePrefix", c=>
            expr.concat(
                expr.jsonPathStrict(c.inputParameters.sourceConfig, "label"), expr.literal("."),
                expr.jsonPathStrict(c.inputParameters.targetConfig, "label"),  expr.literal("."),
                expr.jsonPathStrict(c.inputParameters.snapshotConfig, "label"),  expr.literal("."),
                c.inputParameters.migrationLabel,  expr.literal(".")
            )
        )

        .addSteps(b => b
            .addStep("evaluateMetadata", INTERNAL, "runMetadata", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    commandMode: "evaluate",
                    sourceK8sLabel: expr.jsonPathStrict(b.inputs.sourceConfig, "label"),
                    targetK8sLabel: expr.jsonPathStrict(b.inputs.targetConfig, "label"),
                    snapshotK8sLabel: expr.jsonPathStrict(b.inputs.snapshotConfig, "label"),
                    fromSnapshotMigrationK8sLabel: b.inputs.migrationLabel
                })
            )
            .addStep("approveEvaluate", INTERNAL, "approveEvaluate", c =>
                c.register({
                    "name": expr.concat(b.inputs.approvalNamePrefix, expr.literal("evaluateMetadata"))
                }),
                { when: expr.not(expr.cast(b.inputs.skipEvaluateApproval).to<boolean>()) })
            .addStep("migrateMetadata", INTERNAL, "runMetadata", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    commandMode: "migrate",
                    sourceK8sLabel: expr.jsonPathStrict(b.inputs.sourceConfig, "label"),
                    targetK8sLabel: expr.jsonPathStrict(b.inputs.targetConfig, "label"),
                    snapshotK8sLabel: expr.jsonPathStrict(b.inputs.snapshotConfig, "label"),
                    fromSnapshotMigrationK8sLabel: b.inputs.migrationLabel
                })
            )
            .addStep("approveMigrate", INTERNAL, "approveMigrate", c=>
                c.register({
                    "name": expr.concat(b.inputs.approvalNamePrefix, expr.literal("migrateMetadata"))
                }),
                { when:  { templateExp: expr.not(expr.deserializeRecord(b.inputs.skipMigrateApproval))}})
        )
    )


    .getFullScope();
