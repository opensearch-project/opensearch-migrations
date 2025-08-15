import {z} from 'zod';
import {CLUSTER_CONFIG, IMAGE_PULL_POLICY, IMAGE_SPECIFIER, SNAPSHOT_MIGRATION_CONFIG} from '@/schemas/userSchemas'
import {CommonWorkflowParameters} from "@/workflowTemplates/commonWorkflowTemplates";
import {WorkflowBuilder} from "@/schemas/workflowBuilder";
import {TargetLatchHelpers} from "@/workflowTemplates/targetLatchHelpers";
import {asString, literal} from "@/schemas/expression";

export const FullMigration = WorkflowBuilder.create("FullMigration")
    .addParams(CommonWorkflowParameters)
    .addTemplate("main", t=> t
        .addRequiredInput("sourceMigrationConfigs",
            SNAPSHOT_MIGRATION_CONFIG,
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
    .getFullScope();
