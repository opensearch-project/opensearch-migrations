import {renderWorkflowTemplate} from "@opensearch-migrations/argo-workflow-builders";
import {AllWorkflowTemplates} from "./workflowTemplates/allWorkflowTemplates";
import * as fs from "node:fs";
import path from "node:path";

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

function getOutputDirectory(): string|null {
    const args = process.argv.slice(2);
    const outputDirectoryArgIndex = args.indexOf('--outputDirectory');

    if (outputDirectoryArgIndex !== -1 && args[outputDirectoryArgIndex + 1]) {
        return args[outputDirectoryArgIndex + 1];
    }
    return null;
}

const outputDirectory = getOutputDirectory();
const targetNamespace = getNamespace();

async function createDirectoryIfNotExists(dirPath: string): void {
    try {
        await fs.mkdirSync(dirPath, {recursive:true});
        console.log(`Directory created or already exists: ${dirPath}`);
    } catch (error) {
        console.error(`Error creating directory: ${error}`);
    }
}

async function applyArgoWorkflowTemplate(workflowConfig: any, workflowName: string) {

    // Override namespace in metadata
    if (!workflowConfig.metadata) {
        workflowConfig.metadata = {};
    }
    workflowConfig.metadata.namespace = targetNamespace;

    const textFormattedResource = JSON.stringify(workflowConfig, null, 2);
    if (!outputDirectory) {
        console.log(textFormattedResource);
        return;
    } else {
        const filePath = path.join(outputDirectory, `${workflowName}.yaml`);
        try {
            fs.writeFileSync(filePath, textFormattedResource, 'utf8');
            console.log('File written successfully (synchronous).');
        } catch (error) {
            console.error('Error writing file synchronously:', error);
        }
    }
}

async function writeAllWorkflows() {
    console.log("Deploying Argo WorkflowTemplates to Kubernetes...\n");

    if (outputDirectory !== null) {
        createDirectoryIfNotExists(outputDirectory);
    }

    for (const wf of AllWorkflowTemplates) {
        const finalConfig = renderWorkflowTemplate(wf);

        const wfName = wf.metadata.k8sMetadata.name;
        try {
            await applyArgoWorkflowTemplate(finalConfig, wfName || finalConfig.metadata?.name || 'unknown');
        } catch (error) {
            console.error(`Failed to deploy WorkflowTemplate ${wfName}:`, error);
            // Uncomment to stop on error
            // process.exit(1);
        }
    }

    console.log(`Processed ${AllWorkflowTemplates.length} templates.`);
}

writeAllWorkflows().catch(error => {
    console.error("Deployment failed:", error);
    process.exit(1);
});
