import {z} from 'zod';
import {
    CLUSTER_CONFIG,
    REPLAYER_CONFIG,
    SNAPSHOT_MIGRATION_CONFIG,
    SOURCE_MIGRATION_CONFIG
} from '@/schemas/userSchemas'
import {CommonWorkflowParameters} from "@/workflowTemplates/commonWorkflowTemplates";
import {WorkflowBuilder} from "@/schemas/workflowBuilder";
import {TargetLatchHelpers} from "@/workflowTemplates/targetLatchHelpers";
import {literal} from "@/schemas/expression";
import {LoopWithParams, makeItemsLoop, makeParameterLoop, makeSequenceLoop} from "@/schemas/workflowTypes";

export const FullMigration = WorkflowBuilder.create({
        k8sResourceName: "FullMigration",
        parallelism: 100,
        serviceAccountName: "argo-workflow-executor"
    })
    .addParams(CommonWorkflowParameters)
    .addTemplate("pipelineSourceMigration", t => t
        .addRequiredInput("sourceMigrationConfig", SOURCE_MIGRATION_CONFIG)
        .addSteps(b => b
        )
    )
    .addTemplate("main", t=> t
        .addRequiredInput("sourceMigrationConfigs",
            z.array(SOURCE_MIGRATION_CONFIG),
            "List of server configurations to direct migrated traffic toward")
        .addRequiredInput("targets", z.array(CLUSTER_CONFIG),
            "List of server configurations to direct migrated traffic toward")
        .addOptionalInput("doSecondWhenTest", s=>true)
        .addOptionalInput("imageParams",
            scope => literal(
                Object.fromEntries(["captureProxy", "trafficReplayer", "reindexFromSnapshot", "migrationConsole", "etcdUtils"]
                        .flatMap((k) => [
                            [`${k}Image`, ""],
                            [`${k}ImagePullPolicy`, "IF_NOT_PRESENT"]
                        ])
                )
            ),
            "OCI image locations and pull policies for required images")
        .addSteps(b => b
            .addStep("init", TargetLatchHelpers, "init", steps => ({
                prefix: "w",
                etcdUtilsImagePullPolicy: "aa",
                targets: [],
                configuration: {
                    indices: [],
                    migrations: []
                }
            }))
                .addInternalStep("split", "pipelineSourceMigration", stepScope=> ({
                        sourceMigrationConfig: stepScope.item
                    }),
                    makeParameterLoop(b.inputs.sourceMigrationConfigs)
                )
                .addInternalStep("split2", "pipelineSourceMigration", stepScope=> ({
                        sourceMigrationConfig: stepScope.item
                    }),
                    makeParameterLoop(b.inputs.sourceMigrationConfigs), b.inputs.doSecondWhenTest
                )

            .addStep("cleanup", TargetLatchHelpers, "cleanup", stepScope => ({
                prefix: stepScope.steps.init.prefix,
                etcdUtilsImagePullPolicy: "IF_NOT_PRESENT"
            }))
        )
    )
    .addTemplate("cleanup", t => t
        .addSteps(b=>b)
    )
    .setEntrypoint("main")
    .getFullScope();
