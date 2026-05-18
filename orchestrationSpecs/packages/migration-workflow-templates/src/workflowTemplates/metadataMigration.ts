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
    ContainerBuilder,
    defineParam,
    defineRequiredParam,
    expr, FunctionExpression, InputParamDef, InputParameterSource, InputParametersRecord, InputParamsToExpressions,
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
import {getTransformsPresence, TRANSFORMS_MOUNT_PATH} from "./commonUtils/containerFragments";

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
        expr.makeDict({
            "outputFile": expr.literal(METADATA_OUTPUT_PATH),
            // The EKS workflow re-runs the metadata phase as part of SnapshotMigration
            // reconciliation, alongside the document backfill phase that owns the same
            // target indexes. Forcing --allow-existing-indexes makes the metadata phase
            // idempotent so a re-applied SnapshotMigration doesn't abort on indexes the
            // prior run created.
            "allowExistingIndexes": expr.literal(true)
        })
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

const runMetadataInputs = {
    commandMode: defineRequiredParam<"evaluate" | "migrate">(),
    sourceVersion: defineRequiredParam<string>(),
    targetConfig: defineRequiredParam<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>(),
    snapshotConfig: defineRequiredParam<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>(),
    metadataMigrationConfig: defineRequiredParam<z.infer<typeof ARGO_METADATA_OPTIONS>>(),
    sourceEndpoint: defineParam<string>({expression: expr.literal("")}),
    ...makeRequiredImageParametersForKeys(["MigrationConsole"]),
    sourceK8sLabel: defineRequiredParam<string>(),
    targetK8sLabel: defineRequiredParam<string>(),
    snapshotK8sLabel: defineRequiredParam<string>(),
    fromSnapshotMigrationK8sLabel: defineRequiredParam<string>(),
    crdName: defineRequiredParam<string>(),
    crdUid: defineRequiredParam<string>(),
    resourceCreationTimestamp: defineRequiredParam<string>(),
    workflowCreationTimestamp: defineParam<string>({expression: expr.getWorkflowValue("creationTimestamp")}),
    workflowUid: defineParam<string>({expression: expr.getWorkflowValue("uid")})
};

type MetadataTransformsMode = "none" | "image" | "configMap";

const metadataMigrationBaseBuilder = WorkflowBuilder.create({
    k8sResourceName: "metadata-migration",
    serviceAccountName: "argo-workflow-executor"
})

    .addParams(CommonWorkflowParameters);

function makeMetadataTaskK8sLabel(commandMode: BaseExpression<"evaluate" | "migrate">) {
    return expr.ternary(
        expr.equals(commandMode, expr.literal("evaluate")),
        expr.literal("metadataEvaluate"),
        expr.literal("metadataMigrate")
    );
}

function makeMetadataVolumes(
    inputs: InputParamsToExpressions<typeof runMetadataInputs, InputParameterSource>,
    transformsMode: MetadataTransformsMode
) {
    const baseVolumes = {
        'test-creds': {
            configMap: {
                name: expr.literal("localstack-test-creds"),
                optional: true
            },
            mountPath: "/config/credentials",
            readOnly: true
        }
    };

    if (transformsMode === "none") {
        return baseVolumes;
    }

    if (transformsMode === "image") {
        return {
            ...baseVolumes,
            'user-transforms': {
                image: {
                    reference: expr.dig(expr.deserializeRecord(inputs.metadataMigrationConfig), ["transformsImage"], ""),
                    pullPolicy: expr.dig(
                        expr.deserializeRecord(inputs.metadataMigrationConfig),
                        ["transformsImagePullPolicy"],
                        "IfNotPresent"
                    )
                },
                mountPath: TRANSFORMS_MOUNT_PATH,
                readOnly: true
            }
        };
    }

    return {
        ...baseVolumes,
        'user-transforms': {
            configMap: {
                name: expr.dig(expr.deserializeRecord(inputs.metadataMigrationConfig), ["transformsConfigMap"], "")
            },
            mountPath: TRANSFORMS_MOUNT_PATH,
            readOnly: true
        }
    };
}

type RunMetadataTemplateInputDefs = typeof runMetadataInputs & {
    taskK8sLabel: InputParamDef<string, false>;
};

function buildMetadataContainer<
    B extends ContainerBuilder<any, RunMetadataTemplateInputDefs, {}, {}, {}, any>
