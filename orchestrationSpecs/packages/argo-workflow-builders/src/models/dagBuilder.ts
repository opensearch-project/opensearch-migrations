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
    InputParamsToExpressions,
    TasksOutputsScope,
    TasksWithOutputs,
    WorkflowAndTemplatesScope,
} from "./workflowTypes";
import {
    LabelledAllTasksAsOutputReferenceable,
    InputsFrom,
    KeyFor,
    OutputsFrom,
    ParamsTuple,
    TaskBuilder,
    TaskOpts,
    TaskRebinder
} from "./taskBuilder";
import {RetryParameters, TemplateBodyBuilder, TemplateRebinder} from "./templateBodyBuilder";
import {NonSerializedPlainObject, PlainObject} from "./plainObject";
import {UniqueNameConstraintAtDeclaration, UniqueNameConstraintOutsideDeclaration} from "./scopeConstraints";
import {NamedTask} from "./sharedTypes";

export type DagTaskOpts<TaskScope extends TasksOutputsScope, LoopT extends NonSerializedPlainObject> =
    TaskOpts<TasksOutputsScope, "tasks", LoopT> & { dependencies?: ReadonlyArray<Extract<keyof TaskScope, string>> };

function isDagTaskOpts<TaskScope extends TasksOutputsScope, LoopT extends NonSerializedPlainObject>(
    v: unknown
): v is DagTaskOpts<TasksOutputsScope, LoopT> {
    return !!v
        && typeof v === "object"
        && "dependencies" in (v as any)
        && (Array.isArray((v as any).dependencies) || (v as any).dependencies === undefined);
}

class DagTaskBuilder<
    ContextualScope extends WorkflowAndTemplatesScope,
    TaskScope extends TasksOutputsScope
> extends TaskBuilder<ContextualScope, TaskScope, "tasks", TaskRebinder<ContextualScope>> {
    protected readonly label = "tasks";

    protected onTaskPushed<
        LoopT extends NonSerializedPlainObject,
        OptsType extends TaskOpts<TaskScope, "tasks", LoopT>
    >(
        task: NamedTask, opts?: OptsType
    ): NamedTask {
        return {
            ...super.onTaskPushed(task, opts),
            ...((isDagTaskOpts<TaskScope, LoopT>(opts) && opts.dependencies?.length)
                ? {dependencies: opts.dependencies} : {})
        };
    }
}

// Define the expression context type for DagBuilder
type DagExpressionContext<
    InputParamsScope extends InputParametersRecord,
    TaskScope extends TasksOutputsScope
> = {
    inputs: InputParamsToExpressions<InputParamsScope>;
} & LabelledAllTasksAsOutputReferenceable<TaskScope, "tasks">;

export class DagBuilder<
    ContextualScope extends WorkflowAndTemplatesScope,
    InputParamsScope extends InputParametersRecord,
    TaskScope extends TasksOutputsScope,
    OutputParamsScope extends OutputParametersRecord
> extends TemplateBodyBuilder<
    ContextualScope,
    InputParamsScope,
    TaskScope,
    OutputParamsScope,
    DagBuilder<ContextualScope, InputParamsScope, TaskScope, any>, // Self
    TasksOutputsScope, // BodyBound
    DagExpressionContext<InputParamsScope, TaskScope>
> {
    private readonly taskBuilder: DagTaskBuilder<ContextualScope, TaskScope>;

    constructor(
        contextualScope: ContextualScope,
        inputs: InputParamsScope,
        bodyScope: TaskScope,
        orderedTasks: NamedTask[],
        outputs: OutputParamsScope,
        retryParameters: RetryParameters
    ) {
        // Trick: capture a mutable selfRef within a closure for use inside the rebinder
        let selfRef: DagBuilder<ContextualScope, InputParamsScope, any, any> | undefined;

        const templateRebind: TemplateRebinder<
            ContextualScope,
            InputParamsScope,
            TasksOutputsScope,
            DagExpressionContext<InputParamsScope, any>
        > = (ctx, inScope, body, outScope, retry: RetryParameters) => {
            const currentTasks =
                selfRef ? selfRef.taskBuilder.getTasks().taskList : orderedTasks;
            return new DagBuilder(
                ctx, inScope, body, currentTasks, outScope, retry
            ) as any;
        };

        super(contextualScope, inputs, bodyScope, outputs, retryParameters, templateRebind);

        // This rebinder produces a NEW DagBuilder with the NEW task scope when tasks change
        const tasksRebind: TaskRebinder<ContextualScope> =
            <NS extends TasksOutputsScope>(ctx: ContextualScope, scope: NS, tasks: NamedTask[]) =>
                new DagBuilder<ContextualScope, InputParamsScope, NS, OutputParamsScope>(
                    ctx, this.inputsScope, scope, tasks, this.outputsScope, this.retryParameters
                );

        this.taskBuilder = new DagTaskBuilder(contextualScope, bodyScope, orderedTasks, tasksRebind);

        // finalize selfRef after fields are initialized
        selfRef = this;
    }

    protected getExpressionBuilderContext(): DagExpressionContext<InputParamsScope, TaskScope> {
        return {
            inputs: this.inputs,
            tasks: this.taskBuilder.getTaskOutputsByTaskName()
        } as DagExpressionContext<InputParamsScope, TaskScope>;
    }

    public addTask<
        Name extends string,
        TemplateSource,
        K extends KeyFor<ContextualScope, TemplateSource>,
        LoopT extends NonSerializedPlainObject = never
    >(
        name: UniqueNameConstraintAtDeclaration<Name, TaskScope>,
        source: UniqueNameConstraintOutsideDeclaration<Name, TaskScope, TemplateSource>,
        key: UniqueNameConstraintOutsideDeclaration<Name, TaskScope, K>,
        ...args: ParamsTuple<
            InputsFrom<ContextualScope, TemplateSource, K>,
            Name,
            TaskScope,
            "tasks",
            LoopT,
            DagTaskOpts<TaskScope, LoopT>
        >
    ): UniqueNameConstraintOutsideDeclaration<
        Name,
        TaskScope,
        DagBuilder<
            ContextualScope,
            InputParamsScope,
            ExtendScope<
                TaskScope,
                { [P in Name]: TasksWithOutputs<Name, OutputsFrom<ContextualScope, TemplateSource, K>> }
            >,
            OutputParamsScope
        >
    > {
        return this.taskBuilder.addTask<Name, TemplateSource, K, LoopT, DagTaskOpts<TasksOutputsScope, LoopT>>(
            name, source, key, ...args
        );
    }

    protected getBody() {
        return {dag: this.taskBuilder.getTasks().taskList};
    }
}