import {CallerParams, InputParametersRecord, OutputParamDef, OutputParametersRecord,} from "@/schemas/parameterSchemas";
import {
    AllowLiteralOrExpression,
    ExtendScope,
    LoopWithUnion,
    ParamsWithLiteralsOrExpressions,
    TasksOutputsScope,
    TasksScopeToTasksWithOutputs,
    TasksWithOutputs,
    WorkflowAndTemplatesScope
} from "@/schemas/workflowTypes";
import {SimpleExpression} from "@/schemas/expression";
import {TemplateBodyBuilder} from "@/schemas/templateBodyBuilder";
import {UniqueNameConstraintAtDeclaration, UniqueNameConstraintOutsideDeclaration} from "@/schemas/scopeConstraints";
import {PlainObject} from "@/schemas/plainObject";
import {Workflow} from "@/schemas/workflowBuilder";
import {
    InputKind,
    InputsOf, IsEmptyRecord,
    NamedTask, NormalizeInputs,
    OutputsOf,
    ParamsRegistrationFn,
    TaskBuilder,
    TaskOpts,
    TaskRebinder
} from "@/schemas/taskBuilder";

export interface StepGroup {
    steps: NamedTask[];
}

class StepGroupBuilder<
    ContextualScope extends WorkflowAndTemplatesScope,
    TasksScope extends TasksOutputsScope
> extends TaskBuilder<
    ContextualScope,
    TasksScope,
    TaskRebinder<ContextualScope>
> {
    constructor(
        contextualScope: ContextualScope,
        tasksScope: TasksScope,
        tasks: NamedTask[]
    ) {
        const rebind: TaskRebinder<ContextualScope> =
            <NS extends TasksOutputsScope>(ctx: ContextualScope, scope: NS, t: NamedTask[]) =>
                new StepGroupBuilder<ContextualScope, NS>(ctx, scope, t);

        super(contextualScope, tasksScope, tasks, rebind);
    }
}

/** Helper types for the conditional `addStepGroup` return */
type BuilderLike = { getTasks(): { scope: any; taskList: NamedTask[] } };
type AddStepGroupResult<
    R,
    C extends WorkflowAndTemplatesScope,
    I extends InputParametersRecord,
    S extends TasksOutputsScope,
    O extends OutputParametersRecord
> = R extends BuilderLike
    ? StepsBuilder<C, I, ReturnType<R["getTasks"]>["scope"], O>
    : R;

export class StepsBuilder<
    ContextualScope extends WorkflowAndTemplatesScope,
    InputParamsScope extends InputParametersRecord,
    StepsScope extends TasksOutputsScope,
    OutputParamsScope extends OutputParametersRecord
> extends TemplateBodyBuilder<
    ContextualScope,
    "steps",
    InputParamsScope,
    StepsScope,
    OutputParamsScope,
    StepsBuilder<ContextualScope, InputParamsScope, StepsScope, any>
