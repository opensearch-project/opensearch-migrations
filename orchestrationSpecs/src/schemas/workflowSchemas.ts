import {
    defineParam,
    InputParamDef,
    InputParametersRecord,
    OutputParametersRecord,
    paramsToCallerSchema
} from "@/schemas/parameterSchemas";
import {Scope, ScopeFn, ExtendScope, TemplateSigEntry} from "@/schemas/workflowTypes";
import {z, ZodType, ZodTypeAny} from "zod";

type TypescriptError<Message extends string> = {
    readonly __error: Message;
    readonly __never: never;
};

declare global {
    // true: worse LSP, but squigglies under the name declaration
    // false: squigglies under other parts of named constructs instead of the declaration, but better LSP support
    const __PREFER_UNIQUE_NAME_CHECKS_AT_NAME__: boolean;
}
declare const __PREFER_UNIQUE_NAME_CHECKS_AT_NAME_SITE__: false;

type UniqueNameConstraintOutsideDeclaration<Name extends string, S, TypeWhenValid> =
    typeof __PREFER_UNIQUE_NAME_CHECKS_AT_NAME_SITE__ extends false
        ? Name extends keyof S ? TypescriptError<`Name '${Name}' exists within ${keyof S & string}`> : TypeWhenValid
        : TypeWhenValid;

type UniqueNameConstraintAtDeclaration<Name extends string, S> =
    typeof __PREFER_UNIQUE_NAME_CHECKS_AT_NAME_SITE__ extends true
        ? Name extends keyof S ? TypescriptError<`Name '${Name}' exists within  ${keyof S & string}.`> : Name
        : Name;

type ScopeIsEmptyConstraint<S, T> =
    keyof S extends never
        ? T
        : TypescriptError<`Scope must be empty but contains: ${keyof S & string}`>

class ScopeBuilder<SigScope extends Scope = Scope> {
    constructor(protected readonly sigScope: SigScope) {}

    extendScope<AddSig extends Scope>(fn: ScopeFn<SigScope, AddSig>): ExtendScope<SigScope, AddSig> {
        return {
            ...this.sigScope,
            ...fn(this.sigScope)
        } as ExtendScope<SigScope, AddSig>;
    }

    getScope(): SigScope {
        return this.sigScope;
    }
}

export class WFBuilder<
    MetadataScope extends Scope = Scope,
    WorkflowInputsScope extends Scope = Scope,
    TemplateSigScope extends Scope = Scope,
    TemplateFullScope extends Scope = Scope
> {
    metadataScope: MetadataScope;
    inputsScope: WorkflowInputsScope;
    templateSigScope: TemplateSigScope;
    templateFullScope: TemplateFullScope;

    constructor(
        metadataScope: MetadataScope,
        inputsScope: WorkflowInputsScope,
        templateSigScope: TemplateSigScope,
        templateFullScope: TemplateFullScope
    ) {
        this.metadataScope = metadataScope;
        this.inputsScope = inputsScope;
        this.templateSigScope = templateSigScope;
        this.templateFullScope = templateFullScope;
    }

    static create(k8sResourceName: string) {
        return new WFBuilder({ name: k8sResourceName }, {}, {}, {});
    }

    addParams<P extends InputParametersRecord>(
        params: P
    ): WFBuilder<
        MetadataScope,
        ExtendScope<WorkflowInputsScope, P>,
        TemplateSigScope,
        TemplateFullScope
    > {
        const newInputs = { ...this.inputsScope, ...params } as ExtendScope<WorkflowInputsScope, P>;
        return new WFBuilder(
            this.metadataScope,
            newInputs,
            this.templateSigScope,
            this.templateFullScope
        );
    }

    addTemplate<
        Name extends string,
        TB extends TemplateBuilder<any, any, any, any>,
        FullTemplate extends ReturnType<TB["getFullTemplateScope"]>
    >(
        name: UniqueNameConstraintAtDeclaration<Name, TemplateSigScope>,
        builderFn: UniqueNameConstraintOutsideDeclaration<Name, TemplateSigScope, (tb: TemplateBuilder<{
            workflowParameters: WorkflowInputsScope;
            templates: TemplateSigScope;
        }, {}, {}, {}>) => TB>
    ): UniqueNameConstraintOutsideDeclaration<Name, TemplateSigScope,
        WFBuilder<
            MetadataScope,
            WorkflowInputsScope,
            // update the next line to use the macro
            ExtendScope<TemplateSigScope, { [K in Name]: (Name extends keyof TemplateSigScope ? Exclude<TemplateSigEntry<FullTemplate>, Name> : TemplateSigEntry<FullTemplate>)}>,
            ExtendScope<TemplateFullScope, { [K in Name]: FullTemplate }>
        >
    > {
        const templateScope = {
            workflowParameters: this.inputsScope,
            templates: this.templateSigScope
        };

        // workaround type warning/breakage that I'm creating in the signature w/ `as any`
        const fn = builderFn as (tb: TemplateBuilder<{
            workflowParameters: WorkflowInputsScope;
            templates: TemplateSigScope;
        }, {}>) => TB;
        const templateBuilder = fn(new TemplateBuilder(templateScope, {}, {}, {}) as any);
        const fullTemplate = templateBuilder.getFullTemplateScope();

        const newSig = {
            [name as string]: {
                input: fullTemplate.inputs,
                output: (fullTemplate as any).outputs
            }
        } as { [K in Name]: TemplateSigEntry<FullTemplate> };

        const newFull = {[name as string]: fullTemplate} as { [K in Name]: FullTemplate };

        return new WFBuilder(
            this.metadataScope,
            this.inputsScope,
            {...this.templateSigScope, ...newSig},
            {...this.templateFullScope, ...newFull}
        ) as any;
    }

    getFullScope() {
        return {
            metadata: this.metadataScope,
            workflowParameters: this.inputsScope,
            templates: this.templateFullScope
        };
    }
}

