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

export const FullMigration = WorkflowBuilder.create({
        k8sResourceName: "FullMigration",
        parallelism: 100,
        serviceAccountName: "argo-workflow-executor"
    })
    .addParams(CommonWorkflowParameters)
    .addTemplate("pipelineSourceMigration", t => t
        .addRequiredInput("sourceMigrationConfig", z.array(SOURCE_MIGRATION_CONFIG))
        .addSteps(b => b
        )
    )
    .addTemplate("main", t=> t
        .addRequiredInput("sourceMigrationConfigs",
            z.array(SOURCE_MIGRATION_CONFIG),
            "List of server configurations to direct migrated traffic toward")
        .addRequiredInput("targets", z.array(CLUSTER_CONFIG),
            "List of server configurations to direct migrated traffic toward")
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
            .addStepGroup(gb=>gb
                .addInternalStep("split", "pipelineSourceMigration", steps=> ({
                    sourceMigrationConfig: b.inputs.sourceMigrationConfigs
                }))
            )
            .addStep("cleanup", TargetLatchHelpers, "cleanup", steps => ({
                prefix: steps.init.prefix,
                etcdUtilsImage: "",
                etcdUtilsImagePullPolicy: "IF_NOT_PRESENT"
            }))
        )
    )
    .addTemplate("cleanup", t => t
        .addSteps(b=>b)
    )
    .setEntrypoint("main")
    .getFullScope();
