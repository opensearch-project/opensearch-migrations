import {CaptureProxy} from "./captureProxy";
import {CaptureReplay} from "./captureReplay";
import {CreateOrGetSnapshot} from "./createOrGetSnapshot";
import {CreateSnapshot} from "./createSnapshot";
import {DocumentBulkLoad} from "./documentBulkLoad";
import {FullMigration} from "./fullMigration";
import {FullMigrationWithCli} from "./fullMigrationWithCli";
import {MetadataMigration} from "./metadataMigration";
import {MigrationConsole} from "./migrationConsole";
import {Replayer} from "./replayer";
import {SetupKafka} from "./setupKafka";
import {ConfigManagementHelpers} from "./configManagementHelpers";
<<<<<<< HEAD
import {WorkflowCommandOrchestrator} from "./workflowCommandOrchestrator";
=======
import {WorkflowCommandOrchestrator, FullMigrationWithCli} from "./workflowCommandOrchestrator";
>>>>>>> origin/jenkins-k8s-local-test

export const AllWorkflowTemplates = [
    CaptureProxy,
    CaptureReplay,
    CreateOrGetSnapshot,
    CreateSnapshot,
    DocumentBulkLoad,
    FullMigration,
    FullMigrationWithCli,
    MetadataMigration,
    MigrationConsole,
    Replayer,
    SetupKafka,
    ConfigManagementHelpers,
    WorkflowCommandOrchestrator,
];
