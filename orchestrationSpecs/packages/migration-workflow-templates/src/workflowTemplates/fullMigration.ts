import {z} from 'zod';
import {
    COMPLETE_SNAPSHOT_CONFIG,
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
    configMapKey,
    defineParam,
    defineRequiredParam,
    expr,
    IMAGE_PULL_POLICY,
    InputParamDef,
    INTERNAL,
    makeParameterLoop,
    selectInputsFieldsAsExpressionRecord,
    selectInputsForRegister,
    typeToken,
    WorkflowBuilder
} from '@opensearch-migrations/argo-workflow-builders';
import {DocumentBulkLoad} from "./documentBulkLoad";
import {MetadataMigration} from "./metadataMigration";

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
        .addRequiredInput("metadataMigrationConfig", typeToken<z.infer<typeof METADATA_OPTIONS>>())
        .addRequiredInput("documentBackfillConfig", typeToken<z.infer<typeof RFS_OPTIONS>>())

        .addInputsFromRecord(latchCoordinationPrefixParam)
        .addRequiredInput("useLocalStack", typeToken<boolean>(), "Only used for local testing")
        .addInputsFromRecord(ImageParameters)

        .addSteps(b => b
            .addStep("idGenerator", INTERNAL, "doNothing")
            .addStep("metadataMigrate", MetadataMigration, "migrateMetaData", c => {
                    return c.register({
                        ...selectInputsForRegister(b, c)
                    });
                },
                { when: expr.equals("", expr.asString(b.inputs.metadataMigrationConfig)) }
            )
            .addStep("bulkLoadDocuments", DocumentBulkLoad, "runBulkLoad", c =>
                    c.register({
                        ...(selectInputsForRegister(b, c)),
                        sessionName: c.steps.idGenerator.id
                    }),
                { when: expr.equals("", expr.asString(b.inputs.documentBackfillConfig)) }
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
        .addRequiredInput("snapshotConfig", typeToken<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>())
        .addRequiredInput("migrations", typeToken<z.infer<typeof PER_INDICES_SNAPSHOT_MIGRATION_CONFIG>[]>()) // expand

        .addRequiredInput("sourcePipelineName", typeToken<string>())
        .addRequiredInput("latchCoordinationPrefix", typeToken<string>())
        .addRequiredInput("useLocalStack", typeToken<boolean>(), "Only used for local testing")
        .addInputsFromRecord(ImageParameters)

        .addSteps(b => b

            .addStep("foreachSnapshotMigration", INTERNAL,
                "foreachSnapshotMigration", c=>c.register({
                    ...selectInputsForRegister(b, c),
                    ...selectInputsFieldsAsExpressionRecord(c.item, c)
                }),
                {loopWith: makeParameterLoop(expr.deserializeRecord(b.inputs.migrations))}
            )
        )
    )


    .addTemplate("foreachMigrationPair", t=>t
        .addRequiredInput("sourceConfig", typeToken<z.infer<typeof NAMED_SOURCE_CLUSTER_CONFIG>>())
        .addRequiredInput("targetConfig", typeToken<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>())
        .addRequiredInput("snapshotExtractAndLoadConfigArray", typeToken<z.infer<typeof SNAPSHOT_MIGRATION_CONFIG>[]>()) //expand
        .addRequiredInput("replayerConfig", typeToken<z.infer<typeof REPLAYER_OPTIONS>>())

        .addRequiredInput("latchCoordinationPrefix", typeToken<string>())
        .addRequiredInput("useLocalStack", typeToken<boolean>(), "Only used for local testing")
        .addInputsFromRecord(ImageParameters)

        .addSteps(b=>b
            .addStep("foreachSnapshotExtraction", INTERNAL, "foreachSnapshotExtraction", c =>
                    c.register({
                        ...selectInputsForRegister(b, c),
                        ...selectInputsFieldsAsExpressionRecord(c.item, c),
                        sourcePipelineName: ""
                    }),
                {loopWith: makeParameterLoop(expr.deserializeRecord(b.inputs.snapshotExtractAndLoadConfigArray))}
            )
            // TODO - add a sensor here to wait for an event
        )
    )


    .addTemplate("main", t => t
        .addRequiredInput("migrationConfigs", typeToken<z.infer<typeof PARAMETERIZED_MIGRATION_CONFIG>[]>(),
            "List of server configurations to direct migrated traffic toward") // expand

        .addRequiredInput("latchCoordinationPrefix", typeToken<string>())
        .addOptionalInput("useLocalStack", c => false)
        .addInputsFromRecord(defaultImagesMap(t.inputs.workflowParameters.imageConfigMapName))

        .addSteps(b => b
            .addStep("foreachMigrationPair", INTERNAL, "foreachMigrationPair",
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
    

    .setEntrypoint("main")
    .getFullScope();
