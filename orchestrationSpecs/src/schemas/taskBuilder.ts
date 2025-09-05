import {
    ExtendScope, IfNever, LoopWithUnion, ParamsWithLiteralsOrExpressions,
    TasksOutputsScope, TasksScopeToTasksWithOutputs, TasksWithOutputs,
    TemplateSignaturesScope, WorkflowAndTemplatesScope
} from "@/schemas/workflowTypes";
import { Workflow } from "@/schemas/workflowBuilder";
import { PlainObject } from "@/schemas/plainObject";
import { UniqueNameConstraintAtDeclaration, UniqueNameConstraintOutsideDeclaration } from "@/schemas/scopeConstraints";
import {CallerParams, InputParametersRecord, OutputParamDef, OutputParametersRecord} from "@/schemas/parameterSchemas";
import { SimpleExpression, stepOutput } from "@/schemas/expression";

export type TaskOpts<LoopT extends PlainObject> = {
    loopWith?: LoopWithUnion<LoopT>,
    when?: SimpleExpression<boolean>
}

// Sentinel to try to guarantee that the parameters have been pushed back to the callback
const PARAMS_PUSHED = Symbol('params_pushed');
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

export type ParamsRegistrationFn<
    TaskScope extends TasksOutputsScope,
    Inputs extends InputParametersRecord,
    LoopT extends PlainObject
> = (
    priorTasks: TasksScopeToTasksWithOutputs<TaskScope, LoopT>,
    register: (params: ParamsWithLiteralsOrExpressions<CallerParams<Inputs>>) => ParamsPushedSymbol
) => ParamsPushedSymbol

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

export type ParamsTuple<
    I extends InputParametersRecord,
    Name extends string,
    S extends TasksOutputsScope,
    LoopT extends PlainObject,
    OptsType extends TaskOpts<LoopT> = TaskOpts<LoopT>
> =
    InputKind<I> extends "empty"
        ? [opts?: OptsType]
        : InputKind<I> extends "allOptional"
            ? [
                paramsFn?: UniqueNameConstraintOutsideDeclaration<
                    Name, S, ParamsRegistrationFn<S, I, LoopT>
                >,
                opts?: OptsType
            ]
            : [
                paramsFn: UniqueNameConstraintOutsideDeclaration<
                    Name, S, ParamsRegistrationFn<S, I, LoopT>
                >,
                opts?: OptsType
            ];

export function unpackParams<I extends InputParametersRecord, LoopT extends PlainObject>(
    args: readonly unknown[]
): {
    paramsFn: ParamsRegistrationFn<any, I, LoopT>;
    opts: TaskOpts<LoopT> | undefined;
} {
    const [first, second] = args as [any, any];
    const paramsFn =
        typeof first === "function"
            ? first
            : ((_: unknown, register: (v: {}) => void) => register({}));
    const opts = typeof first === "function" ? second : first;
    return { paramsFn, opts };
}

export type InputsOf<T> =
    T extends { inputs: infer I }
        ? I extends Record<string, any> ? I : never  // Keep it as Record<string, any> to preserve exact keys
        : never;

export type OutputsOf<T> =
    T extends { outputs: infer O }
        ? O extends OutputParametersRecord ? O : {}
        : {};

export type IsInternal<Src> = Src extends typeof INTERNAL ? true : false;

export type KeyFor<C extends WorkflowAndTemplatesScope, Src> =
    IsInternal<Src> extends true
        ? Extract<keyof C["templates"], string>
        : Src extends Workflow<any, any, any>
            ? Extract<keyof Src["templates"], string>
            : never;

export type KeyMustMatch<C extends WorkflowAndTemplatesScope, Src, K> =
    K extends KeyFor<C, Src> ? K : never;

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
    RB extends TaskRebinder<C>
> {
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

    getParamsFromCallback<
        Inputs extends InputParametersRecord,
        LoopT extends PlainObject = never
    >(fn: ParamsRegistrationFn<S, Inputs, LoopT>, context: TasksScopeToTasksWithOutputs<S, LoopT>):
        ParamsWithLiteralsOrExpressions<CallerParams<Inputs>>
    {
        let capturedParams: ParamsWithLiteralsOrExpressions<CallerParams<Inputs>>;

        const register = (params: ParamsWithLiteralsOrExpressions<CallerParams<Inputs>>): ParamsPushedSymbol => {
            capturedParams = params;
            return PARAMS_PUSHED;
        };

        const result = fn(context, register);

        if (result !== PARAMS_PUSHED) {
            throw new Error('Params registration function must call register and return its result');
        }

        return capturedParams!;
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
        ...args: ParamsTuple<InputsFrom<C, TemplateSource, K>, Name, S, LoopT, OptsType>
    ): ApplyRebinder<
        RB,
        C,
        ExtendScope<S, { [P in Name]: TasksWithOutputs<Name, OutputsFrom<C, S, K>> }>
    > {
        const { paramsFn, opts } = unpackParams<InputsFrom<C, S, K>, LoopT>(args);
        const taskContext = this.buildTasksWithOutputs(opts?.loopWith);
        const params = this.getParamsFromCallback(paramsFn as any, taskContext);

        if (source === INTERNAL) {
            // runtime: we just need a string; compile-time was already checked
            const k = key as unknown as string;
            const outputs = (this.contextualScope.templates as any)?.[k]?.outputs as OutputsFrom<C, S, K>;

            const templateCall = this.callTemplate(name as string, k, params, opts?.loopWith);
            return this.addTaskHelper(name, templateCall as any, outputs, opts);
        } else {
            const wf = source as unknown as Workflow<any, any, any>;
            const k = key as unknown as string;
            const outputs = (wf.templates as any)?.[k]?.outputs as OutputsFrom<C, S, K>;

            // keep your current external behavior (no loopWith to callExternalTemplate)
            const templateCall = this.callExternalTemplate(name as string, wf, k as any, params);
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

    protected buildTasksWithOutputs<LoopT extends PlainObject = never>(
        loopWith?: LoopWithUnion<LoopT>
    ): TasksScopeToTasksWithOutputs<S> {
        const tasks: any = {};
        Object.entries(this.tasksScope).forEach(([taskName, taskDef]: [string, any]) => {
            if (taskDef.outputTypes) {
                tasks[taskName] = {};
                Object.entries(taskDef.outputTypes).forEach(([outputName, outputParamDef]: [string, any]) => {
                    tasks[taskName][outputName] = this.getTaskOutputAsExpression(taskName, outputName, outputParamDef);
                });
            }
        });
        return {
            tasks,
            ...(loopWith ? { item: loopWith } : {})
        } as TasksScopeToTasksWithOutputs<S>;
    }

    protected abstract getTaskOutputAsExpression<T extends PlainObject>(taskName: string, outputName: string, outputParamDef: OutputParamDef<any>): SimpleExpression<T>;

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
