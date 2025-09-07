import {z, ZodAny, ZodObject} from 'zod';
import {
    CLUSTER_CONFIG,
    SNAPSHOT_MIGRATION_CONFIG,
    SOURCE_MIGRATION_CONFIG, TARGET_CLUSTER_CONFIG,
    UNKNOWN
} from '@/workflowTemplates/userSchemas'
import {
    CommonWorkflowParameters,
    ImageParameters,
    makeImageParametersForKeys, s3ConfigParam
} from "@/workflowTemplates/commonWorkflowTemplates";
import {WorkflowBuilder} from "@/schemas/workflowBuilder";
import {TargetLatchHelpers} from "@/workflowTemplates/targetLatchHelpers";
import {BaseExpression, equals, literal, path} from "@/schemas/expression";
import {LoopWithParams, makeParameterLoop} from "@/schemas/workflowTypes";
import {defineRequiredParam, typeToken} from "@/schemas/parameterSchemas";
import {INTERNAL, SelectInputsForRegister} from "@/schemas/taskBuilder";
import {CreateOrGetSnapshot} from "@/workflowTemplates/createOrGetSnapshot";
import {DocumentBulkLoad} from "@/workflowTemplates/documentBulkLoad";

const latchCoordinationPrefixParam = {
    latchCoordinationPrefix: defineRequiredParam<string>({description: "Workflow session nonce"})
};

const targetsArrayParam = {
    targets: defineRequiredParam<z.infer<typeof CLUSTER_CONFIG>[]>({
        description: "List of server configurations to direct migrated traffic toward"})
};

const s3ImageTargetParams = {
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
        .addRequiredInput("targetConfig", typeToken<string>())
        .addInputsFromRecord(makeImageParametersForKeys(["EtcdUtils"]))
        .addContainer(cb=>cb
            .addImageInfo(cb.inputs.imageEtcdUtilsLocation, cb.inputs.imageEtcdUtilsPullPolicy)
            .addCommand(["sh", "-c"])
            .addArgs(["echo runReplayerForTarget"])))


    .addTemplate("migrateMetaData", t => t
        .addInputsFromRecord(sourceMigrationParams) // sourceConfig, snapshotConfig, migrationConfig, targets
        .addSteps(sb=>sb))


    .addTemplate("pipelineMigrateFromSnapshot", t=>t
        .addInputsFromRecord(sourceMigrationParams) // // sourceConfig, snapshotConfig, migrationConfig, target
        .addRequiredInput("sourcePipelineName", typeToken<string>())
        .addInputsFromRecord(latchCoordinationPrefixParam)
        .addInputsFromRecord(ImageParameters)

        .addSteps(b=>b
            .addStep("doNothing", INTERNAL, "doNothing")
            .addStep("metadataMigrate", INTERNAL, "migrateMetaData", c =>
                c.register(b.inputs as SelectInputsForRegister<typeof b.inputs, typeof c.register>))
            .addStep("bulkLoadDocuments", DocumentBulkLoad, "runBulkLoadFromConfig", c =>
                c.register({
                ...(b.inputs as SelectInputsForRegister<typeof b.inputs, typeof c.register>),
                sessionName: c.steps.doNothing.id,
                targetConfig: b.inputs.target
            }))
        )
    )

    .addTemplate("pipelineSnapshotToTarget", t=>t
        .addSteps(sb=>sb))


    .addTemplate("pipelineSnapshot", t => t
        .addRequiredInput("sourceConfig", typeToken<z.infer<typeof CLUSTER_CONFIG>>())
        .addRequiredInput("snapshotAndMigrationConfig", typeToken<z.infer<typeof SNAPSHOT_MIGRATION_CONFIG>>())
        .addRequiredInput("sourcePipelineName", typeToken<string>())
        .addInputsFromRecord(s3ImageTargetParams)
        .addInputsFromRecord(latchCoordinationPrefixParam)
        .addSteps(b=> b
                .addStep("createOrGetSnapshot", CreateOrGetSnapshot, "createOrGetSnapshot",
                    c=>c.register({
                        ...(b.inputs as SelectInputsForRegister<typeof b.inputs, typeof c.register>),
                        sourceName: b.inputs.sourcePipelineName
                    }))
                // .addStep("migrateMetadata", INTERNAL, "migrateMetaData",
                //     (steps,register)=>
                //         register({
                //             // ...b.inputs as Omit<typeof b.inputs, "sourceMigrationConfig">,
                //             // snapshotConfig:
                //         }))
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
        .addInputsFromRecord(ImageParameters)

        .addSteps(b => b
            .addStep("generateId", INTERNAL, "doNothing")
            .addStep("init", TargetLatchHelpers, "init", c =>
                c.register({
                    prefix: "w",
                    etcdUtilsImagePullPolicy: "IF_NOT_PRESENT",
                    targets: [],
                    configuration: {
                        indices: [],
                        migrations: []
                    }
                }))
            // .addStep("split", INTERNAL, "pipelineSourceMigration",
            //     (stepScope,register) => register({
            //         sourceMigrationConfig: stepScope.item
            //         snapshotConfig: {
            //
            //         },
            //         migrationConfig: undefined,
            //         targets: [],
            //         latchCoordinationPrefix: stepScope.steps.init.prefix
            //     }),
            //     { loopWith: makeParameterLoop(b.inputs.sourceMigrationConfigs) }
            // )

            .addStep("cleanup", TargetLatchHelpers, "cleanup",
                c => c.register({
                    prefix: c.steps.init.outputs.prefix,
                    etcdUtilsImagePullPolicy: "IF_NOT_PRESENT"
                }))
        )
    )


    .setEntrypoint("main")
    .getFullScope();
