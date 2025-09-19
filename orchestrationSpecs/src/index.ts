import * as k8s from '@kubernetes/client-node';
import {renderWorkflowTemplate} from "@/renderers/argoResourceRenderer";
import {CaptureReplay} from "@/workflowTemplates/captureReplay";
import {CaptureProxy} from "@/workflowTemplates/proxy";
import {CreateOrGetSnapshot} from "@/workflowTemplates/createOrGetSnapshot";
import {CreateSnapshot} from "@/workflowTemplates/createSnapshot";
import {DocumentBulkLoad} from "@/workflowTemplates/documentBulkLoad";
import {FullMigration} from "@/workflowTemplates/fullMigration";
import {LocalstackHelper} from "@/workflowTemplates/localstackHelper";
import {MetadataMigration} from "@/workflowTemplates/metadataMigration";
import {MigrationConsole} from "@/workflowTemplates/migrationConsole";
import {Replayer} from "@/workflowTemplates/replayer";
import {SetupKafka} from "@/workflowTemplates/setupKafka";
import {TargetLatchHelpers} from "@/workflowTemplates/targetLatchHelpers";

// Parse command line arguments and environment variables
function getNamespace(): string {
    // CLI argument takes precedence over environment variable
    const args = process.argv.slice(2);
    const namespaceArgIndex = args.indexOf('--namespace') || args.indexOf('-n');

    if (namespaceArgIndex !== -1 && args[namespaceArgIndex + 1]) {
        return args[namespaceArgIndex + 1];
    }

    // Fallback to environment variable
    if (process.env.NAMESPACE) {
        return process.env.NAMESPACE;
    }

    // Default namespace
    return 'default';
}

function getDryRun(): boolean {
    const args = process.argv.slice(2);
    return args.includes('--dry-run') || process.env.DRY_RUN === 'true';
}

const targetNamespace = getNamespace();
const dryRun = getDryRun();

console.log(`Target namespace: ${targetNamespace}`);
console.log(`Dry run: ${dryRun}`);
console.log("OUTPUT: ");

// const templates = [
//     CaptureReplay,
//     CaptureProxy,
//     CreateOrGetSnapshot,
//     CreateSnapshot,
//     DocumentBulkLoad,
//     FullMigration,
//     LocalstackHelper,
//     MetadataMigration,
//     MigrationConsole,
//     Replayer,
//     SetupKafka,
//     TargetLatchHelpers,
// ];

const templates = [
    TargetLatchHelpers,
    // CaptureReplay,
    // CaptureProxy,
    // CreateOrGetSnapshot,
    // CreateSnapshot,
    // DocumentBulkLoad,
    // FullMigration,
    // LocalstackHelper,
    // MetadataMigration,
    // MigrationConsole,
    // Replayer,
    // SetupKafka,
];

// Initialize Kubernetes client
const kc = new k8s.KubeConfig();
kc.loadFromDefault(); // This loads from ~/.kube/config or in-cluster config

// Create API client for custom resources (Argo Workflows)
const customObjectsApi = kc.makeApiClient(k8s.CustomObjectsApi);

async function applyArgoWorkflowTemplate(workflowConfig: any, workflowName: string) {
    try {
        // Override namespace in metadata
        if (!workflowConfig.metadata) {
            workflowConfig.metadata = {};
        }
        workflowConfig.metadata.namespace = targetNamespace;

        console.log(`Applying Argo WorkflowTemplate: ${workflowName} to namespace: ${targetNamespace}`);

        if (dryRun) {
            console.log(`🔍 DRY RUN: Would apply WorkflowTemplate: ${workflowName}`);
            return;
        }

        await customObjectsApi.createNamespacedCustomObject({
            group: 'argoproj.io',
            version: 'v1alpha1',
            namespace: targetNamespace,
            plural: 'workflowtemplates',
            body: workflowConfig
        });

        console.log(`Successfully applied WorkflowTemplate: ${workflowName}`);

    } catch (error: any) {
        if (error.response?.statusCode === 409) {
            console.log(`WorkflowTemplate ${workflowName} already exists, skipping...`);
        } else {
            console.error(`Failed to apply WorkflowTemplate ${workflowName}:`, error.message);
            throw error;
        }
    }
}

async function deployAllWorkflows() {
    console.log("Deploying Argo WorkflowTemplates to Kubernetes...\n");

    for (const wf of templates) {
        const finalConfig = renderWorkflowTemplate(wf);

        // Log the config for debugging
        console.log(JSON.stringify(finalConfig, null, 2));
        console.log("\n========\n");

        const wfName = wf.metadata.k8sMetadata.name;
        // Apply to Kubernetes
        try {
            await applyArgoWorkflowTemplate(finalConfig, wfName || finalConfig.metadata?.name || 'unknown');
        } catch (error) {
            console.error(`Failed to deploy WorkflowTemplate ${wfName}:`, error);
            // Uncomment to stop on first error:
            // process.exit(1);
        }

        console.log("\n");
    }

    console.log("All Argo WorkflowTemplates deployed!");
}

// Run the deployment
deployAllWorkflows().catch(error => {
    console.error("Deployment failed:", error);
    process.exit(1);
});
