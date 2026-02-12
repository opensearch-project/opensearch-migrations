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
  const response = await customApi.createNamespacedCustomObject(
    "argoproj.io",
    "v1alpha1",
    namespace,
    "workflows",
    workflowObject
  ) as any;
  
  const workflowName = response.body.metadata.name;
  
  // Poll for completion
  const deadline = Date.now() + timeoutMs;
  let workflow: any;
  
  while (Date.now() < deadline) {
    const getResponse = await customApi.getNamespacedCustomObject(
      "argoproj.io",
      "v1alpha1",
      namespace,
      "workflows",
      workflowName
    ) as any;
    
    workflow = getResponse.body;
    const phase = workflow.status?.phase;
    
    if (phase === "Succeeded" || phase === "Failed" || phase === "Error") {
      const duration = Date.now() - startTime;
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
  
  return {
    phase: status.phase,
    message: status.message,
    globalOutputs,
    nodeOutputs,
    duration,
    raw: workflow,
  };
}
