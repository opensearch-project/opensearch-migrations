import { submitAndWait, WorkflowResult } from "./workflowRunner";
import { getTestNamespace } from "./argoCluster";

export interface ProbeConfig {
  inputs?: Record<string, string>;
  expression: string;
}

export async function submitProbe(config: ProbeConfig): Promise<WorkflowResult> {
  const namespace = getTestNamespace();
  
  const inputParams = Object.entries(config.inputs || {}).map(([name, value]) => ({
    name,
    value,
  }));
  
  const inputParamDefs = Object.keys(config.inputs || {}).map(name => ({ name }));
  
  const inputParamArgs = Object.keys(config.inputs || {}).map(name => ({
    name,
    value: `{{workflow.parameters.${name}}}`,
  }));
  
  const workflow = {
    apiVersion: "argoproj.io/v1alpha1",
    kind: "Workflow",
    metadata: {
      generateName: "probe-",
      namespace,
    },
    spec: {
      entrypoint: "main",
      activeDeadlineSeconds: 30,
      serviceAccountName: "test-runner",
      arguments: {
        parameters: inputParams,
      },
      templates: [
        {
          name: "eval",
          inputs: {
            parameters: inputParamDefs,
          },
          suspend: {
            duration: "0",
          },
          outputs: {
            parameters: [
              {
                name: "result",
                valueFrom: {
                  expression: config.expression,
                },
              },
            ],
          },
        },
        {
          name: "main",
          steps: [
            [
              {
                name: "eval",
                template: "eval",
                arguments: {
                  parameters: inputParamArgs,
                },
              },
            ],
          ],
          outputs: {
            parameters: [
              {
                name: "result",
                valueFrom: {
                  parameter: "{{steps.eval.outputs.parameters.result}}",
                },
              },
            ],
          },
        },
      ],
    },
  };
  
  return submitAndWait(workflow);
}

export interface ChainProbeConfig {
  input: string;
  steps: { expression: string }[];
}

export async function submitChainProbe(config: ChainProbeConfig): Promise<WorkflowResult> {
  const namespace = getTestNamespace();
  
  const templates: any[] = [];
  const stepGroups: any[] = [];
  
  // Create a template for each step
  config.steps.forEach((step, idx) => {
    const templateName = `step${idx}`;
    templates.push({
      name: templateName,
      inputs: {
        parameters: [{ name: "input" }],
      },
      suspend: {
        duration: "0",
      },
      outputs: {
        parameters: [
          {
            name: "result",
            valueFrom: {
              expression: step.expression,
            },
          },
        ],
      },
    });
    
    // Build step reference
    const stepInput = idx === 0
      ? "{{workflow.parameters.input}}"
      : `{{steps.step${idx - 1}.outputs.parameters.result}}`;
    
    stepGroups.push([
      {
        name: templateName,
        template: templateName,
        arguments: {
          parameters: [
            {
              name: "input",
              value: stepInput,
            },
          ],
        },
      },
    ]);
  });
  
  // Main template
  templates.push({
    name: "main",
    steps: stepGroups,
    outputs: {
      parameters: [
        {
          name: "result",
          valueFrom: {
            parameter: `{{steps.step${config.steps.length - 1}.outputs.parameters.result}}`,
          },
        },
      ],
    },
  });
  
  const workflow = {
    apiVersion: "argoproj.io/v1alpha1",
    kind: "Workflow",
    metadata: {
      generateName: "chain-probe-",
      namespace,
    },
    spec: {
      entrypoint: "main",
      activeDeadlineSeconds: 30,
      serviceAccountName: "test-runner",
      arguments: {
        parameters: [
          {
            name: "input",
            value: config.input,
          },
        ],
      },
      templates,
    },
  };
  
  return submitAndWait(workflow);
}

export async function submitRenderedWorkflow(
  rendered: any,
  inputOverrides?: Record<string, string>
): Promise<WorkflowResult> {
  const namespace = getTestNamespace();
  
  // Convert WorkflowTemplate to Workflow
  const workflow = {
    ...rendered,
    kind: "Workflow",
    metadata: {
      ...rendered.metadata,
      generateName: (rendered.metadata?.name || "test") + "-",
      namespace,
    },
    spec: {
      ...rendered.spec,
      activeDeadlineSeconds: 30,
      serviceAccountName: "test-runner",
    },
  };
  
  // Apply input overrides
  if (inputOverrides && workflow.spec.arguments?.parameters) {
    workflow.spec.arguments.parameters = workflow.spec.arguments.parameters.map((param: any) => {
      if (inputOverrides.hasOwnProperty(param.name)) {
        return { ...param, value: inputOverrides[param.name] };
      }
      return param;
    });
  }
  
  return submitAndWait(workflow);
}
