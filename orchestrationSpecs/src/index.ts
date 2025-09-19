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
    return 'ma';
}

function getCreateResources(): boolean {
    const args = process.argv.slice(2);
    const createResourcesArgIndex = args.indexOf('--createResources');

    if (createResourcesArgIndex !== -1 && args[createResourcesArgIndex + 1]) {
        const value = args[createResourcesArgIndex + 1].toLowerCase();
        if (value === 'true') return true;
        if (value === 'false') return false;
    }

    // If not found or invalid value, print error and exit
    console.error('Error: --createResources flag is required and must be set to true or false');
    process.exit(1);
}

function getShowResources(): boolean {
    const args = process.argv.slice(2);
    return args.indexOf('--showResources') !== -1;
}

const targetNamespace = getNamespace();
const createResources = getCreateResources();
const showResources = getShowResources();

console.log(`Target namespace: ${targetNamespace}`);
console.log(`Create resources: ${createResources}`);
console.log("OUTPUT: ");

const templates = [
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
    TargetLatchHelpers,
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

        console.log(`Processing Argo WorkflowTemplate: ${workflowName} in namespace: ${targetNamespace}`);

        if (!createResources) {
            console.log(`CREATE_RESOURCES=false: Skipping WorkflowTemplate: ${workflowName}`);
            return;
        }

        // First, try to delete the existing template (no error if it doesn't exist)
        try {
            await customObjectsApi.deleteNamespacedCustomObject({
                group: 'argoproj.io',
                version: 'v1alpha1',
                namespace: targetNamespace,
                plural: 'workflowtemplates',
                name: workflowConfig.metadata.name
            });
            console.log(`🗑️  Deleted existing WorkflowTemplate: ${workflowName}`);
        } catch (deleteError: any) {
            if (deleteError.response?.statusCode === 404) {
                console.log(`ℹ️  WorkflowTemplate ${workflowName} does not exist, proceeding with creation...`);
            } else {
                console.warn(`⚠️  Warning: Failed to delete existing WorkflowTemplate ${workflowName}:`, deleteError.message);
            }
        }

        // Create the new template
        await customObjectsApi.createNamespacedCustomObject({
            group: 'argoproj.io',
            version: 'v1alpha1',
            namespace: targetNamespace,
            plural: 'workflowtemplates',
            body: workflowConfig
        });

        console.log(`Successfully created WorkflowTemplate: ${workflowName}`);

    } catch (error: any) {
        console.error(`Failed to apply WorkflowTemplate ${workflowName}:`, error.message);
        throw error;
    }
}

async function deployAllWorkflows() {
    console.log("Deploying Argo WorkflowTemplates to Kubernetes...\n");

    for (const wf of templates) {
        const finalConfig = renderWorkflowTemplate(wf);

        // Log the config for debugging
        if (showResources) {
            console.log(JSON.stringify(finalConfig, null, 2));
            console.log("\n---\n");
        }

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

    console.log(`Processed ${templates.length} templates.`);
}

// Run the deployment
deployAllWorkflows().catch(error => {
    console.error("Deployment failed:", error);
    process.exit(1);
});