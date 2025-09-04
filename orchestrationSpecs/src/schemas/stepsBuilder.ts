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
import {InputsOf, NamedTask, OutputsOf, TaskBuilder, TaskOpts, TaskRebinder} from "@/schemas/taskBuilder";

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
        LoopT extends PlainObject = never
    >(
        name: UniqueNameConstraintAtDeclaration<Name, StepsScope>,
        workflow: UniqueNameConstraintOutsideDeclaration<Name, StepsScope, TWorkflow>,
        key: UniqueNameConstraintOutsideDeclaration<Name, StepsScope, TKey>,
        paramsFn: UniqueNameConstraintOutsideDeclaration<
            Name,
            StepsScope,
            (steps: TasksScopeToTasksWithOutputs<StepsScope, LoopT>) =>
                ParamsWithLiteralsOrExpressions<CallerParams<TWorkflow["templates"][TKey]["inputs"]>>
        >,
        opts?: TaskOpts<LoopT>
    ): UniqueNameConstraintOutsideDeclaration<
        Name,
        StepsScope,
        StepsBuilder<
            ContextualScope,
            InputParamsScope,
            ExtendScope<
                StepsScope,
                { [K in Name]: TasksWithOutputs<Name, TWorkflow["templates"][TKey]["outputs"]> }
            >,
            OutputParamsScope
        >
    > {
        return this.addStepGroup(groupBuilder =>
            groupBuilder.addExternalTask(name, workflow, key, paramsFn, opts)
        ) as any;
    }

    // Convenience method for single internal step
    public addInternalStep<
        Name extends string,
        TKey extends Extract<keyof ContextualScope["templates"], string>,
        LoopT extends PlainObject = never
    >(
        name: UniqueNameConstraintAtDeclaration<Name, StepsScope>,
        templateKey: UniqueNameConstraintOutsideDeclaration<Name, StepsScope, TKey>,
        paramsFn: UniqueNameConstraintOutsideDeclaration<
            Name,
            StepsScope,
            (steps: TasksScopeToTasksWithOutputs<StepsScope, LoopT>) =>
                ParamsWithLiteralsOrExpressions<CallerParams<InputsOf<ContextualScope["templates"][TKey]>>>
        >,
        opts?: TaskOpts<LoopT>
    ): UniqueNameConstraintOutsideDeclaration<
        Name,
        StepsScope,
        StepsBuilder<
            ContextualScope,
            InputParamsScope,
            ExtendScope<StepsScope, { [K in Name]: TasksWithOutputs<Name, OutputsOf<ContextualScope["templates"][TKey]>> }>,
            OutputParamsScope
        >
    > {
        return this.addStepGroup(groupBuilder =>
            groupBuilder.addInternalTask(name, templateKey, paramsFn, opts)
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
