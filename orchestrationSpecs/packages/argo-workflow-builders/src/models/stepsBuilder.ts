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
import {RetryableTemplateBodyBuilder, RetryableTemplateRebinder} from "./templateBodyBuilder";
import {UniqueNameConstraintAtDeclaration, UniqueNameConstraintOutsideDeclaration} from "./scopeConstraints";
import {NonSerializedPlainObject, PlainObject} from "./plainObject";
import {Workflow} from "./workflowBuilder";
import {
    LabelledAllTasksAsOutputReferenceable,
    getTaskOutputsByTaskName,
    INLINE,
    INTERNAL,
    InlineInputsFrom,
    InlineOutputsFrom,
    InlineTemplateFn,
    InputsFrom,
    KeyFor,
    OutputsFrom,
    ParamsTuple,
    TaskBuilder, TaskOpts,
    TaskRebinder
} from "./taskBuilder";
import {NamedTask} from "./sharedTypes";
import {SynchronizationConfig} from "./synchronization";

export interface StepGroup {
    steps: NamedTask[];
}

class StepGroupBuilder<
    ParentWorkflowScope extends WorkflowAndTemplatesScope,
    TasksScope extends TasksOutputsScope
> extends TaskBuilder<
    ParentWorkflowScope,
    TasksScope,
    "steps",
    TaskRebinder<ParentWorkflowScope>
    > {
    protected readonly label = "steps";

    constructor(
        parentWorkflowScope: ParentWorkflowScope,
        tasksScope: TasksScope,
        tasks: NamedTask[]
    ) {
        const templateRebind: TaskRebinder<ParentWorkflowScope> =
            <NS extends TasksOutputsScope>(ctx: ParentWorkflowScope, scope: NS, t: NamedTask[]) =>
                new StepGroupBuilder<ParentWorkflowScope, NS>(ctx, scope, t);
        super(parentWorkflowScope, tasksScope, tasks, templateRebind);
    }

    public addStep<
        Name extends string,
        TemplateSource extends typeof INTERNAL | Workflow<any, any, any>,
        K extends KeyFor<ParentWorkflowScope, TemplateSource>,
        LoopT extends NonSerializedPlainObject = never
    >(
        name: UniqueNameConstraintAtDeclaration<Name, TasksScope>,
        source: UniqueNameConstraintOutsideDeclaration<Name, TasksScope, TemplateSource>,
        key: UniqueNameConstraintOutsideDeclaration<Name, TasksScope, K>,
        ...args: ParamsTuple<
            InputsFrom<ParentWorkflowScope, TemplateSource, K>,
            Name,
            TasksScope,
            "steps",
            LoopT,
            TaskOpts<TasksScope, "steps", LoopT>>
    ): StepGroupBuilder<
        ParentWorkflowScope,
        ExtendScope<
            TasksScope,
            { [P in Name]: TasksWithOutputs<Name, OutputsFrom<ParentWorkflowScope, TemplateSource, K>> }
        >
    > {
        return this.addTask<Name, TemplateSource, K, LoopT, TaskOpts<TasksScope, "steps", LoopT>>(
            name, source, key, ...args
        );
    }
}

// Define the expression context type for StepsBuilder
type StepsExpressionContext<
    InputParamsScope extends InputParametersRecord,
    StepsScope extends TasksOutputsScope
> = {
    inputs: InputParamsToExpressions<InputParamsScope>;
} & LabelledAllTasksAsOutputReferenceable<StepsScope, "steps">;

export class StepsBuilder<
    ParentWorkflowScope extends WorkflowAndTemplatesScope,
    InputParamsScope extends InputParametersRecord,
    StepsScope extends TasksOutputsScope,
    OutputParamsScope extends OutputParametersRecord
> extends RetryableTemplateBodyBuilder<
    ParentWorkflowScope,
    InputParamsScope,
    StepsScope,
    OutputParamsScope,
    StepsBuilder<ParentWorkflowScope, InputParamsScope, StepsScope, any>,
    TasksOutputsScope,
    StepsExpressionContext<InputParamsScope, StepsScope>
