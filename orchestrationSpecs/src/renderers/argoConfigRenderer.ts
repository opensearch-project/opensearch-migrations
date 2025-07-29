import {ZodTypeAny} from "zod";
import {InputParamDef, InputParametersRecord} from "@/schemas/parameterSchemas";

function formatParameterDefinition<P extends InputParamDef<ZodTypeAny, boolean>>(inputs : P) {
    return {
        ...(inputs.description != null && { description: inputs.description }),
        ...(inputs.defaultValue != null && { value: inputs.defaultValue })
    };
}

function formatParameters<IPR extends InputParametersRecord>(inputs : IPR)  {
    return inputs == undefined ? [] : {
        parameters:
            Object.entries(inputs).map(([fieldName, definition]) => {
                return {
                    name: fieldName,
                    ...formatParameterDefinition(definition)
                }
            })
    }
}

function formatTemplate(template: Record<string, any>) {
    return {
        inputs: formatParameters(template.inputs)
    }
}

export function renderWorkflowTemplate(wf: Record<string, any>) {
    return {
        apiVersion: "argoproj.io/v1alpha1",
        kind: "WorkflowTemplate",

        metadata: {
            name: wf.name,
        },
        spec: {
            serviceAccountName: wf.serviceAccountName,
            entrypoint: "main",
            parallelism: 100,
            ...(wf.workflowParameters != null && { arguments: formatParameters(wf.workflowParams) }),
            templates: (() => {
                const list = [];
                for (const k in wf.templates) {
                    list.push({[k]: formatTemplate(wf.templates[k]) });
                }
                return list;
            })()
        }
    };
}
