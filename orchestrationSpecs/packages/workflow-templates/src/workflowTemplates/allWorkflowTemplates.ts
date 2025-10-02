import {CaptureProxy} from "@/workflowTemplates/captureProxy";
import {CaptureReplay} from "@/workflowTemplates/captureReplay";
import {CreateOrGetSnapshot} from "@/workflowTemplates/createOrGetSnapshot";
import {CreateSnapshot} from "@/workflowTemplates/createSnapshot";
import {DocumentBulkLoad} from "@/workflowTemplates/documentBulkLoad";
import {FullMigration} from "@/workflowTemplates/fullMigration";
import {LocalstackHelper} from "@/workflowTemplates/localstackHelper";
import {MetadataMigration} from "@/workflowTemplates/metadataMigration";
import {MigrationConsole} from "@/workflowTemplates/migrationConsole";
import {Replayer} from "@/workflowTemplates/replayer";
import {SetupKafka} from "@/workflowTemplates/setupKafka";
import {ConfigManagementHelpers} from "@/workflowTemplates/configManagementHelpers";

export const AllWorkflowTemplates = [
    CaptureProxy,
    CaptureReplay,
    CreateOrGetSnapshot,
    CreateSnapshot,
    DocumentBulkLoad,
    FullMigration,
    LocalstackHelper,
    MetadataMigration,
    MigrationConsole,
    Replayer,
    SetupKafka,
    ConfigManagementHelpers,
];
