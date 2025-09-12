import {
    ExtendScope, ExtractOutputParamType, IfNever, LoopWithUnion, ParamsWithLiteralsOrExpressions,
    TasksOutputsScope, TasksWithOutputs,
    TemplateSignaturesScope, WorkflowAndTemplatesScope
} from "@/schemas/workflowTypes";
import { Workflow } from "@/schemas/workflowBuilder";
import { PlainObject } from "@/schemas/plainObject";
import { UniqueNameConstraintAtDeclaration, UniqueNameConstraintOutsideDeclaration } from "@/schemas/scopeConstraints";
import { CallerParams, InputParametersRecord, OutputParamDef, OutputParametersRecord } from "@/schemas/parameterSchemas";
import {
    SimpleExpression,
    loopItem,
    AllowLiteralOrExpression,
    BaseExpression,
    taskOutput,
    expr
} from "@/schemas/expression";

export type TaskOpts<LoopT extends PlainObject> = {
    loopWith?: LoopWithUnion<LoopT>,
    when?: SimpleExpression<boolean>
};

// Sentinel to guarantee that the parameters have been pushed back to the callback
const PARAMS_PUSHED = Symbol("params_pushed");
type ParamsPushedSymbol = typeof PARAMS_PUSHED;

// Sentinel to indicate "use local template by key"
export const INTERNAL: unique symbol = Symbol("INTERNAL_TEMPLATE");

// ==== helpers for InputParamDef-based detection ====

// A field is optional-at-callsite iff its value type has `_hasDefault: true`
export type ValueHasDefault<V> = V extends { _hasDefault: true } ? true : false;

// Collect keys that are *required* at callsite (i.e., no _hasDefault: true)
export type SomeRequiredKey<T> =
    [keyof T] extends [never] ? never
        : { [K in keyof T]-?: ValueHasDefault<T[K]> extends true ? never : K }[keyof T];
export type HasRequiredByDef<T> = [SomeRequiredKey<T>] extends [never] ? false : true;

// Tri-state discriminator for inputs: "empty" | "allOptional" | "hasRequired"
export type InputKind<T> =
    [keyof T] extends [never] ? "empty"
        : HasRequiredByDef<T> extends true ? "hasRequired"
            : "allOptional";

export type NormalizeInputs<T> = [T] extends [undefined] ? {} : T;

export type TaskType = "tasks" | "steps";
type LoopItemType<L extends PlainObject> = [L] extends [never] ? never : AllowLiteralOrExpression<L>;

export type OutputParamsToExpressions<
    Outputs extends OutputParametersRecord,
    Label extends TaskType
> = {
    [K in keyof Outputs]: ReturnType<typeof taskOutput<ExtractOutputParamType<Outputs[K]>, Label>>
};
export type AllTasksAsOutputReferenceable<
    TasksScope extends Record<string, TasksWithOutputs<any, any>>,
    Label extends TaskType
> = {
    [K in Label]: {
        [StepName in keyof TasksScope]:
        TasksScope[StepName] extends TasksWithOutputs<infer _Name, infer Outputs>
            ? {
                id: BaseExpression<string>;
                status: BaseExpression<string>;
                outputs: Outputs extends OutputParametersRecord
                    ? OutputParamsToExpressions<Outputs, Label>
                    : {};
            }
            : { id: BaseExpression<string>; status: BaseExpression<string>; outputs: {} };
    }
};

/**
 * ParamProviderCallbackObject:
 *  - carries a top-level `register` function that accepts ParamsWithLiteralsOrExpressions<CallerParams<Inputs>>
 *  - nests each step under `<Label>.<StepName>.{ id, outputs }`
 *  - preserves `item` when looping
 */
export type ParamProviderCallbackObject<
    TasksScope extends Record<string, TasksWithOutputs<any, any>>,
    Label extends TaskType,
    Inputs extends InputParametersRecord,
    LoopItemsType extends PlainObject = never
> =
    {
        register: (params: ParamsWithLiteralsOrExpressions<CallerParams<Inputs>>) => ParamsPushedSymbol;
        /** runtime copy of the keys to make it possible to narrow values, not just types */
        parameterKeys?: readonly (Extract<keyof Inputs, string>)[];
    } & AllTasksAsOutputReferenceable<TasksScope, Label>
    & IfNever<LoopItemsType, {}, { item: AllowLiteralOrExpression<LoopItemsType> }>;

