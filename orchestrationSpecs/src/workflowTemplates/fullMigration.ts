import { z } from 'zod';
import {
    InputParamDef,
    defineParam,
    defineRequiredParam,
    paramsToCallerSchema
} from '../schemas/parameterSchemas'
import {
    defineOuterWorkflowTemplate, TemplateScope, defineStepsTemplate,
    TemplateDef
} from '../schemas/workflowSchemas'
import {CLUSTER_CONFIG, SNAPSHOT_MIGRATION_CONFIG} from '../schemas/userSchemas'
import {CommonWorkflowParameters} from "@/workflowTemplates/commonWorkflowTemplates";

export class FullMigration extends TemplateScope<typeof CommonWorkflowParameters> {
    main = defineStepsTemplate();
    build() {
        return super.build({
            name: "fullMigration",
            serviceAccountName: "workflow-service-account",
            workflowParameters: CommonWorkflowParameters
        });
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
