import {z} from 'zod';
import {
    CLUSTER_CONFIG,
    COMPLETE_SNAPSHOT_CONFIG,
    DYNAMIC_SNAPSHOT_CONFIG,
    METADATA_OPTIONS,
    PER_INDICES_SNAPSHOT_MIGRATION_CONFIG, RFS_OPTIONS,
    SNAPSHOT_MIGRATION_CONFIG,
    SOURCE_MIGRATION_CONFIG,
    TARGET_CLUSTER_CONFIG
} from '@/workflowTemplates/userSchemas'
import {
    CommonWorkflowParameters,
    ImageParameters,
    LogicalOciImages,
    makeRequiredImageParametersForKeys
} from "@/workflowTemplates/commonWorkflowTemplates";
import {WorkflowBuilder} from "@/argoWorkflowBuilders/models/workflowBuilder";
import {TargetLatchHelpers} from "@/workflowTemplates/targetLatchHelpers";
import {BaseExpression, expr as expr} from "@/argoWorkflowBuilders/models/expression";
import {makeParameterLoop} from "@/argoWorkflowBuilders/models/workflowTypes";
import {configMapKey, defineParam, defineRequiredParam, InputParamDef} from "@/argoWorkflowBuilders/models/parameterSchemas";
import {INTERNAL} from "@/argoWorkflowBuilders/models/taskBuilder";
import {CreateOrGetSnapshot} from "@/workflowTemplates/createOrGetSnapshot";
import {DocumentBulkLoad} from "@/workflowTemplates/documentBulkLoad";
import {IMAGE_PULL_POLICY} from '@/argoWorkflowBuilders/models/containerBuilder';
import {MetadataMigration} from "@/workflowTemplates/metadataMigration";
import {selectInputsForRegister} from "@/argoWorkflowBuilders/models/parameterConversions";
import {typeToken} from "@/argoWorkflowBuilders/models/sharedTypes";

const latchCoordinationPrefixParam = {
    latchCoordinationPrefix: defineRequiredParam<string>({description: "Workflow session nonce"})
};

const targetsArrayParam = {
    targets: defineRequiredParam<z.infer<typeof TARGET_CLUSTER_CONFIG>[]>({
        description: "List of server configurations to direct migrated traffic toward"
    })
};

