import {
    InputParametersRecord, OutputParamDef,
    OutputParametersRecord,
    paramsToCallerSchema
} from "@/schemas/parameterSchemas";
import {
    ExtendScope,
    TasksScopeToTasksWithOutputs,
    TasksWithOutputs,
    ParamsWithLiteralsOrExpressions,
    WorkflowAndTemplatesScope,
    TasksOutputsScope,
    LoopWithUnion
} from "@/schemas/workflowTypes";
import { z, ZodType } from "zod";
import { BaseExpression, SimpleExpression } from "@/schemas/expression";
import { TemplateBodyBuilder } from "@/schemas/templateBodyBuilder";
import {
    UniqueNameConstraintAtDeclaration,
    UniqueNameConstraintOutsideDeclaration
} from "@/schemas/scopeConstraints";
import { PlainObject } from "@/schemas/plainObject";
import { Workflow } from "@/schemas/workflowBuilder";
import {
    NamedTask,
    TaskBuilder,
    TaskBuilderFactory,
    WithScope
} from "@/schemas/taskBuilder";

const TemplateDefSchema = z.object({
    inputs: z.any(),
    outputs: z.any(),
});

export type TemplateDef<
    IN extends InputParametersRecord,
    OUT extends OutputParametersRecord
> = z.infer<typeof TemplateDefSchema> & {
    inputs: IN;
    outputs: OUT;
};

export interface StepGroup {
    steps: NamedTask[];
}

/** Factory that rebinds StepGroupBuilder to a new scope */
class StepsFactory<C extends WorkflowAndTemplatesScope>
    implements TaskBuilderFactory<C, StepGroupBuilder<C, any>> {
    create<NS extends TasksOutputsScope>(
        context: C,
        scope: NS,
        tasks: NamedTask[]
    ): WithScope<StepGroupBuilder<C, any>, NS> {
        return new StepGroupBuilder<C, NS>(context, scope, tasks) as WithScope<
            StepGroupBuilder<C, any>,
            NS
        >;
    }
}

class StepGroupBuilder<
    ContextualScope extends WorkflowAndTemplatesScope,
    TasksScope extends TasksOutputsScope
> extends TaskBuilder<
    ContextualScope,
    TasksScope,
    StepGroupBuilder<ContextualScope, any>,
    StepsFactory<ContextualScope>
> {
    constructor(
        contextualScope: ContextualScope,
        tasksScope: TasksScope,
        tasks: NamedTask[]
    ) {
        super(contextualScope, tasksScope, tasks, new StepsFactory<ContextualScope>());
    }
}

/** Helper types for the conditional `addStepGroup` return */
type BuilderLike = { getTasks(): { scope: any; taskList: NamedTask[] } };
type AddStepGroupResult<
    R,
    C extends WorkflowAndTemplatesScope,
    I extends InputParametersRecord,
    S extends TasksOutputsScope,
    O extends OutputParametersRecord
> = R extends BuilderLike
    ? StepsBuilder<C, I, ReturnType<R["getTasks"]>["scope"], O>
    : R;

export class StepsBuilder<
    ContextualScope extends WorkflowAndTemplatesScope,
    InputParamsScope  extends InputParametersRecord,
    StepsScope extends TasksOutputsScope,
    OutputParamsScope extends OutputParametersRecord
> extends TemplateBodyBuilder<
    ContextualScope,
    "steps",
    InputParamsScope,
    StepsScope,
    OutputParamsScope,
    StepsBuilder<ContextualScope, InputParamsScope, StepsScope, any>
