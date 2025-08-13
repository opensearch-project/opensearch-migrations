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

function extendScope<OS extends Scope, NS extends Scope>(orig: OS, fn: ScopeFn<OS, NS>): ExtendScope<OS, NS> {
    return {
        ...orig,
        ...fn(orig)
    } as ExtendScope<OS, NS>;
}

export class WFBuilder<
    MetadataScope extends Scope = Scope,
    WorkflowInputsScope extends Scope = Scope,
    TemplateSigScope extends Scope = Scope,
    TemplateFullScope extends Scope = Scope
> {
    constructor(
        protected readonly metadataScope: MetadataScope,
        protected readonly inputsScope: WorkflowInputsScope,
        protected readonly templateSigScope: TemplateSigScope,
        protected readonly templateFullScope: TemplateFullScope) {
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
    constructor(protected readonly contextualScope: ContextualScope,
                protected readonly bodyScope: BodyScope,
                protected readonly inputScope: InputParamsScope,
                protected readonly outputScope: OutputParamsScope) {
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
        const newScope = extendScope(this.inputScope, s =>
            ({[name]: param})
        );

        return new TemplateBuilder(this.contextualScope, this.bodyScope, newScope, this.outputScope);
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
        (this.contextualScope, this.inputScope));
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
        (this.contextualScope, this.inputScope));
    }

    addContainer<
        FirstBuilder extends ContainerBuilder<ContextualScope, InputParamsScope, any, any, any>,
        FinalBuilder extends ContainerBuilder<ContextualScope, InputParamsScope, any, any, any>
    >(
        builderFn: ScopeIsEmptyConstraint<BodyScope,
            (b: ContainerBuilder<ContextualScope, InputParamsScope, {}, {}, OutputParamsScope>) => FinalBuilder>,
        factory?: (context:ContextualScope, inputs:InputParamsScope) => FirstBuilder
    ): FinalBuilder {
        const fn = builderFn as (b: ContainerBuilder<ContextualScope, InputParamsScope, {}, {}, {}>) => FinalBuilder;
        return fn((factory ??
            ((c, i) => new ContainerBuilder(c, i, {}, {}, {})))
        (this.contextualScope, this.inputScope));
    }

    getTemplateSignatureScope(): InputParamsScope {
        return this.inputScope;
    }
}

abstract class TemplateBodyBuilder<
    ContextualScope extends Scope,
    BodyKey extends string,
    InputParamsScope extends Scope,
    BodyScope extends Scope,
    OutputParamsScope extends Scope,
    // Key detail: Self is NOT fixed to a particular OutputParamsScope
    Self extends TemplateBodyBuilder<
        ContextualScope,
        BodyKey,
        InputParamsScope,
        BodyScope,
        any,     // <-- decouple Self from OutputParamsScope
        Self
    >
