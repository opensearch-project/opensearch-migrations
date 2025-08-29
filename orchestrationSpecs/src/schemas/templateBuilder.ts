import {
    defineParam,
    InputParamDef, InputParametersRecord, OutputParametersRecord,
    templateInputParametersAsExpressions,
    workflowParametersAsExpressions
} from "@/schemas/parameterSchemas";
import {
    ExtendScope,
    FieldSpecs,
    FieldGroupConstraint,
    FieldSpecsToInputParams,
    WorkflowInputsToExpressions,
    InputParamsToExpressions,
    AllowLiteralOrExpression,
    GenericScope,
} from "@/schemas/workflowTypes";
import {z, ZodType} from "zod";
import {TypescriptError} from "@/utils";
import {
    extendScope,
    ScopeIsEmptyConstraint,
    UniqueNameConstraintAtDeclaration,
    UniqueNameConstraintOutsideDeclaration
} from "./scopeConstraints";
import {StepsBuilder} from "@/schemas/stepsBuilder";
import {ContainerBuilder} from "@/schemas/containerBuilder";
import {DeepWiden, PlainObject} from "@/schemas/plainObject";
import {DagBuilder} from "@/schemas/dagBuilder";
import {K8sResourceBuilder} from "@/schemas/k8sResourceBuilder";

/**
 * Maintains a scope of all previous public parameters (workflow and previous templates' inputs/outputs)
 * as well as newly created inputs/outputs for this template.  To define a specific template, use one of
 * the builder methods, which, like `addTemplate` will return a new builder for that type of template
 * receives the specification up to that point.
 */
export class TemplateBuilder<
    ContextualScope extends { workflowParameters?: InputParametersRecord },
    BodyScope extends GenericScope = GenericScope,
    InputParamsScope extends InputParametersRecord = InputParametersRecord,
    OutputParamsScope extends OutputParametersRecord = OutputParametersRecord
> {
    constructor(protected readonly contextualScope: ContextualScope,
                protected readonly bodyScope: BodyScope,
                protected readonly inputScope: InputParamsScope,
                protected readonly outputScope: OutputParamsScope) {
    }

    private extendWithParam<
        Name extends string,
        R extends boolean,
        T extends PlainObject
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

    addOptionalInput<T extends PlainObject, Name extends string>(
        name: UniqueNameConstraintAtDeclaration<Name, InputParamsScope>,
        defaultValueFromScopeFn: UniqueNameConstraintOutsideDeclaration<Name, InputParamsScope,
            (s: {
                workflowParameters: WorkflowInputsToExpressions<ContextualScope>,
                inputParameters: InputParamsToExpressions<InputParamsScope>,
                rawParameters: { workflow: ContextualScope; currentTemplate: InputParamsScope }
            }) => AllowLiteralOrExpression<DeepWiden<T>>>,
        description?: string
    ): ScopeIsEmptyConstraint<BodyScope, UniqueNameConstraintOutsideDeclaration<Name, InputParamsScope,
        TemplateBuilder<
            ContextualScope,
            BodyScope,
            ExtendScope<InputParamsScope, { [K in Name]: InputParamDef<DeepWiden<T>, false> }>,
            OutputParamsScope
        >>>
    {
        const fn = defaultValueFromScopeFn as (s: {
            workflowParameters: WorkflowInputsToExpressions<ContextualScope>,
            inputParameters: InputParamsToExpressions<InputParamsScope>,
            rawParameters: { workflow: ContextualScope; currentTemplate: InputParamsScope }
        }) => T;
        const param = defineParam({
            defaultValue: fn({
                workflowParameters: workflowParametersAsExpressions(this.contextualScope.workflowParameters || {}) as unknown as WorkflowInputsToExpressions<ContextualScope>,
                inputParameters: templateInputParametersAsExpressions(this.inputScope) as unknown as InputParamsToExpressions<InputParamsScope>,
                rawParameters: { workflow: this.contextualScope,
                currentTemplate: this.getTemplateSignatureScope()
            }}) as DeepWiden<T>,
            description
        });

        return this.extendWithParam(name as string, param) as any;
    }

    addRequiredInput<Name extends string, T extends PlainObject>(
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
    addInputs<NewInputScope extends InputParametersRecord>(
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
            ((c, i) => new DagBuilder(c,this.inputScope,{},[],{})))
        (this.contextualScope, this.inputScope));
    }

    addResourceTask<
        FirstBuilder extends K8sResourceBuilder<ContextualScope, InputParamsScope, any, any>,
        FinalBuilder extends K8sResourceBuilder<ContextualScope, InputParamsScope, any, any>
    >(
        builderFn: ScopeIsEmptyConstraint<BodyScope,
            (b: K8sResourceBuilder<ContextualScope, InputParamsScope, {}, {}>) => FinalBuilder>,
        factory?: (context:ContextualScope, inputs:InputParamsScope) => FirstBuilder
    ): FinalBuilder {
        const fn = builderFn as (b: K8sResourceBuilder<ContextualScope, InputParamsScope, {}, {}>) => FinalBuilder;
        return fn((factory ??
            ((c, i) => new K8sResourceBuilder(c, i, {}, {})))
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
