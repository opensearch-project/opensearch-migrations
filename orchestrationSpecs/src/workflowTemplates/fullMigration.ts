import { z } from 'zod';
import {CLUSTER_CONFIG, IMAGE_PULL_POLICY, IMAGE_SPECIFIER, SNAPSHOT_MIGRATION_CONFIG} from '@/schemas/userSchemas'
import {CommonWorkflowParameters} from "@/workflowTemplates/commonWorkflowTemplates";
import {WFBuilder} from "@/schemas/workflowSchemas";


export const TargetLatchHelpers = WFBuilder.create("TargetLatchHelpers")
    //.addParams(CommonWorkflowParameters)
        // .addParams({foo: defineParam({ defaultValue: "foo" }),})
    .addTemplate("init", t=> t
        .addRequiredInput("targets", z.array(CLUSTER_CONFIG))
        .addRequiredInput("prefix", z.string())
        .addRequiredInput("etcdUtilsImage", IMAGE_SPECIFIER)
        .addRequiredInput("etcdUtilsImagePullPolicy", IMAGE_PULL_POLICY)
        .addSteps(sb => sb
                // .addSingleStep("a", TargetLatchHelpers.templates, "init", { prefix: "a", targets: [], etcdUtilsImage: "", etcdUtilsImagePullPolicy: "" })
                // .addSingleStep("b", TargetLatchHelpers.templates, "init", { prefix: "b", targets: [], etcdUtilsImage: "", etcdUtilsImagePullPolicy: "" })
                // .addSingleStep("c", TargetLatchHelpers.templates, "init", { prefix: "c", targets: [], etcdUtilsImage: "", etcdUtilsImagePullPolicy: "" })
                // .addStepGroup(gb => gb
                //     .addStep("d1", "next")
                //     //.addStep("first", "next"))
                //     .addStep("d2", gb.getStepTasks().scope.a+"")
                // )
            )
    )
    .addTemplate("cleanup", t => t
        .addRequiredInput("prefix", z.string())
        .addRequiredInput("etcdUtilsImage", IMAGE_SPECIFIER)
        .addRequiredInput("etcdUtilsImagePullPolicy", IMAGE_PULL_POLICY)

    )
    .getFullScope();
// .
// .add(s=> ({}))
// .add(s => ({ "params": CommonWorkflowParameters}))
//
// .addParams(CommonWorkflowParameters)
// .
//.build();
;

export const FullMigration = WFBuilder.create("FullMigration")
    .addParams(CommonWorkflowParameters)
    .addTemplate("main", t=> t
        .addRequiredInput("sourceMigrationConfigs",
            SNAPSHOT_MIGRATION_CONFIG,
            "List of server configurations to direct migrated traffic toward")
        .addRequiredInput("targets", z.array(CLUSTER_CONFIG),
            "List of server configurations to direct migrated traffic toward")
        .optionalInput("imageParams",
            scope =>
                Object.fromEntries(["captureProxy", "trafficReplayer", "reindexFromSnapshot", "migrationConsole", "etcdUtils"]
                        .flatMap((k) => [
                            [`${k}Image`, ""],
                            [`${k}ImagePullPolicy`, ""]
                        ])
                ),
            "OCI image locations and pull policies for required images")
        .addSteps(b => b
           .addSingleStep("name", TargetLatchHelpers.templates, "init", {
            prefix: "foo",
            targets: [],
            etcdUtilsImage: "",
            etcdUtilsImagePullPolicy: ""
           })
        )
//        .addOutput("name", stepsSignatures => ...)
    )
    .addTemplate("main2", t => t
        //.addSteps("cleanup", b=>b)
    )
    .getFullScope();

;
//
// export class FullMigration extends OuterWorkflowTemplateScope {
//     // build() {
//     //     return super.build({
//     //         name: "fullMigration",
//     //         serviceAccountName: "workflow-service-account",
//     //         workflowParameters: CommonWorkflowParameters
//     //     });
//     // }
//
//     static get main() : StepsInterface {
//         return new (class {
//             readonly inputs = {
//                 sourceMigrationConfigs: defineRequiredParam({
//                     type: SNAPSHOT_MIGRATION_CONFIG,
//                     description: "List of server configurations to direct migrated traffic toward",
//                 }),
//                 targets: defineRequiredParam({
//                     type: z.array(CLUSTER_CONFIG),
//                     description: "List of server configurations to direct migrated traffic toward"
//                 }),
//                 imageParams: defineParam({
//                     defaultValue:
//                         Object.fromEntries(["captureProxy", "trafficReplayer", "reindexFromSnapshot", "migrationConsole", "etcdUtils"]
//                             .flatMap((k) => [
//                                 [`${k}Image`, ""],
//                                 [`${k}ImagePullPolicy`, ""]
//                             ])
//                         ),
//                     description: "OCI image locations and pull policies for required images"
//                 })
//             };
//
//             readonly steps = stepsList((() => {
//                 const parentInputs = this.inputs;
//                 return new (class {
//                     init = callTemplate(TargetLatchHelpers, "init", {
//                         prefix: ""
//                     });
//                     mainThing = callTemplate(FullMigration, "singleSourceMigration",
//                         {
//                             // sourceConfig: this.inputs.sourceMigrationConfigs,
//                             // targetCluster: this.inputs.targets,
//                             // Can reference init step outputs:
//                             // initData: this.init.templateRef.value.outputs.abc,
//                             // dummy: "custom-migration-name"
//                             required: "aaaa" + parentInputs.sourceMigrationConfigs // TODO - figure out how to get inside the object
//                         });
//                     cleanup = {
//                         templateRef: getKeyAndValue(TargetLatchHelpers, "cleanup"),
//                         arguments: {
//                             parameters: {
//                                 // Can reference previous step outputs:
//                                 // initData: this.init.templateRef.value.outputs.abc,
//                                 // migrationId: this.mainThing.templateRef.value.outputs.migrationId
//                                 required: "aaaa" + parentInputs.sourceMigrationConfigs // TODO - figure out how to get inside the object
//                             }
//                         }
//                     } as WorkflowTask<any, any>;
//                 })();
//             })());
//
//             readonly outputs = {
//                 // foo: this.inputs.sourceMigrationConfigs,
//                 // bar: this.steps.init.templateRef.value.outputs.a,
//                 // migrationResult: this.steps.mainThing.templateRef.value.outputs.migrationId,
//                 // cleanupStatus: this.steps.cleanup.templateRef.value.outputs.status
//             };
//         })();
//     }
//
//     static get singleSourceMigration() {
//         //const s = this.main.steps.mainThing.arguments?.parameters;
//         return new (class {
//             inputs = {
//                 // sourceConfig: defineRequiredParam({
//                 //     type: SNAPSHOT_MIGRATION_CONFIG,
//                 //     description: "Source migration configuration"
//                 // }),
//                 // targetCluster: defineRequiredParam({
//                 //     type: CLUSTER_CONFIG,
//                 //     description: "Target cluster configuration"
//                 // }),
//                 dummy: defineParam({
//                     defaultValue: "default-migration",
//                     description: "Name for this migration"
//                 }),
//                 required: defineRequiredParam({type: z.string()})
//             };
//             outputs = {
//                 migrationId: { type: z.string(), description: "Generated migration ID" },
//                 status: { type: z.string(), description: "Migration status" }
//             };
//         })();
//     }
// }
