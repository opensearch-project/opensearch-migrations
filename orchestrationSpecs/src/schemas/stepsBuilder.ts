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
    StepsOutputsScope, TemplateSignaturesScope, TemplateSigEntry
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
            ExtendScope<StepsScope, { [K in Name]: StepWithOutputs<Name, TWorkflow["templates"][TKey]["outputs"]> }>,
            OutputParamsScope
        >
    > {
        return this.addStepGroup(groupBuilder => {
            return groupBuilder.addStep(name, workflow, key, paramsFn) as any;
        }) as any;
    }

    // addInternalSteps<
    //     Name extends string,
    //     TKey extends string & keyof ContextualScope["templates"],
    //     TTemplate extends ContextualScope["templates"][TKey],
    //     TInput extends TTemplate extends { input: infer I } ? I extends InputParametersRecord ? I : InputParametersRecord : InputParametersRecord,
    //     TOutput extends TTemplate extends { output: infer O } ? O extends OutputParametersRecord ? O : {} : {},
    //     Task extends WorkflowTask<TInput, TOutput>
    // >(
    //     name: UniqueNameConstraintAtDeclaration<Name, StepsScope>,
    //     key: UniqueNameConstraintOutsideDeclaration<Name, StepsScope, TKey>,
    //     paramsFn: UniqueNameConstraintOutsideDeclaration<Name, StepsScope,
    //         (steps: StepsScopeToStepsWithOutputs<StepsScope>) => ParamsWithLiteralsOrExpressions<z.infer<ReturnType<typeof paramsToCallerSchema<ContextualScope["templates"][TKey]["inputs"]>>>>>
    //
    // ): StepsBuilder<
    //     ContextualScope,
    //     InputParamsScope,
    //     ExtendScope<StepsScope, StepsGroup>,
    //     OutputParamsScope
    // > {
    //     return
    // }

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
            [K in Name]: StepWithOutputs<Name, TWorkflow["templates"][TKey]["outputs"]>
        }>>>
    {
        const workflow = workflowIn as TWorkflow;
        const templateKey = key as TKey;
        // Call callTemplate to get the template reference and arguments
        const templateCall = callExternalTemplate(
            workflow,
            templateKey,
            (paramsFn as any)(this.buildStepsWithOutputs())
        );

        return this.addStepHelper(name, templateCall, workflow.templates[templateKey].outputs);
    }

    addInternalStep<
        Name extends string,
        TKey extends string & keyof ContextualScope["templates"],
        TTemplate extends ContextualScope["templates"][TKey],
        TInput extends TTemplate extends { input: infer I } ? I extends InputParametersRecord ? I : InputParametersRecord : InputParametersRecord,
        TOutput extends TTemplate extends { output: infer O } ? O extends OutputParametersRecord ? O : {} : {},
        Task extends WorkflowTask<TInput, TOutput>
    >(
        name: Name,
        templateKey: UniqueNameConstraintAtDeclaration<TKey, StepsScope>,
        paramsFn: UniqueNameConstraintOutsideDeclaration<Name, StepsScope,
            (steps: StepsScopeToStepsWithOutputs<StepsScope>) => ParamsWithLiteralsOrExpressions<z.infer<ReturnType<typeof paramsToCallerSchema<TInput>>>>>
    ): UniqueNameConstraintOutsideDeclaration<TKey, StepsScope,
        StepGroupBuilder<ContextualScope, ExtendScope<StepsScope, {
            [K in Name]: StepWithOutputs<Name, TOutput>
        }>>> {
        const template = this.contextualScope.templates?.[templateKey];
        const outputs = (template && 'output' in template ? template.output : {}) as TOutput;
        const templateCall = this.callTemplate(
            templateKey,
            (paramsFn as any)(this.buildStepsWithOutputs())
        );


        return this.addStepHelper(name, templateCall, outputs) as any;
    }

    private addStepHelper<
        TKey extends string,
        IN extends InputParametersRecord,
        OUT extends OutputParametersRecord,
        Task extends WorkflowTask<IN, OUT>
    >(
        name: UniqueNameConstraintAtDeclaration<TKey, StepsScope>,
        templateCall: Task,
        outputs: OUT
    ): UniqueNameConstraintOutsideDeclaration<TKey, StepsScope,
        StepGroupBuilder<ContextualScope, ExtendScope<StepsScope, {
            [K in TKey]: StepWithOutputs<TKey, OUT>
        }>>>
    {
        const nameStr = name as string;

        // Add to runtime structure
        this.stepTasks.push({
            name: nameStr,
            ...templateCall
        });

        // Create the new step definition with output types
        const stepDef: StepWithOutputs<TKey, OUT> = {
            name: nameStr as TKey,
            // template: name,
            outputTypes: outputs
        };

        // Return new builder with extended scope
        return new StepGroupBuilder(
            this.contextualScope,
            { ...this.stepsScope, [nameStr]: stepDef } as ExtendScope<StepsScope, {
                [K in TKey]: StepWithOutputs<TKey, OUT>
            }>,
            this.stepTasks
        ) as any;
    }

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

    callTemplate<
        TKey extends Extract<keyof TemplateSignaturesScope, string>
    >(
        templateKey: TKey,
        params: z.infer<ReturnType<typeof paramsToCallerSchema<TemplateSignaturesScope[TKey]["input"]>>>
    ): WorkflowTask<
        TemplateSignaturesScope[TKey]["input"],
        TemplateSignaturesScope[TKey]["output"] extends OutputParametersRecord ? TemplateSignaturesScope[TKey]["output"] : {}
    > {
        return {
            type: "local",
            template: templateKey,
            arguments: { parameters: params }
        } as any;
    }
}

export function callExternalTemplate<
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
