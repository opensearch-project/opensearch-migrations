import {z} from 'zod';
import {
    CLUSTER_CONFIG, DYNAMIC_SNAPSHOT_CONFIG, COMPLETE_SNAPSHOT_CONFIG,
    SNAPSHOT_MIGRATION_CONFIG,
    SOURCE_MIGRATION_CONFIG, TARGET_CLUSTER_CONFIG, PER_INDICES_SNAPSHOT_MIGRATION_CONFIG
} from '@/workflowTemplates/userSchemas'
import {
    CommonWorkflowParameters,
    ImageParameters, LogicalOciImages, makeRequiredImageParametersForKeys, dynamicSnapshotConfigParam,
    completeSnapshotConfigParam
} from "@/workflowTemplates/commonWorkflowTemplates";
import {WorkflowBuilder} from "@/schemas/workflowBuilder";
import {TargetLatchHelpers} from "@/workflowTemplates/targetLatchHelpers";
import {expr as EXPR} from "@/schemas/expression";
import {makeParameterLoop} from "@/schemas/workflowTypes";
import {configMapKey, defineParam, defineRequiredParam, InputParamDef} from "@/schemas/parameterSchemas";
import {INTERNAL} from "@/schemas/taskBuilder";
import {CreateOrGetSnapshot} from "@/workflowTemplates/createOrGetSnapshot";
import {DocumentBulkLoad} from "@/workflowTemplates/documentBulkLoad";
import { IMAGE_PULL_POLICY } from '@/schemas/containerBuilder';
import {MetadataMigration} from "@/workflowTemplates/metadataMigration";
import {selectInputsForRegister} from "@/schemas/parameterConversions";
import {typeToken} from "@/schemas/sharedTypes";

const latchCoordinationPrefixParam = {
    latchCoordinationPrefix: defineRequiredParam<string>({description: "Workflow session nonce"})
};

const targetsArrayParam = {
    targets: defineRequiredParam<z.infer<typeof TARGET_CLUSTER_CONFIG>[]>({
        description: "List of server configurations to direct migrated traffic toward"})
};

