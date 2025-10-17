import {
    ExtendScope,
    GenericScope,
    InputParamsToExpressions,
    WorkflowAndTemplatesScope,
    WorkflowInputsToExpressions,
} from "./workflowTypes";
import {
    ScopeIsEmptyConstraint,
    UniqueNameConstraintAtDeclaration,
    UniqueNameConstraintOutsideDeclaration
} from "./scopeConstraints";
import {InputParametersRecord, OutputParamDef, OutputParametersRecord} from "./parameterSchemas";
import {PlainObject} from "./plainObject";
import {AllowLiteralOrExpression, toExpression} from "./expression";
import {templateInputParametersAsExpressions, workflowParametersAsExpressions} from "./parameterConversions";

export type RetryParameters = GenericScope;

/** Rebinder type the concrete subclass provides to the base. */
export type TemplateRebinder<
    ContextualScope extends WorkflowAndTemplatesScope,
    InputParamsScope extends InputParametersRecord,
    BodyBound extends GenericScope,
    ExpressionBuilderContext = { inputs: InputParamsToExpressions<InputParamsScope> }
> = <
    NewBodyScope extends BodyBound,
    NewOutputScope extends OutputParametersRecord,
    Self extends TemplateBodyBuilder<
        ContextualScope,
        InputParamsScope,
        NewBodyScope,
        NewOutputScope,
        Self,
        BodyBound,
        ExpressionBuilderContext
    >
>(
    ctx: ContextualScope,
    inputs: InputParamsScope,
    body: NewBodyScope,
    outputs: NewOutputScope,
    retryParameters: RetryParameters
) => Self;

type ReplaceOutputTypedMembers<
    ContextualScope extends WorkflowAndTemplatesScope,
    InputParamsScope extends InputParametersRecord,
    BodyScope extends BodyBound,
    OutputParamsScope extends OutputParametersRecord,
    Self extends TemplateBodyBuilder<any, any, any, any, any, any, any>,
    BodyBound extends GenericScope = GenericScope,
    ExpressionBuilderContext = { inputs: InputParamsToExpressions<InputParamsScope> }
> =
    Omit<Self, "outputsScope" | "getFullTemplateScope"> &
    TemplateBodyBuilder<
        ContextualScope,
        InputParamsScope,
        BodyScope,
        OutputParamsScope,
        Self,
        BodyBound,
        ExpressionBuilderContext
    >;

export abstract class TemplateBodyBuilder<
    ContextualScope extends WorkflowAndTemplatesScope,
    InputParamsScope extends InputParametersRecord,
    BodyScope extends BodyBound,
    OutputParamsScope extends OutputParametersRecord,
    Self extends TemplateBodyBuilder<
        ContextualScope,
        InputParamsScope,
        BodyScope,
        any,
        Self,
        BodyBound,
        ExpressionBuilderContext
    >,
    BodyBound extends GenericScope = GenericScope,
    ExpressionBuilderContext = { inputs: InputParamsToExpressions<InputParamsScope> }
> {
    constructor(
        protected readonly contextualScope: ContextualScope,
        public readonly inputsScope: InputParamsScope,
        protected readonly bodyScope: BodyScope,
        public readonly outputsScope: OutputParamsScope,
        protected readonly retryParameters: GenericScope,
        protected readonly rebind: TemplateRebinder<ContextualScope, InputParamsScope, BodyBound, ExpressionBuilderContext>
    ) {}

    addOutputs<NewOutputScope extends OutputParametersRecord>(
        builderFn: (
            tb: Self
        ) => TemplateBodyBuilder<
            ContextualScope,
            InputParamsScope,
            BodyScope,
            NewOutputScope,
            Self,
            BodyBound,
            ExpressionBuilderContext
        >,
        _check: ScopeIsEmptyConstraint<OutputParamsScope, boolean>
    ): ReplaceOutputTypedMembers<
        ContextualScope, InputParamsScope, BodyScope, NewOutputScope, Self, BodyBound, ExpressionBuilderContext
    > {
        return builderFn(this as unknown as Self) as unknown as ReplaceOutputTypedMembers<
            ContextualScope, InputParamsScope, BodyScope, NewOutputScope, Self, BodyBound, ExpressionBuilderContext
        >;
    }

    public addRetryParameters(retryParameters: GenericScope) {
        return this.rebind(
            this.contextualScope,
            this.inputsScope,
            this.bodyScope,
            this.outputsScope,
            retryParameters
        );
    }

    /**
     * Default implementation provides just inputs. Subclasses can override to add
     * additional context like steps or tasks.
     */
    protected getExpressionBuilderContext(): ExpressionBuilderContext {
        return {inputs: this.inputs} as ExpressionBuilderContext;
    }

    public addExpressionOutput<
        T extends PlainObject,
        Name extends string
    >(
        name: UniqueNameConstraintAtDeclaration<Name, OutputParamsScope>,
        expressionBuilder: UniqueNameConstraintOutsideDeclaration<Name, OutputParamsScope,
            (b: ExpressionBuilderContext) => AllowLiteralOrExpression<T>>,
        descriptionValue?: string
    ): ReplaceOutputTypedMembers<
        ContextualScope,
        InputParamsScope,
        BodyScope,
        ExtendScope<OutputParamsScope, { [K in Name]: OutputParamDef<T> }>,
        Self,
        BodyBound,
        ExpressionBuilderContext
    > {
        const fn = expressionBuilder as (b: ExpressionBuilderContext) => AllowLiteralOrExpression<T>;
        const newOutputs = {
            ...this.outputsScope,
            [name as string]: {
                fromWhere: "expression" as const,
                expression: toExpression(fn(this.getExpressionBuilderContext())),
                description: descriptionValue
            }
        } as ExtendScope<OutputParamsScope, { [K in Name]: OutputParamDef<T> }>;

        return this.rebind(
            this.contextualScope,
            this.inputsScope,
            this.bodyScope,
            newOutputs,
            this.retryParameters
        ) as unknown as ReplaceOutputTypedMembers<
            ContextualScope,
            InputParamsScope,
            BodyScope,
            typeof newOutputs,
            Self,
            BodyBound,
            ExpressionBuilderContext
        >;
    }

    get inputs(): InputParamsToExpressions<InputParamsScope> {
        const rval = templateInputParametersAsExpressions(this.inputsScope);
        return rval as unknown as InputParamsToExpressions<InputParamsScope>;
    }

    get workflowInputs(): WorkflowInputsToExpressions<ContextualScope> {
        const rval = workflowParametersAsExpressions(this.contextualScope.workflowParameters ?? {});
        return rval as WorkflowInputsToExpressions<ContextualScope>;
    }

    // Type-erasure is fine here.  This is only used for getFullTemplate, where we don't want to allow
    // others to reach into the body anyway.  They should interface through the inputs and outputs exclusively
    protected abstract getBody(): Record<string, any>;

    // used by the TemplateBuilder!
    getFullTemplateScope() {
        return {
            inputs: this.inputsScope,
            outputs: this.outputsScope,
            retryStrategy: this.retryParameters,
            body: this.getBody()
        };
    }
}
