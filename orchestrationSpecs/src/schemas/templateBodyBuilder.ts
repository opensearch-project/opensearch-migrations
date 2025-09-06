import {
    GenericScope,
    InputParamsToExpressions,
    WorkflowAndTemplatesScope,
    WorkflowInputsToExpressions
} from "@/schemas/workflowTypes";
import {ScopeIsEmptyConstraint} from "@/schemas/scopeConstraints";
import {
    InputParametersRecord,
    OutputParametersRecord,
    templateInputParametersAsExpressions,
    workflowParametersAsExpressions
} from "@/schemas/parameterSchemas";

export abstract class TemplateBodyBuilder<
    ContextualScope extends WorkflowAndTemplatesScope,
    InputParamsScope extends InputParametersRecord,
    BodyScope extends GenericScope,
    OutputParamsScope extends OutputParametersRecord,
    // Key detail: Self is NOT fixed to a particular OutputParamsScope
    Self extends TemplateBodyBuilder<
        ContextualScope,
        InputParamsScope,
        BodyScope,
        any,     // <-- decouple Self from OutputParamsScope
        Self
    >
> {
    constructor(
        protected readonly contextualScope: ContextualScope,
        protected readonly inputsScope: InputParamsScope,
        protected readonly bodyScope: BodyScope,
        public readonly outputsScope: OutputParamsScope
    ) {
    }

    addOutputs<NewOutputScope extends OutputParametersRecord>(
        builderFn: (
            tb: Self
        ) => TemplateBodyBuilder<
            ContextualScope,
            InputParamsScope,
            BodyScope,
            NewOutputScope,
            Self
        >,
        check: ScopeIsEmptyConstraint<OutputParamsScope, boolean>
    ): Self &
        TemplateBodyBuilder<
            ContextualScope,
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
                InputParamsScope,
                BodyScope,
                NewOutputScope,
                Self
            >;
    }

    get inputs(): InputParamsToExpressions<InputParamsScope> {
        const rval = templateInputParametersAsExpressions(this.inputsScope);
        return rval as unknown as InputParamsToExpressions<InputParamsScope>;
    }

    get workflowInputs(): WorkflowInputsToExpressions<ContextualScope> {
        const rval = workflowParametersAsExpressions(this.contextualScope.workflowParameters ?? {})
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
