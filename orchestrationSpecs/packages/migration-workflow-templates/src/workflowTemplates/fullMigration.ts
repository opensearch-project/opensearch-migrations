import {z} from 'zod';
import {
    ARGO_CREATE_SNAPSHOT_OPTIONS,
    COMPLETE_SNAPSHOT_CONFIG,
    CREATE_SNAPSHOT_OPTIONS,
    DEFAULT_RESOURCES,
    DYNAMIC_SNAPSHOT_CONFIG,
    getZodKeys,
    METADATA_OPTIONS,
    NAMED_SOURCE_CLUSTER_CONFIG,
    NAMED_TARGET_CLUSTER_CONFIG,
    PARAMETERIZED_MIGRATION_CONFIG,
    PER_INDICES_SNAPSHOT_MIGRATION_CONFIG,
    REPLAYER_OPTIONS,
    RFS_OPTIONS,
    SNAPSHOT_MIGRATION_CONFIG,
    TARGET_CLUSTER_CONFIG
} from '@opensearch-migrations/schemas'
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
import {CreateOrGetSnapshot} from "./createOrGetSnapshot";

import {CommonWorkflowParameters} from "./commonUtils/workflowParameters";
import {ImageParameters, LogicalOciImages, makeRequiredImageParametersForKeys} from "./commonUtils/imageDefinitions";

