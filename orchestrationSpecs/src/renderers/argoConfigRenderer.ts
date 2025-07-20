import {
    OuterWorkflowTemplate, OuterWorkflowTemplateScope,
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

export function renderWorkflowTemplate(wf: OuterWorkflowTemplateScope, name: string) {
    return {
        apiVersion: "argoproj.io/v1alpha1",
        kind: "WorkflowTemplate",

        metadata: {
            name: name,
        },
        spec: {
            serviceAccountName: wf.serviceAccountName,
            entrypoint: "main",
            parallelism: 100,
            ...(wf.workflowParameters != null && { arguments: formatParameters(wf.workflowParameters) }),
            templates: (() => {
                const proto = Object.getPrototypeOf(wf);
                const list = [];
                for (const key of Object.getOwnPropertyNames(proto)) {
                    console.log("key="+key);
                    if (Object.getOwnPropertyDescriptor(proto, key)?.get) { // only check getters
                        const val = (wf as any)[key];
                        debugger
                        list.push({
                            name: key,
                            inputs: formatParameters(val.inputs)
                        });
                    }
                }

                //
                const ctor = wf.constructor  as Record<string, any>;
                for (const staticKey of Object.getOwnPropertyNames(ctor)) {
                    if (staticKey === "prototype" || staticKey === "length" || staticKey === "name") continue; // skip built-ins

                    const staticDescriptor = Object.getOwnPropertyDescriptor(ctor, staticKey);
                    if (staticDescriptor?.get) {
                        const val = ctor[staticKey];
                        list.push({
                            name: staticKey,
                            inputs: formatParameters(val.inputs)
                        });
                    } else if (typeof ctor[staticKey] === "object" && ctor[staticKey]?.inputs) {
                        list.push({
                            name: staticKey,
                            inputs: formatParameters(ctor[staticKey].inputs)
                        });
                    }
                }

                return list;
            })()
        }
    };
}
