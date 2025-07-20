import {renderWorkflowTemplate} from "@/renderers/argoConfigRenderer";
import {paramsToCallerSchema} from "@/schemas/parameterSchemas";
import {z} from "zod/index";
import {FM, FullMigration, TLH} from "@/workflowTemplates/fullMigration";
//
// const finalConfig = renderWorkflowTemplate(new FullMigration(), "fullMigration");
// console.log("OUTPUT: ");
// console.log(JSON.stringify(finalConfig, null, 2));
//
// const FullMigrationSignature = paramsToCallerSchema(fullMigrationWorkflowTemplate.templates.main.inputs);
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


console.log("TargetLatchHelper: " + JSON.stringify(TLH, null, 2));
console.log("FullMigration: " + FM);
