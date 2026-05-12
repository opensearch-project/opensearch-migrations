import {z} from "zod";
import {
    COMPLETE_SNAPSHOT_CONFIG,
    DEFAULT_RESOURCES,
    ARGO_METADATA_OPTIONS,
    ARGO_METADATA_WORKFLOW_OPTION_KEYS,
    NAMED_SOURCE_CLUSTER_CONFIG_WITHOUT_SNAPSHOT_INFO,
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
import {CONTAINER_TEMPLATE_RETRY_STRATEGY} from "./commonUtils/resourceRetryStrategy";
import {
    getApprovalMap,
    getSourceTargetPathAndSnapshotAndMigrationIndex
} from "./commonUtils/configContextPathConstructors";
import {ResourceManagement} from "./resourceManagement";

const METADATA_OUTPUT_PATH = "/tmp/outputs/metadata-output.log";

function makeMetadataOutputS3Key(
    crdName: BaseExpression<string>,
    crdUid: BaseExpression<string>,
    resourceCreationTimestamp: BaseExpression<string>,
    taskK8sLabel: BaseExpression<string>,
    workflowCreationTimestamp: BaseExpression<string>,
    workflowUid: BaseExpression<string>
) {
    return expr.concat(
        expr.literal("migration-outputs/snapshotmigration/"),
        crdName,
        expr.literal("/"),
        resourceCreationTimestamp,
        expr.literal("_"),
        crdUid,
        expr.literal("/"),
        taskK8sLabel,
        expr.literal("/"),
        workflowCreationTimestamp,
        expr.literal("_"),
        workflowUid,
        expr.literal(".log")
    );
}

const COMMON_METADATA_PARAMETERS = {
    snapshotConfig: defineRequiredParam<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>({
        description: "Snapshot storage details (region, endpoint, etc)"
    }),
    sourceLabel: defineRequiredParam<string>(),
    sourceVersion: defineRequiredParam<string>(),
    targetConfig: defineRequiredParam<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>(),
    migrationLabel: defineRequiredParam<string>(),
    crdName: defineRequiredParam<string>(),
    crdUid: defineRequiredParam<string>(),
    resourceCreationTimestamp: defineRequiredParam<string>(),
    configChecksum: defineRequiredParam<string>(),
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
            ...(includes3LocalDir ? {"s3LocalDir": expr.literal("/tmp")} : {})
        })
    );
}

function makeSnapshotParamsDict(
    sourceVersion: BaseExpression<string>,
    snapshotConfig: BaseExpression<Serialized<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>>
) {
    return expr.mergeDicts(
        expr.makeDict({
            "snapshotName": expr.get(expr.deserializeRecord(snapshotConfig), "snapshotName"),
            "sourceVersion": sourceVersion
        }),
        makeRepoParamDict(
            expr.omit(expr.get(expr.deserializeRecord(snapshotConfig), "repoConfig"), "s3RoleArn"),
            true)
    );
}

function makeSourceHostParamsDict(
    sourceVersion: BaseExpression<string>,
    sourceEndpoint: BaseExpression<string>
) {
    return expr.makeDict({
        "sourceHost": sourceEndpoint,
        "sourceVersion": sourceVersion
    });
}

function makeParamsDict(
    sourceVersion: BaseExpression<string>,
    targetConfig: BaseExpression<Serialized<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>>,
    snapshotConfig: BaseExpression<Serialized<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>>,
    options: BaseExpression<Serialized<z.infer<typeof ARGO_METADATA_OPTIONS>>>,
    sourceEndpoint?: BaseExpression<string>
) {
    const targetAndOptions = expr.mergeDicts(
        makeTargetParamDict(targetConfig),
        expr.omit(expr.deserializeRecord(options), ...ARGO_METADATA_WORKFLOW_OPTION_KEYS)
    );

    const snapshotParams = makeSnapshotParamsDict(sourceVersion, snapshotConfig);

    const base = expr.mergeDicts(
        expr.mergeDicts(targetAndOptions, snapshotParams),
        expr.makeDict({"outputFile": expr.literal(METADATA_OUTPUT_PATH)})
    );

    // When sourceEndpoint is non-empty at runtime, add sourceHost param.
    // MetadataMigration CLI prioritizes --source-host over snapshot params.
    if (sourceEndpoint) {
        return expr.mergeDicts(base,
            expr.ternary(
                expr.isEmpty(sourceEndpoint),
                expr.makeDict({}),
                makeSourceHostParamsDict(sourceVersion, sourceEndpoint)
            )
        );
    }

    return base;
}

function makeApprovalCheck<
    IPR extends InputParamsToExpressions<typeof COMMON_METADATA_PARAMETERS, InputParameterSource>
