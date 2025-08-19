import {
    InputParametersRecord, OutputParamDef,
    OutputParametersRecord,
    paramsToCallerSchema
} from "@/schemas/parameterSchemas";
import {
    ExtendScope,
    StepsScopeToStepsWithOutputs,
    StepWithOutputs,
    ParamsWithLiteralsOrExpressions,
    WorkflowAndTemplatesScope,
    StepsOutputsScope
} from "@/schemas/workflowTypes";
import {z, ZodType} from "zod";
import {stepOutput} from "@/schemas/expression";
import {TemplateBodyBuilder} from "@/schemas/templateBodyBuilder";
import {UniqueNameConstraintAtDeclaration, UniqueNameConstraintOutsideDeclaration} from "@/schemas/scopeConstraints";
import {PlainObject} from "@/schemas/plainObject";
import {Workflow, WorkflowBuilder} from "@/schemas/workflowBuilder";

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
    arguments?: { parameters?: Record<string, any>; } & ({
        type: 'external';
        templateRef: { key: string, value: TemplateDef<IN, OUT> };
    } | {
        type: 'local';
        template: string;
    })
}

export type NamedTask<
    IN extends InputParametersRecord = InputParametersRecord,
    OUT extends OutputParametersRecord = OutputParametersRecord,
    Extra extends Record<string, any> = {}
> = { name: string } & WorkflowTask<IN, OUT> & Extra;


export interface StepGroup {
    steps: StepTask[];
}

export type StepTask<
    IN extends InputParametersRecord = InputParametersRecord,
    OUT extends OutputParametersRecord = OutputParametersRecord
> = NamedTask<IN, OUT>;

export class StepsBuilder<
    ContextualScope extends WorkflowAndTemplatesScope,
    InputParamsScope  extends InputParametersRecord,
    StepsScope extends StepsOutputsScope,
    OutputParamsScope extends OutputParametersRecord
> extends TemplateBodyBuilder<ContextualScope, "steps", InputParamsScope, StepsScope, OutputParamsScope,
    StepsBuilder<ContextualScope, InputParamsScope, StepsScope, any>>
{
    constructor(public contextualScope: ContextualScope,
                inputsScope: InputParamsScope,
                bodyScope: StepsScope,
                protected readonly stepGroups: StepGroup[],
                outputsScope: OutputParamsScope) {
        super("steps", contextualScope, inputsScope, bodyScope, outputsScope)
    }

    addStepGroup<NewStepScope extends StepsOutputsScope,
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
        TWorkflow extends Workflow<any, any, any>,
        TKey extends Extract<keyof TWorkflow["templates"], string>
    >(
        name: UniqueNameConstraintAtDeclaration<Name, StepsScope>,
        workflow: UniqueNameConstraintOutsideDeclaration<Name, StepsScope, TWorkflow>,
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
            return groupBuilder.addStep(name, workflow, key, paramsFn) as any;
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

    addExpressionOutput<T extends PlainObject, Name extends string>(name: Name, expression: string, t: ZodType<T>, descriptionValue?: string):
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
    ContextualScope extends WorkflowAndTemplatesScope,
    StepsScope extends StepsOutputsScope
> {
    constructor(protected readonly contextualScope: ContextualScope,
                protected readonly stepsScope: StepsScope,
                protected readonly stepTasks: StepTask[]) {
    }

    addStep<
        Name extends string,
        TWorkflow extends Workflow<any, any, any>,
        TKey extends Extract<keyof TWorkflow["templates"], string>
    >(
        name: UniqueNameConstraintAtDeclaration<Name, StepsScope>,
        workflowIn: UniqueNameConstraintOutsideDeclaration<Name, StepsScope, TWorkflow>,
        key: UniqueNameConstraintOutsideDeclaration<Name, StepsScope, TKey>,
        paramsFn: UniqueNameConstraintOutsideDeclaration<Name, StepsScope,
            (steps: StepsScopeToStepsWithOutputs<StepsScope>) => ParamsWithLiteralsOrExpressions<z.infer<ReturnType<typeof paramsToCallerSchema<TWorkflow["templates"][TKey]["inputs"]>>>>>
    ): UniqueNameConstraintOutsideDeclaration<Name, StepsScope,
        StepGroupBuilder<ContextualScope, ExtendScope<StepsScope, {
            [K in Name]: StepWithOutputs<Name, TKey, TWorkflow["templates"][TKey]["outputs"]>
        }>>>
    {
        const nameStr = name as string;
        const workflow = workflowIn as TWorkflow;
        const templateKey = key as TKey;

        // Build the steps object with output expressions for intellisense
        const stepsWithOutputs = this.buildStepsWithOutputs();

        // Call the user's function to get the parameters
        const params = (paramsFn as any)(stepsWithOutputs);

        // Call callTemplate to get the template reference and arguments
        const templateCall = callTemplate(
            workflow,
            templateKey,
            params
        );

        // Add to runtime structure
        this.stepTasks.push({
            name: nameStr,
            ...templateCall
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

export function callTemplate<
    WF extends Workflow<any, any, any>,
    TKey extends Extract<keyof WF["templates"], string>
>(
    wf: WF,
    templateKey: TKey,
    params: z.infer<ReturnType<typeof paramsToCallerSchema<WF["templates"][TKey]["inputs"]>>>
): WorkflowTask<
    WF["templates"][TKey]["inputs"],
    WF["templates"][TKey]["outputs"] extends OutputParametersRecord ? WF["templates"][TKey]["outputs"] : {}
> {
    return {
        type: "external",
        templateRef: {
            name: templateKey,
            template: wf.metadata.k8sMetadata.name
        },
        arguments: { parameters: params }
    } as any;
}
