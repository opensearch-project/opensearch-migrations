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

import {InputParametersRecord, OutputParamDef, OutputParametersRecord} from "./parameterSchemas";
import {ExtendScope, GenericScope, WorkflowAndTemplatesScope} from "./workflowTypes";
import {RetryParameters, RetryableTemplateBodyBuilder, RetryableTemplateRebinder} from "./templateBodyBuilder";
import {PlainObject} from "./plainObject";
import {UniqueNameConstraintAtDeclaration} from "./scopeConstraints";
import {AllowLiteralOrExpression} from "./expression";
import {TypeToken} from "./sharedTypes";
import {SynchronizationConfig} from "./synchronization";

export type K8sActionVerb = "create" | "get" | "apply" | "delete" | "replace" | "patch";

/** Partial representation of io.argoproj.workflow.v1alpha1.ResourceTemplate */
export type ResourceWorkflowDefinition = {
    action: AllowLiteralOrExpression<K8sActionVerb>,
    failureCondition?: string,
    flags?: string[],
    setOwnerReference?: AllowLiteralOrExpression<boolean>,
    // make these more strongly typed!
    successCondition?: string, // this should be an expression
    manifest: Record<string, any>
};

export class K8sResourceBuilder<
    ParentWorkflowScope extends WorkflowAndTemplatesScope,
    InputParamsScope extends InputParametersRecord,
    ResourceScope extends GenericScope,
    OutputParamsScope extends OutputParametersRecord
> extends RetryableTemplateBodyBuilder<
    ParentWorkflowScope,
    InputParamsScope,
    ResourceScope,
    OutputParamsScope,
    K8sResourceBuilder<ParentWorkflowScope, InputParamsScope, ResourceScope, any>,
    GenericScope // BodyBound
> {
    constructor(
        parentWorkflowScope: ParentWorkflowScope,
        inputsScope: InputParamsScope,
        bodyScope: ResourceScope,
        outputsScope: OutputParamsScope,
        retryParameters: RetryParameters,
        synchronization?: SynchronizationConfig
    ) {
        const templateRebind: RetryableTemplateRebinder<ParentWorkflowScope, InputParamsScope, GenericScope> = (
            ctx, inputs, body, outputs, retry, sync
        ) => new K8sResourceBuilder(ctx, inputs, body, outputs, retry, sync) as any;

        super(parentWorkflowScope, inputsScope, bodyScope, outputsScope, retryParameters, synchronization, templateRebind);
    }

    getBody() {
        return {resource: this.bodyScope};
    }

    // SUBCLASS METHOD: return the concrete subclass directly
    public setDefinition(
        workflowDefinition: ResourceWorkflowDefinition
    ): K8sResourceBuilder<
        ParentWorkflowScope,
        InputParamsScope,
        ResourceScope & ResourceWorkflowDefinition,
        OutputParamsScope
    > {
        const newBody =
            {...(this.bodyScope as object), ...workflowDefinition} as
                ResourceScope & ResourceWorkflowDefinition;

        // Return a concrete instance (not rebind), like DagBuilder does in its own methods
        return new K8sResourceBuilder(
            this.parentWorkflowScope,
            this.inputsScope,
            newBody,
            this.outputsScope,
            this.retryParameters
        );
    }

    addJsonPathOutput<T extends PlainObject, Name extends string>(
        name: UniqueNameConstraintAtDeclaration<Name, OutputParamsScope>,
        pathValue: string,
        _t: TypeToken<T>,
        descriptionValue?: string
    ): K8sResourceBuilder<
        ParentWorkflowScope,
        InputParamsScope,
        ResourceScope,
        ExtendScope<OutputParamsScope, { [K in Name]: OutputParamDef<T> }>
    > {
        // Cast to ExtendScope to avoid adding a string index signature
        const newOutputs = {
            ...this.outputsScope,
            [name as string]: {
                fromWhere: "jsonPath" as const,
                jsonPath: pathValue,
                description: descriptionValue
            }
        } as ExtendScope<OutputParamsScope, { [K in Name]: OutputParamDef<T> }>;

        return new K8sResourceBuilder(
            this.parentWorkflowScope,
            this.inputsScope,
            this.bodyScope,
            newOutputs,
            this.retryParameters
        );
    }
}
