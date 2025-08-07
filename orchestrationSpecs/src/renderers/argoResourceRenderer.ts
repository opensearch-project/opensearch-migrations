import {ZodTypeAny} from "zod";
import {InputParamDef, InputParametersRecord} from "@/schemas/parameterSchemas";
import {Scope} from "@/schemas/workflowTypes";
import {StepGroup} from "@/schemas/workflowSchemas";


export function renderWorkflowTemplate(wf: Scope) {
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
            ...(wf.workflowParameters != null && { arguments: formatParameters(wf.workflowParameters) }),
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

function formatBody(body: Scope) {
    if (body.steps == undefined) {
        return body;
    } else {
        return { steps: (body.stepGroups as StepGroup[]).map(g => g.steps) };
    }
}

function formatTemplate(template: Scope) {
    return {
        inputs: formatParameters(template.inputs),
        ...formatBody(template.body)
    }
}