export const FullMigration = WorkflowBuilder.create({
        k8sResourceName: "FullMigration",
        parallelism: 100,
        serviceAccountName: "argo-workflow-executor"
    })


    .addParams(CommonWorkflowParameters)


    .addTemplate("doNothing", t=>t
        .addSteps(sb=>sb))


    .addTemplate("runReplayerForTarget", t => t
        .addRequiredInput("targetConfig", typeToken<z.infer<typeof TARGET_CLUSTER_CONFIG>>())
        .addInputsFromRecord(makeRequiredImageParametersForKeys(["EtcdUtils"]))
        .addContainer(cb=>cb
            .addImageInfo(cb.inputs.imageEtcdUtilsLocation, cb.inputs.imageEtcdUtilsPullPolicy)
            .addCommand(["sh", "-c"])
            .addArgs(["echo runReplayerForTarget"])))


    .addTemplate("pipelineMigrateFromSnapshot", t=>t
        .addRequiredInput("migrationConfig", typeToken<z.infer<typeof PER_INDICES_SNAPSHOT_MIGRATION_CONFIG>>())
        .addRequiredInput("sourceConfig", typeToken<z.infer<typeof SOURCE_MIGRATION_CONFIG>['source']>())
        .addRequiredInput("snapshotConfig", typeToken<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>())
        .addRequiredInput("target", typeToken<z.infer<typeof TARGET_CLUSTER_CONFIG>>())

        .addInputsFromRecord(latchCoordinationPrefixParam)
        .addRequiredInput("useLocalStack", typeToken<boolean>(), "Only used for local testing")
        .addInputsFromRecord(ImageParameters)

        .addSteps(b=>b
            .addStep("idGenerator", INTERNAL, "doNothing")
            .addStep("metadataMigrate", MetadataMigration, "migrateMetaData", c =>
                c.register({
                    ...selectInputsForRegister(b, c),
                    targetConfig: b.inputs.target,
                    indices: EXPR.jsonPathLoose(b.inputs.migrationConfig, "metadata", "indices"),
                    metadataMigrationConfig: EXPR.jsonPathLoose(b.inputs.migrationConfig, "metadata", "options")
                }))
            .addStep("bulkLoadDocuments", DocumentBulkLoad, "runBulkLoad", c =>
                c.register({
                    ...(selectInputsForRegister(b, c)),
                    sessionName: c.steps.idGenerator.id,
                    targetConfig: b.inputs.target,
                    indices: EXPR.nullCoalesce(EXPR.jsonPathLoose(b.inputs.migrationConfig, "documentBackfillConfigs", "indices"), []),
                    backfillConfig:  EXPR.jsonPathStrict(b.inputs.migrationConfig, "documentBackfillConfigs", "options")
                }))
            .addStep("targetBackfillCompleteCheck", TargetLatchHelpers, "decrementLatch", c=>
                c.register({
                    ...(selectInputsForRegister(b, c)),
                    prefix: b.inputs.latchCoordinationPrefix,
                    targetName: EXPR.jsonPathLoose(b.inputs.target, "name"),
                    processorId: c.steps.idGenerator.id
                }))
            .addStep("runReplayerForTarget", INTERNAL, "runReplayerForTarget", c=>
                c.register({
                    ...selectInputsForRegister(b,c),
                    targetConfig: b.inputs.target
                }))
        )
    )


    .addTemplate("pipelineSnapshotToTarget", t=>t
        .addRequiredInput("migrationConfigs", typeToken<z.infer<typeof SNAPSHOT_MIGRATION_CONFIG>['migrations']>())
        .addRequiredInput("sourceConfig", typeToken<z.infer<typeof SOURCE_MIGRATION_CONFIG>['source']>())
        .addRequiredInput("snapshotConfig", typeToken<z.infer<typeof COMPLETE_SNAPSHOT_CONFIG>>())
        .addRequiredInput("target", typeToken<z.infer<typeof TARGET_CLUSTER_CONFIG>>())
        .addInputsFromRecord(latchCoordinationPrefixParam)
        .addRequiredInput("useLocalStack", typeToken<boolean>(), "Only used for local testing")
        .addInputsFromRecord(ImageParameters)

        .addSteps(b=>b
            .addStep("pipelineMigrateFromSnapshot", INTERNAL, "pipelineMigrateFromSnapshot",
                c=>c.register({
                    ...selectInputsForRegister(b, c),
                    migrationConfig: c.item
                }),
                {loopWith: makeParameterLoop(b.inputs.migrationConfigs)})
        )
    )

    .addTemplate("pipelineSnapshot", t => t
        .addRequiredInput("sourceConfig", typeToken<z.infer<typeof CLUSTER_CONFIG>>())
        .addRequiredInput("snapshotAndMigrationConfig", typeToken<z.infer<typeof SNAPSHOT_MIGRATION_CONFIG>>())
        .addRequiredInput("sourcePipelineName", typeToken<string>())
        .addInputsFromRecord({...dynamicSnapshotConfigParam, ...targetsArrayParam, ...ImageParameters})
        .addRequiredInput("latchCoordinationPrefix", typeToken<string>())
        .addRequiredInput("useLocalStack", typeToken<boolean>(), "Only used for local testing")
        .addSteps(b=> b
                .addStep("createOrGetSnapshot", CreateOrGetSnapshot, "createOrGetSnapshot",
                    c=>c.register({
                        ...selectInputsForRegister(b, c),
                        indices: EXPR.jsonPathLoose(b.inputs.snapshotAndMigrationConfig, "indices"),
                        autocreateSnapshotName: b.inputs.sourcePipelineName
                    }))

                .addStep("pipelineSnapshotToTarget", INTERNAL, "pipelineSnapshotToTarget",
                    c=> c.register({
                        ...selectInputsForRegister(b, c),
                        snapshotConfig: c.steps.createOrGetSnapshot.outputs.snapshotConfig,
                        migrationConfigs: EXPR.jsonPathLoose(b.inputs.snapshotAndMigrationConfig, "migrations"),
                        target: c.item
                    }),
                    {loopWith: makeParameterLoop(b.inputs.targets)})
                )
    )


    .addTemplate("pipelineSourceMigration", t => t
        .addRequiredInput("sourceMigrationConfig", typeToken<z.infer<typeof SOURCE_MIGRATION_CONFIG>>())
        .addInputsFromRecord(targetsArrayParam) // "targetConfig"
        .addInputsFromRecord(completeSnapshotConfigParam)
        .addRequiredInput("latchCoordinationPrefix", typeToken<string>())
        .addRequiredInput("useLocalStack", typeToken<boolean>(), "Only used for local testing")
        .addInputsFromRecord(ImageParameters)

        .addSteps(b=>b
            .addStep("pipelineSnapshot", INTERNAL, "pipelineSnapshot", c =>
                    c.register({
                        ...selectInputsForRegister(b,c),
                        sourceConfig: EXPR.jsonPathLoose(b.inputs.sourceMigrationConfig, "source"),
                        snapshotAndMigrationConfig: c.item,
                        sourcePipelineName: EXPR.toBase64(EXPR.recordToString(EXPR.jsonPathLoose(b.inputs.sourceMigrationConfig, "source")))
                    }),
                {loopWith: makeParameterLoop(EXPR.jsonPathLoose(b.inputs.sourceMigrationConfig, "snapshotAndMigrationConfigs")
                    )})
        )
    )


    .addTemplate("main", t => t
        .addRequiredInput("sourceMigrationConfigs", typeToken<z.infer<typeof SOURCE_MIGRATION_CONFIG>[]>(),
            "List of server configurations to direct migrated traffic toward")
        .addInputsFromRecord(targetsArrayParam)
        .addInputsFromRecord(completeSnapshotConfigParam)
        .addOptionalInput("useLocalStack", c=>false)
        .addInputsFromRecord( // These image configurations have defaults from the ConfigMap
            Object.fromEntries(LogicalOciImages.flatMap(k => [
                [`image${k}Location`, defineParam({
                    type: typeToken<string>(),
                    from: configMapKey(t.inputs.workflowParameters.imageConfigMapName, `${k}Location`)
                })],
                [`image${k}Location`, defineParam({
                    type: typeToken<string>(),
                    from: configMapKey(t.inputs.workflowParameters.imageConfigMapName, `${k}PullPolicy`)
                })]
            ])
            ) as Record<`image${typeof LogicalOciImages[number]}Location`, InputParamDef<string,false>> &
                Record<`image${typeof LogicalOciImages[number]}PullPolicy`, InputParamDef<IMAGE_PULL_POLICY,false>>
        )

        .addSteps(b => b
            .addStep("init", TargetLatchHelpers, "init", c =>
                c.register({
                    ...(selectInputsForRegister(b, c)),
                    prefix: EXPR.concat(EXPR.literal("workflow-"), EXPR.getWorkflowValue("uid")),
                    configuration: b.inputs.sourceMigrationConfigs
                }))
            .addStep("pipelineSourceMigration", INTERNAL, "pipelineSourceMigration",
                c=>c.register({
                    ...selectInputsForRegister(b, c),
                    sourceMigrationConfig: c.item,
                    latchCoordinationPrefix: c.steps.init.outputs.prefix
                }),
                {loopWith: makeParameterLoop(b.inputs.sourceMigrationConfigs)})
            .addStep("cleanup", TargetLatchHelpers, "cleanup",
                c => c.register({
                    ...selectInputsForRegister(b, c),
                    prefix: c.steps.init.outputs.prefix
                }))
        )
    )


    .setEntrypoint("main")
    .getFullScope();
