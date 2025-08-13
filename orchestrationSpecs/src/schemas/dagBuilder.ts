import {
    InputParametersRecord, OutputParamDef,
    OutputParametersRecord,
    paramsToCallerSchema
} from "@/schemas/parameterSchemas";
import {Scope, ExtendScope,} from "@/schemas/workflowTypes";
import {z, ZodType} from "zod";
import {TemplateBodyBuilder} from "@/schemas/templateBodyBuilder";

export class DagBuilder<
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
