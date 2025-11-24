/**
 * DESIGN PRINCIPLE: ERGONOMIC AND INTUITIVE API
 *
 * This schema system is designed to provide an intuitive, ergonomic developer experience.
 * Users should NEVER need to use explicit type casts (as any, as string, etc.) or
 * cumbersome workarounds to make the type system work. If the API requires such casts,
 * the type system implementation needs to be improved, not the caller code.
 *
 * The goal is to make template building feel natural and safe, with proper type inference
 * working automatically without forcing developers to manually specify types.
 */

import {
    ConfigMapKeySelector,
    defineParam,
    InputParamDef,
    InputParametersRecord,
    OutputParametersRecord
} from "./parameterSchemas";
import {
    ExtendScope,
    GenericScope,
    InputParamsToExpressions,
    WorkflowAndTemplatesScope,
    WorkflowInputsToExpressions,
} from "./workflowTypes";
import {
    extendScope,
    FieldGroupConstraint,
    ScopeIsEmptyConstraint,
    UniqueNameConstraintAtDeclaration,
    UniqueNameConstraintOutsideDeclaration
} from "./scopeConstraints";
import {StepsBuilder} from "./stepsBuilder";
import {ContainerBuilder} from "./containerBuilder";
import {DeepWiden, PlainObject} from "./plainObject";
import {DagBuilder} from "./dagBuilder";
import {K8sResourceBuilder} from "./k8sResourceBuilder";
import {AllowLiteralOrExpression, expr, isExpression} from "./expression";
import {typeToken, TypeToken} from "./sharedTypes";
import {templateInputParametersAsExpressions, workflowParametersAsExpressions} from "./parameterConversions";
import { Container } from "../kubernetesResourceTypes/kubernetesTypes";
import { SetRequired } from "../utils";

/**
 * Maintains a scope of all previous public parameters (workflow and previous templates' inputs/outputs)
 * as well as newly created inputs/outputs for this template.  To define a specific template, use one of
 * the builder methods, which, like `addTemplate` will return a new builder for that type of template
 * receives the specification up to that point.
 */
export class TemplateBuilder<
    ContextualScope extends WorkflowAndTemplatesScope,
    BodyScope extends GenericScope = GenericScope,
    InputParamsScope extends InputParametersRecord = InputParametersRecord,
    OutputParamsScope extends OutputParametersRecord = OutputParametersRecord
