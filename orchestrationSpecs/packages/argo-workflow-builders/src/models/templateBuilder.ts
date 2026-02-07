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
import {SuspendTemplateBuilder, DurationInSeconds} from "./suspendTemplateBuilder";
import {WaitForResourceBuilder, WaitForResourceOpts} from "./waitForResourceBuilder";
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
    ParentWorkflowScope extends WorkflowAndTemplatesScope,
    BodyScope extends GenericScope = GenericScope,
    InputParamsScope extends InputParametersRecord = InputParametersRecord,
    OutputParamsScope extends OutputParametersRecord = OutputParametersRecord
> {
    constructor(protected readonly parentWorkflowScope: ParentWorkflowScope,
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
        ParentWorkflowScope,
        BodyScope,
        ExtendScope<InputParamsScope, { [K in Name]: InputParamDef<T, R> }>,
        OutputParamsScope
    > {
        const newScope = extendScope(this.inputScope, s =>
            ({[name]: param})
        );

        return new TemplateBuilder(this.parentWorkflowScope, this.bodyScope, newScope, this.outputScope);
    }

    public get inputs() {
        const workflowParams = workflowParametersAsExpressions(this.parentWorkflowScope.workflowParameters || {});
        const inputParams = templateInputParametersAsExpressions(this.inputScope);

        return {
            workflowParameters: workflowParams as WorkflowInputsToExpressions<ParentWorkflowScope>,
            inputParameters: inputParams as InputParamsToExpressions<InputParamsScope>,
            rawParameters: { // just for debugging
                workflow: this.parentWorkflowScope,
                currentTemplate: this.getTemplateSignatureScope()
            }
        };
    }

    addOptionalInput<T extends PlainObject, Name extends string>(
        name: UniqueNameConstraintAtDeclaration<Name, InputParamsScope>,
        defaultValueFromScopeFn: UniqueNameConstraintOutsideDeclaration<Name, InputParamsScope,
            (s: {
                workflowParameters: WorkflowInputsToExpressions<ParentWorkflowScope>,
                inputParameters: InputParamsToExpressions<InputParamsScope>,
                rawParameters: { workflow: ParentWorkflowScope; currentTemplate: InputParamsScope }
            }) => AllowLiteralOrExpression<T>>,
        description?: string
    ): ScopeIsEmptyConstraint<BodyScope, UniqueNameConstraintOutsideDeclaration<Name, InputParamsScope,
        TemplateBuilder<
            ParentWorkflowScope,
            BodyScope,
            ExtendScope<InputParamsScope, { [K in Name]: InputParamDef<T, false> }>,
            OutputParamsScope
        >>>
    {
        const fn = defaultValueFromScopeFn as (s: {
            workflowParameters: WorkflowInputsToExpressions<ParentWorkflowScope>,
            inputParameters: InputParamsToExpressions<InputParamsScope>,
            rawParameters: { workflow: ParentWorkflowScope; currentTemplate: InputParamsScope }
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
                workflowParameters: WorkflowInputsToExpressions<ParentWorkflowScope>,
                inputParameters: InputParamsToExpressions<InputParamsScope>,
                rawParameters: { workflow: ParentWorkflowScope; currentTemplate: InputParamsScope }
            }) => AllowLiteralOrExpression<T>>,
        description?: string
    ): ScopeIsEmptyConstraint<BodyScope, UniqueNameConstraintOutsideDeclaration<Name, InputParamsScope,
        TemplateBuilder<
            ParentWorkflowScope,
            BodyScope,
            ExtendScope<InputParamsScope, { [K in Name]: InputParamDef<T, false> }>,
            OutputParamsScope
        >>>
    {
        const fn = defaultValueFromScopeFn as (s: {
            workflowParameters: WorkflowInputsToExpressions<ParentWorkflowScope>,
            inputParameters: InputParamsToExpressions<InputParamsScope>,
            rawParameters: { workflow: ParentWorkflowScope; currentTemplate: InputParamsScope }
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
            ParentWorkflowScope,
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
            ParentWorkflowScope,
            BodyScope,
            ExtendScope<InputParamsScope, R>,
            OutputParamsScope
        >
    > {
        return new TemplateBuilder(
            this.parentWorkflowScope,
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
        (tb: TemplateBuilder<ParentWorkflowScope, {}, InputParamsScope, {}>) =>
            TemplateBuilder<ParentWorkflowScope, {}, NewInputScope, {}>
    ): ScopeIsEmptyConstraint<BodyScope, ScopeIsEmptyConstraint<InputParamsScope,
        TemplateBuilder<ParentWorkflowScope, BodyScope, NewInputScope, OutputParamsScope>
    >> {
        const fn = builderFn as (
            tb: TemplateBuilder<ParentWorkflowScope, {}, InputParamsScope, {}>
        ) => TemplateBuilder<ParentWorkflowScope, {}, NewInputScope, {}>;

        return fn(this as any) as any;
    }

    addSteps<
        FirstBuilder extends StepsBuilder<ParentWorkflowScope, InputParamsScope, any, any>,
        FinalBuilder extends StepsBuilder<ParentWorkflowScope, InputParamsScope, any, any>
    >(
        builderFn: ScopeIsEmptyConstraint<BodyScope, (
            b: StepsBuilder<ParentWorkflowScope, InputParamsScope, {}, {}>) => FinalBuilder>,
        factory?: (context: ParentWorkflowScope, inputs: InputParamsScope) => FirstBuilder
    ): FinalBuilder
    {
        const fn = builderFn as (b: StepsBuilder<ParentWorkflowScope, InputParamsScope, {}, {}>) => FinalBuilder;
        return fn((factory ??
            ((c, i) => new StepsBuilder(c, i, {}, [], {}, {}, undefined)))
        (this.parentWorkflowScope, this.inputScope));
    }

    addDag<
        FirstBuilder extends DagBuilder<ParentWorkflowScope, InputParamsScope, any, any>,
        FinalBuilder extends DagBuilder<ParentWorkflowScope, InputParamsScope, any, any>
    >(builderFn: ScopeIsEmptyConstraint<BodyScope,
          (b: DagBuilder<ParentWorkflowScope, InputParamsScope, {}, {}>) => FinalBuilder>,
      factory?: (context: ParentWorkflowScope, inputs: InputParamsScope) => FirstBuilder
    ): FinalBuilder
    {
        const fn = builderFn as (b: DagBuilder<ParentWorkflowScope, InputParamsScope, {}, {}>) => FinalBuilder;
        return fn((factory ??
            ((c, i) => new DagBuilder(c, this.inputScope, {}, [], {}, {}, undefined)))
        (this.parentWorkflowScope, this.inputScope));
    }

    addResourceTask<
        FirstBuilder extends K8sResourceBuilder<ParentWorkflowScope, InputParamsScope, any, any>,
        FinalBuilder extends K8sResourceBuilder<ParentWorkflowScope, InputParamsScope, any, any>
    >(
        builderFn: ScopeIsEmptyConstraint<BodyScope,
            (b: K8sResourceBuilder<ParentWorkflowScope, InputParamsScope, {}, {}>) => FinalBuilder>,
        factory?: (context: ParentWorkflowScope, inputs: InputParamsScope) => FirstBuilder
    ): FinalBuilder
    {
        const fn = builderFn as (b: K8sResourceBuilder<ParentWorkflowScope, InputParamsScope, {}, {}>) => FinalBuilder;
        return fn((factory ??
            ((c, i) => new K8sResourceBuilder(c, i, {}, {}, {})))
        (this.parentWorkflowScope, this.inputScope));
    }

    addContainer<
        FirstBuilder extends ContainerBuilder<ParentWorkflowScope, InputParamsScope, any, any, any, any>,
        FinalBuilder extends ContainerBuilder<ParentWorkflowScope, InputParamsScope,

         GenericScope &
         // Excluding name from container as it is realized during containerBuilder::getBody()
         SetRequired<Omit<Container, "name">, "resources">,
          any, any, any>,
    >(
        builderFn: ScopeIsEmptyConstraint<BodyScope,
            (b: ContainerBuilder<ParentWorkflowScope, InputParamsScope, {}, {}, {}, OutputParamsScope>) => FinalBuilder>,
        factory?: (context: ParentWorkflowScope, inputs: InputParamsScope) => FirstBuilder
    ): FinalBuilder    
    {
        const fn = builderFn as (b: ContainerBuilder<ParentWorkflowScope, InputParamsScope, {}, {}, {}, {}>) => FinalBuilder;
        const result = fn((factory ??
            ((c, i) => new ContainerBuilder(c, i, {}, {}, {}, {}, {}, undefined)))
        (this.parentWorkflowScope, this.inputScope));
        return result;
    }

    addSuspend(duration?: DurationInSeconds): SuspendTemplateBuilder<ParentWorkflowScope, InputParamsScope, {}> {
        return new SuspendTemplateBuilder(
            this.parentWorkflowScope,
            this.inputScope,
            duration !== undefined ? { duration } : {},
            {},
            undefined
        );
    }

    addWaitForResource(
        opts: ScopeIsEmptyConstraint<BodyScope, WaitForResourceOpts>
    ): WaitForResourceBuilder<ParentWorkflowScope, InputParamsScope, {}> {
        return new WaitForResourceBuilder(
            this.parentWorkflowScope,
            this.inputScope,
            opts as WaitForResourceOpts,
            {},
            undefined
        );
    }

    getTemplateSignatureScope(): InputParamsScope {
        return this.inputScope;
    }
}
