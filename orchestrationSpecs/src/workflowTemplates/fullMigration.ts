import { z } from 'zod';
import {CLUSTER_CONFIG, IMAGE_PULL_POLICY, IMAGE_SPECIFIER, SNAPSHOT_MIGRATION_CONFIG} from '@/schemas/userSchemas'
import {CommonWorkflowParameters} from "@/workflowTemplates/commonWorkflowTemplates";
import {TemplateBuilder, WFBuilder} from "@/schemas/workflowSchemas";
import {Scope, ExtendScope} from "@/schemas/workflowTypes";
import {defineParam, InputParamDef} from "@/schemas/parameterSchemas";
import {TypescriptError} from "@/utils";


// Define the common fields with their schemas in one place
const TARGET_LATCH_FIELD_SPECS = {
    prefix: z.string(),
    etcdUtilsImage: IMAGE_SPECIFIER,
    etcdUtilsImagePullPolicy: IMAGE_PULL_POLICY,
    firstThing: ""
} as const;

type TargetLatchFieldNames = keyof typeof TARGET_LATCH_FIELD_SPECS;

// Generate the type from the specs
type TargetLatchFields = {
    [K in TargetLatchFieldNames]: InputParamDef<any, true>;
};

function addCommonTargetLatchFields<
    TB extends TemplateBuilder<any, any, any, any>
>(
    templateBuilder: TB,
    isRequired: TB extends TemplateBuilder<any, any, infer CurrentInputs, any>
        ? keyof CurrentInputs & TargetLatchFieldNames extends never
            ? boolean
            : TypescriptError<`Cannot add common fields: '${keyof CurrentInputs & TargetLatchFieldNames & string}' already exists`>
        : never
): TB extends TemplateBuilder<infer Context, infer Body, infer Inputs, infer Outputs>
    ? TemplateBuilder<Context, Body, ExtendScope<Inputs, TargetLatchFields>, Outputs>
    : never {

    // Iterate over the field specs and add each one
    let result = templateBuilder as any;
    for (const [fieldName, schema] of Object.entries(TARGET_LATCH_FIELD_SPECS)) {
        result = result.addRequiredInput(fieldName, schema);
    }
    return result;
}

export const TargetLatchHelpers = WFBuilder.create("TargetLatchHelpers")
    .addParams(CommonWorkflowParameters)
        // .addParams({foo: defineParam({ defaultValue: "foo" }),})
    .addTemplate("init", ot=> {
            const o = ot
                .addRequiredInput("targets", z.array(CLUSTER_CONFIG))
                //.addOptionalInput("firstThing2", s => "")
                .addOptionalInput("firstThing2", s => "")
                .addOptionalInput("second", s=>"")
                //.addOptionalInput("third", s => s.currentScope.firstThing);
            return addCommonTargetLatchFields(o, true)
                .addOptionalInput("fourth", s=>s.currentScope.firstThing)
                .addOptionalInput("fifth", s=>s.currentScope.prefix);
        }
        // .addContainer(...)
        // .addOutput(...)
    )
    // .addTemplate("decrementLatch", t => t.
    //     addRequiredInput(""))
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
               firstThing: "",
                etcdUtilsImage: "",
                etcdUtilsImagePullPolicy: ""
           })
            .addStep("cleanup", TargetLatchHelpers, "cleanup", {
                prefix: undefined,
                etcdUtilsImage: undefined,
                etcdUtilsImagePullPolicy: undefined
            })
        )
//        .addOutput("name", stepsSignatures => ...)
    )
    .addTemplate("cleanup", t => t
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
