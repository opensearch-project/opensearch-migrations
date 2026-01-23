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
import {SynchronizationConfig} from "./synchronization";

export type RetryParameters = GenericScope;

/** Rebinder type for non-retryable templates */
export type TemplateRebinder<
    ParentWorkflowScope extends WorkflowAndTemplatesScope,
    InputParamsScope extends InputParametersRecord,
    BodyBound extends GenericScope,
    ExpressionBuilderContext = { inputs: InputParamsToExpressions<InputParamsScope> }
> = <
    NewBodyScope extends BodyBound,
    NewOutputScope extends OutputParametersRecord,
    Self extends TemplateBodyBuilder<
        ParentWorkflowScope,
        InputParamsScope,
        NewBodyScope,
        NewOutputScope,
        Self,
        BodyBound,
        ExpressionBuilderContext
    >
>(
    ctx: ParentWorkflowScope,
    inputs: InputParamsScope,
    body: NewBodyScope,
    outputs: NewOutputScope,
    synchronization: SynchronizationConfig | undefined
) => Self;

/** Rebinder type for retryable templates */
export type RetryableTemplateRebinder<
    ParentWorkflowScope extends WorkflowAndTemplatesScope,
    InputParamsScope extends InputParametersRecord,
    BodyBound extends GenericScope,
    ExpressionBuilderContext = { inputs: InputParamsToExpressions<InputParamsScope> }
> = <
    NewBodyScope extends BodyBound,
    NewOutputScope extends OutputParametersRecord,
    Self extends RetryableTemplateBodyBuilder<
        ParentWorkflowScope,
        InputParamsScope,
        NewBodyScope,
        NewOutputScope,
        Self,
        BodyBound,
        ExpressionBuilderContext
    >
>(
    ctx: ParentWorkflowScope,
    inputs: InputParamsScope,
    body: NewBodyScope,
    outputs: NewOutputScope,
    retryParameters: RetryParameters,
    synchronization: SynchronizationConfig | undefined
) => Self;

type ReplaceOutputTypedMembers<
    ParentWorkflowScope extends WorkflowAndTemplatesScope,
    InputParamsScope extends InputParametersRecord,
    BodyScope extends BodyBound,
    OutputParamsScope extends OutputParametersRecord,
    Self extends TemplateBodyBuilder<any, any, any, any, any, any, any>,
    BodyBound extends GenericScope = GenericScope,
    ExpressionBuilderContext = { inputs: InputParamsToExpressions<InputParamsScope> }
> =
    Omit<Self, "outputsScope" | "getFullTemplateScope"> &
    TemplateBodyBuilder<
        ParentWorkflowScope,
        InputParamsScope,
        BodyScope,
        OutputParamsScope,
        Self,
        BodyBound,
        ExpressionBuilderContext
    >;

