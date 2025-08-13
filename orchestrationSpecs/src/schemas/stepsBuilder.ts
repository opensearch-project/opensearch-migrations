import {
    defineParam,
    InputParamDef,
    InputParametersRecord, OutputParamDef,
    OutputParametersRecord,
    paramsToCallerSchema
} from "@/schemas/parameterSchemas";
import {
    Scope,
    ScopeFn,
    ExtendScope,
    TemplateSigEntry,
    FieldSpecs,
    FieldGroupConstraint,
    FieldSpecsToInputParams,
    StepsScopeToStepsWithOutputs,
    StepWithOutputs,
    ParamsWithLiteralsOrExpressions, AllowLiteralOrExpression, InputParamsToExpressions, WorkflowInputsToExpressions
} from "@/schemas/workflowTypes";
import {z, ZodType} from "zod";
import {toEnvVarName, TypescriptError} from "@/utils";
import {inputParam, stepOutput, workflowParam} from "@/schemas/expression";
import {IMAGE_PULL_POLICY} from "@/schemas/userSchemas";
import {TemplateBodyBuilder} from "@/schemas/templateBodyBuilder";
import {UniqueNameConstraintAtDeclaration, UniqueNameConstraintOutsideDeclaration} from "@/schemas/scopeConstraints";

export interface StepGroup {
    steps: StepTask[];
}

export interface StepTask {
    name: string;
    template: string;
    arguments?: {
        parameters?: Record<string, any>;
    };
}

