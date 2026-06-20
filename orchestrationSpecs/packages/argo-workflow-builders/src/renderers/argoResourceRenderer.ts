import {InputParamDef, InputParametersRecord, OutputArtifactsRecord, OutputParamDef, OutputParametersRecord} from "../models/parameterSchemas";
import {
    isAsStringExpression,
    isConcatExpression,
    isFunctionExpression,
    isLiteralExpression,
    isTemplateExpression,
    isWorkflowValue,
    REMOVE_NEXT_QUOTE_SENTINEL,
    REMOVE_PREVIOUS_QUOTE_SENTINEL,
    toArgoExpressionString
} from "./argoExpressionRender";
import {StepGroup} from "../models/stepsBuilder";
import {MISSING_FIELD, PlainObject} from "../models/plainObject";
import {GenericScope, LoopWithUnion} from "../models/workflowTypes";
import {WorkflowBuilder} from "../models/workflowBuilder";
import {
    BaseExpression,
    expr,
    SimpleExpression,
    TemplateExpression,
    UnquotedTypeWrapper
} from "../models/expression";
import {NamedTask} from "../models/sharedTypes";
import * as _ from 'lodash';
import {RawYaml, toSafeYamlOutput} from "../utils";
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
            ...(wf.metadata.onExit ? {onExit: convertTemplateName(wf.metadata.onExit)} : {}),
            parallelism: 100,
            ...(wf.workflowParameters != null && {arguments: formatParameters(wf.workflowParameters)}),
            ...(wf.metadata.synchronization && {synchronization: formatSynchronization(wf.metadata.synchronization)}),
            templates: (() => {
                const list: ReturnType<typeof formatTemplate>[] = [];
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
            const transformed = transformExpressionsDeep(input.defaultValue.expression);
            // Argo parameter values must be strings; serialize object/array defaults as JSON
            out.value = (typeof transformed === "object" && transformed !== null)
                ? JSON.stringify(transformed)
                : transformed;
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
    const {retryStrategy, inputsScope, ...bodyContent} = inline;
    return {
        ...(inputsScope ? {inputs: formatParameters(inputsScope)} : {}),
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
                isTemplateWhenWrapper(when) ?
                `${toArgoExpressionString(when.templateExp, "Outer")}` :
                    `${toArgoExpressionString(when, "IdentifierOnly").replace(/^'|'$/g, '')}`
        }),
        ...{"arguments": {parameters: (formatArguments(args) as object)}},
        ...rest
    };
}

function isTemplateWhenWrapper(
    when: SimpleExpression<boolean> | { templateExp: TemplateExpression<boolean> }
): when is { templateExp: TemplateExpression<boolean> } {
    return typeof when === "object" && when !== null && "templateExp" in when;
}

function formatContainerEnvs(envVars: Record<string, BaseExpression<any>>) {
    const result: any[] = [];
    Object.entries(envVars).forEach(([key, value]) => {
        const transformedValue = transformExpressionsDeep(value);
        const v = ("configMapKeyRef" in value || "secretKeyRef" in value) ?
            { valueFrom: _.omit(transformedValue, "type") } :
            { value: transformedValue };
        result.push({name: key, ...v});
    });
    return result;
}

// A string fully wrapped in the unquote sentinels — an Argo expression that must render unquoted.
const SENTINEL_WRAPPED = new RegExp(`^${REMOVE_PREVIOUS_QUOTE_SENTINEL}([\\s\\S]*)${REMOVE_NEXT_QUOTE_SENTINEL}$`);

// Replace sentinel-wrapped strings with RawYaml markers so the YAML emitter writes them unquoted
// natively (see RawYaml / RAW_YAML_TAG in utils). This is robust where the old approach — strip
// the sentinels and any adjacent quote from the serialized text — was fragile: whether the emitter
// quoted a scalar depended on incidental whitespace in the expression (`: ` vs `:`).
//
// Subtrees with no sentinel are returned by IDENTITY (not cloned), so shared object references
// (e.g. reused retry-strategy constants) stay reference-equal and the YAML emitter can still
// collapse them into anchors/aliases exactly as before.
function markUnquotedNodes(node: any): any {
    if (typeof node === "string") {
        const m = SENTINEL_WRAPPED.exec(node);
        return m ? new RawYaml(m[1]) : node;
    }
    if (Array.isArray(node)) {
        let changed = false;
        const out = node.map(v => { const nv = markUnquotedNodes(v); changed ||= nv !== v; return nv; });
        return changed ? out : node;
    }
    if (node !== null && typeof node === "object") {
        let changed = false;
        const out: Record<string, unknown> = {};
        for (const [k, v] of Object.entries(node)) {
            const nv = markUnquotedNodes(v);
            changed ||= nv !== v;
            out[k] = nv;
        }
        return changed ? out : node;
    }
    return node;
}

