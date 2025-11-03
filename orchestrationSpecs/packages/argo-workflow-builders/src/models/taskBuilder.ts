import {
    ExtendScope,
    ExtractOutputParamType,
    IfNever,
    LoopWithUnion,
    ParamsWithLiteralsOrExpressionsIncludingSerialized,
    TasksOutputsScope,
    TasksWithOutputs,
    TemplateSignaturesScope,
    WorkflowAndTemplatesScope
} from "./workflowTypes";
import {Workflow} from "./workflowBuilder";
import {DeepWiden, NonSerializedPlainObject, PlainObject} from "./plainObject";
import {UniqueNameConstraintAtDeclaration, UniqueNameConstraintOutsideDeclaration} from "./scopeConstraints";
import {InputParametersRecord, OutputParamDef, OutputParametersRecord} from "./parameterSchemas";
import {NamedTask, TaskType} from "./sharedTypes";
import {
    BaseExpression,
    expr,
    FromParameterExpression,
    LoopItemExpression,
    SimpleExpression,
    StepOutputSource,
    TaskOutputSource,
    TemplateExpression
} from "./expression";
import {
    buildDefaultsObject,
    CallerParams,
    DefaultsOfInputs,
    HasRequiredByDef,
    NormalizeInputs
} from "./parameterConversions";

export type WhenCondition = (SimpleExpression<boolean> | {templateExp: TemplateExpression<boolean>});

export type TaskOpts<
    S extends TasksOutputsScope,
    Label extends TaskType,
    LoopT extends NonSerializedPlainObject
> = {
    loopWith?: LoopWithUnion<LoopT> | ((tasks: AllTasksAsOutputReferenceableInner<S, Label>) => LoopWithUnion<LoopT>),
    when?: WhenCondition | ((tasks: AllTasksAsOutputReferenceableInner<S, Label>) => WhenCondition)
};

function reduceWhen<
    S extends TasksOutputsScope,
    Label extends TaskType
>(
    when: WhenCondition | ((tasks: AllTasksAsOutputReferenceableInner<S, Label>) => WhenCondition) | undefined,
    tasks: AllTasksAsOutputReferenceableInner<S, Label>
): WhenCondition | undefined {
    if (when === undefined) {
        return undefined;
    }
    return typeof when === 'function' ? when(tasks) : when;
}

function reduceLoopWith<
    S extends TasksOutputsScope,
    Label extends TaskType,
    LoopT extends NonSerializedPlainObject
>(
    loopWith: LoopWithUnion<LoopT> | ((tasks: AllTasksAsOutputReferenceableInner<S, Label>) => LoopWithUnion<LoopT>) | undefined,
    tasks: AllTasksAsOutputReferenceableInner<S, Label>
): LoopWithUnion<LoopT> | undefined {
    if (loopWith === undefined) {
        return undefined;
    }
    return typeof loopWith === 'function' ? loopWith(tasks) : loopWith;
}

// Sentinel to guarantee that the parameters have been pushed back to the callback
const PARAMS_PUSHED = Symbol("params_pushed");
type ParamsPushedSymbol = typeof PARAMS_PUSHED;

// Sentinel to indicate "use local template by key"
export const INTERNAL: unique symbol = Symbol("INTERNAL_TEMPLATE");

function loopItem<T extends PlainObject>(): TemplateExpression<T> {
    return new LoopItemExpression<T>();
}

function taskOutput<T extends PlainObject, Label extends TaskType>(
    taskType: Label,
    taskName: string,
    parameterName: string,
    def?: OutputParamDef<T>
): SimpleExpression<DeepWiden<T>> {
    return new FromParameterExpression<DeepWiden<T>, TaskOutputSource | StepOutputSource>(
        {
            kind: `${taskType}_output` as any,
            [`${taskType.substring(0, taskType.length - 1)}Name`]: taskName,
            parameterName
        } as (TaskOutputSource | StepOutputSource),
        def as any
    );
}

export type OutputParamsToExpressions<
    Outputs extends OutputParametersRecord,
    Label extends TaskType
