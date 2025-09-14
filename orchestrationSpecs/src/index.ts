import {renderWorkflowTemplate} from "@/renderers/argoResourceRenderer";
import {TargetLatchHelpers} from "@/workflowTemplates/targetLatchHelpers";
import {FullMigration} from "@/workflowTemplates/fullMigration";
import {MigrationConsole} from "@/workflowTemplates/migrationConsole";
import {CreateOrGetSnapshot} from "@/workflowTemplates/createOrGetSnapshot";
import {DocumentBulkLoad} from "@/workflowTemplates/documentBulkLoad";
import {Replayer} from "@/workflowTemplates/replayer";
import {SetupKafka} from "@/workflowTemplates/setupKafka";
import {CreateSnapshot} from "@/workflowTemplates/createSnapshot";


console.log("OUTPUT: ");
const templates = [
    // [CaptureReplay]
    [CreateOrGetSnapshot],
    [CreateSnapshot],
    [DocumentBulkLoad],
    [FullMigration],
    [MigrationConsole],
    [Replayer],
    [SetupKafka],
    [TargetLatchHelpers],
];

for (const [wf] of templates) {
    const finalConfig = renderWorkflowTemplate(wf);
    console.log(JSON.stringify(finalConfig, null, 2));
    console.log("\n\n========\n\n")
}