/**
 * ParamsRegistrationFn is an alias placeholder that defines the callback that addTasks uses.
 */
export type ParamsRegistrationFn<
    TaskScope extends TasksOutputsScope,
    Inputs extends InputParametersRecord,
    Label extends TaskType,
    LoopT extends PlainObject
> = (ctx: ParamProviderCallbackObject<TaskScope, Label, Inputs, LoopT>) => ParamsPushedSymbol;

export type NormalizeInputsOfCtxTemplate<
    C extends WorkflowAndTemplatesScope,
    K extends Extract<keyof C["templates"], string>
> =
    NormalizeInputs<InputsOf<C["templates"][K]>> extends InputParametersRecord
        ? NormalizeInputs<InputsOf<C["templates"][K]>>
        : {};

export type NormalizeInputsOfWfTemplate<
    W extends Workflow<any, any, any>,
    K extends Extract<keyof W["templates"], string>
> =
    W["templates"][K] extends { inputs: infer X }
        ? NormalizeInputs<X> extends InputParametersRecord ? NormalizeInputs<X> : {}
        : {};

// Is K optional in T?
export type IsOptionalKey<T, K extends keyof T> =
    {} extends Pick<T, K> ? true : false;

// Union of the *required* (non-optional) keys of T
export type RequiredKeys<T> =
    T extends object
        ? { [K in keyof T]-?: IsOptionalKey<T, K> extends true ? never : K }[keyof T]
        : never;

// Required keys of the first parameter of R (if it’s an object)
export type RequiredKeysOfRegister<R extends (arg: any, ...rest: any[]) => any> =
    Parameters<R>[0] extends object
        ? RequiredKeys<Parameters<R>[0]>
        : never;

// Convention: an input is optional-at-callsite iff value._hasDefault === true
function isRequiredAtCallsite(v: any): boolean {
    return !(v && v._hasDefault === true);
}

// read an optional runtime allow-list of keys from the callback object
function getAcceptedRegisterKeys(cb: any): string[] | undefined {
    if (cb && Array.isArray(cb.parameterKeys)) return cb.parameterKeys as string[];
    if (cb?.register && Array.isArray(cb.register.parameterKeys)) return cb.register.parameterKeys as string[];
    return undefined;
}

/**
 * NEW: Strongly-typed helper that keeps only the provided keys.
 * - `builder.inputs` can be any record
 * - `keys` must be a readonly array of keys from `builder.inputs`
 * - Return type is precisely Pick<inputs, keys[number]>
 */
export function selectInputsForKeys<
    BuilderT extends { inputs: Record<string, any> },
    Ks extends readonly (keyof BuilderT["inputs"])[]
>(
    builder: BuilderT,
    keys: Ks
): Pick<BuilderT["inputs"], Ks[number]> {
    const src = builder.inputs as Record<string, any>;
    const out: Partial<BuilderT["inputs"]> = {};

    for (const k of keys) {
        if (Object.prototype.hasOwnProperty.call(src, k as string)) {
            // TS is happy because k ∈ keyof BuilderT["inputs"]
            (out as any)[k] = src[k as string];
        }
    }

    return out as Pick<BuilderT["inputs"], Ks[number]>;
}

/**
 * Infers the correct Pick<...> from (b.inputs, c.register) at compile time,
 * and at runtime returns only those inputs considered "required at callsite".
 *
 * B: any object with an `inputs` record
 * C: any object with a `register` function of shape (arg: {...}) => any
 */
export function selectInputsForRegister<
    BuilderT extends { inputs: Record<string, any> },
    ParamsCallbackT extends { register: (arg: any, ...rest: any[]) => any }
>(
    builder: BuilderT,
    callback: ParamsCallbackT
): Pick<BuilderT["inputs"], Extract<keyof BuilderT["inputs"], RequiredKeysOfRegister<ParamsCallbackT["register"]>>>
{
    const keysToKeep = getAcceptedRegisterKeys(callback) as readonly (keyof BuilderT["inputs"])[];
    return selectInputsForKeys(builder, keysToKeep) as any;}

export type ParamsTuple<
    I extends InputParametersRecord,
    Name extends string,
    S extends TasksOutputsScope,
    Label extends TaskType,
    LoopT extends PlainObject,
    OptsType extends TaskOpts<LoopT> = TaskOpts<LoopT>