> {
    constructor(
        protected readonly bodyKey: BodyKey,
        protected readonly contextualScope: ContextualScope,
        protected readonly inputsScope: InputParamsScope,
        protected readonly bodyScope: BodyScope,
        protected readonly outputsScope: OutputParamsScope
    ) {
    }

    addOutputs<NewOutputScope extends Scope>(
        builderFn: (
            tb: Self
        ) => TemplateBodyBuilder<
            ContextualScope,
            BodyKey,
            InputParamsScope,
            BodyScope,
            NewOutputScope,
            Self
        >,
        check: ScopeIsEmptyConstraint<OutputParamsScope, boolean>
    ): Self &
        TemplateBodyBuilder<
            ContextualScope,
            BodyKey,
            InputParamsScope,
            BodyScope,
            NewOutputScope,
            Self
        > {
        // Keep the concrete subclass instance, but overlay the updated output scope type
        return builderFn(this as unknown as Self) as unknown as
            Self &
            TemplateBodyBuilder<
                ContextualScope,
                BodyKey,
                InputParamsScope,
                BodyScope,
                NewOutputScope,
                Self
            >;
    }

    get inputs(): InputParamsToExpressions<InputParamsScope> {
        const result: any = {};
        Object.keys(this.inputsScope).forEach(key => {
            result[key] = inputParam(key, this.inputsScope[key]);
        });
        return result as InputParamsToExpressions<InputParamsScope>;
    }

    get workflowInputs(): WorkflowInputsToExpressions<ContextualScope> {
        const result: any = {};
        const workflowParams = (this.contextualScope as any).workflowParameters || {};
        Object.keys(workflowParams).forEach(key => {
            result[key] = workflowParam(key, workflowParams[key]);
        });
        return result as WorkflowInputsToExpressions<ContextualScope>;
    }

    // Type-erasure is fine here.  This is only used for getFullTemplate, where we don't want to allow
    // others to reach into the body anyway.  They should interface through the inputs and outputs exclusively
    getBody(): { body: { [K in BodyKey]: Record<string, any> } } {
        const impl = { [this.bodyKey]: this.bodyScope } as Record<BodyKey, BodyScope>;
        return { body: {...impl} };
    }

    // used by the TemplateBuilder!
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


export function inputsToEnvVarNames<T extends Record<string, any>>(inputs: T): Record<string, T[keyof T]> {
    const result: Record<string, any> = {};
    Object.entries(inputs).forEach(([key, value]) => {
        result[toEnvVarName(key)] = value;
    });
    return result;
}

export class ContainerBuilder<
    ContextualScope extends Scope,
    InputParamsScope  extends Scope,
    ContainerScope extends Scope,
    EnvScope extends Scope,  // New scope for environment variables
    OutputParamsScope extends Scope
> extends TemplateBodyBuilder<ContextualScope, "container", InputParamsScope, ContainerScope, OutputParamsScope,
    ContainerBuilder<ContextualScope, InputParamsScope, ContainerScope, EnvScope, any>>
{
    constructor(contextualScope: ContextualScope,
                inputsScope: InputParamsScope,
                bodyScope: ContainerScope,
                public readonly envScope: EnvScope,  // Add env scope to constructor
                outputsScope: OutputParamsScope) {
        super("container", contextualScope, inputsScope, bodyScope, outputsScope)
    }

    // Update existing methods to preserve EnvScope type parameter

    addImageInfo(image: AllowLiteralOrExpression<string>,
                 pullPolicy: AllowLiteralOrExpression<z.infer<typeof IMAGE_PULL_POLICY>>):
        ContainerBuilder<
            ContextualScope,
            InputParamsScope,
            ExtendScope<ContainerScope, {
                image: AllowLiteralOrExpression<string>,
                pullPolicy: AllowLiteralOrExpression<string>
            }>,
            EnvScope,  // Preserve env scope
            OutputParamsScope>
    {
        return new ContainerBuilder(this.contextualScope, this.inputsScope, {
            ...this.bodyScope,
            'image': image,
            'pullPolicy': pullPolicy
        }, this.envScope, this.outputsScope);
    }

    addCommand(s: string[]):
        ContainerBuilder<ContextualScope, InputParamsScope,
            ExtendScope<ContainerScope, {command: string[]}>, EnvScope, OutputParamsScope>
    {
        return new ContainerBuilder(this.contextualScope, this.inputsScope, {
                ...this.bodyScope,
                command: s},
            this.envScope,  // Preserve env scope
            this.outputsScope
        );
    }

    addArgs(a: string[]):
        ContainerBuilder<ContextualScope, InputParamsScope,
            ExtendScope<ContainerScope, {args: string[]}>, EnvScope, OutputParamsScope>
    {
        return new ContainerBuilder(this.contextualScope, this.inputsScope, {
                ...this.bodyScope,
                args: a},
            this.envScope,  // Preserve env scope
            this.outputsScope
        );
    }

    addPathOutput<T, Name extends string>(name: Name, pathValue: string, t: ZodType<T>, descriptionValue?: string):
        ContainerBuilder<ContextualScope, InputParamsScope, ContainerScope, EnvScope,
            ExtendScope<OutputParamsScope, { [K in Name]: OutputParamDef<T> }>> {
        return new ContainerBuilder(this.contextualScope, this.inputsScope, this.bodyScope, this.envScope, {
            ...this.outputsScope,
            [name as string]: {
                type: t,
                fromWhere: "path" as const,
                path: pathValue,
                description: descriptionValue
            }
        });
    }

    addExpressionOutput<T, Name extends string>(name: Name, expression: string, t: ZodType<T>, descriptionValue?: string):
        ContainerBuilder<ContextualScope, InputParamsScope, ContainerScope, EnvScope,
            ExtendScope<OutputParamsScope, { [K in Name]: OutputParamDef<T> }>> {
        return new ContainerBuilder(this.contextualScope, this.inputsScope, this.bodyScope, this.envScope, {
            ...this.outputsScope,
            [name as string]: {
                type: t,
                fromWhere: "expression" as const,
                expression: expression,
                description: descriptionValue
            }
        });
    }


    addEnvVar<Name extends string>(
        name: string extends keyof EnvScope  // If EnvScope is Record<string, X>, disable checking
            ? Name
            : Name extends keyof EnvScope
                ? TypescriptError<`Environment variable '${Name}' already exists`>
                : Name,
        value: AllowLiteralOrExpression<string>
    ): ContainerBuilder<
        ContextualScope,
        InputParamsScope,
        ContainerScope,
        ExtendScope<EnvScope, { [K in Name]: AllowLiteralOrExpression<string> }>,
        OutputParamsScope
    >
    {
        return this.addEnvVarUnchecked(name as string, value) as any;
    }

    addEnvVars<NewEnvScope extends Scope>(
        builderFn: (
            cb: ContainerBuilder<ContextualScope, InputParamsScope, ContainerScope, {}, OutputParamsScope>
        ) => ContainerBuilder<ContextualScope, InputParamsScope, ContainerScope, NewEnvScope, OutputParamsScope>
    ): ScopeIsEmptyConstraint<EnvScope,
        ContainerBuilder<ContextualScope, InputParamsScope, ContainerScope, NewEnvScope, OutputParamsScope>
    >
    {
        const emptyEnvBuilder = new ContainerBuilder(
            this.contextualScope,
            this.inputsScope,
            this.bodyScope,
            {} as {},
            this.outputsScope
        );

        return builderFn(emptyEnvBuilder) as any;
    }

    // Keep the old addEnvVars as addEnvVarsObject for direct object usage
    addEnvVarsObject<NewEnvVars extends Record<string, AllowLiteralOrExpression<string>>>(
        envVars: keyof EnvScope & keyof NewEnvVars extends never
            ? NewEnvVars
            : TypescriptError<`Environment variable '${keyof EnvScope & keyof NewEnvVars & string}' already exists`>
    ): ContainerBuilder<
        ContextualScope,
        InputParamsScope,
        ContainerScope,
        ExtendScope<EnvScope, NewEnvVars>,
        OutputParamsScope
    >
    {
        const currentEnv = (this.bodyScope as any).env || {};
        const newEnvScope = { ...this.envScope, ...envVars } as ExtendScope<EnvScope, NewEnvVars>;

        return new ContainerBuilder(this.contextualScope, this.inputsScope, {
            ...this.bodyScope,
            env: { ...currentEnv, ...envVars }
        }, newEnvScope, this.outputsScope);
    }

    // Internal method without constraint checking for use by other methods
    private addEnvVarUnchecked<Name extends string>(
        name: Name,
        value: AllowLiteralOrExpression<string>
    ): ContainerBuilder<
        ContextualScope,
        InputParamsScope,
        ContainerScope,
        ExtendScope<EnvScope, { [K in Name]: AllowLiteralOrExpression<string> }>,
        OutputParamsScope
    >
    {
        const currentEnv = (this.bodyScope as any).env || {};
        const newEnvScope = { ...this.envScope, [name as string]: value } as ExtendScope<EnvScope, { [K in Name]: AllowLiteralOrExpression<string> }>;

        return new ContainerBuilder(this.contextualScope, this.inputsScope, {
            ...this.bodyScope,
            env: { ...currentEnv, [name as string]: value }
        }, newEnvScope, this.outputsScope);
    }

    // Convenience method to map input parameters to environment variables
    addInputsAsEnvVars<ModifiedInputs extends Record<string, AllowLiteralOrExpression<string>> = never>(
        modifierFn?: (inputs: InputParamsToExpressions<InputParamsScope>) => ModifiedInputs
    ): ScopeIsEmptyConstraint<EnvScope,
        ContainerBuilder<
            ContextualScope,
            InputParamsScope,
            ContainerScope,
            ModifiedInputs extends never
                ? Record<string, AllowLiteralOrExpression<string>>  // Default case - less specific
                : ModifiedInputs,  // Modified case - preserve specific keys
            OutputParamsScope
        >>
    {
        const baseInputs = this.inputs as Record<string, AllowLiteralOrExpression<string>>;
        const envVars = modifierFn
            ? modifierFn(this.inputs)
            : inputsToEnvVarNames(baseInputs);

        // Create new ContainerBuilder with envVars as the complete env scope
        return new ContainerBuilder(this.contextualScope, this.inputsScope, {
            ...this.bodyScope,
            env: envVars
        }, envVars as any, this.outputsScope) as any;
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

class StepGroupBuilder<
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

class DagBuilder<
    ContextualScope extends Scope,
    InputParamsScope  extends Scope,
    DagScope extends Scope,
    OutputParamsScope extends Scope
> extends TemplateBodyBuilder<ContextualScope, "dag", InputParamsScope, DagScope, OutputParamsScope,
    DagBuilder<ContextualScope, InputParamsScope, DagScope, any>>
{
    constructor(readonly contextualScope: ContextualScope,
                readonly inputsScope: InputParamsScope,
                readonly bodyScope: DagScope,
                readonly outputsScope: OutputParamsScope) {
        super("dag", contextualScope, inputsScope, bodyScope, outputsScope)
    }

    addTask() {}

    addParameterOutput<T, Name extends string>(name: Name, parameter: string, t: ZodType<T>, descriptionValue?: string):
        DagBuilder<ContextualScope, InputParamsScope, DagScope,
            ExtendScope<OutputParamsScope, { [K in Name]: OutputParamDef<T> }>> {
        return new DagBuilder(this.contextualScope, this.inputsScope, this.bodyScope, {
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
        DagBuilder<ContextualScope, InputParamsScope, DagScope,
            ExtendScope<OutputParamsScope, { [K in Name]: OutputParamDef<T> }>> {
        return new DagBuilder(this.contextualScope, this.inputsScope, this.bodyScope, {
            ...this.outputsScope,
            [name as string]: {
                type: t,
                fromWhere: "expression" as const,
                expression: expression,
                description: descriptionValue
            }
        });
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
