import {z} from 'zod';
import {
    METADATA_OPTIONS,
    PER_INDICES_SNAPSHOT_MIGRATION_CONFIG, RFS_OPTIONS,
    NORMALIZED_PARAMETERIZED_MIGRATION_CONFIG,
    TARGET_CLUSTER_CONFIG, REPO_CONFIGS_MAP, REPLAYER_OPTIONS, TARGET_CLUSTERS_MAP, SOURCE_CLUSTERS_MAP
} from '@/workflowTemplates/userSchemas'
import {
    CommonWorkflowParameters,
    ImageParameters,
    LogicalOciImages,
    makeRequiredImageParametersForKeys
} from "@/workflowTemplates/commonWorkflowTemplates";
import {WorkflowBuilder} from "@/argoWorkflowBuilders/models/workflowBuilder";
import {ConfigManagementHelpers} from "@/workflowTemplates/configManagementHelpers";
import {AllowLiteralOrExpression, expr} from "@/argoWorkflowBuilders/models/expression";
import {makeParameterLoop} from "@/argoWorkflowBuilders/models/workflowTypes";
import {configMapKey, defineParam, defineRequiredParam, InputParamDef} from "@/argoWorkflowBuilders/models/parameterSchemas";
import {INTERNAL} from "@/argoWorkflowBuilders/models/taskBuilder";
import {CreateOrGetSnapshot} from "@/workflowTemplates/createOrGetSnapshot";
import {DocumentBulkLoad} from "@/workflowTemplates/documentBulkLoad";
import {IMAGE_PULL_POLICY} from '@/argoWorkflowBuilders/models/containerBuilder';
import {MetadataMigration} from "@/workflowTemplates/metadataMigration";
import {
    selectInputsFieldsAsExpressionRecord,
    selectInputsForRegister
} from "@/argoWorkflowBuilders/models/parameterConversions";
import {typeToken} from "@/argoWorkflowBuilders/models/sharedTypes";
import {
    SNAPSHOT_MIGRATION_CONFIG,
    NAMED_CLUSTER_CONFIG,
    NAMED_TARGET_CLUSTER_CONFIG, COMPLETE_SNAPSHOT_CONFIG, PARAMETERIZED_MIGRATION_CONFIG
} from "@/workflowTemplates/internalSchemas";

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


    .addTemplate("foreachSourceAndSnapshotAndTargetAndExtractiveMigration", t => t
        .addRequiredInput("sourceConfig", typeToken<z.infer<typeof NAMED_CLUSTER_CONFIG>>())
        .addRequiredInput("snapshotConfig", typeToken<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>())
        .addRequiredInput("perIndicesSnapshotMigrationConfig", typeToken<z.infer<typeof PER_INDICES_SNAPSHOT_MIGRATION_CONFIG>>())
        .addRequiredInput("targetConfig", typeToken<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>())

        .addInputsFromRecord(latchCoordinationPrefixParam)
        .addRequiredInput("useLocalStack", typeToken<boolean>(), "Only used for local testing")
        .addInputsFromRecord(ImageParameters)

        .addSteps(b => b
            .addStep("idGenerator", INTERNAL, "doNothing")
            .addStep("metadataMigrate", MetadataMigration, "migrateMetaData", c => {
                    return c.register({
                        ...selectInputsForRegister(b, c),
                        metadataMigrationConfig: expr.serialize(
                            expr.dig(expr.deserializeRecord(b.inputs.perIndicesSnapshotMigrationConfig),
                                ["metadataConfig"], {} as z.infer<typeof METADATA_OPTIONS>))
                    });
                },
                {when: { templateExp: expr.dig(expr.deserializeRecord(b.inputs.perIndicesSnapshotMigrationConfig),
                            ["metadataConfig", "enabled"], false) }}
            )
            .addStep("bulkLoadDocuments", DocumentBulkLoad, "runBulkLoad", c =>
                    c.register({
                        ...(selectInputsForRegister(b, c)),
                        sessionName: c.steps.idGenerator.id,
                        backfillConfig: expr.serialize(expr.dig(expr.deserializeRecord(b.inputs.perIndicesSnapshotMigrationConfig),
                            ["documentBackfillConfig"], {} as z.infer<typeof RFS_OPTIONS>))
                    }),
                {when: {templateExp: expr.dig(expr.deserializeRecord(b.inputs.perIndicesSnapshotMigrationConfig),
                            ["documentBackfillConfig", "enabled"], false)}}
            )
            .addStep("targetBackfillCompleteCheck", ConfigManagementHelpers, "decrementLatch", c =>
                c.register({
                    ...(selectInputsForRegister(b, c)),
                    prefix: b.inputs.latchCoordinationPrefix,
                    targetName: expr.jsonPathStrict(b.inputs.targetConfig, "name"),
                    processorId: c.steps.idGenerator.id
                }))
            .addStep("runReplayerForTarget", INTERNAL, "runReplayerForTarget", c =>
                c.register({
                    ...selectInputsForRegister(b, c)
                }))
        )
    )

    .addTemplate("foreachSourceAndSnapshotAndTarget", t => t
        .addRequiredInput("sourceConfig", typeToken<z.infer<typeof NAMED_CLUSTER_CONFIG>>())
        .addRequiredInput("snapshotConfig", typeToken<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>())
        .addRequiredInput("perIndicesSnapshotMigrationConfigArray", typeToken<z.infer<typeof PER_INDICES_SNAPSHOT_MIGRATION_CONFIG>[]>())  //expand
        .addRequiredInput("targetConfig", typeToken<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>())

        .addInputsFromRecord(latchCoordinationPrefixParam)
        .addRequiredInput("useLocalStack", typeToken<boolean>(), "Only used for local testing")
        .addInputsFromRecord(ImageParameters)

        .addSteps(b => b
            .addStep("foreachSourceAndSnapshotAndTargetAndExtractiveMigration", INTERNAL,
                "foreachSourceAndSnapshotAndTargetAndExtractiveMigration", c=>c.register({
                    ...selectInputsForRegister(b, c),
                    perIndicesSnapshotMigrationConfig: c.item
                }),
                {loopWith: makeParameterLoop(expr.deserializeRecord(b.inputs.perIndicesSnapshotMigrationConfigArray))}
            )
        )
    )


    .addTemplate("foreachSourceAndSnapshotExtract", t => t
        .addRequiredInput("sourceConfig", typeToken<z.infer<typeof NAMED_CLUSTER_CONFIG>>())
        .addRequiredInput("snapshotExtractAndLoadConfig", typeToken<z.infer<typeof SNAPSHOT_MIGRATION_CONFIG>>())
        .addRequiredInput("targetArray", typeToken<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>[]>()) //expand
        
        .addRequiredInput("sourcePipelineName", typeToken<string>())
        .addRequiredInput("latchCoordinationPrefix", typeToken<string>())
        .addRequiredInput("useLocalStack", typeToken<boolean>(), "Only used for local testing")
        .addInputsFromRecord(ImageParameters)

        .addSteps(b => b
            .addStep("createOrGetSnapshot", CreateOrGetSnapshot, "createOrGetSnapshot",
                c => c.register({
                    ...selectInputsForRegister(b, c),
                    // ...selectInputsFieldsAsExpressionRecord(b.inputs.snapshotConfigAlias, c),
                    indices: expr.serialize(
                        expr.dig(expr.deserializeRecord(b.inputs.snapshotExtractAndLoadConfig), ["indices"], [])),
                    autocreateSnapshotName: b.inputs.sourcePipelineName,
                    snapshotConfig: expr.serialize(
                        expr.dig(expr.deserializeRecord(b.inputs.snapshotExtractAndLoadConfig),
                            ["snapshotConfig"], {} as any))
                }))

            .addStep("foreachSourceAndSnapshotAndTarget", INTERNAL, "foreachSourceAndSnapshotAndTarget",
                c => c.register({
                    ...selectInputsForRegister(b, c),
                    perIndicesSnapshotMigrationConfigArray: expr.serialize(
                        expr.jsonPathStrict(b.inputs.snapshotExtractAndLoadConfig, "migrations")),
                    targetConfig: c.item,
                    snapshotConfig: expr.serialize(c.steps.createOrGetSnapshot.outputs.snapshotConfig)
                }),
                {loopWith: makeParameterLoop(expr.deserializeRecord(b.inputs.targetArray))})
        )
    )

    
    .addTemplate("foreachSource", t=>t
        .addRequiredInput("sourceConfig", typeToken<z.infer<typeof NAMED_CLUSTER_CONFIG>>())
        .addRequiredInput("snapshotExtractAndLoadConfigArray", typeToken<z.infer<typeof SNAPSHOT_MIGRATION_CONFIG>[]>()) //expand
        .addRequiredInput("targetArray", typeToken<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>[]>())
        .addRequiredInput("replayerConfig", typeToken<z.infer<typeof REPLAYER_OPTIONS>>())

        .addRequiredInput("latchCoordinationPrefix", typeToken<string>())
        .addRequiredInput("useLocalStack", typeToken<boolean>(), "Only used for local testing")
        .addInputsFromRecord(ImageParameters)

        .addSteps(b=>b
            .addStep("foreachSourceAndSnapshotExtract", INTERNAL, "foreachSourceAndSnapshotExtract", c =>
                    c.register({
                        ...selectInputsForRegister(b, c),
                        snapshotExtractAndLoadConfig: c.item,
                        sourcePipelineName: "TODO",
                    }),
                {loopWith: makeParameterLoop(expr.deserializeRecord(b.inputs.snapshotExtractAndLoadConfigArray))}
            )
        )
    )

    .addTemplate("foreachMigrationConfig", t => t
        .addRequiredInput("sourceArray", typeToken<z.infer<typeof NAMED_CLUSTER_CONFIG>[]>()) //expand
        .addRequiredInput("snapshotExtractAndLoadConfigArray", typeToken<z.infer<typeof SNAPSHOT_MIGRATION_CONFIG>[]>())
        .addRequiredInput("targetArray", typeToken<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>[]>())
        .addRequiredInput("replayerConfig", typeToken<z.infer<typeof REPLAYER_OPTIONS>>())

        .addRequiredInput("latchCoordinationPrefix", typeToken<string>())
        .addRequiredInput("useLocalStack", typeToken<boolean>(), "Only used for local testing")
        .addInputsFromRecord(ImageParameters)

        .addSteps(b => b
            .addStep("foreachSource", INTERNAL, "foreachSource", c =>
                    c.register({
                        ...selectInputsForRegister(b, c),
                        sourceConfig: c.item
                    }),
                {loopWith: makeParameterLoop(expr.deserializeRecord(b.inputs.sourceArray))}
            )
        )
    )


    .addTemplate("fullAfterInitialization", t => t
        .addRequiredInput("migrationConfigs", typeToken<z.infer<typeof PARAMETERIZED_MIGRATION_CONFIG>[]>(),
            "List of server configurations to direct migrated traffic toward") // expand

        .addRequiredInput("latchCoordinationPrefix", typeToken<string>())
        .addOptionalInput("useLocalStack", c => false)
        .addInputsFromRecord(defaultImagesMap(t.inputs.workflowParameters.imageConfigMapName))

        .addSteps(b => b
            .addStep("foreachMigrationConfig", INTERNAL, "foreachMigrationConfig",
                c => c.register({
                    ...selectInputsForRegister(b, c),
                    ...selectInputsFieldsAsExpressionRecord(c.item, c)
                }),
                {loopWith: makeParameterLoop(expr.deserializeRecord(b.inputs.migrationConfigs))})
            .addStep("cleanup", ConfigManagementHelpers, "cleanup",
                c => c.register({
                    ...selectInputsForRegister(b, c),
                    prefix: b.inputs.latchCoordinationPrefix
                }))
        )
    )


    .addTemplate("main", t => t
        .addRequiredInput("sourceClusters", typeToken<z.infer<typeof SOURCE_CLUSTERS_MAP>>(),
            "List of server configurations to migrate data from")
        .addRequiredInput("targetClusters", typeToken<z.infer<typeof TARGET_CLUSTERS_MAP>>(),
            "List of server configurations to direct migrated data to")
        .addRequiredInput("snapshotRepoConfigs", typeToken<z.infer<typeof REPO_CONFIGS_MAP>[]>())

        .addRequiredInput("sourceMigrationConfigs", typeToken<z.infer<typeof NORMALIZED_PARAMETERIZED_MIGRATION_CONFIG>[]>(),
            "List of server configurations to direct migrated traffic toward")

        .addOptionalInput("useLocalStack", c => false)
        .addInputsFromRecord(defaultImagesMap(t.inputs.workflowParameters.imageConfigMapName))

        .addSteps(b => b
            .addStep("init", ConfigManagementHelpers, "prepareConfigs", c =>
                c.register({
                    ...(selectInputsForRegister(b, c)),
                    prefix: expr.concat(expr.literal("workflow-"), expr.getWorkflowValue("uid"))
                }))
            .addStep("fullMigration", INTERNAL, "fullAfterInitialization", c=>c.register({
                    latchCoordinationPrefix: c.steps.init.outputs.prefix,
                    migrationConfigs: expr.serialize(c.steps.init.outputs.denormalizedConfigArray)
                })
            )
        )
    )


    .setEntrypoint("main")
    .getFullScope();
