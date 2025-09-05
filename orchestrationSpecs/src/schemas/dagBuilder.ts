import { InputParametersRecord, OutputParametersRecord } from "@/schemas/parameterSchemas";
import {
    ExtendScope,
    TasksOutputsScope,
    TasksWithOutputs, WorkflowAndTemplatesScope,
} from "@/schemas/workflowTypes";
import {
    InputsFrom
    , KeyFor,
    NamedTask, OutputsFrom,
    ParamsTuple,
    TaskBuilder,
    TaskOpts,
    TaskRebinder
} from "@/schemas/taskBuilder";
import { TemplateBodyBuilder } from "@/schemas/templateBodyBuilder";
import { PlainObject } from "@/schemas/plainObject";
import { UniqueNameConstraintAtDeclaration, UniqueNameConstraintOutsideDeclaration } from "@/schemas/scopeConstraints";

export type DagTaskOpts<TaskScope extends TasksOutputsScope, LoopT extends PlainObject> = TaskOpts<LoopT> & {
    dependencies?: ReadonlyArray<Extract<keyof TaskScope, string>>
}

export class DagBuilder<
    ContextualScope extends WorkflowAndTemplatesScope,
    InputParamsScope extends InputParametersRecord,
    TaskScope extends TasksOutputsScope,
    OutputParamsScope extends OutputParametersRecord
> extends TemplateBodyBuilder<
    ContextualScope,
    "dag",
    InputParamsScope,
    TaskScope,
    OutputParamsScope,
    any
> {
    private readonly taskBuilder: TaskBuilder<
        ContextualScope,
        TaskScope,
        TaskRebinder<ContextualScope>
    >;

    constructor(
        contextualScope: ContextualScope,
        inputs: InputParamsScope,
        bodyScope: TaskScope,
        orderedTasks: NamedTask[],
        outputs: OutputParamsScope
    ) {
        super("dag" as const, contextualScope, inputs, bodyScope, outputs);

        // This rebinder produces a NEW DagBuilder with the NEW scope NS
        const rebind: TaskRebinder<ContextualScope> =
            <NS extends TasksOutputsScope>(ctx: ContextualScope, scope: NS, tasks: NamedTask[]) =>
                new DagBuilder<ContextualScope, InputParamsScope, NS, OutputParamsScope>(
                    ctx, this.inputsScope, scope, tasks, this.outputsScope
                );

        this.taskBuilder = new TaskBuilder(contextualScope, bodyScope, orderedTasks, rebind);
    }

    public addTask<
        Name extends string,
        TemplateSource,
        K extends KeyFor<ContextualScope, TemplateSource>,
        LoopT extends PlainObject = never
    >(
        name: UniqueNameConstraintAtDeclaration<Name, TaskScope>,
        source: UniqueNameConstraintOutsideDeclaration<Name, TaskScope, TemplateSource>,
        key: UniqueNameConstraintOutsideDeclaration<Name, TaskScope, K>,
        ...args: ParamsTuple<InputsFrom<ContextualScope, TemplateSource, K>, Name, TaskScope, LoopT>
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
        return this.taskBuilder.addTask<Name, TemplateSource, K, LoopT>(name, source, key, ...args);
    }

    getTasks() {
        return this.taskBuilder.getTasks();
    }
}
