import {z} from 'zod';
import {
    COMPLETE_SNAPSHOT_CONFIG,
    DYNAMIC_SNAPSHOT_CONFIG,
    getZodKeys,
    METADATA_OPTIONS,
    NAMED_SOURCE_CLUSTER_CONFIG,
    NAMED_TARGET_CLUSTER_CONFIG,
    NORMALIZED_PARAMETERIZED_MIGRATION_CONFIG,
    PARAMETERIZED_MIGRATION_CONFIG,
    PER_INDICES_SNAPSHOT_MIGRATION_CONFIG,
    REPLAYER_OPTIONS,
    RFS_OPTIONS,
    SNAPSHOT_MIGRATION_CONFIG,
    SOURCE_CLUSTERS_MAP,
    TARGET_CLUSTER_CONFIG,
    TARGET_CLUSTERS_MAP
} from '@opensearch-migrations/schemas'
import {
    CommonWorkflowParameters,
    ImageParameters,
    LogicalOciImages,
    makeRequiredImageParametersForKeys
} from "./commonWorkflowTemplates";
import {ConfigManagementHelpers} from "./configManagementHelpers";
import {
    AllowLiteralOrExpression,
    BaseExpression,
    configMapKey,
    defineParam,
    defineRequiredParam,
    expr,
    IMAGE_PULL_POLICY,
    InputParamDef,
    INTERNAL,
    makeParameterLoop,
    NonSerializedPlainObject,
    selectInputsFieldsAsExpressionRecord,
    selectInputsForRegister,
    Serialized, transformZodObjectToParams,
    typeToken,
    WorkflowBuilder
} from '@opensearch-migrations/argo-workflow-builders';
import {DocumentBulkLoad} from "./documentBulkLoad";
import {MetadataMigration} from "./metadataMigration";
import {CreateOrGetSnapshot} from "./createOrGetSnapshot";

const latchCoordinationPrefixParam = {
    latchCoordinationPrefix: defineRequiredParam<string>({description: "Workflow session nonce"})
};

function lowercaseFirst(str: string): string {
    return str.charAt(0).toLowerCase() + str.slice(1);
}

function defaultImagesMap(imageConfigMapName: AllowLiteralOrExpression<string>) {
    return Object.fromEntries(LogicalOciImages.flatMap(k => [
            [`image${k}Location`, defineParam({
                type: typeToken<string>(),
                from: configMapKey(imageConfigMapName,
                    `${lowercaseFirst(k)}Image`)
            })],
            [`image${k}PullPolicy`, defineParam({
                type: typeToken<string>(),
                from: configMapKey(imageConfigMapName,
                    `${lowercaseFirst(k)}PullPolicy`)
            })]
        ])
    ) as Record<`image${typeof LogicalOciImages[number]}Location`, InputParamDef<string, false>> &
        Record<`image${typeof LogicalOciImages[number]}PullPolicy`, InputParamDef<IMAGE_PULL_POLICY, false>>;
}

