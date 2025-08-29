import {
    InputParametersRecord,
    OutputParametersRecord, paramsToCallerSchema
} from "@/schemas/parameterSchemas";
import {
    LoopWithUnion,
    ParamsWithLiteralsOrExpressions,
    TasksOutputsScope, TasksScopeToTasksWithOutputs,
    WorkflowAndTemplatesScope,
} from "@/schemas/workflowTypes";

import {NamedTask, ParamsFromContextFn, TaskBuilder, TaskBuilderFactory, WithScope} from "@/schemas/taskBuilder";
import {TemplateBodyBuilder} from "@/schemas/templateBodyBuilder";
import {PlainObject} from "@/schemas/plainObject";
import {UniqueNameConstraintAtDeclaration, UniqueNameConstraintOutsideDeclaration} from "@/schemas/scopeConstraints";
import {z} from "zod/index";
import {SimpleExpression} from "@/schemas/expression";

class DagFactory<
    ContextualScope extends WorkflowAndTemplatesScope,
    InputParamsScope  extends InputParametersRecord,
    OutputParamsScope extends OutputParametersRecord
>
    implements TaskBuilderFactory<ContextualScope, DagBuilder<ContextualScope, InputParamsScope, any, OutputParamsScope>>
{
    constructor(
        public readonly inputsScope: InputParamsScope,
        public readonly outputsScope: OutputParamsScope
    ) {}

    create<NS extends TasksOutputsScope>(
        context: ContextualScope,
        scope: NS,
        tasks: NamedTask[]
    ): WithScope<DagBuilder<ContextualScope, InputParamsScope, any, OutputParamsScope>, NS> {
        return new DagBuilder(context, this.inputsScope, scope, tasks, this.outputsScope) as WithScope<
            DagBuilder<ContextualScope, InputParamsScope, NS, OutputParamsScope>,
            NS
        >;
    }
}

export class DagBuilder<
    ContextualScope extends WorkflowAndTemplatesScope,
    InputParamsScope extends InputParametersRecord,
    TaskScope extends TasksOutputsScope,
    OutputParamsScope extends OutputParametersRecord
> extends TemplateBodyBuilder<ContextualScope, "dag", InputParamsScope, TaskScope, OutputParamsScope, any> {
    private readonly taskBuilder: TaskBuilder<ContextualScope, TaskScope, any, any>;
    public addExternalTask!: OmitThisParameter<
        TaskBuilder<ContextualScope, TaskScope, any, any>["addExternalTask"]
    >;
    public addInternalTask<
        Name extends string,
        TKey extends Extract<keyof ContextualScope["templates"], string>,
        TTemplate extends ContextualScope["templates"][TKey],
        TInput extends TTemplate extends { input: infer I }
            ? I extends InputParametersRecord ? I : InputParametersRecord
            : InputParametersRecord,
        TOutput extends TTemplate extends { output: infer O }
            ? O extends OutputParametersRecord ? O : {}
            : {},
        LoopT extends PlainObject = never
    >(
        name: UniqueNameConstraintAtDeclaration<Name, TaskScope>,
        templateKey: UniqueNameConstraintOutsideDeclaration<Name, TaskScope, TKey>,
        paramsFn: UniqueNameConstraintOutsideDeclaration<Name, TaskScope,
            (tasks: TasksScopeToTasksWithOutputs<TaskScope, LoopT>) =>
                ParamsWithLiteralsOrExpressions<z.infer<ReturnType<typeof paramsToCallerSchema<TInput>>>>
        >,
        loopWith?: LoopWithUnion<LoopT>,
        when?: SimpleExpression<boolean>
    ) {
        return this.taskBuilder.addInternalTask(name, templateKey, paramsFn, loopWith, when);
    }

    constructor(
        contextualScope: ContextualScope,
        inputs: InputParamsScope,
        bodyScope: TaskScope,
        orderedTasks: NamedTask[],
        outputs: OutputParamsScope
    ) {
        super("dag" as const, contextualScope, inputs, bodyScope, outputs);
        this.taskBuilder =
            new TaskBuilder(contextualScope, bodyScope, orderedTasks, new DagFactory(inputs, outputs));

        this.addExternalTask = this.taskBuilder.addExternalTask.bind(this.taskBuilder);
        this.addInternalTask = this.taskBuilder.addInternalTask.bind(this.taskBuilder);
    }


    getTasks() {
        return this.taskBuilder.getTasks();
    }
}