import {renderWorkflowTemplate} from "@/renderers/argoResourceRenderer";
import {TargetLatchHelpers} from "@/workflowTemplates/targetLatchHelpers";
import {FullMigration} from "@/workflowTemplates/fullMigration";
import {MigrationConsole} from "@/workflowTemplates/migrationConsole";
import {CreateOrGetSnapshot} from "@/workflowTemplates/createOrGetSnapshot";
import {DocumentBulkLoad} from "@/workflowTemplates/documentBulkLoad";
import {Replayer} from "@/workflowTemplates/replayer";
import {SetupKafka} from "@/workflowTemplates/setupKafka";
import {CreateSnapshot} from "@/workflowTemplates/createSnapshot";
import {LocalstackHelper} from "@/workflowTemplates/localstackHelper";
import {MetadataMigration} from "@/workflowTemplates/metadataMigration";
import {CaptureReplay} from "@/workflowTemplates/captureReplay";
import {CaptureProxy} from "@/workflowTemplates/proxy";


console.log("OUTPUT: ");
const templates = [
    CaptureReplay,
    CaptureProxy,
    CreateOrGetSnapshot,
    CreateSnapshot,
    DocumentBulkLoad,
    FullMigration,
    LocalstackHelper,
    MetadataMigration,
    MigrationConsole,
    Replayer,
    SetupKafka,
    TargetLatchHelpers,
];

for (const wf of templates) {
    const finalConfig = renderWorkflowTemplate(wf);
    console.log(JSON.stringify(finalConfig, null, 2));
    console.log("\n\n========\n\n")
}
