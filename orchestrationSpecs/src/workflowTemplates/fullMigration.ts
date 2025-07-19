import { z } from 'zod';
import {defineParam, defineRequiredParam, InputParametersRecord} from '@/schemas/parameterSchemas'
import {
    OuterWorkflowTemplateScope,
    WorkflowTask,
    ContainerTemplateDef,
    callTemplate, StepsInterface, stepsList
} from '@/schemas/workflowSchemas'
import {CLUSTER_CONFIG, SNAPSHOT_MIGRATION_CONFIG} from '@/schemas/userSchemas'
import {CommonWorkflowParameters} from "@/workflowTemplates/commonWorkflowTemplates";
import {getKeyAndValue} from "@/utils";
import {EverythingToRecordScope, getAlwaysMatchPredicate, sameMatchingItems} from "@/scopeHelpers";

export class TargetLatchHelpers extends OuterWorkflowTemplateScope {
    static get init() {
        return new (class {
            inputs = {
                prefix: defineRequiredParam({type: z.string()}),
                other: defineParam({defaultValue: 8})
            };
            outputs = {
                prefix: { type: z.string() },
                processorsPerTarget: { type: z.number() }
            };
            container = {

            }
        })();
    }
    static get cleanup() {
        return new (class {
            inputs = {
                prefix: defineRequiredParam({type: z.string()})
            };
        })();
    }
}

export class FullMigration extends OuterWorkflowTemplateScope {
    // build() {
    //     return super.build({
    //         name: "fullMigration",
    //         serviceAccountName: "workflow-service-account",
    //         workflowParameters: CommonWorkflowParameters
    //     });
    // }

    static get main() : StepsInterface {
        return new (class {
            readonly inputs = {
                sourceMigrationConfigs: defineRequiredParam({
                    type: SNAPSHOT_MIGRATION_CONFIG,
                    description: "List of server configurations to direct migrated traffic toward",
                }),
                targets: defineRequiredParam({
                    type: z.array(CLUSTER_CONFIG),
                    description: "List of server configurations to direct migrated traffic toward"
                }),
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

            readonly steps = stepsList((() => {
                const parentInputs = this.inputs;
                return new (class {
                    init = callTemplate(TargetLatchHelpers, "init", {
                        prefix: ""
                    });
                    mainThing = callTemplate(FullMigration, "singleSourceMigration",
                        {
                            // sourceConfig: this.inputs.sourceMigrationConfigs,
                            // targetCluster: this.inputs.targets,
                            // Can reference init step outputs:
                            // initData: this.init.templateRef.value.outputs.abc,
                            // dummy: "custom-migration-name"
                            required: "aaaa" + parentInputs.sourceMigrationConfigs // TODO - figure out how to get inside the object
                        });
                    cleanup = {
                        templateRef: getKeyAndValue(TargetLatchHelpers, "cleanup"),
                        arguments: {
                            parameters: {
                                // Can reference previous step outputs:
                                // initData: this.init.templateRef.value.outputs.abc,
                                // migrationId: this.mainThing.templateRef.value.outputs.migrationId
                                required: "aaaa" + parentInputs.sourceMigrationConfigs // TODO - figure out how to get inside the object
                            }
                        }
                    } as WorkflowTask<any, any>;
                })();
            })());
            
            readonly outputs = {
                // foo: this.inputs.sourceMigrationConfigs,
                // bar: this.steps.init.templateRef.value.outputs.a,
                // migrationResult: this.steps.mainThing.templateRef.value.outputs.migrationId,
                // cleanupStatus: this.steps.cleanup.templateRef.value.outputs.status
            };
        })();
    }

    static get singleSourceMigration() {
        //const s = this.main.steps.mainThing.arguments?.parameters;
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
                }),
                required: defineRequiredParam({type: z.string()})
            };
            outputs = {
                migrationId: { type: z.string(), description: "Generated migration ID" },
                status: { type: z.string(), description: "Migration status" }
            };
        })();
    }
}