/**
 * Maintains a scope of all previous public parameters (workflow and previous templates' inputs/outputs)
 * as well as newly created inputs/outputs for this template.  To define a specific template, use one of
 * the builder methods, which, like `addTemplate` will return a new builder for that type of template
 * receives the specification up to that point.
 */
export class TemplateBuilder<
    ContextualScope extends Scope,
    BodyScope extends Scope = Scope,
    InputParamsScope extends Scope = Scope,
    OutputParamsScope extends Scope = Scope
> {
    private readonly contextualScope: ContextualScope;
    private readonly bodyScope: BodyScope;
    private readonly inputScopeBuilder: ScopeBuilder<InputParamsScope>;
    private readonly outputScopeBuilder: ScopeBuilder<OutputParamsScope>;

    constructor(contextualScope: ContextualScope,
                bodyScope: BodyScope,
                inputScope: InputParamsScope,
                outputScope: OutputParamsScope) {
        this.contextualScope = contextualScope;
        this.bodyScope = bodyScope;
        this.inputScopeBuilder = new ScopeBuilder(inputScope);
        this.outputScopeBuilder = new ScopeBuilder(outputScope);
    }

    private extendWithParam<
        Name extends string,
        R extends boolean,
        T
    >(
        name: Name,
        param: InputParamDef<T, R>
    ): TemplateBuilder<
        ContextualScope,
        BodyScope,
        ExtendScope<InputParamsScope, { [K in Name]: InputParamDef<T, R> }>,
        OutputParamsScope
    > {
        const newScope = this.inputScopeBuilder.extendScope(s =>
            ({[name]: param})
        );

        return new TemplateBuilder(this.contextualScope, this.bodyScope, newScope, this.outputScopeBuilder.getScope());
    }

    optionalInput<T, Name extends string>(
        name: UniqueNameConstraintAtDeclaration<Name, InputParamsScope>,
        defaultValueFromScopeFn: UniqueNameConstraintOutsideDeclaration<Name, InputParamsScope,
            (s: { context: ContextualScope; currentScope: InputParamsScope }) => T>,
        description?: string
    ): ScopeIsEmptyConstraint<BodyScope, UniqueNameConstraintOutsideDeclaration<Name, InputParamsScope,
        TemplateBuilder<
            ContextualScope,
            BodyScope,
            ExtendScope<InputParamsScope, { [K in Name]: InputParamDef<T, false> }>,
            OutputParamsScope
        > > >
    {
        const fn = defaultValueFromScopeFn as (s: { context: ContextualScope; currentScope: InputParamsScope }) => T;
        const param = defineParam({
            defaultValue: fn({
                context: this.contextualScope,
                currentScope: this.getTemplateSignatureScope()
            }),
            description
        });

        return this.extendWithParam(name as string, param) as any;
    }

    addRequiredInput<Name extends string>(
        name: UniqueNameConstraintAtDeclaration<Name, InputParamsScope>,
        t: ScopeIsEmptyConstraint<BodyScope, UniqueNameConstraintOutsideDeclaration<Name, InputParamsScope, any>>,
        description?: string
    ): UniqueNameConstraintOutsideDeclaration<Name, InputParamsScope,
        TemplateBuilder<
            ContextualScope,
            BodyScope,
            ExtendScope<InputParamsScope, { [K in Name]: InputParamDef<any, true> } >,
            OutputParamsScope
        >>
    {
        const param: InputParamDef<any, true> = {
            type: t as any,
            description
        };

        return this.extendWithParam(name as any, param) as any;
    }

    addSteps<SB extends StepsBuilder<ContextualScope, any>>(
        builderFn: ScopeIsEmptyConstraint<BodyScope, (
            b: StepsBuilder<ContextualScope, {}>) => SB>
    ): TemplateBuilder<
        ContextualScope,
        ReturnType<SB["getSteps"]>,
        InputParamsScope,
        OutputParamsScope
    >
    {
        const fn = builderFn as (b: StepsBuilder<ContextualScope, {}>) => SB;
        const steps = fn(new StepsBuilder(this.contextualScope, {}, [])).getSteps();
        return new TemplateBuilder(
            this.contextualScope,
            steps,
            this.inputScopeBuilder.getScope(),
            this.outputScopeBuilder.getScope()
        ) as any;
    }

    addDag<
        DagScope extends Scope,
        DB extends DagBuilder<ContextualScope, DagScope>,
        FullDag extends ReturnType<DB["getDag"]>
    >(builderFn: ScopeIsEmptyConstraint<BodyScope,
        (b: DagBuilder<ContextualScope, {}>) => DB>
    ): TemplateBuilder<ContextualScope, FullDag, InputParamsScope, OutputParamsScope>
    {
        const fn = builderFn as (b: DagBuilder<ContextualScope, {}>) => DB;
        const steps = fn(new DagBuilder(this.contextualScope, {})).getDag();
        return new TemplateBuilder(this.contextualScope, steps, this.inputScopeBuilder.getScope(), this.outputScopeBuilder.getScope()) as any
    }

    addContainer() {

    }

    getTemplateSignatureScope(): InputParamsScope {
        return this.inputScopeBuilder.getScope();
    }

    getFullTemplateScope(): {
        inputs: InputParamsScope,
        body: BodyScope
    } {
        return {
            inputs: this.inputScopeBuilder.getScope(),
            body: this.bodyScope}
    }
}

