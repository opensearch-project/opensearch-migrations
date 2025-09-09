import {renderWorkflowTemplate} from "@/renderers/argoResourceRenderer";
import {SetupKafka} from "@/workflowTemplates/setupKafka";
import {TargetLatchHelpers} from "@/workflowTemplates/targetLatchHelpers";
import {FullMigration} from "@/workflowTemplates/fullMigration";
import {MigrationConsole} from "@/workflowTemplates/migrationConsole";
import {CreateOrGetSnapshot} from "@/workflowTemplates/createOrGetSnapshot";
import {DocumentBulkLoad} from "@/workflowTemplates/documentBulkLoad";


console.log("OUTPUT: ");
const templates = [
    // [CaptureReplay]
    // [CreateOrGetSnapshot],
    // [DocumentBulkLoad],
    // [FullMigration],
    [MigrationConsole],
    // [SetupKafka],
    // [TargetLatchHelpers],
];

for (const [wf] of templates) {
    const finalConfig = renderWorkflowTemplate(wf);
    console.log(JSON.stringify(finalConfig, null, 2));
    console.log("\n\n========\n\n")
}