> {
    constructor(protected readonly contextualScope: ContextualScope,
                protected readonly bodyScope: BodyScope,
                public readonly inputScope: InputParamsScope,
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

    public get inputs() {
        const workflowParams = workflowParametersAsExpressions(this.contextualScope.workflowParameters || {});
        const inputParams = templateInputParametersAsExpressions(this.inputScope);

        return {
            workflowParameters: workflowParams as WorkflowInputsToExpressions<ContextualScope>,
            inputParameters: inputParams as InputParamsToExpressions<InputParamsScope>,
            rawParameters: { // just for debugging
                workflow: this.contextualScope,
                currentTemplate: this.getTemplateSignatureScope()
            }
        };
    }

    addOptionalInput<T extends PlainObject, Name extends string>(
        name: UniqueNameConstraintAtDeclaration<Name, InputParamsScope>,
        defaultValueFromScopeFn: UniqueNameConstraintOutsideDeclaration<Name, InputParamsScope,
            (s: {
                workflowParameters: WorkflowInputsToExpressions<ContextualScope>,
                inputParameters: InputParamsToExpressions<InputParamsScope>,
                rawParameters: { workflow: ContextualScope; currentTemplate: InputParamsScope }
            }) => AllowLiteralOrExpression<T>>,
        description?: string
    ): ScopeIsEmptyConstraint<BodyScope, UniqueNameConstraintOutsideDeclaration<Name, InputParamsScope,
        TemplateBuilder<
            ContextualScope,
            BodyScope,
            ExtendScope<InputParamsScope, { [K in Name]: InputParamDef<T, false> }>,
            OutputParamsScope
        >>>
    {
        const fn = defaultValueFromScopeFn as (s: {
            workflowParameters: WorkflowInputsToExpressions<ContextualScope>,
            inputParameters: InputParamsToExpressions<InputParamsScope>,
            rawParameters: { workflow: ContextualScope; currentTemplate: InputParamsScope }
        }) => T;
        const e = fn(this.inputs) as T;
        return this.extendWithParam(name as string,
            defineParam({expression: isExpression(e) ? e : expr.literal(e), description})) as any;
    }

    addOptionalOrConfigMap<T extends PlainObject, Name extends string>(
        name: UniqueNameConstraintAtDeclaration<Name, InputParamsScope>,
        configMapRef: ScopeIsEmptyConstraint<BodyScope, UniqueNameConstraintOutsideDeclaration<Name, InputParamsScope, ConfigMapKeySelector>>,
        t: ScopeIsEmptyConstraint<BodyScope, UniqueNameConstraintOutsideDeclaration<Name, InputParamsScope, TypeToken<T>>>,
        defaultValueFromScopeFn?: UniqueNameConstraintOutsideDeclaration<Name, InputParamsScope,
            (s: {
                workflowParameters: WorkflowInputsToExpressions<ContextualScope>,
                inputParameters: InputParamsToExpressions<InputParamsScope>,
                rawParameters: { workflow: ContextualScope; currentTemplate: InputParamsScope }
            }) => AllowLiteralOrExpression<T>>,
        description?: string
    ): ScopeIsEmptyConstraint<BodyScope, UniqueNameConstraintOutsideDeclaration<Name, InputParamsScope,
        TemplateBuilder<
            ContextualScope,
            BodyScope,
            ExtendScope<InputParamsScope, { [K in Name]: InputParamDef<T, false> }>,
            OutputParamsScope
        >>>
    {
        const fn = defaultValueFromScopeFn as (s: {
            workflowParameters: WorkflowInputsToExpressions<ContextualScope>,
            inputParameters: InputParamsToExpressions<InputParamsScope>,
            rawParameters: { workflow: ContextualScope; currentTemplate: InputParamsScope }
        }) => T;
        return this.extendWithParam(name as string,
            defineParam<DeepWiden<T>>({
                from: configMapRef as ConfigMapKeySelector,
                type: t as TypeToken<DeepWiden<T>>,
                description,
                ...( fn ? {expression: fn(this.inputs) as DeepWiden<T>} : {})
            })) as any;
    }

    addRequiredInput<Name extends string, T extends PlainObject>(
        name: UniqueNameConstraintAtDeclaration<Name, InputParamsScope>,
        t: ScopeIsEmptyConstraint<BodyScope, UniqueNameConstraintOutsideDeclaration<Name, InputParamsScope, TypeToken<T>>>,
        description?: string
    ): UniqueNameConstraintOutsideDeclaration<Name, InputParamsScope,
        TemplateBuilder<
            ContextualScope,
            BodyScope,
            ExtendScope<InputParamsScope, { [K in Name]: InputParamDef<T, true> }>,
            OutputParamsScope
        >>
    {
        const param: InputParamDef<T, true> = {description} as const;
        return this.extendWithParam(name as any, param) as any;
    }

    /**
     * Add multiple incomingRecord using a function that operates on this TemplateBuilder
     * The function can only modify the input scope of the builder - other scopes must remain unchanged
     */
    addInputsFromRecord<
        R extends InputParametersRecord
    >(
        incomingRecord: ScopeIsEmptyConstraint<BodyScope, FieldGroupConstraint<InputParamsScope, R>>
    ): ScopeIsEmptyConstraint<
        BodyScope,
        TemplateBuilder<
            ContextualScope,
            BodyScope,
            ExtendScope<InputParamsScope, R>,
            OutputParamsScope
        >
    > {
        return new TemplateBuilder(
            this.contextualScope,
            this.bodyScope,
            extendScope(this.inputScope, () => incomingRecord as R),
            this.outputScope
        ) as any;
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
        factory?: (context: ContextualScope, inputs: InputParamsScope) => FirstBuilder
    ): FinalBuilder
    {
        const fn = builderFn as (b: StepsBuilder<ContextualScope, InputParamsScope, {}, {}>) => FinalBuilder;
        return fn((factory ??
            ((c, i) => new StepsBuilder(c, i, {}, [], {}, {})))
        (this.contextualScope, this.inputScope));
    }

    addDag<
        FirstBuilder extends DagBuilder<ContextualScope, InputParamsScope, any, any>,
        FinalBuilder extends DagBuilder<ContextualScope, InputParamsScope, any, any>
    >(builderFn: ScopeIsEmptyConstraint<BodyScope,
          (b: DagBuilder<ContextualScope, InputParamsScope, {}, {}>) => FinalBuilder>,
      factory?: (context: ContextualScope, inputs: InputParamsScope) => FirstBuilder
    ): FinalBuilder
    {
        const fn = builderFn as (b: DagBuilder<ContextualScope, InputParamsScope, {}, {}>) => FinalBuilder;
        return fn((factory ??
            ((c, i) => new DagBuilder(c, this.inputScope, {}, [], {}, {})))
        (this.contextualScope, this.inputScope));
    }

    addResourceTask<
        FirstBuilder extends K8sResourceBuilder<ContextualScope, InputParamsScope, any, any>,
        FinalBuilder extends K8sResourceBuilder<ContextualScope, InputParamsScope, any, any>
    >(
        builderFn: ScopeIsEmptyConstraint<BodyScope,
            (b: K8sResourceBuilder<ContextualScope, InputParamsScope, {}, {}>) => FinalBuilder>,
        factory?: (context: ContextualScope, inputs: InputParamsScope) => FirstBuilder
    ): FinalBuilder
    {
        const fn = builderFn as (b: K8sResourceBuilder<ContextualScope, InputParamsScope, {}, {}>) => FinalBuilder;
        return fn((factory ??
            ((c, i) => new K8sResourceBuilder(c, i, {}, {}, {})))
        (this.contextualScope, this.inputScope));
    }

    addContainer<
        FirstBuilder extends ContainerBuilder<ContextualScope, InputParamsScope, any, any, any, any>,
        FinalBuilder extends ContainerBuilder<ContextualScope, InputParamsScope,

         GenericScope &
         // Excluding name from container as it is realized during containerBuilder::getBody()
         SetRequired<Omit<Container, "name">, "resources">,
          any, any, any>,
    >(
        builderFn: ScopeIsEmptyConstraint<BodyScope,
            (b: ContainerBuilder<ContextualScope, InputParamsScope, {}, {}, {}, OutputParamsScope>) => FinalBuilder>,
        factory?: (context: ContextualScope, inputs: InputParamsScope) => FirstBuilder
    ): FinalBuilder    
    {
        const fn = builderFn as (b: ContainerBuilder<ContextualScope, InputParamsScope, {}, {}, {}, {}>) => FinalBuilder;
        const result = fn((factory ??
            ((c, i) => new ContainerBuilder(c, i, {}, {}, {}, {}, {})))
        (this.contextualScope, this.inputScope));
        return result;
    }

    getTemplateSignatureScope(): InputParamsScope {
        return this.inputScope;
    }
}
