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
import {RfsCoordinatorCluster} from "./rfsCoordinatorCluster";
import {S3TrafficLoader} from "./s3TrafficLoader";
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
    RfsCoordinatorCluster,
    S3TrafficLoader,
    SetupCapture,
    SetupKafka,
    TestMigrationWithWorkflowCli,
];
