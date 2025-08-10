import {
    defineParam,
    InputParamDef,
    InputParametersRecord,
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
    FieldSpecsToInputParams
} from "@/schemas/workflowTypes";
import {z, ZodType, ZodTypeAny} from "zod";
import {TypescriptError} from "@/utils";
import {Expression, inputParam, inputParams} from "@/schemas/expression";
import {IMAGE_PULL_POLICY} from "@/schemas/userSchemas";

declare const __PREFER_UNIQUE_NAME_CHECKS_AT_NAME_SITE__: false;

export type UniqueNameConstraintOutsideDeclaration<Name extends string, S, TypeWhenValid> =
    typeof __PREFER_UNIQUE_NAME_CHECKS_AT_NAME_SITE__ extends false
        ? Name extends keyof S ? TypescriptError<`Name '${Name}' exists within ${keyof S & string}`> : TypeWhenValid
        : TypeWhenValid;

export type UniqueNameConstraintAtDeclaration<Name extends string, S> =
    typeof __PREFER_UNIQUE_NAME_CHECKS_AT_NAME_SITE__ extends true
        ? Name extends keyof S ? TypescriptError<`Name '${Name}' exists within  ${keyof S & string}.`> : Name
        : Name;

export type ScopeIsEmptyConstraint<S, T> =
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
    ): keyof WorkflowInputsScope & keyof P extends never
        ? WFBuilder<
            MetadataScope,
            ExtendScope<WorkflowInputsScope, P>,
            TemplateSigScope,
            TemplateFullScope
        >
        : TypescriptError<`Parameter name '${keyof WorkflowInputsScope & keyof P & string}' already exists in workflow inputs`> {
        const newInputs = { ...this.inputsScope, ...params } as ExtendScope<WorkflowInputsScope, P>;
        return new WFBuilder(
            this.metadataScope,
            newInputs,
            this.templateSigScope,
            this.templateFullScope
        ) as any;
    }

    addTemplate<
        Name extends string,
        TB extends { getFullTemplateScope(): any },
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

    addOptionalInput<T, Name extends string>(
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
        >>> {
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

    addRequiredInput<Name extends string, T>(
        name: UniqueNameConstraintAtDeclaration<Name, InputParamsScope>,
        t: ScopeIsEmptyConstraint<BodyScope, UniqueNameConstraintOutsideDeclaration<Name, InputParamsScope, ZodType<T>>>,
        description?: string
    ): UniqueNameConstraintOutsideDeclaration<Name, InputParamsScope,
        TemplateBuilder<
            ContextualScope,
            BodyScope,
            ExtendScope<InputParamsScope, { [K in Name]: InputParamDef<T, true> }>,
            OutputParamsScope
        >> {
        const param: InputParamDef<T, true> = {
            type: t as ZodType<T>,
            description
        };

        return this.extendWithParam(name as any, param) as any;
    }

    /**
     * Add multiple fields at once from a field specification object.
     * Provides type safety by checking for name conflicts with existing fields.
     */
    addMultipleRequiredInputs_withoutStrongTypesYet<T extends FieldSpecs>(
        fieldSpecs: FieldGroupConstraint<T, InputParamsScope, T>,
        checkTypes: keyof InputParamsScope & keyof T extends never
            ? any
            : TypescriptError<`Cannot add field group: '${keyof InputParamsScope & keyof T & string}' already exists`>
    ): ScopeIsEmptyConstraint<BodyScope,
        TemplateBuilder<
            ContextualScope,
            BodyScope,
            ExtendScope<InputParamsScope, FieldSpecsToInputParams<T>>,
            OutputParamsScope
        >
    > {
        const specs = fieldSpecs as T;

        // Iterate over the field specs and add each one
        let result = this as any;
        for (const [fieldName, schema] of Object.entries(specs)) {
            result = result.addRequiredInput(fieldName, schema);
        }
        return result as any;
    }

    /**
     * Add multiple inputs using a function that operates on this TemplateBuilder
     * The function can only modify inputs - other scopes must remain unchanged
     */
    addInputs<NewInputScope extends Scope>(
        builderFn:
        (tb: TemplateBuilder<ContextualScope, {}, InputParamsScope, {}>) =>
            TemplateBuilder<ContextualScope, {}, NewInputScope, {}>
    ): ScopeIsEmptyConstraint<BodyScope, ScopeIsEmptyConstraint<InputParamsScope,
        TemplateBuilder<ContextualScope, BodyScope, NewInputScope, OutputParamsScope>
    >> {
        const fn = builderFn as (
            tb: TemplateBuilder<ContextualScope, {}, InputParamsScope, {}>
        ) => TemplateBuilder<ContextualScope, {}, NewInputScope, {}>;

        return fn(this as any) as any;
    }

    addSteps<
        FirstBuilder extends StepsBuilder<ContextualScope, InputParamsScope, any, any>,
        FinalBuilder extends StepsBuilder<ContextualScope, InputParamsScope, any, any>
    >(
        builderFn: ScopeIsEmptyConstraint<BodyScope, (
            b: StepsBuilder<ContextualScope, InputParamsScope, {}, {}>) => FinalBuilder>,
        factory?: (context:ContextualScope, inputs:InputParamsScope) => FirstBuilder
    ): FinalBuilder
    {
        const fn = builderFn as (b: StepsBuilder<ContextualScope, InputParamsScope, {}, {}>) => FinalBuilder;
        return fn((factory ??
            ((c, i) => new StepsBuilder(c, i, {}, [], {})))
        (this.contextualScope, this.inputScopeBuilder.getScope()));
    }

    addDag<
        FirstBuilder extends DagBuilder<ContextualScope, InputParamsScope, any, any>,
        FinalBuilder extends DagBuilder<ContextualScope, InputParamsScope, any, any>
    >(builderFn: ScopeIsEmptyConstraint<BodyScope,
        (b: DagBuilder<ContextualScope, InputParamsScope, {}, {}>) => FinalBuilder>,
      factory?: (context:ContextualScope, inputs:InputParamsScope) => FirstBuilder
    ): FinalBuilder
    {
        const fn = builderFn as (b: DagBuilder<ContextualScope, InputParamsScope, {}, {}>) => FinalBuilder;
        return fn((factory ??
            ((c, i) => new DagBuilder(c, i, {}, {})))
        (this.contextualScope, this.inputScopeBuilder.getScope()));
    }

    addContainer<
        FirstBuilder extends ContainerBuilder<ContextualScope, InputParamsScope, any, any>,
        FinalBuilder extends ContainerBuilder<ContextualScope, InputParamsScope, any, any>
    >(
        builderFn: ScopeIsEmptyConstraint<BodyScope,
            (b: ContainerBuilder<ContextualScope, InputParamsScope, {}, OutputParamsScope>) => FinalBuilder>,
        factory?: (context:ContextualScope, inputs:InputParamsScope) => FirstBuilder
    ): FinalBuilder {
        const fn = builderFn as (b: ContainerBuilder<ContextualScope, InputParamsScope, {}, {}>) => FinalBuilder;
        return fn((factory ??
            ((c, i) => new ContainerBuilder(c, i, {}, {})))
        (this.contextualScope, this.inputScopeBuilder.getScope()));
    }

    getTemplateSignatureScope(): InputParamsScope {
        return this.inputScopeBuilder.getScope();
    }
}

abstract class TemplateBodyBuilder<
    ContextualScope extends Scope,
    BodyKey extends string,
    InputParamsScope  extends Scope,
    BodyScope extends Scope,
    OutputParamsScope extends Scope
> {
    constructor(readonly bodyKey: BodyKey,
                readonly contextualScope: ContextualScope,
                readonly inputsScope: InputParamsScope,
                readonly bodyScope: BodyScope,
                readonly outputsScope: OutputParamsScope) {
    }

    getInputParam<K extends keyof InputParamsScope>(
        key: K
    ): ReturnType<typeof inputParam<
        InputParamsScope[K] extends { type: ZodType<infer T> } ? T : never
    >> {
        return inputParam(key as string, this.inputsScope[key]);
    }

    /**
     * Add multiple outputs using a function that operates on this TemplateBuilder
     * The function can only modify outputs - other scopes must remain unchanged.
     */
    addOutputs<NewOuputScope extends Scope>(
        builderFn:
        (tb: TemplateBuilder<ContextualScope, {}, InputParamsScope, OutputParamsScope>) =>
            TemplateBuilder<ContextualScope, {}, InputParamsScope, NewOuputScope>,
        check: ScopeIsEmptyConstraint<OutputParamsScope, boolean>
    ): ScopeIsEmptyConstraint<OutputParamsScope,
        TemplateBodyBuilder<ContextualScope, BodyKey, InputParamsScope, BodyScope, NewOuputScope>>
    {
        const fn = builderFn as (
            tb: TemplateBuilder<ContextualScope, {}, InputParamsScope, OutputParamsScope>
        ) => TemplateBuilder<ContextualScope, {}, InputParamsScope, NewOuputScope>;

        return fn(this as any) as any;
    }

    getBody(): { body: { [K in BodyKey]: Record<string, any> } } {
        const impl = { [this.bodyKey]: this.bodyScope } as Record<BodyKey, BodyScope>;
        return { body: {...impl} };
    }

    getFullTemplateScope(): {
        inputs: InputParamsScope,
        outputs: OutputParamsScope,
        body: Record<string, any> // implementation of the body is purposefully type-erased
    } {
        return {
            inputs: this.inputsScope,
            outputs: this.outputsScope,
            ...this.getBody()
        };
    }
}

class ContainerBuilder<
    ContextualScope extends Scope,
    InputParamsScope  extends Scope,
    ContainerScope extends Scope,
    OutputParamsScope extends Scope
> extends TemplateBodyBuilder<ContextualScope, "container", InputParamsScope, ContainerScope, OutputParamsScope> {

    constructor(readonly contextualScope: ContextualScope,
                readonly inputsScope: InputParamsScope,
                readonly bodyScope: ContainerScope,
                readonly outputsScope: OutputParamsScope) {
        super("container", contextualScope, inputsScope, bodyScope, outputsScope)
    }

    addImageInfo(image: Expression<string>,
                 pullPolicy: Expression<z.infer<typeof IMAGE_PULL_POLICY>>):
        ContainerBuilder<
            ContextualScope,
            InputParamsScope,
            ExtendScope<ContainerScope, {image: Expression<string>, pullPolicy: Expression<string> }>,
            OutputParamsScope>
    {
        return new ContainerBuilder(this.contextualScope, this.inputsScope, {
            ...this.bodyScope,
            'image': image,
            'pullPolicy': pullPolicy
        }, this.outputsScope);
    }

    addCommand(s: string[]):
        ContainerBuilder<ContextualScope, InputParamsScope,
            ExtendScope<ContainerScope, {command: string[]}>, OutputParamsScope>
    {
        return new ContainerBuilder(this.contextualScope, this.inputsScope, {
                ...this.bodyScope,
                command: s},
            this.outputsScope
        );
    }

    addArgs(a: string[]):
        ContainerBuilder<ContextualScope, InputParamsScope,
            ExtendScope<ContainerScope, {args: string[]}>, OutputParamsScope>
    {
        return new ContainerBuilder(this.contextualScope, this.inputsScope, {
                ...this.bodyScope,
                args: a},
            this.outputsScope
        );
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
}

class StepsBuilder<
    ContextualScope extends Scope,
    InputParamsScope  extends Scope,
    StepsScope extends Scope,
    OutputParamsScope extends Scope
> extends TemplateBodyBuilder<ContextualScope, "steps", InputParamsScope, StepsScope, OutputParamsScope> {
    private readonly stepGroups: StepGroup[] = []; // Runtime ordering

    constructor(readonly contextualScope: ContextualScope,
                readonly inputsScope: InputParamsScope,
                readonly bodyScope: StepsScope,
                stepGroups: StepGroup[],
                readonly outputsScope: OutputParamsScope) {
        super("steps", contextualScope, inputsScope, bodyScope, outputsScope)
        this.stepGroups = stepGroups;
    }

    addStepGroup<NewStepScope extends Scope,
        GB extends StepGroupBuilder<any, any>,
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
        StepDef,
        TWorkflow extends { templates: Record<string, { inputs: InputParametersRecord; outputs?: OutputParametersRecord }> },
        TKey extends Extract<keyof TWorkflow["templates"], string>
    >(
        name: UniqueNameConstraintAtDeclaration<Name, StepsScope>,
        workflowBuilder: UniqueNameConstraintOutsideDeclaration<Name, StepsScope, TWorkflow>,
        key: UniqueNameConstraintOutsideDeclaration<Name, StepsScope, TKey>,
        params: UniqueNameConstraintOutsideDeclaration<Name, StepsScope,
            z.infer<ReturnType<typeof paramsToCallerSchema<TWorkflow["templates"][TKey]["inputs"]>>>>
    ): UniqueNameConstraintOutsideDeclaration<Name, StepsScope,
        StepsBuilder<
            ContextualScope,
            InputParamsScope,
            ExtendScope<StepsScope, { [K in Name]: StepDef }>,
            OutputParamsScope
        >
    > {
        return this.addStepGroup(groupBuilder => {
            // addStep returns a constrained type, so we need to cast it for internal use
            return groupBuilder.addStep(name, workflowBuilder, key, params) as any;
        }) as any;
    }

    getBody(): { body: { steps: Record<string, any> } } {
        // discard stepsScope as it was only necessary for building outputs.
        // it isn't preserved as a map for the final, ordered representation
        return { body: { steps: this.stepGroups } };
    }
}

class StepGroupBuilder<
    ContextualScope extends Scope,
    StepsScope extends Scope
> {
    readonly contextualScope: ContextualScope;
    readonly stepsScope: StepsScope;
    private readonly stepTasks: StepTask[] = [];

    constructor(contextualScope: ContextualScope, stepsScope: StepsScope, stepTasks: StepTask[]) {
        this.contextualScope = contextualScope;
        this.stepsScope = stepsScope;
        this.stepTasks = stepTasks;
    }

    addStep<
        Name extends string,
        StepDef,
        TWorkflow extends { templates: Record<string, { inputs: InputParametersRecord; outputs?: OutputParametersRecord }> },
        TKey extends Extract<keyof TWorkflow["templates"], string>
    >(
        name: UniqueNameConstraintAtDeclaration<Name, StepsScope>,
        workflowBuilder: UniqueNameConstraintOutsideDeclaration<Name, StepsScope, TWorkflow>,
        key: UniqueNameConstraintOutsideDeclaration<Name, StepsScope, TKey>,
        params: UniqueNameConstraintOutsideDeclaration<Name, StepsScope, z.infer<ReturnType<typeof paramsToCallerSchema<TWorkflow["templates"][TKey]["inputs"]>>>>
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
            arguments: templateCall.arguments
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
    InputParamsScope  extends Scope,
    DagScope extends Scope,
    OutputParamsScope extends Scope
> extends TemplateBodyBuilder<ContextualScope, "dag", InputParamsScope, DagScope, OutputParamsScope>{
    constructor(readonly contextualScope: ContextualScope,
                readonly inputsScope: InputParamsScope,
                readonly bodyScope: DagScope,
                readonly outputsScope: OutputParamsScope) {
        super("dag", contextualScope, inputsScope, bodyScope, outputsScope)
    }

    addTask() {}
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
