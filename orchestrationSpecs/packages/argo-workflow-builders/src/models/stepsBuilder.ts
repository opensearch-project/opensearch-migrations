/**
 * DESIGN PRINCIPLE: ERGONOMIC AND INTUITIVE API
 *
 * This schema system is designed to provide an intuitive, ergonomic developer experience.
 * Users should NEVER need to use explicit type casts (as any, as string, etc.) or
 * cumbersome workarounds to make the type system work. If the API requires such casts,
 * the type system implementation needs to be improved, not the caller code.
 *
 * The goal is to make template building feel natural and safe, with proper type inference
 * working automatically without forcing developers to manually specify types.
 */

import {InputParametersRecord, OutputParametersRecord} from "./parameterSchemas";
import {
    ExtendScope,
    GenericScope,
    InputParamsToExpressions,
    TasksOutputsScope,
    TasksWithOutputs,
    WorkflowAndTemplatesScope
} from "./workflowTypes";
import {TemplateBodyBuilder} from "./templateBodyBuilder";
import {UniqueNameConstraintAtDeclaration, UniqueNameConstraintOutsideDeclaration} from "./scopeConstraints";
import {NonSerializedPlainObject, PlainObject} from "./plainObject";
import {
    LabelledAllTasksAsOutputReferenceable,
    getTaskOutputsByTaskName,
    InputsFrom,
    KeyFor,
    OutputsFrom,
    ParamsTuple,
    TaskBuilder, TaskOpts,
    TaskRebinder
} from "./taskBuilder";
import {NamedTask} from "./sharedTypes";

export interface StepGroup {
    steps: NamedTask[];
}

class StepGroupBuilder<
    ContextualScope extends WorkflowAndTemplatesScope,
    TasksScope extends TasksOutputsScope
> extends TaskBuilder<
    ContextualScope,
    TasksScope,
    "steps",
    TaskRebinder<ContextualScope>
> {
    protected readonly label = "steps";

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

type BuilderLike = { getTasks(): { scope: any; taskList: NamedTask[] } };

// Define the expression context type for StepsBuilder
type StepsExpressionContext<
    InputParamsScope extends InputParametersRecord,
    StepsScope extends TasksOutputsScope
> = {
    inputs: InputParamsToExpressions<InputParamsScope>;
} & LabelledAllTasksAsOutputReferenceable<StepsScope, "steps">;

export class StepsBuilder<
    ContextualScope extends WorkflowAndTemplatesScope,
    InputParamsScope extends InputParametersRecord,
    StepsScope extends TasksOutputsScope,
    OutputParamsScope extends OutputParametersRecord
> extends TemplateBodyBuilder<
    ContextualScope,
    InputParamsScope,
    StepsScope,
    OutputParamsScope,
    StepsBuilder<ContextualScope, InputParamsScope, StepsScope, any>,
    TasksOutputsScope, // <-- BodyBound for this subclass
    StepsExpressionContext<InputParamsScope, StepsScope>
> {
    constructor(
        public readonly contextualScope: ContextualScope,
        inputsScope: InputParamsScope,
        public readonly bodyScope: StepsScope,
        readonly stepGroups: StepGroup[],
        public readonly outputsScope: OutputParamsScope,
        protected readonly retryParameters: GenericScope
    ) {
        const rebind = <
            NewBodyScope extends TasksOutputsScope,
            NewOutputScope extends OutputParametersRecord,
            Self extends TemplateBodyBuilder<any, any, any, any, any, any, any>
        >(
            ctx: ContextualScope,
            inputs: InputParamsScope,
            body: NewBodyScope,
            outputs: NewOutputScope,
            retry: GenericScope,
        ): Self => {
            return new StepsBuilder(ctx, inputs, body, this.stepGroups, outputs, retry) as any;
        };

        super(contextualScope, inputsScope, bodyScope, outputsScope, retryParameters, rebind);
    }

    protected getExpressionBuilderContext(): StepsExpressionContext<InputParamsScope, StepsScope> {
        return {
            inputs: this.inputs,
            steps: getTaskOutputsByTaskName(this.bodyScope, "steps")
        } as StepsExpressionContext<InputParamsScope, StepsScope>;
    }

    /**
     * Accept **any** builder-like result that has getTasks().
     */
    public addStepGroup<R>(
        builderFn: (groupBuilder: StepGroupBuilder<ContextualScope, StepsScope>) => R
    ): R extends BuilderLike
        ? StepsBuilder<ContextualScope, InputParamsScope, ReturnType<R["getTasks"]>["scope"], OutputParamsScope>
        : R
    {
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
                this.outputsScope,
                this.retryParameters
            ) as any;
        } else {
            return newGroup; // Propagate the error object to the callsite
        }
    }

    // Convenience method for single step
    public addStep<
        Name extends string,
        TemplateSource,
        K extends KeyFor<ContextualScope, TemplateSource>,
        LoopT extends NonSerializedPlainObject = never
    >(
        name: UniqueNameConstraintAtDeclaration<Name, StepsScope>,
        source: UniqueNameConstraintOutsideDeclaration<Name, StepsScope, TemplateSource>,
        key: UniqueNameConstraintOutsideDeclaration<Name, StepsScope, K>,
        ...args: ParamsTuple<
            InputsFrom<ContextualScope, TemplateSource, K>,
            Name,
            StepsScope,
            "steps",
            LoopT,
            TaskOpts<StepsScope, "steps", LoopT>>
    ): StepsBuilder<
        ContextualScope,
        InputParamsScope,
        ExtendScope<
            StepsScope,
            { [P in Name]: TasksWithOutputs<Name, OutputsFrom<ContextualScope, TemplateSource, K>> }
        >,
        OutputParamsScope
    > {
        return this.addStepGroup(gb => {
            return gb.addTask<Name, TemplateSource, K, LoopT, TaskOpts<StepsScope, "steps", LoopT>>(
                name, source, key, ...args
            );
        }) as any;
    }

    protected getBody() {
        return {steps: this.stepGroups};
    }
}