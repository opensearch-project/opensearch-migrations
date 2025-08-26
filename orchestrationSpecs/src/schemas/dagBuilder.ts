import {
    InputParametersRecord,
    OutputParamDef,
    OutputParametersRecord
} from "@/schemas/parameterSchemas";
import {
    ExtendScope,
    TasksOutputsScope,
    WorkflowAndTemplatesScope,
} from "@/schemas/workflowTypes";
import { z, ZodType } from "zod";
import { PlainObject } from "@/schemas/plainObject";
import {NamedTask, TaskBuilder, TaskBuilderFactory, WithScope} from "@/schemas/taskBuilder";

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
    InputParamsScope  extends InputParametersRecord,
    TasksScope extends TasksOutputsScope,
    OutputParamsScope extends OutputParametersRecord
> extends TaskBuilder<
    ContextualScope,
    TasksScope,
    DagBuilder<ContextualScope, InputParamsScope, TasksScope, OutputParamsScope>,
    DagFactory<ContextualScope, InputParamsScope, OutputParamsScope>
> {
    constructor(
        public readonly contextualScope: ContextualScope,
        public readonly inputsScope: InputParamsScope,
        public readonly tasksScope: TasksScope,
        public readonly bodyScope: NamedTask[],
        public readonly outputsScope: OutputParamsScope
    ) {
        super(contextualScope, tasksScope, bodyScope,
            new DagFactory<ContextualScope, InputParamsScope, OutputParamsScope>(inputsScope, outputsScope));
    }

    addParameterOutput<T extends PlainObject, Name extends string>(
        name: Name,
        parameter: string,
        t: ZodType<T>,
        descriptionValue?: string
    ):
        DagBuilder<
            ContextualScope,
            InputParamsScope,
            TasksScope,
            ExtendScope<OutputParamsScope, { [K in Name]: OutputParamDef<T> }>
        > {
        return new DagBuilder(
            this.contextualScope,
            this.inputsScope,
            this.tasksScope,
            this.bodyScope,
            {
                ...this.outputsScope,
                [name as string]: {
                    type: t,
                    fromWhere: "parameter" as const,
                    parameter,
                    description: descriptionValue
                }
            }
        );
    }

    addExpressionOutput<T extends PlainObject, Name extends string>(
        name: Name,
        expression: string,
        t: ZodType<T>,
        descriptionValue?: string
    ):
        DagBuilder<
            ContextualScope,
            InputParamsScope,
            TasksScope,
            ExtendScope<OutputParamsScope, { [K in Name]: OutputParamDef<T> }>
        > {
        return new DagBuilder(
            this.contextualScope,
            this.inputsScope,
            this.tasksScope,
            this.bodyScope,
            {
                ...this.outputsScope,
                [name as string]: {
                    type: t,
                    fromWhere: "expression" as const,
                    expression,
                    description: descriptionValue
                }
            }
        );
    }
}