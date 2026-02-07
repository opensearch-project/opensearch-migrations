import {InputParamDef, InputParametersRecord, OutputParamDef, OutputParametersRecord} from "../models/parameterSchemas";
import {
    REMOVE_NEXT_QUOTE_SENTINEL,
    REMOVE_PREVIOUS_QUOTE_SENTINEL,
    toArgoExpressionString
} from "./argoExpressionRender";
import {StepGroup} from "../models/stepsBuilder";
import {MISSING_FIELD, PlainObject} from "../models/plainObject";
import {GenericScope, LoopWithUnion} from "../models/workflowTypes";
import {WorkflowBuilder} from "../models/workflowBuilder";
import {BaseExpression, makeDirectTypeProxy, UnquotedTypeWrapper} from "../models/expression";
import {NamedTask} from "../models/sharedTypes";
import { omit } from 'lodash';
import {toSafeYamlOutput} from "../utils";
import {SynchronizationConfig} from "../models/synchronization";

function isDefault<T extends PlainObject>(
    p: InputParamDef<T, boolean>
): p is InputParamDef<T, false> & { _hasDefault: true; defaultValue: T } {
    return (p as any)._hasDefault === true;
}

export function renderWorkflowTemplate<WF extends ReturnType<WorkflowBuilder<any, any, any>["getFullScope"]>>(wf: WF) {
    // Validate synchronization before rendering
    validateSynchronizationUniqueness(wf);
    
    return {
        apiVersion: "argoproj.io/v1alpha1",
        kind: "WorkflowTemplate",

        metadata: wf.metadata.k8sMetadata,
        spec: {
            serviceAccountName: wf.metadata.serviceAccountName,
            entrypoint: wf.metadata.entrypoint,
            parallelism: 100,
            ...(wf.workflowParameters != null && {arguments: formatParameters(wf.workflowParameters)}),
            ...(wf.metadata.synchronization && {synchronization: formatSynchronization(wf.metadata.synchronization)}),
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

function formatParameterDefinition<T extends PlainObject, P extends InputParamDef<T, boolean>>(input: P) {
    const out: Record<string, unknown> = {};
    if (input.description != null) {
        out.description = input.description;
    }
    if (isDefault(input)) {
        if (input.defaultValue.expression !== undefined) {
            out.value = transformExpressionsDeep(input.defaultValue.expression);
        }
        if (input.defaultValue.from !== undefined) {
            const f = input.defaultValue.from;
            out.valueFrom = {
                configMapKeyRef: {
                    name: transformExpressionsDeep(f.name),
                    key: f.key,
                    optional: f.optional
                }
            }
        }
        if (input.defaultValue.expression === undefined && input.defaultValue.from === undefined) {
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
        return {withParam: `${toArgoExpressionString(loopWith.value)}`};
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

function formatInlineTemplate(inline: Record<string, any>): Record<string, any> {
    const {retryStrategy, ...bodyContent} = inline;
    return {
        ...formatBody(bodyContent),
        ...(retryStrategy ? {retryStrategy} : {})
    };
}

function formatStepOrTask<T extends NamedTask & { withLoop?: unknown }>(step: T) {
    const {
        templateRef: {template: trTemplate, ...trRest} = {},
        template = undefined,
        withLoop,
        when,
        args,
        inline = undefined,
        ...rest
    } = step as T & { inline?: Record<string, any> };
    return {
        ...(undefined === template   ? {} : {template: convertTemplateName(template as string)} ),
        ...(undefined === trTemplate ? {} : {templateRef: { template: convertTemplateName(trTemplate as string), ...trRest}}),
        ...(undefined === inline     ? {} : {inline: formatInlineTemplate(inline)}),
        ...(undefined === withLoop   ? {} : renderWithLoop(withLoop as LoopWithUnion<any>)),
        ...(undefined === when       ? {} : {
            when:
                (when && typeof when === "object" && "templateExp" in when) ?
                `${toArgoExpressionString(when.templateExp, "Outer")}` :
                    `${toArgoExpressionString(when, "IdentifierOnly").replace(/^'|'$/g, '')}`
        }),
        ...{"arguments": {parameters: (formatArguments(args) as object)}},
        ...rest
    };
}

function formatContainerEnvs(envVars: Record<string, BaseExpression<any>>) {
    const result: any[] = [];
    Object.entries(envVars).forEach(([key, value]) => {
        const transformedValue = transformExpressionsDeep(value);
        const v = ("configMapKeyRef" in value || "secretKeyRef" in value) ?
            { valueFrom: omit(transformedValue, "type") } :
            { value: transformedValue };
        result.push({name: key, ...v});
    });
    return result;
}

export function unwrapPlaceholdersAndStringify(obj: any): string {
    const result = toSafeYamlOutput(obj);
    // in this yaml output, the value won't need to be quoted when it starts with a string -
    // as long as REMOVE_PREVIOUS_QUOTE_SENTINEL starts with a character, there won't actually be a quote
    return result
        .replace(new RegExp(`${REMOVE_PREVIOUS_QUOTE_SENTINEL}`, 'g'), '')
        .replace(new RegExp(`${REMOVE_NEXT_QUOTE_SENTINEL}`, 'g'), '');
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
                    manifest: unwrapPlaceholdersAndStringify(transformExpressionsDeep(manifest)),
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
            const { duration } = body.suspend;
            return duration !== undefined
                ? { suspend: { duration: String(Math.floor(duration / 1000)) } }
                : { suspend: {} };
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
                    return {expression: toArgoExpressionString(def.expression, "None", "Expression")};
                case "parameter":
                    return {parameter: toArgoExpressionString(def.parameter)};
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
                    return {default: toArgoExpressionString(def.default)};
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

function formatSynchronization(sync: SynchronizationConfig) {
    const result: any = {};
    
    if (sync.semaphores && sync.semaphores.length > 0) {
        result.semaphores = sync.semaphores.map(sem => {
            if ('configMapKeyRef' in sem) {
                return {
                    configMapKeyRef: {
                        name: transformExpressionsDeep(sem.configMapKeyRef.name),
                        key: transformExpressionsDeep(sem.configMapKeyRef.key)
                    },
                    ...(sem.namespace && { namespace: sem.namespace })
                };
            } else {
                return {
                    database: sem.database,
                    ...(sem.namespace && { namespace: sem.namespace })
                };
            }
        });
    }
    
    if (sync.mutexes && sync.mutexes.length > 0) {
        result.mutexes = sync.mutexes.map(mutex => ({
            name: mutex.name,
            ...(mutex.database && { database: mutex.database }),
            ...(mutex.namespace && { namespace: mutex.namespace })
        }));
    }
    
    return result;
}

function formatTemplate(templates: GenericScope, templateName: string) {
    const template = templates[templateName];
    return {
        name: convertTemplateName(templateName),
        ...(template.inputs === undefined ? {} : {inputs: formatParameters(template.inputs)}),
        ...formatBody(template.body),
        ...(template.retryStrategy && Object.keys(template.retryStrategy).length > 0 ?
            {retryStrategy: template.retryStrategy} : {}),
        ...(template.synchronization && {synchronization: formatSynchronization(template.synchronization)}),
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

/**
 * Recursively transforms any Expression instances found within records and arrays.
 * Asserts when non-plain objects are found since they shouldn't exist in this model.
 */
export function transformExpressionsDeep<T>(input: T) {
    function visit(node: any): any {
        if (node instanceof BaseExpression) {
            return toArgoExpressionString(node);
        }

        if (isPrimitiveLiteral(node)) {
            return node;
        }

        if (Array.isArray(node)) {
            return (node as unknown[]).map(visit);
        }

        // sending destructured output into this function is useful & the runtime creates that object
        // w/ a prototype function, hence the reason that this is commented out, at least for now

        //assertPlainObject(node);
        const out: Record<string, unknown> = {};
        for (const [k, v] of Object.entries(node as Record<string, unknown>)) {
            out[k] = visit(v);
        }
        return out;
    }

    return visit(input);
}

function validateSynchronizationUniqueness<WF extends ReturnType<WorkflowBuilder<any, any, any>["getFullScope"]>>(wf: WF) {
    const configMapRefs = new Set<string>();
    
    // Check workflow-level synchronization
    if (wf.metadata.synchronization) {
        collectConfigMapRefs(wf.metadata.synchronization, configMapRefs, 'workflow');
    }
    
    // Check template-level synchronization
    for (const [templateName, template] of Object.entries(wf.templates)) {
        if (template.synchronization) {
            collectConfigMapRefs(template.synchronization, configMapRefs, `template '${templateName}'`);
        }
    }
}

function collectConfigMapRefs(sync: SynchronizationConfig, refs: Set<string>, location: string) {
    if (sync.semaphores) {
        for (const sem of sync.semaphores) {
            if ('configMapKeyRef' in sem) {
                const refKey = `${sem.namespace || 'default'}/${sem.configMapKeyRef.name}/${sem.configMapKeyRef.key}`;
                if (refs.has(refKey)) {
                    throw new Error(`Duplicate ConfigMap reference found: ${refKey} in ${location}`);
                }
                refs.add(refKey);
            }
        }
    }
}
