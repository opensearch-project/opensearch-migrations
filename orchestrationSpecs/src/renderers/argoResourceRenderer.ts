import {ZodTypeAny} from "zod";
import {InputParamDef, InputParametersRecord} from "@/schemas/parameterSchemas";
import {Scope} from "@/schemas/workflowTypes";
import {StepGroup} from "@/schemas/workflowSchemas";
import {Expression} from "@/schemas/expression";
import {toArgoExpression} from "@/renderers/argoExpressionRender";


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
    if (body) {
        if (body.steps == undefined) {
            return transformExpressionsDeep(body);
        } else {
            return {steps: (body.steps as StepGroup[]).map(g => transformExpressionsDeep(g.steps))};
        }
    } else {
        return {};
    }
}

function formatTemplate(template: Scope) {
    return {
        inputs: formatParameters(template.inputs),
        ...formatBody(template.body)
    }
}

// Helper: detect plain objects (not class instances)
function isPlainObject(v: unknown): v is Record<string, unknown> {
    if (v === null || typeof v !== "object") return false;
    const proto = Object.getPrototypeOf(v);
    return proto === Object.prototype || proto === null;
}

function isPrimitiveLiteral(v: unknown): v is string | number | boolean | null {
    const t = typeof v;
    return v === null || t === "string" || t === "number" || t === "boolean";
}

function assertPlainObject(v: unknown): asserts v is Record<string, unknown> {
    if (isPlainObject(v)) return;
    throw new Error(`Expected plain object; got ${Object.prototype.toString.call(v)}`);
}

// Output type mapping: replace any Expression with toArgoExpression's return type, recurse through arrays/objects
type ReplaceExpressions<T> =
    T extends Expression<any> ? ReturnType<typeof toArgoExpression> :
        T extends readonly (infer U)[] ? ReadonlyArray<ReplaceExpressions<U>> :
            T extends (infer U)[] ? Array<ReplaceExpressions<U>> :
                T extends object ? { [K in keyof T]: ReplaceExpressions<T[K]> } :
                    T;

/**
 * Recursively transforms any Expression instances found within records and arrays.
 * Asserts when non-plain objects are found since they shouldn't exist in this model.
 */
export function transformExpressionsDeep<T>(input: T): ReplaceExpressions<T> {
    function visit<U>(node: U): ReplaceExpressions<U> {
        if (node instanceof Expression) {
            return toArgoExpression(node) as ReplaceExpressions<U>;
        }

        if (isPrimitiveLiteral(node)) {
            return node as ReplaceExpressions<U>;
        }

        if (Array.isArray(node)) {
            return (node as unknown[]).map(visit) as ReplaceExpressions<U>;
        }

        assertPlainObject(node);
        const out: Record<string, unknown> = {};
        for (const [k, v] of Object.entries(node as Record<string, unknown>)) {
            out[k] = visit(v);
        }
        return out as ReplaceExpressions<U>;
    }

    return visit(input);
}