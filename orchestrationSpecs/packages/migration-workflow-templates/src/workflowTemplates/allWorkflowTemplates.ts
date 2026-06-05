import {CaptureProxy} from "./captureProxy";
import {CreateOrGetSnapshot} from "./createOrGetSnapshot";
import {CreateOrGetSnapshotGcs} from "./createOrGetSnapshotGcs";
import {CreateSnapshot} from "./createSnapshot";
import {CreateSnapshotGcs} from "./createSnapshotGcs";
import {DocumentBulkLoad} from "./documentBulkLoad";
import {DocumentBulkLoadGcs} from "./documentBulkLoadGcs";
import {FullMigration} from "./fullMigration";
import {TestMigrationWithWorkflowCli} from "./testMigrationWithWorkflowCli";
import {MetadataMigration} from "./metadataMigration";
import {MetadataMigrationGcs} from "./metadataMigrationGcs";
import {MigrationConsole} from "./migrationConsole";
import {Replayer} from "./replayer";
import {ResourceManagement} from "./resourceManagement";
import {RfsCoordinatorCluster} from "./rfsCoordinatorCluster";
import {SetupKafka} from "./setupKafka";
import {SetupCapture} from "./setupCapture";

export const AllWorkflowTemplates = [
    CaptureProxy,
    CreateOrGetSnapshot,
    CreateOrGetSnapshotGcs,
    CreateSnapshot,
    CreateSnapshotGcs,
    DocumentBulkLoad,
    DocumentBulkLoadGcs,
    FullMigration,
    MetadataMigration,
    MetadataMigrationGcs,
    MigrationConsole,
    Replayer,
    ResourceManagement,
    RfsCoordinatorCluster,
    SetupCapture,
    SetupKafka,
    TestMigrationWithWorkflowCli,
];
