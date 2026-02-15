import { KubeConfig, CustomObjectsApi } from "@kubernetes/client-node";
import { getKubeConfig, getTestNamespace } from "./argoCluster";

export interface NodeOutput {
  parameters: Record<string, string>;
  phase: string;
  message?: string;
}

export interface WorkflowResult {
  phase: string;
  message?: string;
  globalOutputs: Record<string, string>;
  nodeOutputs: Record<string, NodeOutput>;
  duration: number;
  raw: any;
}

function truncate(value: string, max: number = 300): string {
  return value.length <= max ? value : `${value.slice(0, max - 1)}…`;
}

function formatFailureDiagnostics(workflow: any): string {
  const metadata = workflow?.metadata ?? {};
  const status = workflow?.status ?? {};
  const nodes = status.nodes ?? {};
  const lines: string[] = [];

  lines.push(`Workflow: ${metadata.name ?? "<unknown>"}`);
  lines.push(`Phase: ${status.phase ?? "<unknown>"}`);
  if (status.message) {
    lines.push(`Status message: ${truncate(String(status.message), 600)}`);
  }

  const problematicNodes = Object.values<any>(nodes)
    .filter((n) => n?.phase === "Failed" || n?.phase === "Error")
    .slice(0, 8);

  if (problematicNodes.length > 0) {
    lines.push("Problem nodes:");
    for (const node of problematicNodes) {
      const display = node.displayName || node.name || "<unnamed>";
      const template = node.templateName ? ` template=${node.templateName}` : "";
      const msg = node.message ? ` message=${truncate(String(node.message), 400)}` : "";
      lines.push(`- ${display} phase=${node.phase}${template}${msg}`);
    }
  }

  const statusJson = JSON.stringify(status, null, 2);
  lines.push(`Status snapshot: ${truncate(statusJson, 4000)}`);
  return lines.join("\n");
}

export async function submitAndWait(
  workflowObject: any,
  timeoutMs: number = 60000
): Promise<WorkflowResult> {
  const kc = new KubeConfig();
  kc.loadFromString(getKubeConfig());
  const customApi = kc.makeApiClient(CustomObjectsApi);
  
  const namespace = getTestNamespace();
  const startTime = Date.now();
  
  // Submit workflow
  const response = await customApi.createNamespacedCustomObject({
    group: "argoproj.io",
    version: "v1alpha1",
    namespace,
    plural: "workflows",
    body: workflowObject,
  }) as any;
  
  const workflowName = response.metadata.name;
  
  // Poll for completion
  const deadline = Date.now() + timeoutMs;
  let workflow: any;
  
  while (Date.now() < deadline) {
    workflow = await customApi.getNamespacedCustomObject({
      group: "argoproj.io",
      version: "v1alpha1",
      namespace,
      plural: "workflows",
      name: workflowName,
    }) as any;
    
    const phase = workflow.status?.phase;
    
    if (phase === "Succeeded" || phase === "Failed" || phase === "Error") {
      const duration = Date.now() - startTime;
      if (phase === "Failed" || phase === "Error") {
        console.error(`[workflow-failure]\n${formatFailureDiagnostics(workflow)}`);
      }
      return extractResults(workflow, duration);
    }
    
    await new Promise(resolve => setTimeout(resolve, 500));
  }
  
  throw new Error(
    `Workflow ${workflowName} timed out after ${timeoutMs}ms. Last status: ${JSON.stringify(workflow?.status, null, 2)}`
  );
}

function extractResults(workflow: any, duration: number): WorkflowResult {
  const status = workflow.status || {};
  
  // Extract global outputs
  const globalOutputs: Record<string, string> = {};
  if (status.outputs?.parameters) {
    for (const param of status.outputs.parameters) {
      globalOutputs[param.name] = param.value;
    }
  }
  
  // Extract node outputs
  const nodeOutputs: Record<string, NodeOutput> = {};
  if (status.nodes) {
    for (const [nodeId, node] of Object.entries<any>(status.nodes)) {
      const displayName = node.displayName;
      const parameters: Record<string, string> = {};
      
      if (node.outputs?.parameters) {
        for (const param of node.outputs.parameters) {
          parameters[param.name] = param.value;
        }
      }
      
      nodeOutputs[displayName] = {
        parameters,
        phase: node.phase,
        message: node.message,
      };
    }
  }
  
  // FALLBACK: If globalOutputs is empty, try to populate from the main/entrypoint node
  if (Object.keys(globalOutputs).length === 0) {
    // Find the entrypoint node (usually matches workflow name or has templateName matching entrypoint)
    const workflowName = workflow.metadata?.name;
    const entrypointName = workflow.spec?.entrypoint;
    
    for (const [displayName, nodeOutput] of Object.entries(nodeOutputs)) {
      const nodeId = Object.keys(status.nodes).find((id: string) => 
        status.nodes[id].displayName === displayName
      );
      
      if (!nodeId) continue;
      
      const node = status.nodes[nodeId];
      
      // Check if this is the entrypoint node
      if (displayName === workflowName || node?.templateName === entrypointName) {
        // Copy its outputs to globalOutputs
        Object.assign(globalOutputs, nodeOutput.parameters);
        break;
      }
    }
  }
  
  return {
    phase: status.phase,
    message: status.message ?? (status.phase === "Failed" || status.phase === "Error"
      ? formatFailureDiagnostics(workflow)
      : undefined),
    globalOutputs,
    nodeOutputs,
    duration,
    raw: workflow,
  };
}
