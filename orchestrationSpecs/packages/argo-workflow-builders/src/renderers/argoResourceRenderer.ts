import {InputParamDef, InputParametersRecord, OutputParamDef, OutputParametersRecord} from "../models/parameterSchemas";
import {toArgoExpression} from "./argoExpressionRender";
import {StepGroup} from "../models/stepsBuilder";
import {MISSING_FIELD, PlainObject} from "../models/plainObject";
import {GenericScope, LoopWithUnion} from "../models/workflowTypes";
import {WorkflowBuilder} from "../models/workflowBuilder";
import {BaseExpression} from "../models/expression";
import {NamedTask} from "../models/sharedTypes";

function isDefault<T extends PlainObject>(
    p: InputParamDef<T, boolean>
): p is InputParamDef<T, false> & { _hasDefault: true; defaultValue: T } {
    return (p as any)._hasDefault === true;
}

export function renderWorkflowTemplate<WF extends ReturnType<WorkflowBuilder<any, any, any>["getFullScope"]>>(wf: WF) {
    return {
        apiVersion: "argoproj.io/v1alpha1",
        kind: "WorkflowTemplate",

        metadata: wf.metadata.k8sMetadata,
        spec: {
            serviceAccountName: wf.metadata.serviceAccountName,
            entrypoint: wf.metadata.entrypoint,
            parallelism: 100,
            ...(wf.workflowParameters != null && {arguments: formatParameters(wf.workflowParameters)}),
            templates: (() => {
                const list = [];
                for (const k in wf.templates) {
                    list.push(formatTemplate(wf.templates, k));
                }
                return list;
            })()
        }
    };
}

function formatParameterDefinition<T extends PlainObject, P extends InputParamDef<T, boolean>>(inputs: P) {
    const out: Record<string, unknown> = {};
    if (inputs.description != null) {
        out.description = inputs.description;
    }
    if (isDefault(inputs)) {
        if (inputs.defaultValue.expression !== undefined) {
            out.value = transformExpressionsDeep(inputs.defaultValue.expression);
        } else if (inputs.defaultValue.from !== undefined) {
            const f = inputs.defaultValue.from;
            out.valueFrom = {
                configMapKeyRef: {
                    name: transformExpressionsDeep(f.name),
                    key: f.key,
                    optional: f.optional
                }
            }
        } else {
            throw new Error("Invalid DefaultSpec: neither expression nor from provided");
        }
    }
    return out;
}

