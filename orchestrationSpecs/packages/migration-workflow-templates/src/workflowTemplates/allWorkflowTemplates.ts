import {CaptureProxy} from "./captureProxy";
import {CaptureReplay} from "./captureReplay";
import {CreateOrGetSnapshot} from "./createOrGetSnapshot";
import {CreateSnapshot} from "./createSnapshot";
import {DocumentBulkLoad} from "./documentBulkLoad";
import {FullMigration} from "./fullMigration";
import {MetadataMigration} from "./metadataMigration";
import {MigrationConsole} from "./migrationConsole";
import {Replayer} from "./replayer";
import {SetupKafka} from "./setupKafka";
import {ConfigManagementHelpers} from "./configManagementHelpers";

export const AllWorkflowTemplates = [
    CaptureProxy,
    CaptureReplay,
    CreateOrGetSnapshot,
    CreateSnapshot,
    DocumentBulkLoad,
    FullMigration,
    MetadataMigration,
    MigrationConsole,
    Replayer,
    SetupKafka,
    ConfigManagementHelpers,
];
