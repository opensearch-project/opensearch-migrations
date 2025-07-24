import {renderWorkflowTemplate} from "@/renderers/argoConfigRenderer";
import {FullMigration, TargetLatchHelpers} from "@/workflowTemplates/fullMigration";

console.log("TargetLatchHelper: " + JSON.stringify(TargetLatchHelpers, null, 2));
console.log("\n\n\n");

const finalConfig = renderWorkflowTemplate(TargetLatchHelpers);
console.log("OUTPUT: ");
console.log(JSON.stringify(finalConfig, null, 2));