export abstract class TemplateBodyBuilder<
    ParentWorkflowScope extends WorkflowAndTemplatesScope,
    InputParamsScope extends InputParametersRecord,
    BodyScope extends BodyBound,
    OutputParamsScope extends OutputParametersRecord,
    Self extends TemplateBodyBuilder<
        ParentWorkflowScope,
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
        protected readonly parentWorkflowScope: ParentWorkflowScope,
        public readonly inputsScope: InputParamsScope,
        protected readonly bodyScope: BodyScope,
        public readonly outputsScope: OutputParamsScope,
        protected readonly synchronization: SynchronizationConfig | undefined,
        protected readonly rebind: TemplateRebinder<ParentWorkflowScope, InputParamsScope, BodyBound, ExpressionBuilderContext>
    ) {}

    addOutputs<NewOutputScope extends OutputParametersRecord>(
        builderFn: (
            tb: Self
        ) => TemplateBodyBuilder<
            ParentWorkflowScope,
            InputParamsScope,
            BodyScope,
            NewOutputScope,
            Self,
            BodyBound,
            ExpressionBuilderContext
        >,
        _check: ScopeIsEmptyConstraint<OutputParamsScope, boolean>
    ): ReplaceOutputTypedMembers<
        ParentWorkflowScope, InputParamsScope, BodyScope, NewOutputScope, Self, BodyBound, ExpressionBuilderContext
    > {
        return builderFn(this as unknown as Self) as unknown as ReplaceOutputTypedMembers<
            ParentWorkflowScope, InputParamsScope, BodyScope, NewOutputScope, Self, BodyBound, ExpressionBuilderContext
        >;
    }

    public addSynchronization(synchronizationBuilderFn: (b: ExpressionBuilderContext) => SynchronizationConfig) {
        return this.rebind(
            this.parentWorkflowScope,
            this.inputsScope,
            this.bodyScope,
            this.outputsScope,
            synchronizationBuilderFn(this.getExpressionBuilderContext())
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
        ParentWorkflowScope,
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
            this.parentWorkflowScope,
            this.inputsScope,
            this.bodyScope,
            newOutputs,
            this.synchronization
        ) as unknown as ReplaceOutputTypedMembers<
            ParentWorkflowScope,
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

    get workflowInputs(): WorkflowInputsToExpressions<ParentWorkflowScope> {
        const rval = workflowParametersAsExpressions(this.parentWorkflowScope.workflowParameters ?? {});
        return rval as WorkflowInputsToExpressions<ParentWorkflowScope>;
    }

    protected abstract getBody(): Record<string, any>;

    getFullTemplateScope() {
        return {
            inputs: this.inputsScope,
            outputs: this.outputsScope,
            body: this.getBody(),
            synchronization: this.synchronization
        };
    }
}

/** Extended builder for templates that support retry strategies */
export abstract class RetryableTemplateBodyBuilder<
    ParentWorkflowScope extends WorkflowAndTemplatesScope,
    InputParamsScope extends InputParametersRecord,
    BodyScope extends BodyBound,
    OutputParamsScope extends OutputParametersRecord,
    Self extends RetryableTemplateBodyBuilder<
        ParentWorkflowScope,
        InputParamsScope,
        BodyScope,
        any,
        Self,
        BodyBound,
        ExpressionBuilderContext
    >,
    BodyBound extends GenericScope = GenericScope,
    ExpressionBuilderContext = { inputs: InputParamsToExpressions<InputParamsScope> }
> extends TemplateBodyBuilder<
    ParentWorkflowScope,
    InputParamsScope,
    BodyScope,
    OutputParamsScope,
    Self,
    BodyBound,
    ExpressionBuilderContext
> {
    constructor(
        parentWorkflowScope: ParentWorkflowScope,
        inputsScope: InputParamsScope,
        bodyScope: BodyScope,
        outputsScope: OutputParamsScope,
        protected readonly retryParameters: RetryParameters,
        synchronization: SynchronizationConfig | undefined,
        protected readonly retryableRebind: RetryableTemplateRebinder<ParentWorkflowScope, InputParamsScope, BodyBound, ExpressionBuilderContext>
    ) {
        const baseRebind: TemplateRebinder<ParentWorkflowScope, InputParamsScope, BodyBound, ExpressionBuilderContext> = (
            ctx, inputs, body, outputs, sync
        ) => retryableRebind(ctx, inputs, body, outputs, this.retryParameters, sync) as any;
        
        super(parentWorkflowScope, inputsScope, bodyScope, outputsScope, synchronization, baseRebind);
    }

    public addRetryParameters(retryParameters: GenericScope) {
        return this.retryableRebind(
            this.parentWorkflowScope,
            this.inputsScope,
            this.bodyScope,
            this.outputsScope,
            retryParameters,
            this.synchronization
        );
    }

    public addSynchronization(synchronizationBuilderFn: (b: ExpressionBuilderContext) => SynchronizationConfig) {
        return this.retryableRebind(
            this.parentWorkflowScope,
            this.inputsScope,
            this.bodyScope,
            this.outputsScope,
            this.retryParameters,
            synchronizationBuilderFn(this.getExpressionBuilderContext())
        );
    }

    getFullTemplateScope() {
        return {
            inputs: this.inputsScope,
            outputs: this.outputsScope,
            retryStrategy: this.retryParameters,
            body: this.getBody(),
            synchronization: this.synchronization
        };
    }
}