export class StepsBuilder<
    ContextualScope extends Scope,
    InputParamsScope  extends Scope,
    StepsScope extends Scope,
    OutputParamsScope extends Scope
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

    addStepGroup<NewStepScope extends Scope,
        GB extends StepGroupBuilder<ContextualScope, any>,
        StepsGroup extends ReturnType<GB["getStepTasks"]>>
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
        const results = newSteps.getStepTasks();
        return new StepsBuilder(this.contextualScope, this.inputsScope, results.scope,
            [...this.stepGroups, {steps: results.taskList}], this.outputsScope) as any;
    }

    // Convenience method for single step
    addStep<
        Name extends string,
        TWorkflow extends { templates: Record<string, { inputs: InputParametersRecord; outputs?: OutputParametersRecord }> },
        TKey extends Extract<keyof TWorkflow["templates"], string>
    >(
        name: UniqueNameConstraintAtDeclaration<Name, StepsScope>,
        workflowBuilder: UniqueNameConstraintOutsideDeclaration<Name, StepsScope, TWorkflow>,
        key: UniqueNameConstraintOutsideDeclaration<Name, StepsScope, TKey>,
        paramsFn: UniqueNameConstraintOutsideDeclaration<Name, StepsScope,
            (steps: StepsScopeToStepsWithOutputs<StepsScope>) => ParamsWithLiteralsOrExpressions<z.infer<ReturnType<typeof paramsToCallerSchema<TWorkflow["templates"][TKey]["inputs"]>>>>>
    ): UniqueNameConstraintOutsideDeclaration<Name, StepsScope,
        StepsBuilder<
            ContextualScope,
            InputParamsScope,
            ExtendScope<StepsScope, { [K in Name]: StepWithOutputs<Name, TKey, TWorkflow["templates"][TKey]["outputs"]> }>,
            OutputParamsScope
        >
    > {
        return this.addStepGroup(groupBuilder => {
            return groupBuilder.addStep(name, workflowBuilder, key, paramsFn) as any;
        }) as any;
    }

    addParameterOutput<T, Name extends string>(name: Name, parameter: string, t: ZodType<T>, descriptionValue?: string):
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

    addExpressionOutput<T, Name extends string>(name: Name, expression: string, t: ZodType<T>, descriptionValue?: string):
        StepsBuilder<ContextualScope, InputParamsScope, StepsScope,
            ExtendScope<OutputParamsScope, { [K in Name]: OutputParamDef<T> }>> {
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

export class StepGroupBuilder<
    ContextualScope extends Scope,
    StepsScope extends Scope
> {
    constructor(protected readonly contextualScope: ContextualScope,
                protected readonly stepsScope: StepsScope,
                protected readonly stepTasks: StepTask[]) {
    }

    addStep<
        Name extends string,
        TWorkflow extends { templates: Record<string, { inputs: InputParametersRecord; outputs?: OutputParametersRecord }> },
        TKey extends Extract<keyof TWorkflow["templates"], string>
    >(
        name: UniqueNameConstraintAtDeclaration<Name, StepsScope>,
        workflowBuilder: UniqueNameConstraintOutsideDeclaration<Name, StepsScope, TWorkflow>,
        key: UniqueNameConstraintOutsideDeclaration<Name, StepsScope, TKey>,
        paramsFn: UniqueNameConstraintOutsideDeclaration<Name, StepsScope,
            (steps: StepsScopeToStepsWithOutputs<StepsScope>) => ParamsWithLiteralsOrExpressions<z.infer<ReturnType<typeof paramsToCallerSchema<TWorkflow["templates"][TKey]["inputs"]>>>>>
    ): UniqueNameConstraintOutsideDeclaration<Name, StepsScope,
        StepGroupBuilder<ContextualScope, ExtendScope<StepsScope, {
            [K in Name]: StepWithOutputs<Name, TKey, TWorkflow["templates"][TKey]["outputs"]>
        }>>>
    {
        const nameStr = name as string;
        const workflow = workflowBuilder as TWorkflow;
        const templateKey = key as TKey;

        // Build the steps object with output expressions for intellisense
        const stepsWithOutputs = this.buildStepsWithOutputs();

        // Call the user's function to get the parameters
        const params = (paramsFn as any)(stepsWithOutputs);

        // Call callTemplate to get the template reference and arguments
        const templateCall = callTemplate(
            workflow.templates,
            templateKey,
            params
        );

        // Add to runtime structure
        this.stepTasks.push({
            name: nameStr,
            template: templateCall.templateRef.key,
            arguments: templateCall.arguments
        });

        // Create the new step definition with output types
        const stepDef: StepWithOutputs<Name, TKey, TWorkflow["templates"][TKey]["outputs"]> = {
            name: nameStr as Name,
            template: templateKey,
            outputTypes: workflow.templates[templateKey].outputs || {} as any
        };

        // Return new builder with extended scope
        return new StepGroupBuilder(
            this.contextualScope,
            { ...this.stepsScope, [nameStr]: stepDef } as ExtendScope<StepsScope, {
                [K in Name]: StepWithOutputs<Name, TKey, TWorkflow["templates"][TKey]["outputs"]>
            }>,
            this.stepTasks
        ) as any;
    }

    // Add this private helper method to build the steps object with outputs
    private buildStepsWithOutputs(): StepsScopeToStepsWithOutputs<StepsScope> {
        const result: any = {};

        Object.entries(this.stepsScope).forEach(([stepName, stepDef]: [string, any]) => {
            if (stepDef.outputTypes) {
                result[stepName] = {};
                Object.entries(stepDef.outputTypes).forEach(([outputName, outputParamDef]: [string, any]) => {
                    result[stepName][outputName] = stepOutput(stepName, outputName, outputParamDef);
                });
            }
        });

        return result as StepsScopeToStepsWithOutputs<StepsScope>;
    }

    getStepTasks() {
        return { scope: this.stepsScope, taskList: this.stepTasks };
    }
}

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

export type WorkflowTask<
    IN extends InputParametersRecord,
    OUT extends OutputParametersRecord
> = {
    templateRef: { key: string, value: TemplateDef<IN,OUT> }
    arguments?: { parameters: any }
}

export function callTemplate<
    TClass extends Record<string, { inputs: InputParametersRecord; outputs?: OutputParametersRecord }>,
    TKey extends Extract<keyof TClass, string>
>(
    classConstructor: TClass,
    key: TKey,
    params: z.infer<ReturnType<typeof paramsToCallerSchema<TClass[TKey]["inputs"]>>>
): WorkflowTask<TClass[TKey]["inputs"], TClass[TKey]["outputs"] extends OutputParametersRecord ? TClass[TKey]["outputs"] : {}> {
    const value = classConstructor[key];
    return {
        templateRef: { key, value },
        arguments: { parameters: params }
    } as any;
}
