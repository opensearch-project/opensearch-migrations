import {z} from 'zod';
import {CLUSTER_CONFIG, SOURCE_MIGRATION_CONFIG} from '@/workflowTemplates/userSchemas'
import {CommonWorkflowParameters} from "@/workflowTemplates/commonWorkflowTemplates";
import {WorkflowBuilder} from "@/schemas/workflowBuilder";
import {TargetLatchHelpers} from "@/workflowTemplates/targetLatchHelpers";
import {BaseExpression, equals, literal} from "@/schemas/expression";
import {makeParameterLoop} from "@/schemas/workflowTypes";
import {typeToken} from "@/schemas/parameterSchemas";

const leftE: BaseExpression<string, "govaluate"> = literal("a");
const rightE: BaseExpression<string, "govaluate"> = literal("a");
const eE: BaseExpression<boolean, "govaluate"> = equals(leftE, rightE);

export const FullMigration = WorkflowBuilder.create({
        k8sResourceName: "FullMigration",
        parallelism: 100,
        serviceAccountName: "argo-workflow-executor"
    })
    .addParams(CommonWorkflowParameters)
    .addTemplate("pipelineSourceMigration", t => t
        .addRequiredInput("sourceMigrationConfig", typeToken<z.infer<typeof SOURCE_MIGRATION_CONFIG>>())
        .addSteps(b => b // TODO empty for now
        )
    )
    .addTemplate("main", t => t
        .addOptionalInput("simpleString", s => "hello")
        .addRequiredInput("sourceMigrationConfigs",
            typeToken<z.infer<typeof SOURCE_MIGRATION_CONFIG>[]>(),
            "List of server configurations to direct migrated traffic toward")
        .addRequiredInput("targets", typeToken<z.infer<typeof CLUSTER_CONFIG>[]>(),
            "List of server configurations to direct migrated traffic toward")
        .addOptionalInput("doSecondWhenTest", s => true)
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
            .addExternalStep("init", TargetLatchHelpers, "init", (steps,register) => register({
                prefix: "w",
                etcdUtilsImagePullPolicy: "IF_NOT_PRESENT",
                targets: [],
                configuration: {
                    indices: [],
                    migrations: []
                }
            }))
            .addInternalStep("split", "pipelineSourceMigration", (stepScope,register) => register({
                    sourceMigrationConfig: stepScope.item
                }),
                { loopWith: makeParameterLoop(b.inputs.sourceMigrationConfigs) }
            )
            .addInternalStep("split2", "pipelineSourceMigration", (stepScope,register) => register({
                    sourceMigrationConfig: stepScope.item
                }),
                {
                    loopWith: makeParameterLoop(b.inputs.sourceMigrationConfigs),
                    when: equals(literal("hello"), b.inputs.simpleString)
                }
                //equals(literal("never"), concat(b.inputs.simpleString)) // compile error - as expected!
            )

            .addExternalStep("cleanup", TargetLatchHelpers, "cleanup", (stepScope,register) => register({
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