export function unwrapPlaceholdersAndStringify(obj: any): string {
    return toSafeYamlOutput(markUnquotedNodes(obj));
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
            const {resource, ...restOfBody} = body;
            const {manifest, ...rest} = resource;
            return {
                resource: {
                    manifest: unwrapPlaceholdersAndStringify(transformExpressionsDeep(manifest, true)),
                    ...transformExpressionsDeep(rest)
                },
                ...transformExpressionsDeep(restOfBody)
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
    const paramOutputs = formatOutputParameters(template.outputs);
    const artifactOutputs = formatOutputArtifacts(template.outputArtifacts);
    const outputs = {
        ...paramOutputs,
        ...(artifactOutputs ? { artifacts: artifactOutputs } : {})
    };
    return {
        name: convertTemplateName(templateName),
        ...(template.inputs === undefined ? {} : {inputs: formatParameters(template.inputs)}),
        ...formatBody(template.body),
        ...(template.retryStrategy && Object.keys(template.retryStrategy).length > 0 ?
            {retryStrategy: template.retryStrategy} : {}),
        ...(template.synchronization && {synchronization: formatSynchronization(template.synchronization)}),
        outputs
    }
}

function formatOutputArtifacts(artifacts: OutputArtifactsRecord | undefined) {
    if (!artifacts || Object.keys(artifacts).length === 0) return undefined;
    return Object.values(artifacts).map(a => ({
        name: a.name,
        path: a.path,
        archive: a.archive ?? { none: {} },
        ...(a.s3 ? { s3: transformExpressionsDeep(a.s3) } : {})
    }));
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

const KNOWN_STRING_FUNCTIONS = new Set([
    "fromBase64",
    "join",
    "lower",
    "sprig.regexFind",
    "sprig.regexReplaceAll",
    "string",
    "toBase64",
    "toJSON",
    "upper",
]);

function isKnownStringExpression(node: BaseExpression<any, any>): boolean {
    if (isLiteralExpression(node)) {
        return typeof node.value === "string";
    }
    if (
        isAsStringExpression(node) ||
        isConcatExpression(node) ||
        isTemplateExpression(node) ||
        isWorkflowValue(node)
    ) {
        return true;
    }
    return isFunctionExpression(node) && KNOWN_STRING_FUNCTIONS.has(node.functionName);
}

/**
 * Recursively transforms any Expression instances found within records and arrays.
 * Asserts when non-plain objects are found since they shouldn't exist in this model.
 *
 * `escapeStringScalars` (set only for resource manifests, which are serialized to a YAML string
 * that Argo then substitutes into as raw text at runtime) wraps each top-level string-scalar
 * expression in `expr.yamlSafeString`, so newlines / quotes / the `---` separator (e.g. a PEM
 * cert) survive YAML re-parsing. Skipped: literals (the YAML emitter already escapes them) and
 * `UnquotedTypeWrapper` non-string scalars — anything nested inside one renders as a single
 * `{{=...}}` block and is never revisited here, so composed `sprig.dict(...)` structures are not
 * double-encoded (which is why escaping must happen here, not at `makeStringTypeProxy`).
 */
export function transformExpressionsDeep<T>(input: T, escapeStringScalars = false) {
    function visit(node: any): any {
        if (node instanceof BaseExpression) {
            if (
                escapeStringScalars &&
                node instanceof UnquotedTypeWrapper &&
                node.mode !== "yaml-safe-json" &&
                isKnownStringExpression(node.value)
            ) {
                throw new Error(
                    "Raw unquoted string expression in a resource manifest. " +
                    "Use makeStringTypeProxy or expr.yamlSafeString for strings; " +
                    "makeDirectTypeProxy is only for non-string YAML values."
                );
            }
            const needsEscape = escapeStringScalars
                && !(node instanceof UnquotedTypeWrapper)
                && !isLiteralExpression(node);
            return toArgoExpressionString(needsEscape ? expr.yamlSafeString(node) : node);
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
