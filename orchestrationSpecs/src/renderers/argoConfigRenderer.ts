import {
    OuterWorkflowTemplate,
    TemplateDef
} from "@/schemas/workflowSchemas";
import {ZodTypeAny} from "zod";
import {InputParamDef, InputParametersRecord} from "@/schemas/parameterSchemas";

function formatParameterDefinition<P extends InputParamDef<ZodTypeAny, boolean>>(inputs : P) {
    return {
        ...(inputs.description != null && { description: inputs.description }),
        ...(inputs.defaultValue != null && { value: inputs.defaultValue })
    };
}

function formatParameters<IPR extends InputParametersRecord>(inputs : IPR)  {
    return {
        parameters:
            Object.entries(inputs).map(([fieldName, definition]) => {
                return {
                    name: fieldName,
                    ...formatParameterDefinition(definition)
                }
            })
    }
}

export function renderWorkflowTemplate<
    T extends Record<string, TemplateDef<any, any>>,
    IPR extends InputParametersRecord
>(wf: OuterWorkflowTemplate<T, IPR>) {
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
            ...(wf.workflowParams != null && { arguments: formatParameters(wf.workflowParams) }),
            templates: Object.entries(wf.templates).map(([key, {inputs, ...rest}]) => ({
                name: key,
                inputs: formatParameters(inputs),
                ...rest
            }))
            // ... you can optionally include other fields like templates[] here
        },
    };
}