export const FullMigration = WorkflowBuilder.create({
    k8sResourceName: "full-migration",
    parallelism: 100,
    serviceAccountName: "argo-workflow-executor"
})


    .addParams(CommonWorkflowParameters)


    .addTemplate("doNothing", t => t
        .addSteps(b => b.addStepGroup(c => c)))


    .addTemplate("runReplayerForTarget", t => t
        .addRequiredInput("targetConfig", typeToken<z.infer<typeof TARGET_CLUSTER_CONFIG>>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["EtcdUtils"]))
        .addContainer(cb => cb
            .addImageInfo(cb.inputs.imageEtcdUtilsLocation, cb.inputs.imageEtcdUtilsPullPolicy)
            .addCommand(["sh", "-c"])
            .addArgs(["echo runReplayerForTarget"])))


    .addTemplate("foreachSnapshotMigration", t => t
        .addRequiredInput("sourceConfig", typeToken<z.infer<typeof NAMED_SOURCE_CLUSTER_CONFIG>>())
        .addRequiredInput("targetConfig", typeToken<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>())
        .addRequiredInput("snapshotConfig", typeToken<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>())
        .addOptionalInput("metadataMigrationConfig", c=>
            expr.empty<z.infer<typeof METADATA_OPTIONS>>())
        .addOptionalInput("documentBackfillConfig",  c=>
            expr.empty<z.infer<typeof RFS_OPTIONS>>())

        .addInputsFromRecord(latchCoordinationPrefixParam)
        .addInputsFromRecord(ImageParameters)

        .addSteps(b => b
            .addStep("idGenerator", INTERNAL, "doNothing")
            .addStep("metadataMigrate", MetadataMigration, "migrateMetaData", c => {
                    return c.register({
                        ...selectInputsForRegister(b, c)
                    });
                },
                { when: { templateExp: expr.not(expr.isEmpty(b.inputs.metadataMigrationConfig)) }}
            )
            .addStep("bulkLoadDocuments", DocumentBulkLoad, "runBulkLoad", c =>
                    c.register({
                        ...(selectInputsForRegister(b, c)),
                        sessionName: c.steps.idGenerator.id
                    }),
                { when: { templateExp: expr.not(expr.isEmpty(b.inputs.documentBackfillConfig)) }}
            )
            .addStep("targetBackfillCompleteCheck", ConfigManagementHelpers, "decrementLatch", c =>
                c.register({
                    ...(selectInputsForRegister(b, c)),
                    prefix: b.inputs.latchCoordinationPrefix,
                    targetName: expr.jsonPathStrict(b.inputs.targetConfig, "name"),
                    processorId: c.steps.idGenerator.id
                }))
            // TODO - move this upward
            .addStep("runReplayerForTarget", INTERNAL, "runReplayerForTarget", c =>
                c.register({
                    ...selectInputsForRegister(b, c)
                }))
        )
    )


    .addTemplate("foreachSnapshotExtraction", t => t
        .addRequiredInput("sourceConfig", typeToken<z.infer<typeof NAMED_SOURCE_CLUSTER_CONFIG>>())
        .addRequiredInput("targetConfig", typeToken<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>())
        .addInputsFromRecord(transformZodObjectToParams(SNAPSHOT_MIGRATION_CONFIG))

        .addOptionalInput("sourcePipelineName", c=>
            expr.concatWith("_",
                expr.get(expr.deserializeRecord(c.inputParameters.sourceConfig), "name"),
                expr.join(expr.deserializeRecord(c.inputParameters.indices))
            )
        )
        .addRequiredInput("latchCoordinationPrefix", typeToken<string>())
        .addInputsFromRecord(ImageParameters)

        .addSteps(b => b
            .addStep("createOrGetSnapshot", CreateOrGetSnapshot, "createOrGetSnapshot",
                c => c.register({
                    ...selectInputsForRegister(b, c),
                    // ...selectInputsFieldsAsExpressionRecord(b.inputs.snapshotConfigAlias, c),
                    indices: b.inputs.indices,
                    autocreateSnapshotName: b.inputs.sourcePipelineName,
                }))

            .addStep("foreachSnapshotMigration", INTERNAL, "foreachSnapshotMigration", c=> {
                    const d = c.defaults;
                    const o = c.item;
                    console.log(d + " " + o);
                    return c.register({
                        ...(() => {
                            const { snapshotConfig, ...rest } = selectInputsForRegister(b, c);
                            return rest;
                        })(),
                        ...selectInputsFieldsAsExpressionRecord(c.item, c,
                            getZodKeys(PER_INDICES_SNAPSHOT_MIGRATION_CONFIG)),
                        snapshotConfig: expr.serialize(c.steps.createOrGetSnapshot.outputs.snapshotConfig)
                    });
                },
                {loopWith: makeParameterLoop(expr.deserializeRecord(b.inputs.migrations))}

            )
        )
    )


    .addTemplate("foreachMigrationPair", t=>t
        .addRequiredInput("sourceConfig", typeToken<z.infer<typeof NAMED_SOURCE_CLUSTER_CONFIG>>())
        .addRequiredInput("targetConfig", typeToken<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>())
        .addOptionalInput("snapshotExtractAndLoadConfigArray",
            c => expr.empty<z.infer<typeof SNAPSHOT_MIGRATION_CONFIG>[]>())
        .addOptionalInput("replayerConfig",
            c => expr.empty<z.infer<typeof REPLAYER_OPTIONS>>())

        .addRequiredInput("latchCoordinationPrefix", typeToken<string>())
        .addInputsFromRecord(ImageParameters)

        .addSteps(b=>b
            .addStep("foreachSnapshotExtraction", INTERNAL, "foreachSnapshotExtraction", c =>
                    c.register({
                        ...selectInputsForRegister(b, c),
                        ...selectInputsFieldsAsExpressionRecord(c.item, c, getZodKeys(SNAPSHOT_MIGRATION_CONFIG))
                    }),
                {
                    when: { templateExp: expr.not(expr.isEmpty(b.inputs.snapshotExtractAndLoadConfigArray)) },
                    loopWith: makeParameterLoop(
                        expr.deserializeRecord(b.inputs.snapshotExtractAndLoadConfigArray))
                }
            )
            // TODO - add a sensor here to wait for an event
        )
    )


    .addTemplate("main", t => t
        .addRequiredInput("migrationConfigs", typeToken<z.infer<typeof PARAMETERIZED_MIGRATION_CONFIG>[]>(),
            "List of server configurations to direct migrated traffic toward") // expand

        .addRequiredInput("latchCoordinationPrefix", typeToken<string>())
        .addInputsFromRecord(defaultImagesMap(t.inputs.workflowParameters.imageConfigMapName))

        .addSteps(b => b
            .addStep("foreachMigrationPair", INTERNAL, "foreachMigrationPair",
                c => {
                    return c.register({
                        ...selectInputsForRegister(b, c),
                        ...selectInputsFieldsAsExpressionRecord(c.item, c,
                            getZodKeys(PARAMETERIZED_MIGRATION_CONFIG))
                    });
                },
                {loopWith: makeParameterLoop(expr.deserializeRecord(b.inputs.migrationConfigs))})
            .addStep("cleanup", ConfigManagementHelpers, "cleanup",
                c => c.register({
                    ...selectInputsForRegister(b, c),
                    prefix: b.inputs.latchCoordinationPrefix
                }))
        )
    )


    .setEntrypoint("main")
    .getFullScope();
