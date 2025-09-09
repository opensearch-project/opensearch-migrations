import {
    GenericScope,
    InputParamsToExpressions,
    WorkflowAndTemplatesScope,
    WorkflowInputsToExpressions,
    ExtendScope,
} from "@/schemas/workflowTypes";
import { ScopeIsEmptyConstraint, UniqueNameConstraintAtDeclaration, UniqueNameConstraintOutsideDeclaration } from "@/schemas/scopeConstraints";
import {
    InputParametersRecord,
    OutputParamDef,
    OutputParametersRecord,
    templateInputParametersAsExpressions,
    workflowParametersAsExpressions
} from "@/schemas/parameterSchemas";
import { AllowLiteralOrExpression } from "@/schemas/workflowTypes";
import { PlainObject } from "@/schemas/plainObject";

/** Rebinder type the concrete subclass provides to the base. */
export type TemplateRebinder<
    ContextualScope extends WorkflowAndTemplatesScope,
    InputParamsScope extends InputParametersRecord,
    BodyBound extends GenericScope
> = <
    NewBodyScope extends BodyBound,
    NewOutputScope extends OutputParametersRecord,
    Self extends TemplateBodyBuilder<
        ContextualScope,
        InputParamsScope,
        NewBodyScope,
        NewOutputScope,
        Self,
        BodyBound
    >
>(
    ctx: ContextualScope,
    inputs: InputParamsScope,
    body: NewBodyScope,
    outputs: NewOutputScope
) => Self;

type ReplaceOutputTypedMembers<
    ContextualScope extends WorkflowAndTemplatesScope,
    InputParamsScope extends InputParametersRecord,
    BodyScope extends BodyBound,
    OutputParamsScope extends OutputParametersRecord,
    Self extends TemplateBodyBuilder<any, any, any, any, any>,
    BodyBound extends GenericScope = GenericScope
> =
    Omit<Self, "outputsScope" | "getFullTemplateScope"> &
    TemplateBodyBuilder<ContextualScope, InputParamsScope, BodyScope, OutputParamsScope, Self, BodyBound>;

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
        BodyBound
    >,
    BodyBound extends GenericScope = GenericScope
> {
    constructor(
        protected readonly contextualScope: ContextualScope,
        public readonly inputsScope: InputParamsScope,
        protected readonly bodyScope: BodyScope,
        public readonly outputsScope: OutputParamsScope,
        protected readonly rebind: TemplateRebinder<ContextualScope, InputParamsScope, BodyBound>
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
            BodyBound
        >,
        _check: ScopeIsEmptyConstraint<OutputParamsScope, boolean>
    ): ReplaceOutputTypedMembers<
        ContextualScope, InputParamsScope, BodyScope, NewOutputScope, Self, BodyBound
    > {
        return builderFn(this as unknown as Self) as unknown as ReplaceOutputTypedMembers<
            ContextualScope, InputParamsScope, BodyScope, NewOutputScope, Self, BodyBound
        >;
    }

    public addExpressionOutput<
        T extends PlainObject,
        Name extends string
    >(
        name: UniqueNameConstraintAtDeclaration<Name, OutputParamsScope>,
        expressionBuilder: UniqueNameConstraintOutsideDeclaration<Name, OutputParamsScope,
            (b: InputParamsToExpressions<InputParamsScope>) => AllowLiteralOrExpression<T>>,
        descriptionValue?: string
    ): ReplaceOutputTypedMembers<
        ContextualScope,
        InputParamsScope,
        BodyScope,
        ExtendScope<OutputParamsScope, { [K in Name]: OutputParamDef<T> }>,
        Self,
        BodyBound
    > {
        const fn = expressionBuilder as (b: InputParamsToExpressions<InputParamsScope>) => AllowLiteralOrExpression<T>;
        const newOutputs = {
            ...this.outputsScope,
            [name as string]: {
                fromWhere: "expression" as const,
                expression: fn(this.inputs),
                description: descriptionValue
            }
        } as ExtendScope<OutputParamsScope, { [K in Name]: OutputParamDef<T> }>;

        return this.rebind(
            this.contextualScope,
            this.inputsScope,
            this.bodyScope,
            newOutputs
        ) as unknown as ReplaceOutputTypedMembers<
            ContextualScope,
            InputParamsScope,
            BodyScope,
            typeof newOutputs,
            Self,
            BodyBound
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
    getFullTemplateScope(): {
        inputs: InputParamsScope,
        outputs: OutputParamsScope,
        body: Record<string, any> // implementation of the body's type is purposefully type-erased
    } {
        return {
            inputs: this.inputsScope,
            outputs: this.outputsScope,
            body: this.getBody()
        };
    }
}