const uniqueRunNonceParam = {
    uniqueRunNonce: defineRequiredParam<string>({description: "Workflow session nonce"})
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
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["MigrationConsole"]))
        .addContainer(cb => cb
            .addImageInfo(cb.inputs.imageMigrationConsoleLocation, cb.inputs.imageMigrationConsolePullPolicy)
            .addCommand(["sh", "-c"])
            .addResources(DEFAULT_RESOURCES.MIGRATION_CONSOLE_CLI)
            .addArgs(["echo runReplayerForTarget"])))


    .addTemplate("migrateFromSnapshot", t => t
        .addRequiredInput("sourceConfig", typeToken<z.infer<typeof NAMED_SOURCE_CLUSTER_CONFIG>>())
        .addRequiredInput("targetConfig", typeToken<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>())
        .addRequiredInput("snapshotConfig", typeToken<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>())
        .addRequiredInput("migrationLabel", typeToken<string>())
        // groupName facilitates grouping within the python `workflow` tools
        .addRequiredInput("groupName", typeToken<string>())
        .addOptionalInput("metadataMigrationConfig", c=>
            expr.empty<z.infer<typeof METADATA_OPTIONS>>())
        .addOptionalInput("documentBackfillConfig", c=>
            expr.empty<z.infer<typeof RFS_OPTIONS>>())

        .addInputsFromRecord(uniqueRunNonceParam)
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
            .addStep("bulkLoadDocuments", DocumentBulkLoad, "setupAndRunBulkLoad", c =>
                    c.register({
                        ...(selectInputsForRegister(b, c)),
                        sessionName: c.steps.idGenerator.id,
                        sourceVersion: expr.jsonPathStrict(b.inputs.sourceConfig, "version"),
                        sourceLabel: expr.jsonPathStrict(b.inputs.sourceConfig, "label")
                    }),
                { when: { templateExp: expr.not(expr.isEmpty(b.inputs.documentBackfillConfig)) }}
            )
            // .addStep("targetBackfillCompleteCheck", ConfigManagementHelpers, "decrementLatch", c =>
            //     c.register({
            //         ...(selectInputsForRegister(b, c)),
            //         prefix: b.inputs.uniqueRunNonce,
            //         targetName: expr.jsonPathStrict(b.inputs.targetConfig, "name"),
            //         processorId: c.steps.idGenerator.id
            //     }))
            // // TODO - move this upward
            // .addStep("runReplayerForTarget", INTERNAL, "runReplayerForTarget", c =>
            //     c.register({
            //         ...selectInputsForRegister(b, c)
            //     }))
        )
    )


    .addTemplate("getSnapshotThenMigrateSnapshot", t => t
        .addRequiredInput("sourceConfig", typeToken<z.infer<typeof NAMED_SOURCE_CLUSTER_CONFIG>>())
        .addRequiredInput("targetConfig", typeToken<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>())
        .addRequiredInput("snapshotConfig", typeToken<z.infer<typeof SNAPSHOT_MIGRATION_CONFIG>['snapshotConfig']>())
        .addRequiredInput("migrations", typeToken<z.infer<typeof SNAPSHOT_MIGRATION_CONFIG>['migrations']>())
        // groupName facilitates grouping within the python `workflow` tools
        .addRequiredInput("groupName", typeToken<string>())
        .addOptionalInput("createSnapshotConfig",
                c=> expr.empty<z.infer<typeof ARGO_CREATE_SNAPSHOT_OPTIONS>>())

        .addRequiredInput("uniqueRunNonce", typeToken<string>())
        .addInputsFromRecord(ImageParameters)

        .addSteps(b => b
            .addStep("createOrGetSnapshot", CreateOrGetSnapshot, "createOrGetSnapshot",
                c => c.register({
                    ...selectInputsForRegister(b, c),
                    targetLabel: expr.jsonPathStrict(b.inputs.targetConfig, "label")
                }))

            .addStep("migrateFromSnapshot", INTERNAL, "migrateFromSnapshot", c=> {
                    return c.register({
                        ...(() => {
                            const {snapshotConfig, groupName, ...rest} = selectInputsForRegister(b, c);
                            return rest;
                        })(),
                        ...selectInputsFieldsAsExpressionRecord(c.item, c,
                            getZodKeys(PER_INDICES_SNAPSHOT_MIGRATION_CONFIG)),
                        snapshotConfig: c.steps.createOrGetSnapshot.outputs.snapshotConfig,
                        migrationLabel: expr.get(c.item, "label"),
                        groupName: expr.get(c.item, "label")
                    });
                },
                {loopWith: makeParameterLoop(expr.deserializeRecord(b.inputs.migrations))}

            )
        )
    )


    .addTemplate("migration", t=>t
        .addRequiredInput("sourceConfig", typeToken<z.infer<typeof NAMED_SOURCE_CLUSTER_CONFIG>>())
        .addRequiredInput("targetConfig", typeToken<z.infer<typeof NAMED_TARGET_CLUSTER_CONFIG>>())
        // groupName facilitates grouping within the python `workflow` tools
        .addOptionalInput("groupName", c => expr.concat(
            expr.get(expr.deserializeRecord(c.inputParameters.sourceConfig), "label"),
            expr.literal(" to "),
            expr.get(expr.deserializeRecord(c.inputParameters.targetConfig), "label"),
        ))
        .addOptionalInput("snapshotExtractAndLoadConfigArray",
            c => expr.empty<z.infer<typeof SNAPSHOT_MIGRATION_CONFIG>[]>())
        .addOptionalInput("replayerConfig",
            c => expr.empty<z.infer<typeof REPLAYER_OPTIONS>>())

        .addRequiredInput("uniqueRunNonce", typeToken<string>())
        .addInputsFromRecord(ImageParameters)

        .addSteps(b=>b
            .addStep("getSnapshotThenMigrateSnapshot", INTERNAL, "getSnapshotThenMigrateSnapshot", c => {
                    const {groupName, ...rest} = selectInputsForRegister(b, c);
                    return c.register({
                        ...rest,
                        ...selectInputsFieldsAsExpressionRecord(c.item, c, getZodKeys(SNAPSHOT_MIGRATION_CONFIG)),
                        groupName: expr.get(c.item, "label")

                    })
                },
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

        .addRequiredInput("uniqueRunNonce", typeToken<string>())
        .addInputsFromRecord(defaultImagesMap(t.inputs.workflowParameters.imageConfigMapName))

        .addSteps(b => b
            .addStep("migration", INTERNAL, "migration",
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
                    prefix: b.inputs.uniqueRunNonce
                }))
        )
    )


    .setEntrypoint("main")
    .getFullScope();
