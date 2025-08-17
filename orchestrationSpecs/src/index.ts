import {FullMigration} from "@/workflowTemplates/fullMigration";
import { FieldCount } from "@/utils";
import {renderWorkflowTemplate} from "@/renderers/argoResourceRenderer";
import {TargetLatchHelpers} from "@/workflowTemplates/targetLatchHelpers";

// console.log("TargetLatchHelper: " + JSON.stringify(TargetLatchHelpers, null, 2));
// console.log("FullMigration: " + JSON.stringify(FullMigration, null, 2));
// console.log("\n\n\n");

console.log("OUTPUT: ");
const finalConfigTlh = renderWorkflowTemplate(TargetLatchHelpers);
console.log(JSON.stringify(finalConfigTlh, null, 2));

//const finalConfigFm = renderWorkflowTemplate(FullMigration);
//console.log(JSON.stringify(finalConfigFm, null, 2));

