import {renderWorkflowTemplate} from "@/renderers/argoConfigRenderer";
import {paramsToCallerSchema} from "@/schemas/parameterSchemas";
import {z} from "zod/index";
import {FullMigration} from "@/workflowTemplates/fullMigration";
//
// const finalConfig = renderWorkflowTemplate(fullMigrationWorkflowTemplate);
// console.log("OUTPUT: ");
// console.log(JSON.stringify(finalConfig, null, 2));
//
`// const FullMigrationSignature = paramsToCallerSchema(fullMigrationWorkflowTemplate.templates.main.inputs);
// type FullMigrationMainInputs = z.infer<typeof FullMigrationSignature>;
//`
// const t : FullMigrationMainInputs = {
//     targets: [],
//     sourceMigrationConfigs: {
//         indices: [],
//         migrations: []
//     }
//     //imageParams: {}
// };


console.log("full migration: " + FullMigration);