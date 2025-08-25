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
    TasksOutputsScope, TemplateSignaturesScope, TemplateSigEntry, LoopWithUnion, IfNever
} from "@/schemas/workflowTypes";
import {z, ZodType} from "zod";
import {BaseExpression, SimpleExpression, stepOutput} from "@/schemas/expression";
import {TemplateBodyBuilder} from "@/schemas/templateBodyBuilder";
import {UniqueNameConstraintAtDeclaration, UniqueNameConstraintOutsideDeclaration} from "@/schemas/scopeConstraints";
import {PlainObject} from "@/schemas/plainObject";
import {Workflow, WorkflowBuilder} from "@/schemas/workflowBuilder";
import {NamedTask, TaskBuilder} from "@/schemas/taskBuilder";

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

class StepGroupBuilder<
    ContextualScope extends WorkflowAndTemplatesScope,
    TasksScope extends TasksOutputsScope
> extends TaskBuilder<ContextualScope, TasksScope> {

}

export class StepsBuilder<
    ContextualScope extends WorkflowAndTemplatesScope,
    InputParamsScope  extends InputParametersRecord,
    StepsScope extends TasksOutputsScope,
    OutputParamsScope extends OutputParametersRecord
> extends TemplateBodyBuilder<ContextualScope, "steps", InputParamsScope, StepsScope, OutputParamsScope,
    StepsBuilder<ContextualScope, InputParamsScope, StepsScope, any>>
{
    constructor(contextualScope: ContextualScope,
                inputsScope: InputParamsScope,
                bodyScope: StepsScope,
                protected readonly stepGroups: StepGroup[],
                outputsScope: OutputParamsScope) {
        super("steps", contextualScope, inputsScope, bodyScope, outputsScope)
    }

    addStepGroup<NewStepScope extends TasksOutputsScope,
        GB extends StepGroupBuilder<ContextualScope, any>,
        StepsGroup extends ReturnType<GB["getTasks"]>>
    (
        builderFn: (groupBuilder: StepGroupBuilder<ContextualScope, StepsScope>) => GB
    ): StepsBuilder<
        ContextualScope,
        InputParamsScope,
        ExtendScope<StepsScope, StepsGroup>,
        OutputParamsScope
    > {
        // TODO - add the other steps into the contextual scope
        const newSteps = builderFn(new StepGroupBuilder(this.contextualScope, this.bodyScope, []));
        const results = newSteps.getTasks();
        return new StepsBuilder(this.contextualScope, this.inputsScope, results.scope,
            [...this.stepGroups, {steps: results.taskList}], this.outputsScope) as any;
    }

    // Convenience method for single step
    addStep<
        Name extends string,
        TWorkflow extends Workflow<any, any, any>,
        TKey extends Extract<keyof TWorkflow["templates"], string>,
        LoopT extends PlainObject = never
    >(
        name: UniqueNameConstraintAtDeclaration<Name, StepsScope>,
        workflow: UniqueNameConstraintOutsideDeclaration<Name, StepsScope, TWorkflow>,
        key: UniqueNameConstraintOutsideDeclaration<Name, StepsScope, TKey>,
        paramsFn: UniqueNameConstraintOutsideDeclaration<Name, StepsScope,
            (steps: TasksScopeToTasksWithOutputs<StepsScope, LoopT>) =>
                ParamsWithLiteralsOrExpressions<z.infer<
                    ReturnType<typeof paramsToCallerSchema<TWorkflow["templates"][TKey]["inputs"]>>
                >>
        >,
        loopWith?: LoopWithUnion<LoopT>,
        when?: SimpleExpression<boolean>
    ): UniqueNameConstraintOutsideDeclaration<Name, StepsScope,
        StepsBuilder<
            ContextualScope,
            InputParamsScope,
            ExtendScope<StepsScope, { [K in Name]: TasksWithOutputs<Name, TWorkflow["templates"][TKey]["outputs"]> }>,
            OutputParamsScope
        >
    > {
        return this.addStepGroup(groupBuilder => {
            return groupBuilder.addExternalTask(name, workflow, key, paramsFn, loopWith, when) as any;
        }) as any;
    }

    addInternalStep<
        Name extends string,
        TKey extends Extract<keyof ContextualScope["templates"], string>,
        TTemplate extends ContextualScope["templates"][TKey],
        TInput extends TTemplate extends { input: infer I } ? I extends InputParametersRecord ? I : InputParametersRecord : InputParametersRecord,
        TOutput extends TTemplate extends { output: infer O } ? O extends OutputParametersRecord ? O : {} : {},
        LoopT extends PlainObject = never
    >(
        name: UniqueNameConstraintAtDeclaration<Name, StepsScope>,
        templateKey: UniqueNameConstraintOutsideDeclaration<Name, StepsScope, TKey>,
        paramsFn: UniqueNameConstraintOutsideDeclaration<Name, StepsScope,
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
            ExtendScope<
                StepsScope,
                { [K in Name]: TasksWithOutputs<Name, TOutput> }
            >,
            OutputParamsScope
        >
    > {
        return this.addStepGroup(groupBuilder => {
            return groupBuilder.addInternalTask(name, templateKey, paramsFn, loopWith, when) as any;
        }) as any;
    }

    addParameterOutput<T extends PlainObject, Name extends string>(name: Name, parameter: string, t: ZodType<T>, descriptionValue?: string):
        StepsBuilder<ContextualScope, InputParamsScope, StepsScope,
            ExtendScope<OutputParamsScope, { [K in Name]: OutputParamDef<T> }>> {
        return new StepsBuilder(this.contextualScope, this.inputsScope, this.bodyScope, this.stepGroups, {
            ...this.outputsScope,
            [name as string]: {
                type: t,
                fromWhere: "parameter" as const,
                parameter: parameter,
                description: descriptionValue
            }
        });
    }

    addExpressionOutput<T extends PlainObject, Name extends string>(
        name: Name,
        expression: string,
        t: ZodType<T>,
        descriptionValue?: string
    ):
        StepsBuilder<ContextualScope, InputParamsScope, StepsScope,
            ExtendScope<OutputParamsScope, { [K in Name]: OutputParamDef<T> }>>
    {
        return new StepsBuilder(this.contextualScope, this.inputsScope, this.bodyScope, this.stepGroups, {
            ...this.outputsScope,
            [name as string]: {
                type: t,
                fromWhere: "expression" as const,
                expression: expression,
                description: descriptionValue
            }
        });
    }

    getBody(): { body: { steps: Record<string, any> } } {
        // discard stepsScope as it was only necessary for building outputs.
        // it isn't preserved as a map for the final, ordered representation
        return { body: { steps: this.stepGroups } };
    }
}