> {
    constructor(
        contextualScope: ContextualScope,
        public inputsScope: InputParamsScope,
        bodyScope: StepsScope,
        public readonly stepGroups: StepGroup[],
        public outputsScope: OutputParamsScope
    ) {
        super("steps", contextualScope, inputsScope, bodyScope, outputsScope);
    }

    /**
     * Accept **any** builder-like result that has getTasks().
     * If caller returns a TypescriptError<...>, it won't have getTasks and we propagate that error type back.
     * If it succeeds, we infer the extended scope and return a new StepsBuilder with that scope.
     */
    public addStepGroup<R>(
        builderFn: (groupBuilder: StepGroupBuilder<ContextualScope, StepsScope>) => R
    ): AddStepGroupResult<R, ContextualScope, InputParamsScope, StepsScope, OutputParamsScope> {
        const newGroup = builderFn(
            new StepGroupBuilder(this.contextualScope, this.bodyScope, [])
        ) as any;

        if (newGroup && typeof newGroup.getTasks === "function") {
            const results = newGroup.getTasks();
            return new StepsBuilder(
                this.contextualScope,
                this.inputsScope,
                results.scope,
                [...this.stepGroups, {steps: results.taskList}],
                this.outputsScope
            ) as AddStepGroupResult<R, ContextualScope, InputParamsScope, StepsScope, OutputParamsScope>;
        }

        // Propagate the error/other type to the callsite
        return newGroup as AddStepGroupResult<R, ContextualScope, InputParamsScope, StepsScope, OutputParamsScope>;
    }

    // Convenience method for single external step
    public addExternalStep<
        Name extends string,
        TWorkflow extends Workflow<any, any, any>,
        TKey extends Extract<keyof TWorkflow["templates"], string>,
        LoopT extends PlainObject = never,

        // Extract and normalize the Inputs type once
        IInputs extends InputParametersRecord =
            TWorkflow["templates"][TKey] extends { inputs: infer X }
                ? NormalizeInputs<X> extends InputParametersRecord ? NormalizeInputs<X> : {}
                : {}
    >(
        name: UniqueNameConstraintAtDeclaration<Name, StepsScope>,
        workflow: UniqueNameConstraintOutsideDeclaration<Name, StepsScope, TWorkflow>,
        key: UniqueNameConstraintOutsideDeclaration<Name, StepsScope, TKey>,

        // 🔑 3-way conditional parameter list:
        ...args: InputKind<IInputs> extends "empty"
            ? [opts?: TaskOpts<LoopT>] // no fields -> paramsFn not accepted/needed
            : InputKind<IInputs> extends "allOptional"
                ? [paramsFn?: UniqueNameConstraintOutsideDeclaration<
                    Name,
                    StepsScope,
                    ParamsRegistrationFn<StepsScope, IInputs, LoopT>
                >, opts?: TaskOpts<LoopT>] // fields exist but all optional -> paramsFn optional
                : [paramsFn: UniqueNameConstraintOutsideDeclaration<
                    Name,
                    StepsScope,
                    ParamsRegistrationFn<StepsScope, IInputs, LoopT>
                >, opts?: TaskOpts<LoopT>] // at least one required -> paramsFn required
    ): UniqueNameConstraintOutsideDeclaration<
        Name,
        StepsScope,
        StepsBuilder<
            ContextualScope,
            InputParamsScope,
            ExtendScope<StepsScope, {
                [K in Name]: TasksWithOutputs<Name, TWorkflow["templates"][TKey]["outputs"]>
            }>,
            OutputParamsScope
        >
    > {
        // Runtime branching identical to internal version:
        const [first, second] = args as [any, any];

        const paramsFn =
            typeof first === "function"
                ? first
                : ((_: unknown, register: (v: {}) => void) => register({})); // ok for empty/all-optional

        const opts = typeof first === "function" ? second : first;

        return this.addStepGroup(groupBuilder =>
            groupBuilder.addExternalTask(
                name,
                workflow,
                key,
                paramsFn as any,
                opts as TaskOpts<LoopT> | undefined
            )
        ) as any;
    }


    // Convenience method for single internal step
// ==== addInternalStep with tri-state conditional tuple ====

    public addInternalStep<
        Name extends string,
        TKey extends Extract<keyof ContextualScope["templates"], string>,
        LoopT extends PlainObject = never,
        // Normalize once for stable discrimination
        IInputs extends InputParametersRecord =
            NormalizeInputs<InputsOf<ContextualScope["templates"][TKey]>> extends InputParametersRecord
                ? NormalizeInputs<InputsOf<ContextualScope["templates"][TKey]>>
                : {}
    >(
        name: UniqueNameConstraintAtDeclaration<Name, StepsScope>,
        templateKey: UniqueNameConstraintOutsideDeclaration<Name, StepsScope, TKey>,

        // 3-way conditional on argument shape:
        ...args: InputKind<IInputs> extends "empty"
            ? [opts?: TaskOpts<LoopT>] // no fields at all -> paramsFn not accepted/needed
            : InputKind<IInputs> extends "allOptional"
                ? [paramsFn?: UniqueNameConstraintOutsideDeclaration<
                    Name, StepsScope, ParamsRegistrationFn<StepsScope, IInputs, LoopT>
                >, opts?: TaskOpts<LoopT>] // fields exist but all optional -> paramsFn optional
                : [paramsFn: UniqueNameConstraintOutsideDeclaration<
                    Name, StepsScope, ParamsRegistrationFn<StepsScope, IInputs, LoopT>
                >, opts?: TaskOpts<LoopT>] // at least one required -> paramsFn required
    ): UniqueNameConstraintOutsideDeclaration<
        Name,
        StepsScope,
        StepsBuilder<
            ContextualScope,
            InputParamsScope,
            ExtendScope<
                StepsScope,
                { [K in Name]: TasksWithOutputs<Name, OutputsOf<ContextualScope["templates"][TKey]>> }
            >,
            OutputParamsScope
        >
    > {
        // Runtime branching identical to before:
        const [first, second] = args as [any, any];

        const paramsFn =
            typeof first === "function"
                ? first
                : ((_: unknown, register: (v: {}) => void) => register({})); // okay for empty/all-optional

        const opts = typeof first === "function" ? second : first;

        return this.addStepGroup(groupBuilder =>
            groupBuilder.addInternalTask(
                name,
                templateKey,
                paramsFn as any,
                opts as TaskOpts<LoopT> | undefined
            )
        ) as any;
    }

    public addExpressionOutput<T extends PlainObject, Name extends string>(
        name: UniqueNameConstraintAtDeclaration<Name, OutputParamsScope>,
        expression: UniqueNameConstraintOutsideDeclaration<Name, OutputParamsScope, AllowLiteralOrExpression<T>>,
        descriptionValue?: string
    ):
        UniqueNameConstraintOutsideDeclaration<Name, OutputParamsScope,
            StepsBuilder<
                ContextualScope,
                InputParamsScope,
                StepsScope,
                ExtendScope<OutputParamsScope, { [K in Name]: OutputParamDef<T> }>>>
    {
        return new StepsBuilder(
            this.contextualScope,
            this.inputsScope,
            this.bodyScope,
            this.stepGroups,
            {
                ...this.outputsScope,
                [name as string]: {
                    fromWhere: "expression" as const,
                    expression,
                    description: descriptionValue
                }
            }
        ) as any;
    }

    getBody(): { body: { steps: StepGroup[] } } {
        // Steps are an ordered list of StepGroups in the final manifest.
        return {body: {steps: this.stepGroups}};
    }
}