> =
    InputKind<I> extends "empty"
        ? [opts?: OptsType]
        : InputKind<I> extends "allOptional"
            ? [
                paramsFn?: UniqueNameConstraintOutsideDeclaration<
                    Name, S, ParamsRegistrationFn<S, I, Label, LoopT>
                >,
                opts?: OptsType
            ]
            : [
                paramsFn: UniqueNameConstraintOutsideDeclaration<
                    Name, S, ParamsRegistrationFn<S, I, Label, LoopT>
                >,
                opts?: OptsType
            ];

export function unpackParams<
    I extends InputParametersRecord,
    Label extends TaskType,
    LoopT extends PlainObject
>(
    args: readonly unknown[]
): {
    paramsFn: ParamsRegistrationFn<any, I, Label, LoopT>;
    opts: TaskOpts<LoopT> | undefined;
} {
    const [first, second] = args as [any, any];
    const paramsFn =
        typeof first === "function"
            ? first
            : ((ctx: { register: (v: {}) => ParamsPushedSymbol }) => ctx.register({})) as any;
    const opts = typeof first === "function" ? second : first;
    return { paramsFn, opts };
}

export type InputsOf<T> =
    T extends { inputs: infer I }
        ? I extends Record<string, any> ? I : never  // Keep it as Record<string, any> to preserve exact keys
        : never;

export type OutputsOf<T> =
    T extends { outputs?: infer O }
        ? O extends OutputParametersRecord ? O : {}
        : {};

export type IsInternal<Src> = Src extends typeof INTERNAL ? true : false;

export type KeyFor<C extends WorkflowAndTemplatesScope, Src> =
    IsInternal<Src> extends true
        ? Extract<keyof C["templates"], string>
        : Src extends Workflow<any, any, any>
            ? Extract<keyof Src["templates"], string>
            : never;

export type InputsFrom<C extends WorkflowAndTemplatesScope, Src, K> =
    IsInternal<Src> extends true
        ? NormalizeInputsOfCtxTemplate<C, Extract<K, Extract<keyof C["templates"], string>>>
        : Src extends Workflow<any, any, any>
            ? NormalizeInputsOfWfTemplate<Src, Extract<K, Extract<keyof Src["templates"], string>>>
            : never;

export type OutputsFrom<C extends WorkflowAndTemplatesScope, Src, K> =
    IsInternal<Src> extends true
        ? OutputsOf<C["templates"][Extract<K, Extract<keyof C["templates"], string>>]>
        : Src extends Workflow<any, any, any>
            ? OutputsOf<Src["templates"][Extract<K, Extract<keyof Src["templates"], string>>]>
            : never;

export function getTaskOutputsByTaskName<
    TaskScope extends TasksOutputsScope
>(
    tasksScope: TaskScope,
    taskType: TaskType
) {
    const tasksByName: Record<string, any> = {};

    Object.entries(tasksScope).forEach(([taskName, taskDef]: [string, any]) => {
        const outputs: Record<string, any> = {};
        if (taskDef.outputTypes) {
            Object.entries(taskDef.outputTypes).forEach(([outputName, outputParamDef]: [string, any]) => {
                outputs[outputName] = taskOutput(taskType, taskName, outputName, outputParamDef);
            });
        }
        tasksByName[taskName] = {
            id: expr.taskData(taskType, taskName, "id"),
            status: expr.taskData(taskType, taskName, "status"),
            outputs
        };
    });
    return tasksByName as Record<TaskType, {
        id: BaseExpression<string>;
        status: BaseExpression<string>;
        outputs: Record<string, any>
    }>;
}

export type WorkflowTask<
    IN extends InputParametersRecord,
    OUT extends OutputParametersRecord,
    LoopT extends PlainObject = never
> = {
    args?: { parameters?: Record<string, any> },
    when?: SimpleExpression<boolean>
} & (
    | { templateRef: { name: string; template: string } }
    | { template: string }
    ) & IfNever<LoopT, {}, { withLoop: LoopT }>;

export type NamedTask<
    IN extends InputParametersRecord = InputParametersRecord,
    OUT extends OutputParametersRecord = OutputParametersRecord,
    LoopT extends PlainObject = never,
    Extra extends Record<string, any> = {}
> = { name: string } & WorkflowTask<IN, OUT, LoopT> & Extra;