export interface StepGroup {
    steps: StepTask[];
}

export interface StepTask {
    name: string;
    template: string;
    arguments?: {
        parameters?: Record<string, any>;
    };
    dependencies?: string[];
}

class StepsBuilder<
    ContextualScope extends Scope,
    StepsScope extends Scope
> {
    readonly contextualScope: ContextualScope;
    private readonly stepsScope: StepsScope;
    private readonly stepGroups: StepGroup[] = []; // Runtime ordering

    constructor(contextualScope: ContextualScope, stepsScope: StepsScope, stepGroups: StepGroup[]) {
        this.contextualScope = contextualScope;
        this.stepsScope = stepsScope;
        this.stepGroups = stepGroups;
    }

    addStepGroup<NewStepScope extends Scope,
        GB extends StepGroupBuilder<any, any>,
        StepsGroup extends ReturnType<GB["getStepTasks"]>>
    (
        builderFn: (groupBuilder: StepGroupBuilder<ContextualScope, StepsScope>) => GB
    ): StepsBuilder<
        ContextualScope,
        ExtendScope<StepsScope, StepsGroup>
    > {
        // TODO - add the other steps into the contextual scope
        const newSteps = builderFn(new StepGroupBuilder(this.contextualScope, this.stepsScope, []));
        const results = newSteps.getStepTasks();
        return new StepsBuilder(this.contextualScope, results.scope,
            [...this.stepGroups, {steps: results.taskList}]) as any;
    }

    // Convenience method for single step
    addStep<
        Name extends string, 
        StepDef,
        TWorkflow extends { templates: Record<string, any> },
        TKey extends Extract<keyof TWorkflow["templates"], string>
    >(
        name: UniqueNameConstraintAtDeclaration<Name, StepsScope>,
        workflowBuilder: UniqueNameConstraintOutsideDeclaration<Name, StepsScope, TWorkflow>,
        key: UniqueNameConstraintOutsideDeclaration<Name, StepsScope, TKey>,
        params: UniqueNameConstraintOutsideDeclaration<Name, StepsScope, z.infer<ReturnType<typeof paramsToCallerSchema<TWorkflow["templates"][TKey]["inputs"]>>>>,
        dependencies?: string[]
    ): UniqueNameConstraintOutsideDeclaration<Name, StepsScope,
        StepsBuilder<
            ContextualScope,
            ExtendScope<StepsScope, { [K in Name]: StepDef }>
        >
    > {
        return this.addStepGroup(groupBuilder => {
            // addStep returns a constrained type, so we need to cast it for internal use
            return groupBuilder.addStep(name, workflowBuilder, key, params, dependencies) as any;
        }) as any;
    }

    getSteps(): {
        steps: StepsScope;
        stepGroups: StepGroup[]; // Runtime structure for serialization
    } {
        return {
            steps: this.stepsScope,
            stepGroups: this.stepGroups
        };
    }
}

