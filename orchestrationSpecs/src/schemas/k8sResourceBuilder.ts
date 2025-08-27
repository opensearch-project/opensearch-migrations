import {OutputParamDef, InputParametersRecord, OutputParametersRecord} from "@/schemas/parameterSchemas";
import {
    DataScope,
    ExtendScope,
    AllowLiteralOrExpression,
    InputParamsToExpressions,
    WorkflowAndTemplatesScope, GenericScope
} from "@/schemas/workflowTypes";
import {z, ZodType} from "zod";
import {TemplateBodyBuilder} from "@/schemas/templateBodyBuilder";
import {PlainObject} from "@/schemas/plainObject";
import {BaseExpression} from "@/schemas/expression";

export type K8sActionVerb = "create" | "get" | "apply" | "delete" | "replace" | "patch";

/**
 * This is partial representation of the io.argoproj.workflow.v1alpha1.ResourceTemplate
 * with limited type-safety across its fields.  Over time, this can be expanded to provide more value
 */
export type ResourceWorkflowDefinition = {
    action: AllowLiteralOrExpression<K8sActionVerb>,
    setOwnerReference?: AllowLiteralOrExpression<boolean>,
    // make these more strongly typed!
    successCondition?: AllowLiteralOrExpression<string>,
    manifest: Record<string, any>
};

export class K8sResourceBuilder<
    ContextualScope extends WorkflowAndTemplatesScope,
    InputParamsScope extends InputParametersRecord,
    ContainerScope extends GenericScope,
    OutputParamsScope extends OutputParametersRecord
> extends TemplateBodyBuilder<ContextualScope, "container", InputParamsScope, ContainerScope, OutputParamsScope,
    K8sResourceBuilder<ContextualScope, InputParamsScope, ContainerScope, any>>
{
    constructor(contextualScope: ContextualScope,
                inputsScope: InputParamsScope,
                bodyScope: ContainerScope,
                outputsScope: OutputParamsScope,
                public readonly workflowDefinition?: ResourceWorkflowDefinition) {
        super("container", contextualScope, inputsScope, bodyScope, outputsScope)
    }

    setDefinition(workflowDefinition: ResourceWorkflowDefinition) {
        return new K8sResourceBuilder(this.contextualScope, this.inputsScope, this.bodyScope, this.outputsScope,
            workflowDefinition);
    }

    addJsonPathOutput<T extends PlainObject, Name extends string>(
        name: Name,
        pathValue: string, // todo - make this strongly typed
        t: ZodType<T>,
        descriptionValue?: string):
        K8sResourceBuilder<ContextualScope, InputParamsScope, ContainerScope,
            ExtendScope<OutputParamsScope, { [K in Name]: OutputParamDef<T> }>> {
        return new K8sResourceBuilder(this.contextualScope, this.inputsScope, this.bodyScope, {
            ...this.outputsScope,
            [name as string]: {
                type: t,
                fromWhere: "path" as const,
                path: pathValue,
                description: descriptionValue
            },
        }, this.workflowDefinition);
    }
}