export type TaskRebinder<C extends WorkflowAndTemplatesScope> =
    <NS extends TasksOutputsScope>(context: C, scope: NS, tasks: NamedTask[]) => any;

/** Helper to “apply” the rebinder and get its precise return type */
export type ApplyRebinder<
    RB,
    C extends WorkflowAndTemplatesScope,
    NS extends TasksOutputsScope
> = RB extends (context: C, scope: NS, tasks: NamedTask[]) => infer R ? R : never;

/**
 * Base TaskBuilder THAT CAN REBIND ITS RETURN TYPE:
 * - C: contextual scope
 * - S: current tasks scope
 * - RB: a function type that, given a NEW scope NS, returns the concrete “next builder” type
 *
 * IMPORTANT: every method that “extends the scope” now returns ApplyRebinder<RB, C, NewScope>.
 */
export abstract class TaskBuilder<
    C extends WorkflowAndTemplatesScope,
    S extends TasksOutputsScope,
    Label extends TaskType,
    RB extends TaskRebinder<C>
> {
    protected abstract readonly label: Label;

    constructor(
        protected readonly contextualScope: C,
        protected readonly tasksScope: S,
        protected readonly orderedTaskList: NamedTask[],
        /** the rebinder function that constructs the next instance (subclass, wrapper, etc.) */
        private readonly rebind: RB
    ) {}

    /** Hook for subclasses/wrappers to customize task creation (e.g. DAG adds `when`). */
    protected onTaskPushed<LoopT extends PlainObject, OptsType extends TaskOpts<LoopT>>(task: NamedTask, opts?: OptsType): NamedTask {
        return opts?.when !== undefined ? { ...task, when: opts?.when } : task;
    }

    protected getParamsFromCallback<
        Inputs extends InputParametersRecord,
        LoopT extends PlainObject = never
    >(
        inputs: Inputs,
        fn: ParamsRegistrationFn<S, Inputs, Label, LoopT>,
        loopWith?: LoopWithUnion<LoopT>
    ): ParamsWithLiteralsOrExpressions<CallerParams<Inputs>> {
        let capturedParams!: ParamsWithLiteralsOrExpressions<CallerParams<Inputs>>;

        const result = fn(this.buildParamProviderCallbackObject<Inputs, LoopT>(inputs,
            (p: ParamsWithLiteralsOrExpressions<CallerParams<Inputs>>) => {
                capturedParams = p;
                return PARAMS_PUSHED;
            }, loopWith));

        if (result !== PARAMS_PUSHED) {
            throw new Error("Params registration function must call ctx.register(...) and return its result");
        }

        return capturedParams;
    }

    public addTask<
        Name extends string,
        TemplateSource,                      // typeof INTERNAL | Workflow<...>
        K extends KeyFor<C, TemplateSource>, // tie K to S so key autocompletes
        LoopT extends PlainObject,
        OptsType extends TaskOpts<LoopT> = TaskOpts<LoopT>
    >(
        name: UniqueNameConstraintAtDeclaration<Name, S>,
        source: UniqueNameConstraintOutsideDeclaration<Name, S, TemplateSource>,
        key: UniqueNameConstraintOutsideDeclaration<Name, S, K>,
        ...args: ParamsTuple<InputsFrom<C, TemplateSource, K>, Name, S, Label, LoopT, OptsType>
    ): ApplyRebinder<
        RB,
        C,
        ExtendScope<S, { [P in Name]: TasksWithOutputs<Name, OutputsFrom<C, TemplateSource, K>> }>
    > {
        const keyStr = key as unknown as string;
        const inputs = (source === INTERNAL) ?
             (this.contextualScope.templates as any)?.[keyStr]?.inputs as InputsFrom<C, TemplateSource, K> :
             (((source as Workflow<any, any, any>).templates as any)?.[keyStr]?.inputs as InputsFrom<C, TemplateSource, K>);
        const { paramsFn, opts } = unpackParams<InputsFrom<C, TemplateSource, K>, Label, LoopT>(args);
        const params =
            this.getParamsFromCallback<InputsFrom<C, TemplateSource, K>, LoopT>(inputs, paramsFn as any, opts?.loopWith);

        if (source === INTERNAL) {
            const outputs = (this.contextualScope.templates as any)?.[keyStr]?.outputs as OutputsFrom<C, TemplateSource, K>;
            const templateCall = this.callTemplate(name as string, keyStr, params, opts?.loopWith);
            return this.addTaskHelper(name, templateCall as any, outputs, opts);
        } else {
            const wf = source as unknown as Workflow<any, any, any>;
            const outputs = (wf.templates as any)?.[keyStr]?.outputs as OutputsFrom<C, TemplateSource, K>;
            const templateCall = this.callExternalTemplate(name as string, source as any, keyStr as any, params);
            return this.addTaskHelper(name, templateCall as any, outputs, opts);
        }
    }

    /** Core helper extends scope and returns the REBOUND type for the new scope. */
    protected addTaskHelper<
        TKey extends string,
        IN extends InputParametersRecord,
        OUT extends OutputParametersRecord,
        LoopT extends PlainObject,
        OptsType extends TaskOpts<LoopT>
    >(
        name: UniqueNameConstraintAtDeclaration<TKey, S>,
        templateCall: NamedTask<IN, OUT, LoopT>,
        outputs: OUT,
        opts?: OptsType
    ): ApplyRebinder<RB, C, ExtendScope<S, { [K in TKey]: TasksWithOutputs<TKey, OUT> }>> {
        const nameStr = name as string;
        this.orderedTaskList.push(this.onTaskPushed(templateCall, opts));

        const taskSig: TasksWithOutputs<TKey, OUT> = {
            name: nameStr as TKey,
            outputTypes: outputs
        };

        const nextScope = {
            ...this.tasksScope,
            [nameStr]: taskSig
        } as ExtendScope<S, { [K in TKey]: TasksWithOutputs<TKey, OUT> }>;

        // Use the rebinder to produce the precise next instance
        return this.rebind(this.contextualScope, nextScope, this.orderedTaskList) as any;
    }

    public getTaskOutputsByTaskName() {
        return getTaskOutputsByTaskName(this.tasksScope, this.label);
    }

    protected buildParamProviderCallbackObject<
        Inputs extends InputParametersRecord,
        LoopT extends PlainObject = never
    >(
        inputs: Inputs,
        register: (params: ParamsWithLiteralsOrExpressions<CallerParams<Inputs>>) => ParamsPushedSymbol,
        loopWith?: LoopWithUnion<LoopT>
    ): ParamProviderCallbackObject<S, Label, Inputs, LoopT> {
        const tasksByName = this.getTaskOutputsByTaskName();

        return {
            register,
            parameterKeys: Object.keys(inputs) as Extract<keyof Inputs, string>[],
            [this.label]: tasksByName,
            ...(loopWith ? { item: loopItem<LoopT>() } : {})
        } as unknown as ParamProviderCallbackObject<S, Label, Inputs, LoopT>;
    }

    public getTasks() {
        return { scope: this.tasksScope, taskList: this.orderedTaskList };
    }

    protected callTemplate<
        TKey extends Extract<keyof TemplateSignaturesScope, string>,
        LoopT extends PlainObject = never
    >(
        name: string,
        templateKey: TKey,
        params: CallerParams<TemplateSignaturesScope[TKey]["inputs"]>,
        loopWith?: LoopWithUnion<LoopT>
    ): NamedTask<
        TemplateSignaturesScope[TKey]["inputs"],
        TemplateSignaturesScope[TKey]["outputs"] extends OutputParametersRecord ? TemplateSignaturesScope[TKey]["outputs"] : {}
    > {
        return {
            name,
            template: templateKey,
            ...(loopWith ? { withLoop: loopWith } : {}),
            args: params
        };
    }

    protected callExternalTemplate<
        WF extends Workflow<any, any, any>,
        TKey extends Extract<keyof WF["templates"], string>,
        ParamsType,
        LoopT extends PlainObject = never
    >(
        name: string,
        wf: WF,
        templateKey: TKey,
        params: ParamsType,
        loopWith?: LoopWithUnion<LoopT>
    ): NamedTask<
        WF["templates"][TKey]["inputs"],
        WF["templates"][TKey]["outputs"] extends OutputParametersRecord ? WF["templates"][TKey]["outputs"] : {}
    > {
        return {
            name,
            templateRef: { name: templateKey, template: wf.metadata.k8sMetadata.name },
            ...(loopWith ? { withLoop: loopWith } : {}),
            args: params
        } as any;
    }
}