class StepGroupBuilder<
    ContextualScope extends Scope,
    StepsScope extends Scope
> {
    private readonly contextualScope: ContextualScope;
    private readonly stepsScope: StepsScope;
    private readonly stepTasks: StepTask[] = [];

    constructor(contextualScope: ContextualScope, stepsScope: StepsScope, stepTasks: StepTask[]) {
        this.contextualScope = contextualScope;
        this.stepsScope = stepsScope;
        this.stepTasks = stepTasks;
    }

    addStep<
        Name extends string, 
        StepDef,
        TWorkflow extends { templates: Record<string, any> },
        TKey extends Extract<keyof TWorkflow["templates"], string>
    >(
        name: UniqueNameConstraintAtDeclaration<Name, StepsScope>,
        workflowBuilder: UniqueNameConstraintOutsideDeclaration<Name, StepsScope, TWorkflow>,
        key: UniqueNameConstraintOutsideDeclaration<Name, StepsScope, TKey>,
        params: UniqueNameConstraintOutsideDeclaration<Name, StepsScope, z.infer<ReturnType<typeof paramsToCallerSchema<TWorkflow["templates"][TKey]["inputs"]>>>>,
        dependencies?: string[]
    ): UniqueNameConstraintOutsideDeclaration<Name, StepsScope,
        StepGroupBuilder<ContextualScope, ExtendScope<StepsScope, { [K in Name]: StepDef }>>>
    {
        // Use type assertions since constraints prevent invalid calls at compile time
        const nameStr = name as string;
        
        // Call callTemplate to get the template reference and arguments
        const templateCall = callTemplate(
            (workflowBuilder as TWorkflow).templates,
            key as TKey,
            params as z.infer<ReturnType<typeof paramsToCallerSchema<TWorkflow["templates"][TKey]["inputs"]>>>
        );

        // Add to runtime structure
        this.stepTasks.push({
            name: nameStr,
            template: templateCall.templateRef.key,
            arguments: templateCall.arguments,
            dependencies
        });

        // Return type-level representation - use 'as any' to bypass constraint checking in implementation
        return new StepGroupBuilder(
            this.contextualScope,
            { ...this.stepsScope, [nameStr]: { name: nameStr, template: templateCall.templateRef.key } } as ExtendScope<StepsScope, { [K in Name]: StepDef }>,
            this.stepTasks
        ) as any;
    }

    getStepTasks() {
        return { scope: this.stepsScope, taskList: this.stepTasks };
    }
}

class DagBuilder<
    ContextualScope extends Scope,
    DagScope extends Scope
> {
    private readonly contextualScope: ContextualScope;
    private readonly stepsScope: DagScope;

    constructor(contextualScope: ContextualScope, stepsScope: DagScope) {
        this.contextualScope = contextualScope;
        this.stepsScope = stepsScope;
    }
    addTask() {}

    getDag(): { steps: DagScope } {
        return { steps: this.stepsScope };
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
    TClass extends Record<string, any>,
    TKey extends Extract<keyof TClass, string>
>(
    classConstructor: TClass,
    key: TKey,
    params: z.infer<ReturnType<typeof paramsToCallerSchema<TClass[TKey]["inputs"]>>>
): WorkflowTask<TClass[TKey]["inputs"], TClass[TKey]["outputs"]> {
    const value = classConstructor[key];
    return {
        templateRef: { key, value },
        arguments: { parameters: params }
    };
}