> {
    constructor(
        public readonly parentWorkflowScope: ParentWorkflowScope,
        inputsScope: InputParamsScope,
        public readonly bodyScope: StepsScope,
        readonly stepGroups: StepGroup[],
        public readonly outputsScope: OutputParamsScope,
        retryParameters: GenericScope,
        public readonly synchronization: SynchronizationConfig | undefined
    ) {
        const templateRebind: RetryableTemplateRebinder<
            ParentWorkflowScope,
            InputParamsScope,
            // This builder only exposes and needs to expose its task outputs, not the whole body.
            // That's why we ONLY bind the body to the task outputs
            TasksOutputsScope,
            StepsExpressionContext<InputParamsScope, StepsScope>
        > = (ctx, inputs, body, outputs, retry, synchronization) =>
            new StepsBuilder(ctx, inputs, body, this.stepGroups, outputs, retry, synchronization) as any;

        super(parentWorkflowScope, inputsScope, bodyScope, outputsScope, retryParameters, synchronization, templateRebind);
    }

    protected getExpressionBuilderContext(): StepsExpressionContext<InputParamsScope, StepsScope> {
        return {
            inputs: this.inputs,
            steps: getTaskOutputsByTaskName(this.bodyScope, "steps")
        } as StepsExpressionContext<InputParamsScope, StepsScope>;
    }

    /**
     * Adds one parallel step group (one inner `steps` array in Argo).
     * The callback must return StepGroupBuilder to keep strong typing
     * for chained step additions and step-output references.
     */
    public addStepGroup<NewStepsScope extends TasksOutputsScope>(
        builderFn: (
            groupBuilder: StepGroupBuilder<ParentWorkflowScope, StepsScope>
        ) => StepGroupBuilder<ParentWorkflowScope, NewStepsScope>
    ): StepsBuilder<ParentWorkflowScope, InputParamsScope, NewStepsScope, OutputParamsScope> {
        const newGroup = builderFn(
            new StepGroupBuilder(this.parentWorkflowScope, this.bodyScope, [])
        );
        const results = newGroup.getTasks();
        return new StepsBuilder(
            this.parentWorkflowScope,
            this.inputsScope,
            results.scope,
            [...this.stepGroups, {steps: results.taskList}],
            this.outputsScope,
            this.retryParameters,
            this.synchronization
        ) as any;
    }

    // Convenience method for single step
    public addStep<
        Name extends string,
        TemplateSource extends typeof INTERNAL | Workflow<any, any, any>,
        K extends KeyFor<ParentWorkflowScope, TemplateSource>,
        LoopT extends NonSerializedPlainObject = never
    >(
        name: UniqueNameConstraintAtDeclaration<Name, StepsScope>,
        source: UniqueNameConstraintOutsideDeclaration<Name, StepsScope, TemplateSource>,
        key: UniqueNameConstraintOutsideDeclaration<Name, StepsScope, K>,
        ...args: ParamsTuple<
            InputsFrom<ParentWorkflowScope, TemplateSource, K>,
            Name,
            StepsScope,
            "steps",
            LoopT,
            TaskOpts<StepsScope, "steps", LoopT>>
    ): StepsBuilder<
        ParentWorkflowScope,
        InputParamsScope,
        ExtendScope<
            StepsScope,
            { [P in Name]: TasksWithOutputs<Name, OutputsFrom<ParentWorkflowScope, TemplateSource, K>> }
        >,
        OutputParamsScope
    >;
    public addStep<
        Name extends string,
        InlineFnType extends InlineTemplateFn<ParentWorkflowScope>,
        LoopT extends NonSerializedPlainObject = never
    >(
        name: UniqueNameConstraintAtDeclaration<Name, StepsScope>,
        source: UniqueNameConstraintOutsideDeclaration<Name, StepsScope, typeof INLINE>,
        inlineFn: UniqueNameConstraintOutsideDeclaration<Name, StepsScope, InlineFnType>,
        ...args: ParamsTuple<InlineInputsFrom<InlineFnType>, Name, StepsScope, "steps", LoopT, TaskOpts<StepsScope, "steps", LoopT>>
    ): StepsBuilder<
        ParentWorkflowScope,
        InputParamsScope,
        ExtendScope<StepsScope, { [P in Name]: TasksWithOutputs<Name, InlineOutputsFrom<InlineFnType>> }>,
        OutputParamsScope
    >;
    public addStep(name: any, source: any, keyOrFn?: any, ...restArgs: any[]): any {
        return this.addStepGroup(gb => (gb as any).addTask(name, source, keyOrFn, ...restArgs));
    }

    /**
     * Made public because waitForResourceBuilder uses this to make its own body.
     */
    public getBody() {
        return {steps: this.stepGroups};
    }
}