function formatParameters<IPR extends InputParametersRecord>(inputs: IPR) {
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

function renderWithLoop<T extends PlainObject>(loopWith: LoopWithUnion<T>) {
    if (loopWith.loopWith == "items") {
        return {withItems: loopWith.items};
    } else if (loopWith.loopWith == "sequence") {
        return {withSequence: loopWith.count};
    } else if (loopWith.loopWith == "params") {
        return {withParam: `${toArgoExpression(loopWith.value)}`};
    } else {
        throw new Error(`Expected loopWith value; got ${loopWith}`);
    }
}

function formatArguments(passedParameters: { parameters?: Record<string, any> | undefined } | undefined) {
    if (passedParameters == undefined) {
        return [];
    }
    
    return Object.entries(passedParameters).map(([key, value]) => ({
        name: key,
        value: (value as any === MISSING_FIELD ? "" : transformExpressionsDeep(value))
    }));
}

function formatStepOrTask<T extends NamedTask & { withLoop?: unknown }>(step: T) {
    const {
        templateRef: {template: trTemplate, ...trRest} = {},
        template = undefined,
        withLoop,
        when,
        args,
        ...rest
    } = step;
    return {
        ...(undefined === template   ? {} : {template: convertTemplateName(template as string)} ),
        ...(undefined === trTemplate ? {} : {templateRef: { template: convertTemplateName(trTemplate as string), ...trRest}}),
        ...(undefined === withLoop   ? {} : renderWithLoop(withLoop as LoopWithUnion<any>)),
        ...(undefined === when       ? {} : {
            when:
                (when && typeof when === "object" && "templateExp" in when) ?
                `${toArgoExpression(when.templateExp, "Outer")}` :
                    `${toArgoExpression(when, "IdentifierOnly")}`
        }),
        ...{"arguments": {parameters: (formatArguments(args) as object)}},
        ...rest
    };
}

function formatContainerEnvs(envVars: Record<string, BaseExpression<any>>) {
    const result: any[] = [];
    Object.entries(envVars).forEach(([key, value]) => {
        result.push({name: key, value: transformExpressionsDeep(value)});
    });
    return result;
}

function formatBody(body: GenericScope) {
    if (body) {
        if (body.steps !== undefined) {
            return {
                steps: (body.steps as StepGroup[])
                    .map(g => g.steps.map(s => formatStepOrTask(s)))
            };
        } else if (body.dag !== undefined) {
            return {dag: {tasks: (body.dag as []).map(t => formatStepOrTask(t))}};
        } else if (body.resource !== undefined) {
            const {manifest, ...rest} = body.resource;
            return {
                resource: {
                    manifest: JSON.stringify(transformExpressionsDeep(manifest)),
                    ...transformExpressionsDeep(rest)
                }
            };
        } else if (body.container !== undefined) {
            const {container, ...restOfBody} = body;
            const {env, ...restOfContainer} = container;
            return {
                container: {
                    ...(env === undefined ? {} : {env: formatContainerEnvs(env)}),
                    ...transformExpressionsDeep(restOfContainer)
                },
                ...transformExpressionsDeep(restOfBody)
            };
        } else if (body.suspend !== undefined) {
            return {suspend: {}};
        } else {
            return transformExpressionsDeep(body);
        }
    } else {
        return {};
    }
}

function formatOutputSource(def: OutputParamDef<any>) {
    return {
        valueFrom: (() => {
            switch (def.fromWhere) {
                case "path":
                    return {path: def.path};
                case "expression":
                    return {expression: toArgoExpression(def.expression, "None")};
                case "parameter":
                    return {parameter: toArgoExpression(def.parameter)};
                case "jsonPath":
                    return {jsonPath: def.jsonPath};
                case "jqFilter":
                    return {jqFilter: def.jqFilter};
                case "event":
                    return {event: def.event};
                case "configMapKeyRef":
                    return {configMapKeyRef: def.configMapKeyRef};
                case "supplied":
                    return {supplied: def.supplied};
                case "default":
                    return {default: toArgoExpression(def.default)};
                default:
                    throw new Error(`Unsupported output parameter type: ${(def as any).fromWhere}`);
            }
        })()
    }
}

function formatOutputParameters<OPR extends OutputParametersRecord>(outputs: OPR) {
    if (!outputs) return undefined;

    return {
        parameters: Object.entries(outputs).map(([fieldName, definition]) => ({
            name: fieldName,
            ...(definition.description && {description: definition.description}),
            ...formatOutputSource(definition)
        }))
    };
}

function convertTemplateName(n: string) {
    return n.toLowerCase();
}

function formatTemplate(templates: GenericScope, templateName: string) {
    const template = templates[templateName];
    return {
        name: convertTemplateName(templateName),
        ...(template.inputs === undefined ? {} : {inputs: formatParameters(template.inputs)}),
        ...formatBody(template.body),
        ...(template.retryStrategy && Object.keys(template.retryStrategy).length > 0 ?
            {retryStrategy: template.retryStrategy} : {}),
        outputs: formatOutputParameters(template.outputs)
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
    T extends BaseExpression<any> ? ReturnType<typeof toArgoExpression> :
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
        if (node instanceof BaseExpression) {
            return `${toArgoExpression(node)}` as ReplaceExpressions<U>;
        }

        if (isPrimitiveLiteral(node)) {
            return node as ReplaceExpressions<U>;
        }

        if (Array.isArray(node)) {
            return (node as unknown[]).map(visit) as ReplaceExpressions<U>;
        }

        // sending destructured output into this function is useful & the runtime creates that object
        // w/ a prototype function, hence the reason that this is commented out, at least for now

        //assertPlainObject(node);
        const out: Record<string, unknown> = {};
        for (const [k, v] of Object.entries(node as Record<string, unknown>)) {
            out[k] = visit(v);
        }
        return out as ReplaceExpressions<U>;
    }

    return visit(input);
}
