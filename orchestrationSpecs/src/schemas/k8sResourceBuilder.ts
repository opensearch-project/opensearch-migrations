import {InputParametersRecord, OutputParamDef, OutputParametersRecord, TypeToken} from "@/schemas/parameterSchemas";
import {AllowLiteralOrExpression, ExtendScope, GenericScope, WorkflowAndTemplatesScope} from "@/schemas/workflowTypes";
import {TemplateBodyBuilder} from "@/schemas/templateBodyBuilder";
import {PlainObject} from "@/schemas/plainObject";

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
    ResourceScope extends GenericScope,
    OutputParamsScope extends OutputParametersRecord
> extends TemplateBodyBuilder<ContextualScope, InputParamsScope, ResourceScope, OutputParamsScope,
    K8sResourceBuilder<ContextualScope, InputParamsScope, ResourceScope, any>>
{
    constructor(contextualScope: ContextualScope,
                inputsScope: InputParamsScope,
                bodyScope: ResourceScope,
                outputsScope: OutputParamsScope) {
        super(contextualScope, inputsScope, bodyScope, outputsScope)
    }

    protected getBody() {
        return { resource: this.bodyScope };
    }

    setDefinition(workflowDefinition: ResourceWorkflowDefinition) {
        const newBody = {
            ...this.bodyScope,
            workflowDefinition
        }
        return new K8sResourceBuilder(this.contextualScope, this.inputsScope, newBody, this.outputsScope);
    }

    addJsonPathOutput<T extends PlainObject, Name extends string>(
        name: Name,
        pathValue: string, // todo - make this strongly typed
        t: TypeToken<T>,
        descriptionValue?: string):
        K8sResourceBuilder<ContextualScope, InputParamsScope, ResourceScope,
            ExtendScope<OutputParamsScope, { [K in Name]: OutputParamDef<T> }>> {
        return new K8sResourceBuilder(this.contextualScope, this.inputsScope, this.bodyScope, {
            ...this.outputsScope,
            [name as string]: {
                fromWhere: "path" as const,
                path: pathValue,
                description: descriptionValue
            },
        });
    }
}
