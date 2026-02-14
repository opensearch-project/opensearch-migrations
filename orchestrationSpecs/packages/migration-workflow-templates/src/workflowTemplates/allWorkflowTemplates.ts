import {CaptureProxy} from "./captureProxy";
import {CreateOrGetSnapshot} from "./createOrGetSnapshot";
import {CreateSnapshot} from "./createSnapshot";
import {DocumentBulkLoad} from "./documentBulkLoad";
import {FullMigration} from "./fullMigration";
import {TestMigrationWithWorkflowCli} from "./testMigrationWithWorkflowCli";
import {MetadataMigration} from "./metadataMigration";
import {MigrationConsole} from "./migrationConsole";
import {Replayer} from "./replayer";
import {ResourceManagement} from "./resourceManagement";
import {SetupKafka} from "./setupKafka";
import {SetupCapture} from "./setupCapture";

export const AllWorkflowTemplates = [
    CaptureProxy,
    CreateOrGetSnapshot,
    CreateSnapshot,
    DocumentBulkLoad,
    FullMigration,
    MetadataMigration,
    MigrationConsole,
    Replayer,
    ResourceManagement,
    SetupCapture,
    SetupKafka,
    TestMigrationWithWorkflowCli,
];
