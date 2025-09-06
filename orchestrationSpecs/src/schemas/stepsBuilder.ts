import {InputParametersRecord, OutputParamDef, OutputParametersRecord,} from "@/schemas/parameterSchemas";
import {
    AllowLiteralOrExpression,
    ExtendScope,
    TasksOutputsScope,
    TasksWithOutputs,
    WorkflowAndTemplatesScope
} from "@/schemas/workflowTypes";
import {TemplateBodyBuilder} from "@/schemas/templateBodyBuilder";
import {UniqueNameConstraintAtDeclaration, UniqueNameConstraintOutsideDeclaration} from "@/schemas/scopeConstraints";
import {PlainObject} from "@/schemas/plainObject";
import {
    InputsFrom, KeyFor, KeyMustMatch,
    NamedTask,
    OutputsFrom,
    ParamsTuple,
    TaskBuilder,
    TaskRebinder,
} from "@/schemas/taskBuilder";
import {SimpleExpression, stepOutput} from "@/schemas/expression";

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

    protected getTaskOutputAsExpression<T extends PlainObject>(
        taskName: string, outputName: string, outputParamDef: OutputParamDef<any>
    ): SimpleExpression<T> {
        return stepOutput(taskName, outputName, outputParamDef);
    }
}

type BuilderLike = { getTasks(): { scope: any; taskList: NamedTask[] } };

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
    StepsBuilder<ContextualScope, InputParamsScope, StepsScope, any>
> {
    constructor(
        contextualScope: ContextualScope,
        inputsScope: InputParamsScope,
        bodyScope: StepsScope,
        readonly stepGroups: StepGroup[],
        outputsScope: OutputParamsScope
    ) {
        super(contextualScope, inputsScope, bodyScope, outputsScope);
    }

    /**
     * Accept **any** builder-like result that has getTasks().
     */
    public addStepGroup<R>(
        builderFn: (groupBuilder: StepGroupBuilder<ContextualScope, StepsScope>) => R
    ): R extends BuilderLike ?
        StepsBuilder<ContextualScope, InputParamsScope, ReturnType<R["getTasks"]>["scope"], OutputParamsScope> : R
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
                this.outputsScope
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
        LoopT extends PlainObject = never
    >(
        name: UniqueNameConstraintAtDeclaration<Name, StepsScope>,
        source: UniqueNameConstraintOutsideDeclaration<Name, StepsScope, TemplateSource>,
        key: UniqueNameConstraintOutsideDeclaration<Name, StepsScope, K>,
        ...args: ParamsTuple<InputsFrom<ContextualScope, TemplateSource, K>, Name, StepsScope, LoopT>
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
            return gb.addTask<Name, TemplateSource, K, LoopT>(name, source, key, ...args);
        }) as any;
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

    protected getBody() {
        return {steps: this.stepGroups};
    }
}