function lowercaseFirst(str: string): string {
    return str.charAt(0).toLowerCase() + str.slice(1);
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


    .addTemplate("pipelineMigrateFromSnapshot", t => t
        .addRequiredInput("migrationConfig", typeToken<z.infer<typeof PER_INDICES_SNAPSHOT_MIGRATION_CONFIG>>())
        .addRequiredInput("sourceConfig", typeToken<z.infer<typeof SOURCE_MIGRATION_CONFIG>['source']>())
        .addRequiredInput("snapshotConfig", typeToken<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>())
        .addRequiredInput("target", typeToken<z.infer<typeof TARGET_CLUSTER_CONFIG>>())

        .addInputsFromRecord(latchCoordinationPrefixParam)
        .addRequiredInput("useLocalStack", typeToken<boolean>(), "Only used for local testing")
        .addInputsFromRecord(ImageParameters)

        .addSteps(b => b
            .addStep("idGenerator", INTERNAL, "doNothing")
            .addStep("metadataMigrate", MetadataMigration, "migrateMetaData", c => {
                    return c.register({
                        ...selectInputsForRegister(b, c),
                        targetConfig: b.inputs.target,
                        indices: expr.serialize(
                            expr.dig(expr.deserializeRecord(b.inputs.migrationConfig),
                                ["metadata", "indices"], [])),
                        metadataMigrationConfig: expr.serialize(
                            expr.dig(expr.deserializeRecord(b.inputs.migrationConfig),
                                ["metadata", "options"], {} as z.infer<typeof METADATA_OPTIONS>))
                    });
                },
                {when: { templateExp: expr.hasKey(expr.deserializeRecord(b.inputs.migrationConfig), "metadata") }}
            )
            .addStep("bulkLoadDocuments", DocumentBulkLoad, "runBulkLoad", c =>
                c.register({
                    ...(selectInputsForRegister(b, c)),
                    sessionName: c.steps.idGenerator.id,
                    targetConfig: b.inputs.target,
                    indices: expr.serialize(expr.dig(expr.deserializeRecord(b.inputs.migrationConfig),
                        ["documentBackfillConfig", "indices"], [] as string[])),
                    backfillConfig: expr.serialize(expr.dig(expr.deserializeRecord(b.inputs.migrationConfig),
                        ["documentBackfillConfig", "options"], {} as z.infer<typeof RFS_OPTIONS>))
                }),
                {when: {templateExp: expr.hasKey(expr.deserializeRecord(b.inputs.migrationConfig), "documentBackfillConfig")}}
            )
            .addStep("targetBackfillCompleteCheck", TargetLatchHelpers, "decrementLatch", c =>
                c.register({
                    ...(selectInputsForRegister(b, c)),
                    prefix: b.inputs.latchCoordinationPrefix,
                    targetName: expr.jsonPathStrict(b.inputs.target, "name"),
                    processorId: c.steps.idGenerator.id
                }))
            .addStep("runReplayerForTarget", INTERNAL, "runReplayerForTarget", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    targetConfig: b.inputs.target
                }))
        )
    )


    .addTemplate("pipelineSnapshotToTarget", t => t
        .addRequiredInput("migrationConfigs", typeToken<z.infer<typeof SNAPSHOT_MIGRATION_CONFIG>['migrations']>())
        .addRequiredInput("sourceConfig", typeToken<z.infer<typeof SOURCE_MIGRATION_CONFIG>['source']>())
        .addRequiredInput("snapshotConfig", typeToken<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>())
        .addRequiredInput("target", typeToken<z.infer<typeof TARGET_CLUSTER_CONFIG>>())
        .addInputsFromRecord(latchCoordinationPrefixParam)
        .addRequiredInput("useLocalStack", typeToken<boolean>(), "Only used for local testing")
        .addInputsFromRecord(ImageParameters)

        .addSteps(b => b
            .addStep("pipelineMigrateFromSnapshot", INTERNAL, "pipelineMigrateFromSnapshot",
                c => c.register({
                    ...selectInputsForRegister(b, c),
                    migrationConfig: c.item
                }),
                {loopWith: makeParameterLoop(expr.deserializeRecord(b.inputs.migrationConfigs))})
        )
    )

    .addTemplate("snapshotAndLoad", t => t
        .addRequiredInput("sourceConfig", typeToken<z.infer<typeof CLUSTER_CONFIG>>())
        .addRequiredInput("snapshotAndMigrationConfig", typeToken<z.infer<typeof SNAPSHOT_MIGRATION_CONFIG>>())
        .addRequiredInput("sourcePipelineName", typeToken<string>())
        .addInputsFromRecord({...targetsArrayParam, ...ImageParameters})
        .addRequiredInput("latchCoordinationPrefix", typeToken<string>())
        .addRequiredInput("useLocalStack", typeToken<boolean>(), "Only used for local testing")
        .addSteps(b => b
            .addStep("createOrGetSnapshot", CreateOrGetSnapshot, "createOrGetSnapshot",
                c => c.register({
                    ...selectInputsForRegister(b, c),
                    indices: expr.serialize(
                        expr.dig(expr.deserializeRecord(b.inputs.snapshotAndMigrationConfig),
                            ["indices"], [])),
                    snapshotConfig: expr.serialize(
                        expr.dig(expr.deserializeRecord(b.inputs.snapshotAndMigrationConfig),
                            ["snapshotConfig"], {} as z.infer<typeof DYNAMIC_SNAPSHOT_CONFIG>)),
                    autocreateSnapshotName: b.inputs.sourcePipelineName
                }))

            .addStep("pipelineSnapshotToTarget", INTERNAL, "pipelineSnapshotToTarget",
                c => c.register({
                    ...selectInputsForRegister(b, c),
                    snapshotConfig: expr.serialize(c.steps.createOrGetSnapshot.outputs.snapshotConfig),
                    migrationConfigs: expr.serialize(
                        expr.dig(expr.deserializeRecord(b.inputs.snapshotAndMigrationConfig),
                            ["migrations"], [])),
                    target: c.item
                }),
                {loopWith: makeParameterLoop(expr.deserializeRecord(b.inputs.targets))})
        )
    )


    .addTemplate("migrateEachSourceToTargets", t => t
        .addRequiredInput("sourceMigrationConfig", typeToken<z.infer<typeof SOURCE_MIGRATION_CONFIG>>())
        .addInputsFromRecord(targetsArrayParam) // "targetConfig"
        .addRequiredInput("latchCoordinationPrefix", typeToken<string>())
        .addRequiredInput("useLocalStack", typeToken<boolean>(), "Only used for local testing")
        .addInputsFromRecord(ImageParameters)

        .addSteps(b => b
            .addStep("snapshotAndLoad", INTERNAL, "snapshotAndLoad", c =>
                    c.register({
                        ...selectInputsForRegister(b, c),
                        sourceConfig: expr.serialize(expr.jsonPathStrict(b.inputs.sourceMigrationConfig, "source")),
                        snapshotAndMigrationConfig: c.item,
                        sourcePipelineName: expr.toBase64(expr.asString(expr.recordToString(
                            expr.jsonPathStrict(b.inputs.sourceMigrationConfig, "source"))))
                    }),
                {loopWith: makeParameterLoop(
                    expr.jsonPathStrict(b.inputs.sourceMigrationConfig, "snapshotExtractAndLoadConfigs"))}
            )
        )
    )


    .addTemplate("main", t => t
        .addRequiredInput("sourceMigrationConfigs", typeToken<z.infer<typeof SOURCE_MIGRATION_CONFIG>[]>(),
            "List of server configurations to direct migrated traffic toward")
        .addInputsFromRecord(targetsArrayParam)
        .addOptionalInput("useLocalStack", c => false)
        .addInputsFromRecord( // These image configurations have defaults from the ConfigMap
            Object.fromEntries(LogicalOciImages.flatMap(k => [
                    [`image${k}Location`, defineParam({
                        type: typeToken<string>(),
                        from: configMapKey(t.inputs.workflowParameters.imageConfigMapName,
                            `${lowercaseFirst(k)}Image`)
                    })],
                    [`image${k}PullPolicy`, defineParam({
                        type: typeToken<string>(),
                        from: configMapKey(t.inputs.workflowParameters.imageConfigMapName,
                            `${lowercaseFirst(k)}PullPolicy`)
                    })]
                ])
            ) as Record<`image${typeof LogicalOciImages[number]}Location`, InputParamDef<string, false>> &
                Record<`image${typeof LogicalOciImages[number]}PullPolicy`, InputParamDef<IMAGE_PULL_POLICY, false>>
        )

        .addSteps(b => b
            .addStep("init", TargetLatchHelpers, "init", c =>
                c.register({
                    ...(selectInputsForRegister(b, c)),
                    prefix: expr.concat(expr.literal("workflow-"), expr.getWorkflowValue("uid")),
                    configuration: b.inputs.sourceMigrationConfigs
                }))
            .addStep("migrateEachSourceToTargets", INTERNAL, "migrateEachSourceToTargets",
                c => c.register({
                    ...selectInputsForRegister(b, c),
                    sourceMigrationConfig: c.item,
                    latchCoordinationPrefix: c.steps.init.outputs.prefix
                }),
                {loopWith: makeParameterLoop(expr.deserializeRecord(b.inputs.sourceMigrationConfigs))})
            .addStep("cleanup", TargetLatchHelpers, "cleanup",
                c => c.register({
                    ...selectInputsForRegister(b, c),
                    prefix: c.steps.init.outputs.prefix
                }))
        )
    )


    .setEntrypoint("main")
    .getFullScope();
