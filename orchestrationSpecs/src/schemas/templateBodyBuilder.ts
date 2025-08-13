import {Scope,InputParamsToExpressions, WorkflowInputsToExpressions} from "@/schemas/workflowTypes";
import {inputParam, workflowParam} from "@/schemas/expression";
import {ScopeIsEmptyConstraint} from "@/schemas/scopeConstraints";

export abstract class TemplateBodyBuilder<
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