>(
    inputs: IPR,
    skipApprovalMap: BaseExpression<any>,
    ...innerSkipFlags: string[]
) {
    return new FunctionExpression<boolean, any, any, "complicatedExpression">("sprig.dig", [
        ...getSourceTargetPathAndSnapshotAndMigrationIndex(inputs.sourceLabel,
            inputs.targetConfig,
            expr.jsonPathStrict(inputs.snapshotConfig, "label"),
            inputs.migrationLabel
        ),
        ...(innerSkipFlags !== undefined ? innerSkipFlags.map(f => expr.literal(f)) : []),
        expr.literal(false),
        expr.deserializeRecord(skipApprovalMap)
    ]);
}

export const MetadataMigration = WorkflowBuilder.create({
    k8sResourceName: "metadata-migration",
    serviceAccountName: "argo-workflow-executor"
})

    .addParams(CommonWorkflowParameters)


    .addTemplate("runMetadata", t => t
        .addRequiredInput("commandMode", typeToken<"evaluate" | "migrate">())
        .addRequiredInput("sourceVersion", typeToken<string>())
        .addRequiredInput("targetConfig", typeToken<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>())
        .addRequiredInput("snapshotConfig", typeToken<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>())
        .addRequiredInput("metadataMigrationConfig", typeToken<z.infer<typeof ARGO_METADATA_OPTIONS>>())
        .addOptionalInput("sourceEndpoint", c => expr.literal(""))
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addRequiredInput("sourceK8sLabel", typeToken<string>())
        .addRequiredInput("targetK8sLabel", typeToken<string>())
        .addRequiredInput("snapshotK8sLabel", typeToken<string>())
        .addRequiredInput("fromSnapshotMigrationK8sLabel", typeToken<string>())
        .addRequiredInput("crdName", typeToken<string>())
        .addRequiredInput("crdUid", typeToken<string>())
        .addRequiredInput("resourceCreationTimestamp", typeToken<string>())
        .addOptionalInput("workflowCreationTimestamp", c => expr.getWorkflowValue("creationTimestamp"))
        .addOptionalInput("workflowUid", c => expr.getWorkflowValue("uid"))
        .addOptionalInput("taskK8sLabel", c => expr.ternary(
            expr.equals(c.inputParameters.commandMode, expr.literal("evaluate")),
            expr.literal("metadataEvaluate"),
            expr.literal("metadataMigrate")
        ))

        .addContainer(b => b
            .addImageInfo(b.inputs.imageMigrationConsoleLocation, b.inputs.imageMigrationConsolePullPolicy)
            .addVolumesFromRecord({
                'test-creds': {
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
                    makeParamsDict(b.inputs.sourceVersion, b.inputs.targetConfig, b.inputs.snapshotConfig, b.inputs.metadataMigrationConfig,
                        b.inputs.sourceEndpoint
                    )
                ))
            ])
            .addArtifactOutput("metadataOutput", METADATA_OUTPUT_PATH, {
                s3Key: makeMetadataOutputS3Key(
                    b.inputs.crdName,
                    b.inputs.crdUid,
                    b.inputs.resourceCreationTimestamp,
                    b.inputs.taskK8sLabel,
                    b.inputs.workflowCreationTimestamp,
                    b.inputs.workflowUid
                )
            })
            .addPodMetadata(({inputs}) => ({
                labels: {
                    'migrations.opensearch.org/source': inputs.sourceK8sLabel,
                    'migrations.opensearch.org/target': inputs.targetK8sLabel,
                    'migrations.opensearch.org/snapshot': inputs.snapshotK8sLabel,
                    'migrations.opensearch.org/from-snapshot-migration': inputs.fromSnapshotMigrationK8sLabel,
                    'migrations.opensearch.org/task': inputs.taskK8sLabel
                }
            }))
        )
        .addRetryParameters(CONTAINER_TEMPLATE_RETRY_STRATEGY)
    )


    .addTemplate("approveEvaluate", t => t
        .addRequiredInput("name", typeToken<string>())
        .addSteps(b => b
            .addStep("waitForApproval", ResourceManagement, "waitForApproval", c =>
                c.register({resourceName: b.inputs.name}))
        )
    )
    .addTemplate("approveMigrate", t => t
        .addRequiredInput("name", typeToken<string>())
        .addSteps(b => b
            .addStep("waitForApproval", ResourceManagement, "waitForApproval", c =>
                c.register({resourceName: b.inputs.name}))
        )
    )


    .addTemplate("migrateMetaData", t => t
        .addRequiredInput("metadataMigrationConfig", typeToken<z.infer<typeof ARGO_METADATA_OPTIONS>>())
        .addOptionalInput("sourceEndpoint", c => expr.literal(""))
        .addInputsFromRecord(COMMON_METADATA_PARAMETERS)
        .addInputsFromRecord(
            getApprovalMap(t.inputs.workflowParameters.approvalConfigMapName, typeToken<{}>()))
        .addOptionalInput("workflowCreationTimestamp", c => expr.getWorkflowValue("creationTimestamp"))
        .addOptionalInput("workflowUid", c => expr.getWorkflowValue("uid"))
        .addOptionalInput("skipEvaluateApproval", c =>
            makeApprovalCheck(c.inputParameters, c.inputParameters.skipApprovalMap, "evaluateMetadata"))
        .addOptionalInput("skipMigrateApproval", c =>
            makeApprovalCheck(c.inputParameters, c.inputParameters.skipApprovalMap, "migrateMetadata"))
        .addOptionalInput("approvalNameSuffix", c =>
            expr.concat(
                expr.literal("."),
                c.inputParameters.sourceLabel, expr.literal("-"),
                expr.jsonPathStrict(c.inputParameters.targetConfig, "label"), expr.literal("-"),
                expr.jsonPathStrict(c.inputParameters.snapshotConfig, "label"), expr.literal("-"),
                c.inputParameters.migrationLabel
            )
        )

        .addSteps(b => b
            .addStep("evaluateMetadata", INTERNAL, "runMetadata", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    commandMode: "evaluate",
                    sourceK8sLabel: b.inputs.sourceLabel,
                    targetK8sLabel: expr.jsonPathStrict(b.inputs.targetConfig, "label"),
                    snapshotK8sLabel: expr.jsonPathStrict(b.inputs.snapshotConfig, "label"),
                    fromSnapshotMigrationK8sLabel: b.inputs.migrationLabel,
                    crdName: b.inputs.crdName,
                    crdUid: b.inputs.crdUid,
                    resourceCreationTimestamp: b.inputs.resourceCreationTimestamp,
                    workflowCreationTimestamp: b.inputs.workflowCreationTimestamp,
                    workflowUid: b.inputs.workflowUid
                })
            )
            .addStep("patchMetadataEvaluateOutput", ResourceManagement, "patchSnapshotMigrationOutputEvaluate", c =>
                c.register({
                    resourceName: b.inputs.crdName,
                    artifactName: expr.literal("metadataOutput"),
                    s3Key: makeMetadataOutputS3Key(
                        b.inputs.crdName,
                        b.inputs.crdUid,
                        b.inputs.resourceCreationTimestamp,
                        expr.literal("metadataEvaluate"),
                        b.inputs.workflowCreationTimestamp,
                        b.inputs.workflowUid
                    ),
                    resourceUid: b.inputs.crdUid,
                    configChecksum: b.inputs.configChecksum
                })
            )
            .addStep("approveEvaluate", INTERNAL, "approveEvaluate", c =>
                c.register({
                    "name": expr.concat(expr.literal("evaluatemetadata"), b.inputs.approvalNameSuffix)
                }),
                {when: expr.not(b.inputs.skipEvaluateApproval)}
            )
            .addStep("migrateMetadata", INTERNAL, "runMetadata", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    commandMode: "migrate",
                    sourceK8sLabel: b.inputs.sourceLabel,
                    targetK8sLabel: expr.jsonPathStrict(b.inputs.targetConfig, "label"),
                    snapshotK8sLabel: expr.jsonPathStrict(b.inputs.snapshotConfig, "label"),
                    fromSnapshotMigrationK8sLabel: b.inputs.migrationLabel,
                    crdName: b.inputs.crdName,
                    crdUid: b.inputs.crdUid,
                    resourceCreationTimestamp: b.inputs.resourceCreationTimestamp,
                    workflowCreationTimestamp: b.inputs.workflowCreationTimestamp,
                    workflowUid: b.inputs.workflowUid
                })
            )
            .addStep("patchMetadataMigrateOutput", ResourceManagement, "patchSnapshotMigrationOutputMigrate", c =>
                c.register({
                    resourceName: b.inputs.crdName,
                    artifactName: expr.literal("metadataOutput"),
                    s3Key: makeMetadataOutputS3Key(
                        b.inputs.crdName,
                        b.inputs.crdUid,
                        b.inputs.resourceCreationTimestamp,
                        expr.literal("metadataMigrate"),
                        b.inputs.workflowCreationTimestamp,
                        b.inputs.workflowUid
                    ),
                    resourceUid: b.inputs.crdUid,
                    configChecksum: b.inputs.configChecksum
                })
            )
            .addStep("approveMigrate", INTERNAL, "approveMigrate", c =>
                c.register({
                    "name": expr.concat(expr.literal("migratemetadata"), b.inputs.approvalNameSuffix)
                }),
                {when: expr.not(b.inputs.skipMigrateApproval)}
            )
        )
    )


    .getFullScope();
