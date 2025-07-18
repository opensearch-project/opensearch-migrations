import { z } from 'zod';
import {defineParam, defineRequiredParam, InputParametersRecord} from '@/schemas/parameterSchemas'
import {
    OuterWorkflowTemplateScope,
    WorkflowTask,
    ContainerTemplateDef, callTemplate, templateRef, StepsTemplate, TypedStepsTemplate, callTemplateWithInputs
} from '@/schemas/workflowSchemas'
import {CLUSTER_CONFIG, SNAPSHOT_MIGRATION_CONFIG} from '@/schemas/userSchemas'
import {CommonWorkflowParameters} from "@/workflowTemplates/commonWorkflowTemplates";
import {getKeyAndValue} from "@/utils";
import {EverythingToRecordScope, getAlwaysMatchPredicate, sameMatchingItems} from "@/scopeHelpers";

export class TargetLatchHelpers extends OuterWorkflowTemplateScope<typeof CommonWorkflowParameters> {
    static get init(): ContainerTemplateDef<InputParametersRecord> {
        return {
            container: {args: [], image: ""},
            inputs: {},
            outputs: {
                abc: { type: z.string() }
            }
        };
    }
    static get cleanup(): ContainerTemplateDef<InputParametersRecord> {
        return {
            container: {args: [], image: ""},
            inputs: {},
            outputs: {}};
    }
}

export class FullMigration extends OuterWorkflowTemplateScope<typeof CommonWorkflowParameters> {
    // build() {
    //     return super.build({
    //         name: "fullMigration",
    //         serviceAccountName: "workflow-service-account",
    //         workflowParameters: CommonWorkflowParameters
    //     });
    // }

    static get main() {
        return new (class extends TypedStepsTemplate<any, any> {
            readonly inputs = {
                sourceMigrationConfigs: defineRequiredParam({
                    type: SNAPSHOT_MIGRATION_CONFIG,
                    description: "List of server configurations to direct migrated traffic toward",
                }),
                targets: defineRequiredParam({
                    type: z.array(CLUSTER_CONFIG),
                    description: "List of server configurations to direct migrated traffic toward"
                }),
                test: defineParam(({defaultValue: "hello"})),
                foo: "bar",
                //    s3Params: defineParam({type: }),

                imageParams: defineParam({
                    defaultValue:
                        Object.fromEntries(["captureProxy", "trafficReplayer", "reindexFromSnapshot", "migrationConsole", "etcdUtils"]
                            .flatMap((k) => [
                                [`${k}Image`, ""],
                                [`${k}ImagePullPolicy`, ""]
                            ])
                        ),
                    description: "OCI image locations and pull policies for required images"
                })
            };
            
            readonly steps = {
                init: callTemplate(TargetLatchHelpers, "init", {}),
                mainThing: callTemplateWithInputs(
                    templateRef(FullMigration, "singleSourceMigration"),
                    {
                        // sourceConfig: this.inputs.sourceMigrationConfigs, // Test: commenting this out should cause compile error
                        // targetCluster: this.inputs.targets,
                        // migrationName and dryRun are optional since they have defaults
                        dummy: "custom-migration-name"
                    }
                ),
                cleanup: {
                    templateRef: getKeyAndValue(TargetLatchHelpers, "cleanup")
                } as WorkflowTask<any, any>
            };
            
            readonly outputs = {
                foo: this.inputs.sourceMigrationConfigs,
                bar: this.steps.init.templateRef.value.outputs.abc
            };
        })();
    }

    static get singleSourceMigration() {
        return new (class {
            inputs = {
                // sourceConfig: defineRequiredParam({
                //     type: SNAPSHOT_MIGRATION_CONFIG,
                //     description: "Source migration configuration"
                // }),
                // targetCluster: defineRequiredParam({
                //     type: CLUSTER_CONFIG,
                //     description: "Target cluster configuration"
                // }),
                dummy: defineParam({
                    defaultValue: "default-migration",
                    description: "Name for this migration"
                })
            };
            outputs = {
                migrationId: { type: z.string(), description: "Generated migration ID" },
                status: { type: z.string(), description: "Migration status" }
            };
        })();
    }
}

// export const fullMigrationWorkflowTemplate = defineOuterWorkflowTemplate({
//     name: "fullMigration",
//     serviceAccountName: "serviceaccount",
//     workflowParams: CommonWorkflowParameters,
//     templates: {
//         main: {
//             inputs: {
//                 sourceMigrationConfigs: defineRequiredParam({ type: SNAPSHOT_MIGRATION_CONFIG,
//                     description: "List of server configurations to direct migrated traffic toward",
//                 }),
//                 targets: defineRequiredParam({ type: z.array(CLUSTER_CONFIG),
//                     description: "List of server configurations to direct migrated traffic toward" } ),
//                 //    s3Params: defineParam({type: }),
//
//                 imageParams: defineParam({
//                     defaultValue:
//                         Object.fromEntries([ "captureProxy", "trafficReplayer", "reindexFromSnapshot", "migrationConsole", "etcdUtils" ]
//                             .flatMap((k) => [
//                                 [`${k}Image`, ""],
//                                 [`${k}ImagePullPolicy`, ""]
//                             ])
//                         ),
//                     description: "OCI image locations and pull policies for required images"
//                 })
//             }, // as const satisfies Record<string, InputParamDef<any>>;
//             steps: [{
//                 init: {
//                     templateRef: {}
//                 },
//                 singleSourceMigration: {
//                     template: {
//                         // .. .../singleSourceMigration
//                     }
//                 },
//                 cleanup: {
//                     templateRef: {},
//                     arguments: {
//                         parameters: {
//                             // steps.init.outputs.parameters.prefix
//                         }
//                     }
//                 }
//             }]
//         },
//         singleSourceMigration: {
//             inputs: {
//                 //fullMigrationWorkflowTemplate.
//             }
//         }
//     }
// });
//