> {
    constructor(
        contextualScope: ContextualScope,
        public inputsScope: InputParamsScope,
        bodyScope: StepsScope,
        protected readonly stepGroups: StepGroup[],
        outputsScope: OutputParamsScope
    ) {
        super("steps", contextualScope, inputsScope, bodyScope, outputsScope);
    }

    /**
     * Accept **any** builder-like result that has getTasks().
     * If caller returns a TypescriptError<...>, it won't have getTasks and we propagate that error type back.
     * If it succeeds, we infer the extended scope and return a new StepsBuilder with that scope.
     */
    addStepGroup<R>(
        builderFn: (groupBuilder: StepGroupBuilder<ContextualScope, StepsScope>) => R
    ): AddStepGroupResult<R, ContextualScope, InputParamsScope, StepsScope, OutputParamsScope> {
        const newGroup = builderFn(
            new StepGroupBuilder(this.contextualScope, this.bodyScope, [])
        ) as any;

        if (newGroup && typeof newGroup.getTasks === "function") {
            const results = newGroup.getTasks();
            return new StepsBuilder(
                this.contextualScope,
                this.inputsScope,
                results.scope,
                [...this.stepGroups, { steps: results.taskList }],
                this.outputsScope
            ) as AddStepGroupResult<R, ContextualScope, InputParamsScope, StepsScope, OutputParamsScope>;
        }

        // Propagate the error/other type to the callsite
        return newGroup as AddStepGroupResult<R, ContextualScope, InputParamsScope, StepsScope, OutputParamsScope>;
    }

    // Convenience method for single external step
    addStep<
        Name extends string,
        TWorkflow extends Workflow<any, any, any>,
        TKey extends Extract<keyof TWorkflow["templates"], string>,
        LoopT extends PlainObject = never
    >(
        name: UniqueNameConstraintAtDeclaration<Name, StepsScope>,
        workflow: UniqueNameConstraintOutsideDeclaration<Name, StepsScope, TWorkflow>,
        key: UniqueNameConstraintOutsideDeclaration<Name, StepsScope, TKey>,
        paramsFn: UniqueNameConstraintOutsideDeclaration<
            Name,
            StepsScope,
            (steps: TasksScopeToTasksWithOutputs<StepsScope, LoopT>) =>
                ParamsWithLiteralsOrExpressions<
                    z.infer<ReturnType<typeof paramsToCallerSchema<TWorkflow["templates"][TKey]["inputs"]>>>
                >
        >,
        loopWith?: LoopWithUnion<LoopT>,
        when?: SimpleExpression<boolean>
    ): UniqueNameConstraintOutsideDeclaration<
        Name,
        StepsScope,
        StepsBuilder<
            ContextualScope,
            InputParamsScope,
            ExtendScope<
                StepsScope,
                { [K in Name]: TasksWithOutputs<Name, TWorkflow["templates"][TKey]["outputs"]> }
            >,
            OutputParamsScope
        >
    > {
        return this.addStepGroup(groupBuilder =>
            groupBuilder.addExternalTask(name, workflow, key, paramsFn, loopWith, when)
        ) as any;
    }

    // Convenience method for single internal step
    addInternalStep<
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
        name: UniqueNameConstraintAtDeclaration<Name, StepsScope>,
        templateKey: UniqueNameConstraintOutsideDeclaration<Name, StepsScope, TKey>,
        paramsFn: UniqueNameConstraintOutsideDeclaration<
            Name,
            StepsScope,
            (steps: TasksScopeToTasksWithOutputs<StepsScope, LoopT>) =>
                ParamsWithLiteralsOrExpressions<
                    z.infer<ReturnType<typeof paramsToCallerSchema<TInput>>>
                >
        >,
        loopWith?: LoopWithUnion<LoopT>,
        when?: SimpleExpression<boolean>
    ): UniqueNameConstraintOutsideDeclaration<
        Name,
        StepsScope,
        StepsBuilder<
            ContextualScope,
            InputParamsScope,
            ExtendScope<StepsScope, { [K in Name]: TasksWithOutputs<Name, TOutput> }>,
            OutputParamsScope
        >
    > {
        return this.addStepGroup(groupBuilder =>
            groupBuilder.addInternalTask(name, templateKey, paramsFn, loopWith, when)
        ) as any;
    }

    addExpressionOutput<T extends PlainObject, Name extends string>(
        name: UniqueNameConstraintAtDeclaration<Name, OutputParamsScope>,
        expression: UniqueNameConstraintOutsideDeclaration<Name, OutputParamsScope, BaseExpression<T>>,
        t: UniqueNameConstraintOutsideDeclaration<Name, OutputParamsScope, ZodType<T>>,
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
                    type: t,
                    fromWhere: "expression" as const,
                    expression,
                    description: descriptionValue
                }
            }
        ) as any;
    }

    getBody(): { body: { steps: StepGroup[] } } {
        // Steps are an ordered list of StepGroups in the final manifest.
        return { body: { steps: this.stepGroups } };
    }
}