> = {
    [K in keyof Outputs]: ReturnType<typeof taskOutput<ExtractOutputParamType<Outputs[K]>, Label>>
};

export type AllTasksAsOutputReferenceableInner<
    TasksScope extends Record<string, TasksWithOutputs<any, any>>,
    Label extends TaskType
> = {
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

export type LabelledAllTasksAsOutputReferenceable<
    TasksScope extends Record<string, TasksWithOutputs<any, any>>,
    Label extends TaskType
> = {
    [K in Label]: AllTasksAsOutputReferenceableInner<TasksScope, Label>
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
    LoopItemsType extends NonSerializedPlainObject = never
> =
    {
        register: (params: ParamsWithLiteralsOrExpressionsIncludingSerialized<CallerParams<Inputs>>) => ParamsPushedSymbol;
        /** runtime copy of the keys to make it possible to narrow values, not just types */
        parameterKeys?: readonly (Extract<keyof Inputs, string>)[];
        defaults: DefaultsOfInputs<Inputs>;
        defaultKeys?: readonly (Extract<keyof DefaultsOfInputs<Inputs>, string>)[];
    } & LabelledAllTasksAsOutputReferenceable<TasksScope, Label>
    & IfNever<LoopItemsType, {}, { item: BaseExpression<LoopItemsType> }>;

/**
 * ParamsRegistrationFn is an alias placeholder that defines the callback that addTasks uses.
 */
export type ParamsRegistrationFn<
    TaskScope extends TasksOutputsScope,
    Inputs extends InputParametersRecord,
    Label extends TaskType,
    LoopT extends NonSerializedPlainObject
> = (ctx: ParamProviderCallbackObject<TaskScope, Label, Inputs, LoopT>) => ParamsPushedSymbol;

// Tri-state discriminator for inputs: "empty" | "allOptional" | "hasRequired"
export type InputKind<T> =
    [keyof T] extends [never] ? "empty"
        : HasRequiredByDef<T> extends true ? "hasRequired"
            : "allOptional";

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
    Label extends TaskType,
    LoopT extends NonSerializedPlainObject,
    OptsType extends TaskOpts<S, Label, LoopT>
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
    S extends TasksOutputsScope,
    Label extends TaskType,
    LoopT extends NonSerializedPlainObject
>(
    args: readonly unknown[]
): {
    paramsFn: ParamsRegistrationFn<any, I, Label, LoopT>;
    opts: TaskOpts<S, Label, LoopT> | undefined;
} {
    const [first, second] = args as [any, any];
    const paramsFn =
        typeof first === "function"
            ? first
            : ((ctx: { register: (v: {}) => ParamsPushedSymbol }) => ctx.register({})) as any;
    const opts = typeof first === "function" ? second : first;
    return {paramsFn, opts};
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
    TaskScope extends TasksOutputsScope,
    Label extends TaskType
>(
    tasksScope: TaskScope,
    taskType: Label
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
    return tasksByName as AllTasksAsOutputReferenceableInner<TaskScope, Label>;
}

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
    protected onTaskPushed<
        LoopT extends NonSerializedPlainObject,
        OptsType extends TaskOpts<S, Label, LoopT>
    >(task: NamedTask, opts?: OptsType): NamedTask {
        const reducedWhen = reduceWhen(opts?.when, this.getTaskOutputsByTaskName());
        return {
            ...task,
            ...(reducedWhen === undefined ? {} : {when: reducedWhen})
        };
    }

    protected getParamsFromCallback<
        Inputs extends InputParametersRecord,
        LoopT extends NonSerializedPlainObject = never
    >(
        inputs: Inputs,
        fn: ParamsRegistrationFn<S, Inputs, Label, LoopT>,
        loopWith?: LoopWithUnion<LoopT>
    ): ParamsWithLiteralsOrExpressionsIncludingSerialized<CallerParams<Inputs>> {
        let capturedParams!: ParamsWithLiteralsOrExpressionsIncludingSerialized<CallerParams<Inputs>>;

        const result = fn(this.buildParamProviderCallbackObject<Inputs, LoopT>(inputs,
            (p: ParamsWithLiteralsOrExpressionsIncludingSerialized<CallerParams<Inputs>>) => {
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
        LoopT extends NonSerializedPlainObject,
        OptsType extends TaskOpts<S, Label, LoopT>
    >(
        name: UniqueNameConstraintAtDeclaration<Name, S>,
        source: UniqueNameConstraintOutsideDeclaration<Name, S, TemplateSource>,
        key: UniqueNameConstraintOutsideDeclaration<Name, S, K>,
        ...args: ParamsTuple<InputsFrom<C, TemplateSource, K>, Name, S, Label, LoopT, OptsType>
    ): ApplyRebinder<
        RB,
        C,
        ExtendScope<S, { [P in Name]: TasksWithOutputs<Name, OutputsFrom<C, S, K>> }>
    > {
        const keyStr = key as unknown as string;
        const inputs = (source === INTERNAL) ?
            (this.contextualScope.templates as any)?.[keyStr]?.inputs as InputsFrom<C, TemplateSource, K> :
            (((source as Workflow<any, any, any>).templates as any)?.[keyStr]?.inputs as InputsFrom<C, TemplateSource, K>);
        const {paramsFn, opts} = unpackParams<InputsFrom<C, TemplateSource, K>, S, Label, LoopT>(args);
        const loopWith = reduceLoopWith(opts?.loopWith, this.getTaskOutputsByTaskName());
        const params = inputs === undefined ? {} :
            this.getParamsFromCallback<InputsFrom<C, TemplateSource, K>, LoopT>(inputs, paramsFn as any, loopWith);

        if (source === INTERNAL) {
            const outputs = (this.contextualScope.templates as any)?.[keyStr]?.outputs as OutputsFrom<C, TemplateSource, K>;
            const templateCall = this.callTemplate(name as string, keyStr, params, loopWith);
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
        LoopT extends NonSerializedPlainObject,
        OptsType extends TaskOpts<S, Label, LoopT>
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
        LoopT extends NonSerializedPlainObject = never
    >(
        inputs: Inputs,
        register: (params: ParamsWithLiteralsOrExpressionsIncludingSerialized<CallerParams<Inputs>>) => ParamsPushedSymbol,
        loopWith?: LoopWithUnion<LoopT>
    ): ParamProviderCallbackObject<S, Label, Inputs, LoopT> {
        const tasksByName = this.getTaskOutputsByTaskName();

        const defaultsRecord = inputs === undefined ? {} : buildDefaultsObject(inputs as any);
        const defaultKeys = Object.keys(defaultsRecord) as Extract<keyof DefaultsOfInputs<Inputs>, string>[];

        return {
            register, // register is the main thing.  The rest is metadata that some utilities use to lock things down more
            parameterKeys: Object.keys(inputs) as Extract<keyof Inputs, string>[],
            defaults: defaultsRecord as DefaultsOfInputs<Inputs>,
            defaultKeys,
            [this.label]: tasksByName,
            ...(loopWith ? {item: loopItem<LoopT>()} : {})
        } as unknown as ParamProviderCallbackObject<S, Label, Inputs, LoopT>;
    }

    public getTasks() {
        return {scope: this.tasksScope, taskList: this.orderedTaskList};
    }

    protected callTemplate<
        TKey extends Extract<keyof TemplateSignaturesScope, string>,
        LoopT extends NonSerializedPlainObject = never
    >(
        name: string,
        templateKey: TKey,
        params: ParamsWithLiteralsOrExpressionsIncludingSerialized<CallerParams<TemplateSignaturesScope[TKey]["inputs"]>>,
        loopWith?: LoopWithUnion<LoopT>
    ): NamedTask<
        TemplateSignaturesScope[TKey]["inputs"],
        TemplateSignaturesScope[TKey]["outputs"] extends OutputParametersRecord ? TemplateSignaturesScope[TKey]["outputs"] : {}
    > {
        return {
            name,
            template: templateKey,
            ...(loopWith ? {withLoop: loopWith} : {}),
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
            templateRef: {name: wf.metadata.k8sMetadata.name, template: templateKey},
            ...(loopWith ? {withLoop: loopWith} : {}),
            args: params
        } as any;
    }
}
