import {z} from 'zod';
import {
    CLUSTER_CONFIG, S3_CONFIG,
    SNAPSHOT_MIGRATION_CONFIG,
    SOURCE_MIGRATION_CONFIG, TARGET_CLUSTER_CONFIG
} from '@/workflowTemplates/userSchemas'
import {
    CommonWorkflowParameters,
    ImageParameters, LogicalOciImages, makeRequiredImageParametersForKeys,
    s3ConfigParam
} from "@/workflowTemplates/commonWorkflowTemplates";
import {WorkflowBuilder} from "@/schemas/workflowBuilder";
import {TargetLatchHelpers} from "@/workflowTemplates/targetLatchHelpers";
import {expr as EXPR} from "@/schemas/expression";
import {makeParameterLoop} from "@/schemas/workflowTypes";
import {configMapKey, defineParam, defineRequiredParam, InputParamDef, typeToken} from "@/schemas/parameterSchemas";
import {INTERNAL, selectInputsForRegister} from "@/schemas/taskBuilder";
import {CreateOrGetSnapshot} from "@/workflowTemplates/createOrGetSnapshot";
import {DocumentBulkLoad} from "@/workflowTemplates/documentBulkLoad";
import { IMAGE_PULL_POLICY } from '@/schemas/containerBuilder';

const latchCoordinationPrefixParam = {
    latchCoordinationPrefix: defineRequiredParam<string>({description: "Workflow session nonce"})
};

const targetsArrayParam = {
    targets: defineRequiredParam<z.infer<typeof TARGET_CLUSTER_CONFIG>[]>({
        description: "List of server configurations to direct migrated traffic toward"})
};

const s3ImageTargetParams = { ...s3ConfigParam, ...targetsArrayParam, ...ImageParameters }

const sourceMigrationParams = { // sourceMigrationConfig, snapshotConfig, migrationConfig
    sourceConfig: defineRequiredParam<z.infer<typeof SOURCE_MIGRATION_CONFIG>['source']>(),
    snapshotName: defineRequiredParam<string>(),
    s3Config: defineRequiredParam<z.infer<typeof S3_CONFIG>>(),
    migrationConfig: defineRequiredParam<z.infer<typeof SNAPSHOT_MIGRATION_CONFIG>['migrations']>(),
    target: defineRequiredParam<z.infer<typeof TARGET_CLUSTER_CONFIG>>(
        {description: "Server configuration to direct migrated traffic toward" })
}

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


    .addTemplate("migrateMetaData", t => t
        .addInputsFromRecord(sourceMigrationParams) // sourceConfig, snapshotConfig, migrationConfig, targets
        .addSteps(sb=>sb))


    .addTemplate("pipelineMigrateFromSnapshot", t=>t
        .addInputsFromRecord(sourceMigrationParams) // sourceConfig, snapshotConfig, migrationConfig, target
        .addInputsFromRecord(latchCoordinationPrefixParam)
        .addInputsFromRecord(ImageParameters)

        .addSteps(b=>b
            .addStep("idGenerator", INTERNAL, "doNothing")
            .addStep("metadataMigrate", INTERNAL, "migrateMetaData", c =>
                c.register(selectInputsForRegister(b, c)))
            .addStep("bulkLoadDocuments", DocumentBulkLoad, "runBulkLoad", c =>
                c.register({
                    ...(selectInputsForRegister(b, c)),
                    sessionName: c.steps.idGenerator.id,
                    targetConfig: b.inputs.target
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
        .addInputsFromRecord(sourceMigrationParams) // sourceConfig, snapshotConfig, migrationConfig, target
        .addInputsFromRecord(latchCoordinationPrefixParam)
        .addInputsFromRecord(ImageParameters)

        .addSteps(b=>b
            .addStep("pipelineMigrateFromSnapshot", INTERNAL, "pipelineMigrateFromSnapshot",
                c=>c.register(selectInputsForRegister(b, c)))
        )
    )

    .addTemplate("pipelineSnapshot", t => t
        .addRequiredInput("sourceConfig", typeToken<z.infer<typeof CLUSTER_CONFIG>>())
        .addRequiredInput("snapshotAndMigrationConfig", typeToken<z.infer<typeof SNAPSHOT_MIGRATION_CONFIG>>())
        .addRequiredInput("sourcePipelineName", typeToken<string>())
        .addInputsFromRecord(s3ImageTargetParams) // s3ConfigParam, targets, ImageParameters
        .addRequiredInput("latchCoordinationPrefix", typeToken<string>())
        .addSteps(b=> b
                .addStep("createOrGetSnapshot", CreateOrGetSnapshot, "createOrGetSnapshot",
                    c=>c.register({
                        ...selectInputsForRegister(b,c),
                        sourceName: b.inputs.sourcePipelineName
                    }))

                .addStep("pipelineSnapshotToTarget", INTERNAL, "pipelineSnapshotToTarget",
                    c=> c.register({
                        ...selectInputsForRegister(b, c),
                        snapshotName: c.steps.createOrGetSnapshot.outputs.snapshotName,
                        migrationConfig: EXPR.jsonPathLoose(b.inputs.snapshotAndMigrationConfig, "migrations"),
                        target: c.item
                    }),
                    {loopWith: makeParameterLoop(b.inputs.targets)})
                )
    )


    .addTemplate("pipelineSourceMigration", t => t
        .addRequiredInput("sourceMigrationConfig", typeToken<z.infer<typeof SOURCE_MIGRATION_CONFIG>>())
        .addInputsFromRecord(targetsArrayParam) // "targetConfig"
        .addInputsFromRecord(s3ConfigParam) // s3Config
        .addRequiredInput("latchCoordinationPrefix", typeToken<string>())
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
        .addInputsFromRecord(s3ConfigParam)
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
