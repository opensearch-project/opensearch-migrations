import {z, ZodAny, ZodObject} from 'zod';
import {
    CLUSTER_CONFIG, S3_CONFIG,
    SNAPSHOT_MIGRATION_CONFIG,
    SOURCE_MIGRATION_CONFIG, TARGET_CLUSTER_CONFIG,
    UNKNOWN
} from '@/workflowTemplates/userSchemas'
import {
    CommonWorkflowParameters,
    ImageParameters,
    makeImageParametersForKeys
} from "@/workflowTemplates/commonWorkflowTemplates";
import {WorkflowBuilder} from "@/schemas/workflowBuilder";
import {TargetLatchHelpers} from "@/workflowTemplates/targetLatchHelpers";
import {BaseExpression, equals, literal, path} from "@/schemas/expression";
import {LoopWithParams, makeParameterLoop} from "@/schemas/workflowTypes";
import {defineRequiredParam, typeToken} from "@/schemas/parameterSchemas";
import {INTERNAL} from "@/schemas/taskBuilder";

const leftE: BaseExpression<string, "govaluate"> = literal("a");
const rightE: BaseExpression<string, "govaluate"> = literal("a");
const eE: BaseExpression<boolean, "govaluate"> = equals(leftE, rightE);

const s3ArrayParam = {
    s3Config: defineRequiredParam<z.infer<typeof S3_CONFIG>[]>({
        description: "S3 connection info (region, endpoint, etc)"})
};

const targetsArrayParam = {
    targets: defineRequiredParam<z.infer<typeof CLUSTER_CONFIG>[]>({
        description: "List of server configurations to direct migrated traffic toward"})
};

const s3ImageTargetParams = {
    ...s3ArrayParam, ...targetsArrayParam, ...ImageParameters
}

const latchCoordinationPrefixParam = {
    latchCoordinationPrefix: defineRequiredParam<string>({description: "Workflow session nonce"})
};

const sourceMigrationParams = {
    sourceMigrationConfig: defineRequiredParam<z.infer<typeof SOURCE_MIGRATION_CONFIG>>(),
    snapshotConfig: defineRequiredParam<z.infer<typeof SNAPSHOT_MIGRATION_CONFIG>>(),
    migrationConfig: defineRequiredParam<z.infer<typeof UNKNOWN>>(),
    ...targetsArrayParam
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
        .addInputsFromRecord(sourceMigrationParams)
        .addSteps(sb=>sb))


    .addTemplate("pipelineSnapshot", t => t
        .addRequiredInput("sourceConfig", typeToken<z.infer<typeof CLUSTER_CONFIG>>())
        .addRequiredInput("snapshotAndMigrationConfig", typeToken<z.infer<typeof SNAPSHOT_MIGRATION_CONFIG>>())
        .addRequiredInput("sourcePipelineName", typeToken<string>())
        .addInputsFromRecord(s3ImageTargetParams)
        .addInputsFromRecord(latchCoordinationPrefixParam)
        .addSteps(b=> b
                .addStep("generateId", INTERNAL, "doNothing")
                // .addStep("migrateMetadata", INTERNAL, "migrateMetaData",
                //     (steps,register)=>
                //         register({
                //             // ...b.inputs as Omit<typeof b.inputs, "sourceMigrationConfig">,
                //             // snapshotConfig:
                //         }))
        ))

    .addTemplate("pipelineSourceMigration", t => t

        .addRequiredInput("sourceMigrationConfig", typeToken<z.infer<typeof SOURCE_MIGRATION_CONFIG>>())
        .addInputsFromRecord(targetsArrayParam) // "targetConfig"
        .addInputsFromRecord(s3ArrayParam) // s3Config
        .addRequiredInput("latchCoordinationPrefix", typeToken<string>())
        .addInputsFromRecord(ImageParameters)

        .addSteps(b=>b
            .addStep("pipelineSnapshot", INTERNAL, "pipelineSnapshot",
                (steps,register)=>
                    register({
                        sourceConfig: path(b.inputs.sourceMigrationConfig, "source"),
                        snapshotAndMigrationConfig: steps.item,
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
        .addInputsFromRecord(s3ArrayParam)
        .addInputsFromRecord(ImageParameters)

        .addSteps(b => b
            .addStep("generateId", INTERNAL, "doNothing")
            .addStep("init", TargetLatchHelpers, "init",
                (steps,register) => register({
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
            //         latchCoordinationPrefix: stepScope.tasks.init.prefix
            //     }),
            //     { loopWith: makeParameterLoop(b.inputs.sourceMigrationConfigs) }
            // )

            .addStep("cleanup", TargetLatchHelpers, "cleanup",
                (stepScope,register) => register({
                    prefix: stepScope.tasks.init.prefix,
                    etcdUtilsImagePullPolicy: "IF_NOT_PRESENT"
                }))
        )
    )
    .addTemplate("cleanup", t => t
        .addSteps(b => b)
    )
    .setEntrypoint("main")
    .getFullScope();
