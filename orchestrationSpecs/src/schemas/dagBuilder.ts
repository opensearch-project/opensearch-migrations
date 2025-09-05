// dagBuilder.ts
import { CallerParams, InputParametersRecord, OutputParametersRecord } from "@/schemas/parameterSchemas";
import {
    ExtendScope,
    LoopWithUnion, ParamsWithLiteralsOrExpressions, TasksOutputsScope,
    TasksScopeToTasksWithOutputs, TasksWithOutputs, WorkflowAndTemplatesScope,
} from "@/schemas/workflowTypes";
import {
    InputsOf,
    NamedTask,
    OutputsOf,
    ParamsRegistrationFn,
    TaskBuilder,
    TaskOpts,
    TaskRebinder
} from "@/schemas/taskBuilder";
import { TemplateBodyBuilder } from "@/schemas/templateBodyBuilder";
import { PlainObject } from "@/schemas/plainObject";
import { UniqueNameConstraintAtDeclaration, UniqueNameConstraintOutsideDeclaration } from "@/schemas/scopeConstraints";
import {Workflow} from "@/schemas/workflowBuilder";

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

    public addExternalTask<
        Name extends string,
        TWorkflow extends Workflow<any, any, any>,
        TKey extends Extract<keyof TWorkflow["templates"], string>,
        LoopT extends PlainObject = never
    >(
        name: UniqueNameConstraintAtDeclaration<Name, TaskScope>,
        workflow: UniqueNameConstraintOutsideDeclaration<Name, TaskScope, TWorkflow>,
        key: UniqueNameConstraintOutsideDeclaration<Name, TaskScope, TKey>,
        paramsFn: UniqueNameConstraintOutsideDeclaration<
            Name,
            TaskScope,
            ParamsRegistrationFn<TaskScope, TWorkflow["templates"][TKey]["inputs"], LoopT>
        >,
        opts?: DagTaskOpts<TaskScope, LoopT>
    ): UniqueNameConstraintOutsideDeclaration<
        Name,
        TaskScope,
        DagBuilder<
            ContextualScope,
            InputParamsScope,
            ExtendScope<
                TaskScope,
                { [K in Name]: TasksWithOutputs<Name, TWorkflow["templates"][TKey]["outputs"]> }
            >,
            OutputParamsScope
        >
    > {
        return this.taskBuilder.addExternalTask(name, workflow, key, paramsFn, opts);
    }

    public addInternalTask<
        Name extends string,
        TKey extends Extract<keyof ContextualScope["templates"], string>,
        TTemplate extends ContextualScope["templates"][TKey],
        LoopT extends PlainObject = never
    >(
        name: UniqueNameConstraintAtDeclaration<Name, TaskScope>,
        templateKey: UniqueNameConstraintOutsideDeclaration<Name, TaskScope, TKey>,
        paramsFn: UniqueNameConstraintOutsideDeclaration<
            Name,
            TaskScope,
            ParamsRegistrationFn<TaskScope, InputsOf<TTemplate>, LoopT>
        >,
        opts?: DagTaskOpts<TaskScope, LoopT>
    ): UniqueNameConstraintOutsideDeclaration<
        Name,
        TaskScope,
        DagBuilder<
            ContextualScope,
            InputParamsScope,
            ExtendScope<TaskScope, { [K in Name]: TasksWithOutputs<Name, OutputsOf<TTemplate>> }>,
            OutputParamsScope
        >
    > {
        return this.taskBuilder.addInternalTask(name, templateKey, paramsFn, opts);
    }

    getTasks() {
        return this.taskBuilder.getTasks();
    }
}