>(
    builder: B,
    transformsMode: MetadataTransformsMode
) {
    const {inputs} = builder;
    return builder
        .addImageInfo(inputs.imageMigrationConsoleLocation, inputs.imageMigrationConsolePullPolicy)
        .addVolumesFromRecord(makeMetadataVolumes(inputs, transformsMode))
        .addEnvVar("AWS_SHARED_CREDENTIALS_FILE",
            expr.ternary(
                expr.dig(expr.deserializeRecord(inputs.snapshotConfig), ["repoConfig", "useLocalStack"], false),
                expr.literal("/config/credentials/configuration"),
                expr.literal(""))
        )
        .addEnvVar("JDK_JAVA_OPTIONS",
            expr.dig(expr.deserializeRecord(inputs.metadataMigrationConfig), ["jvmArgs"], "")
        )
        .addEnvVarsFromRecord(getTargetHttpAuthCreds(getHttpAuthSecretName(inputs.targetConfig)))
        .addResources(DEFAULT_RESOURCES.JAVA_MIGRATION_CONSOLE_CLI)
        .addCommand(["/root/metadataMigration/bin/MetadataMigration"])
        .addArgs([
            inputs.commandMode,
            expr.literal("---INLINE-JSON"),
            expr.asString(expr.serialize(
                makeParamsDict(inputs.sourceVersion, inputs.targetConfig, inputs.snapshotConfig, inputs.metadataMigrationConfig,
                    inputs.sourceEndpoint
                )
            ))
        ])
        .addArtifactOutput("metadataOutput", METADATA_OUTPUT_PATH, {
            s3Key: makeMetadataOutputS3Key(
                inputs.crdName,
                inputs.crdUid,
                inputs.resourceCreationTimestamp,
                inputs.taskK8sLabel,
                inputs.workflowCreationTimestamp,
                inputs.workflowUid
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
        }));
}

function addMetadataTransformTemplates(builder: typeof metadataMigrationBaseBuilder) {
    return builder
        .addTemplate("runMetadataNoTransforms", t => t
            .addInputsFromRecord(runMetadataInputs)
            .addOptionalInput("taskK8sLabel", c => makeMetadataTaskK8sLabel(c.inputParameters.commandMode))
            .addContainer(b => buildMetadataContainer(b, "none"))
            .addRetryParameters(CONTAINER_TEMPLATE_RETRY_STRATEGY)
        )
        .addTemplate("runMetadataWithImageTransforms", t => t
            .addInputsFromRecord(runMetadataInputs)
            .addOptionalInput("taskK8sLabel", c => makeMetadataTaskK8sLabel(c.inputParameters.commandMode))
            .addContainer(b => buildMetadataContainer(b, "image"))
        )
        .addTemplate("runMetadataWithConfigMapTransforms", t => t
            .addInputsFromRecord(runMetadataInputs)
            .addOptionalInput("taskK8sLabel", c => makeMetadataTaskK8sLabel(c.inputParameters.commandMode))
            .addContainer(b => buildMetadataContainer(b, "configMap"))
        );
}

export const MetadataMigration = addMetadataTransformTemplates(metadataMigrationBaseBuilder)
    .addTemplate("runMetadata", t => t
        .addInputsFromRecord(runMetadataInputs)
        .addOptionalInput("taskK8sLabel", c => makeMetadataTaskK8sLabel(c.inputParameters.commandMode))
        .addSteps(b => {
            const metadataMigrationConfig =
                expr.cast(expr.deserializeRecord(b.inputs.metadataMigrationConfig)).to<Record<string, PlainObject>>();
            const transformsImage = expr.dig(metadataMigrationConfig as any, ["transformsImage"], "") as BaseExpression<string>;
            const transformsConfigMap = expr.dig(metadataMigrationConfig as any, ["transformsConfigMap"], "") as BaseExpression<string>;
            const {hasImage, hasConfigMapOnly, hasNone} =
                getTransformsPresence(transformsImage, transformsConfigMap);

            return b
                .addStep("withImageTransforms", INTERNAL, "runMetadataWithImageTransforms", c =>
                    c.register({
                        ...selectInputsForRegister(b, c),
                        sourceEndpoint: b.inputs.sourceEndpoint,
                        workflowCreationTimestamp: b.inputs.workflowCreationTimestamp,
                        workflowUid: b.inputs.workflowUid,
                        taskK8sLabel: b.inputs.taskK8sLabel
                    }),
                    {when: {templateExp: hasImage}}
                )
                .addStep("withConfigMapTransforms", INTERNAL, "runMetadataWithConfigMapTransforms", c =>
                    c.register({
                        ...selectInputsForRegister(b, c),
                        sourceEndpoint: b.inputs.sourceEndpoint,
                        workflowCreationTimestamp: b.inputs.workflowCreationTimestamp,
                        workflowUid: b.inputs.workflowUid,
                        taskK8sLabel: b.inputs.taskK8sLabel
                    }),
                    {when: {templateExp: hasConfigMapOnly}}
                )
                .addStep("withoutTransforms", INTERNAL, "runMetadataNoTransforms", c =>
                    c.register({
                        ...selectInputsForRegister(b, c),
                        sourceEndpoint: b.inputs.sourceEndpoint,
                        workflowCreationTimestamp: b.inputs.workflowCreationTimestamp,
                        workflowUid: b.inputs.workflowUid,
                        taskK8sLabel: b.inputs.taskK8sLabel
                    }),
                    {when: {templateExp: hasNone}}
                );
        })
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
