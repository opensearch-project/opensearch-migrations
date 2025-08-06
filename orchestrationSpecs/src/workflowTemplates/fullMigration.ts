import {z} from 'zod';
import {CLUSTER_CONFIG, IMAGE_PULL_POLICY, IMAGE_SPECIFIER, SNAPSHOT_MIGRATION_CONFIG} from '@/schemas/userSchemas'
import {CommonWorkflowParameters} from "@/workflowTemplates/commonWorkflowTemplates";
import {TemplateBuilder, WFBuilder} from "@/schemas/workflowSchemas";
import {defineParam, paramsToCallerSchema} from "@/schemas/parameterSchemas";
import {Scope} from "@/schemas/workflowTypes";

const TARGET_LATCH_FIELD_SPECS = {
    prefix: z.string(),
    etcdUtilsImage: IMAGE_SPECIFIER,
    etcdUtilsImagePullPolicy: IMAGE_PULL_POLICY
} as const;


// just to show off addInputs, which can only work when InputParamsScope is {}
const addCommonTargetLatchInputs = <C extends Scope>(tb: TemplateBuilder<C, {}, {}, {}>) =>
    tb.addMultipleRequiredInputs(TARGET_LATCH_FIELD_SPECS, true);

export const TargetLatchHelpers = WFBuilder.create("TargetLatchHelpers")
    .addParams(CommonWorkflowParameters)
    .addTemplate("init", t=> t
        .addInputs(addCommonTargetLatchInputs)
        .addRequiredInput("targets", z.array(CLUSTER_CONFIG))
        .addRequiredInput("configuration", SNAPSHOT_MIGRATION_CONFIG)
        .addContainer(t=>t)
    )
    .addTemplate("decrementLatch", t => t
        .addInputs(addCommonTargetLatchInputs)
        .addRequiredInput("target", CLUSTER_CONFIG)
        .addRequiredInput("processor", z.string())
    )
    .addTemplate("cleanup", t => t
        .addInputs(addCommonTargetLatchInputs)
    )
    .getFullScope();
;

export const FullMigration = WFBuilder.create("FullMigration")
    //.addParams(CommonWorkflowParameters)
    .addParams({a: defineParam({ defaultValue: "http://etcd.ma.svc.cluster.local:2379" }),
        b: defineParam({ defaultValue: "http://etcd.ma.svc.cluster.local:2379" })})
    .addParams({d: defineParam({ defaultValue: "http://etcd.ma.svc.cluster.local:2379" }),
        c: defineParam({ defaultValue: "http://etcd.ma.svc.cluster.local:2379" })})
    .addTemplate("main", t=> t
        .addRequiredInput("sourceMigrationConfigs",
            SNAPSHOT_MIGRATION_CONFIG,
            "List of server configurations to direct migrated traffic toward")
        .addRequiredInput("targets", z.array(CLUSTER_CONFIG),
            "List of server configurations to direct migrated traffic toward")
        .addOptionalInput("imageParams",
            scope =>
                Object.fromEntries(["captureProxy", "trafficReplayer", "reindexFromSnapshot", "migrationConsole", "etcdUtils"]
                        .flatMap((k) => [
                            [`${k}Image`, ""],
                            [`${k}ImagePullPolicy`, ""+scope.context.workflowParameters.a]
                        ])
                ),
            "OCI image locations and pull policies for required images")
        .addSteps(b => b
            .addStep("main", TargetLatchHelpers, "init", {
                prefix: "foo",
                targets: [],
                etcdUtilsImage: "",
                etcdUtilsImagePullPolicy: "",
                configuration: {
                    indices: [],
                    migrations: []
                }
            })
            .addStep("cleanup", TargetLatchHelpers, "cleanup", {
                prefix: undefined,
                etcdUtilsImage: undefined,
                etcdUtilsImagePullPolicy: undefined
            })
        )
    )
    .addTemplate("cleanup", t => t

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
