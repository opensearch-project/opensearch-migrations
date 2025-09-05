import {InputParametersRecord, OutputParamDef, OutputParametersRecord} from "@/schemas/parameterSchemas";
import {
    ExtendScope,
    TasksOutputsScope,
    TasksWithOutputs, WorkflowAndTemplatesScope,
} from "@/schemas/workflowTypes";
import {
    InputsFrom,
    KeyFor,
    NamedTask,
    OutputsFrom,
    ParamsTuple,
    TaskBuilder,
    TaskOpts,
    TaskRebinder
} from "@/schemas/taskBuilder";
import { TemplateBodyBuilder } from "@/schemas/templateBodyBuilder";
import { PlainObject } from "@/schemas/plainObject";
import { UniqueNameConstraintAtDeclaration, UniqueNameConstraintOutsideDeclaration } from "@/schemas/scopeConstraints";
import {SimpleExpression, stepOutput, taskOutput} from "@/schemas/expression";

export type DagTaskOpts<TaskScope extends TasksOutputsScope, LoopT extends PlainObject> = TaskOpts<LoopT> & {
    dependencies?: ReadonlyArray<Extract<keyof TaskScope, string>>
}

function isDagTaskOpts<TaskScope extends TasksOutputsScope, LoopT extends PlainObject>(
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
> extends TaskBuilder<ContextualScope, TaskScope, TaskRebinder<ContextualScope>> {
    protected onTaskPushed<LoopT extends PlainObject, OptsType extends TaskOpts<LoopT>>(
        task: NamedTask, opts?: OptsType
    ): NamedTask {
        return {
            ...super.onTaskPushed(task),
            ...((isDagTaskOpts<TaskScope, LoopT>(opts) && opts.dependencies?.length)
                ? { dependencies: opts.dependencies } : {})
        };
    }

    protected getTaskOutputAsExpression<T extends PlainObject>(
        taskName: string, outputName: string, outputParamDef: OutputParamDef<any>
    ): SimpleExpression<T> {
        return taskOutput(taskName, outputName, outputParamDef);
    }
}

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
    any
> {
    private readonly taskBuilder: DagTaskBuilder<ContextualScope, TaskScope>;

    constructor(
        contextualScope: ContextualScope,
        inputs: InputParamsScope,
        bodyScope: TaskScope,
        orderedTasks: NamedTask[],
        outputs: OutputParamsScope
    ) {
        super(contextualScope, inputs, bodyScope, outputs);

        // This rebinder produces a NEW DagBuilder with the NEW scope NS
        const rebind: TaskRebinder<ContextualScope> =
            <NS extends TasksOutputsScope>(ctx: ContextualScope, scope: NS, tasks: NamedTask[]) =>
                new DagBuilder<ContextualScope, InputParamsScope, NS, OutputParamsScope>(
                    ctx, this.inputsScope, scope, tasks, this.outputsScope
                );

        this.taskBuilder = new DagTaskBuilder(contextualScope, bodyScope, orderedTasks, rebind);
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
        ...args: ParamsTuple<InputsFrom<ContextualScope, TemplateSource, K>, Name, TaskScope, LoopT, DagTaskOpts<TaskScope, LoopT>>
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

    protected getBody() {
        return { dag: this.taskBuilder.getTasks().taskList };
    }
}