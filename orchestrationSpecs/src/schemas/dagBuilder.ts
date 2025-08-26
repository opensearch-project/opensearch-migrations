import {
    InputParametersRecord,
    OutputParametersRecord
} from "@/schemas/parameterSchemas";
import {
    TasksOutputsScope,
    WorkflowAndTemplatesScope,
} from "@/schemas/workflowTypes";

import {NamedTask, ParamsFromContextFn, TaskBuilder, TaskBuilderFactory, WithScope} from "@/schemas/taskBuilder";
import {TemplateBodyBuilder} from "@/schemas/templateBodyBuilder";

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
    TasksScope extends TasksOutputsScope,
    OutputParamsScope extends OutputParametersRecord
> extends TemplateBodyBuilder<ContextualScope, "dag", InputParamsScope, TasksScope, OutputParamsScope, any> {
    private readonly taskBuilder: TaskBuilder<ContextualScope, TasksScope, any, any>;
    private addExternalTask!: OmitThisParameter<
        TaskBuilder<ContextualScope, TasksScope, any, any>["addExternalTask"]
    >;
    private addInternalTask: OmitThisParameter<
        TaskBuilder<ContextualScope, TasksScope, any, any>["addInternalTask"]
    >;

    constructor(
        contextualScope: ContextualScope,
        inputs: InputParamsScope,
        bodyScope: TasksScope,
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