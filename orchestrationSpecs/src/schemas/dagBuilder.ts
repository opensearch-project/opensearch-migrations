import {
    InputParametersRecord, OutputParamDef,
    OutputParametersRecord,
    paramsToCallerSchema
} from "@/schemas/parameterSchemas";
import {ExtendScope, StepsOutputsScope, StepWithOutputs, WorkflowAndTemplatesScope,} from "@/schemas/workflowTypes";
import {z, ZodType} from "zod";
import {TemplateBodyBuilder} from "@/schemas/templateBodyBuilder";
import {PlainObject} from "@/schemas/plainObject";

export class DagBuilder<
    ContextualScope extends WorkflowAndTemplatesScope,
    InputParamsScope  extends InputParametersRecord,
    DagScope extends StepsOutputsScope, // TODO FIXME
    OutputParamsScope extends OutputParametersRecord
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

    addParameterOutput<T extends PlainObject, Name extends string>(name: Name, parameter: string, t: ZodType<T>, descriptionValue?: string):
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

    addExpressionOutput<T extends PlainObject, Name extends string>(name: Name, expression: string, t: ZodType<T>, descriptionValue?: string):
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
