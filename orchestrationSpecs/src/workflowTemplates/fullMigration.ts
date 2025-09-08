import {z, ZodAny, ZodObject} from 'zod';
import {
    CLUSTER_CONFIG,
    SNAPSHOT_MIGRATION_CONFIG,
    SOURCE_MIGRATION_CONFIG, TARGET_CLUSTER_CONFIG,
    UNKNOWN
} from '@/workflowTemplates/userSchemas'
import {
    CommonWorkflowParameters,
    ImageParameters, LogicalOciImages, makeRequiredImageParametersForKeys,
    s3ConfigParam
} from "@/workflowTemplates/commonWorkflowTemplates";
import {WorkflowBuilder} from "@/schemas/workflowBuilder";
import {TargetLatchHelpers} from "@/workflowTemplates/targetLatchHelpers";
import {BaseExpression, configMap, equals, literal, path} from "@/schemas/expression";
import {LoopWithParams, makeParameterLoop} from "@/schemas/workflowTypes";
import {defineParam, defineRequiredParam, InputParamDef, typeToken} from "@/schemas/parameterSchemas";
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

const s3ImageTargetParams = { // s3ConfigParam, targets, ImageParameters
    ...s3ConfigParam, ...targetsArrayParam, ...ImageParameters
}

const sourceMigrationParams = { // sourceMigrationConfig, snapshotConfig, migrationConfig
    sourceConfig: defineRequiredParam<z.infer<typeof SOURCE_MIGRATION_CONFIG>['source']>(),
    snapshotConfig: defineRequiredParam<z.infer<typeof SNAPSHOT_MIGRATION_CONFIG>>(),
    migrationConfig: defineRequiredParam<z.infer<typeof UNKNOWN>>(),
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
            .addStep("bulkLoadDocuments", DocumentBulkLoad, "runBulkLoadFromConfig", c =>
                c.register({
                    ...(selectInputsForRegister(b, c)),
                    sessionName: c.steps.idGenerator.id,
                    targetConfig: b.inputs.target
                }))
            .addStep("targetBackfillCompleteCheck", TargetLatchHelpers, "decrementLatch", c=>
                c.register({
                    ...(selectInputsForRegister(b, c)),
                    prefix: b.inputs.latchCoordinationPrefix,
                    targetName: path(b.inputs.target, "name"),
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
        .addSteps(b=> b
                .addStep("createOrGetSnapshot", CreateOrGetSnapshot, "createOrGetSnapshot",
                    c=>c.register({
                        ...selectInputsForRegister(b,c),
                        sourceName: b.inputs.sourcePipelineName
                    }))

                // .addStep("pipelineSnapshotToTarget", INTERNAL, "pipelineSnapshotToTarget",
                //     c=>c.register({
                //         ...b.inputs as Omit<typeof b.inputs, "sourceMigrationConfig">,
                //         target: c.item,
                //         snapshotConfig: c.steps.createOrGetSnapshot.outputs.snapshotConfig,
                //         migrationConfig: literal({}),
                //         imageCaptureProxyLocation: b.inputs.imageCaptureProxyLocation
                //
                //         }),
                //     {loopWith: makeParameterLoop(b.inputs.targets)})
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
                        sourceConfig: path(b.inputs.sourceMigrationConfig, "source"),
                        snapshotAndMigrationConfig: c.item,
                        ...(b.inputs as Pick<typeof b.inputs,("targets" | "s3Config" | "latchCoordinationPrefix") > ),
                        sourcePipelineName: '' // value: "{{=let jscfg=fromJSON(inputs.parameters['source-migration-config']); lower(toBase64(toJSON(jscfg['source'])))}}"
                    }),
                {loopWith: makeParameterLoop(path(b.inputs.sourceMigrationConfig, "snapshotAndMigrationConfigs"))})
        )
    )


    .addTemplate("main", t => t
        .addRequiredInput("sourceMigrationConfigs", // LOOP OVER THESE
            typeToken<z.infer<typeof SOURCE_MIGRATION_CONFIG>[]>(),
            "List of server configurations to direct migrated traffic toward")
        .addInputsFromRecord(targetsArrayParam)
        .addInputsFromRecord(s3ConfigParam)
        .addInputsFromRecord(
            Object.fromEntries(LogicalOciImages.flatMap(k => [
                [`image${k}Location`, defineParam({defaultValue: configMap(t.inputs.workflowParameters.imageConfigMapName, `${k}Location`)})],
                [`image${k}PullPolicy`, defineParam({defaultValue: configMap(t.inputs.workflowParameters.imageConfigMapName, `${k}PullPolicy`)})]
            ])
            ) as Record<`image${typeof LogicalOciImages[number]}Location`, InputParamDef<string,false>> &
                Record<`image${typeof LogicalOciImages[number]}PullPolicy`, InputParamDef<IMAGE_PULL_POLICY,false>>
        )

        .addSteps(b => b
            .addStep("generateId", INTERNAL, "doNothing")
            .addStep("init", TargetLatchHelpers, "init", c =>
                c.register({
                    ...(selectInputsForRegister(b, c)),
                    prefix: "w",
                    targets: [],
                    configuration: {
                        indices: [],
                        migrations: []
                    }
                }))
            .addStep("cleanup", TargetLatchHelpers, "cleanup",
                c => c.register({
                    ...selectInputsForRegister(b, c),
                    prefix: c.steps.init.outputs.prefix
                }))
        )
    )


    .setEntrypoint("main")
    .getFullScope();
