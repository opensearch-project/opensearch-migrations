import {renderWorkflowTemplate} from "@/renderers/argoConfigRenderer";
import {FullMigration, TargetLatchHelpers} from "@/workflowTemplates/fullMigration";

console.log("TargetLatchHelper: " + JSON.stringify(TargetLatchHelpers, null, 2));
console.log("FullMigration: " + JSON.stringify(FullMigration, null, 2));
console.log("\n\n\n");

const finalConfigTlh = renderWorkflowTemplate(TargetLatchHelpers);
const finalConfigFm = renderWorkflowTemplate(FullMigration);
console.log("OUTPUT: ");
console.log(JSON.stringify(finalConfigTlh, null, 2));
console.log(JSON.stringify(finalConfigFm, null, 2));
