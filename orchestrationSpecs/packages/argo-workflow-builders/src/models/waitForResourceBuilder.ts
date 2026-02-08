import {ExtendScope, GenericScope, WorkflowAndTemplatesScope} from "./workflowTypes";
import {InputParametersRecord, OutputParamDef, OutputParametersRecord} from "./parameterSchemas";
import {RetryableTemplateBodyBuilder, RetryableTemplateRebinder, RetryParameters} from "./templateBodyBuilder";
import {SynchronizationConfig} from "./synchronization";
import {ResourceWorkflowDefinition} from "./k8sResourceBuilder";
import {PlainObject} from "./plainObject";
import {UniqueNameConstraintAtDeclaration} from "./scopeConstraints";
import {TypeToken} from "./sharedTypes";
import {AllowLiteralOrExpression} from "./expression";

export interface WaitForCreationOpts {
    resourceType: AllowLiteralOrExpression<string>;
    resourceName: AllowLiteralOrExpression<string>;
    namespace: AllowLiteralOrExpression<string>;
    kubectlImage?: string;
    retryLimit?: number;
    retryBackoffDuration?: string;
    timeout?: string;
}

export class WaitForResourceBuilder<
    ParentWorkflowScope extends WorkflowAndTemplatesScope,
    InputParamsScope extends InputParametersRecord,
    ResourceScope extends GenericScope,
    OutputParamsScope extends OutputParametersRecord
> extends RetryableTemplateBodyBuilder<
    ParentWorkflowScope,
    InputParamsScope,
    ResourceScope,
    OutputParamsScope,
    WaitForResourceBuilder<ParentWorkflowScope, InputParamsScope, ResourceScope, any>,
    GenericScope
> {
    constructor(
        parentWorkflowScope: ParentWorkflowScope,
        inputsScope: InputParamsScope,
        bodyScope: ResourceScope,
        outputsScope: OutputParamsScope,
        retryParameters: RetryParameters,
        synchronization?: SynchronizationConfig,
        protected readonly waitForCreationOpts?: WaitForCreationOpts
    ) {
        const templateRebind: RetryableTemplateRebinder<ParentWorkflowScope, InputParamsScope, GenericScope> = (
            ctx, inputs, body, outputs, retry, sync
        ) => new WaitForResourceBuilder(ctx, inputs, body, outputs, retry, sync, this.waitForCreationOpts) as any;

        super(parentWorkflowScope, inputsScope, bodyScope, outputsScope, retryParameters, synchronization, templateRebind);
    }

    public setDefinition(
        workflowDefinition: ResourceWorkflowDefinition
    ): WaitForResourceBuilder<
        ParentWorkflowScope,
        InputParamsScope,
        ResourceScope & ResourceWorkflowDefinition,
        OutputParamsScope
    > {
        const newBody = {...(this.bodyScope as object), ...workflowDefinition} as
            ResourceScope & ResourceWorkflowDefinition;
        return new WaitForResourceBuilder(
            this.parentWorkflowScope, this.inputsScope, newBody, this.outputsScope,
            this.retryParameters, this.synchronization, this.waitForCreationOpts
        );
    }

    public setWaitForCreation(
        opts: WaitForCreationOpts
    ): WaitForResourceBuilder<ParentWorkflowScope, InputParamsScope, ResourceScope, OutputParamsScope> {
        return new WaitForResourceBuilder(
            this.parentWorkflowScope, this.inputsScope, this.bodyScope, this.outputsScope,
            this.retryParameters, this.synchronization, opts
        );
    }

    addJsonPathOutput<T extends PlainObject, Name extends string>(
        name: UniqueNameConstraintAtDeclaration<Name, OutputParamsScope>,
        pathValue: string,
        _t: TypeToken<T>,
        descriptionValue?: string
    ): WaitForResourceBuilder<
        ParentWorkflowScope,
        InputParamsScope,
        ResourceScope,
        ExtendScope<OutputParamsScope, { [K in Name]: OutputParamDef<T> }>
    > {
        const newOutputs = {
            ...this.outputsScope,
            [name as string]: {
                fromWhere: "path" as const,
                path: pathValue,
                description: descriptionValue
            }
        } as ExtendScope<OutputParamsScope, { [K in Name]: OutputParamDef<T> }>;

        return new WaitForResourceBuilder(
            this.parentWorkflowScope, this.inputsScope, this.bodyScope, newOutputs,
            this.retryParameters, this.synchronization, this.waitForCreationOpts
        );
    }

    protected getBody() {
        const resourceBody = {
            action: "get" as const,
            ...this.bodyScope
        };

        if (!this.waitForCreationOpts) {
            return {resource: resourceBody};
        }

        const {
            resourceType,
            resourceName,
            namespace,
            kubectlImage = "bitnami/kubectl:latest",
            retryLimit = 12,
            retryBackoffDuration = "30s",
            timeout = "60s"
        } = this.waitForCreationOpts;

        return {
            steps: [
                {
                    steps: [{
                        name: "wait-for-create",
                        inline: {
                            container: {
                                name: "main",
                                image: kubectlImage,
                                command: ["kubectl"],
                                args: [
                                    "wait", "--for=create",
                                    `${resourceType}/${resourceName}`,
                                    `-n`, namespace,
                                    `--timeout=${timeout}`
                                ],
                                resources: {}
                            },
                            retryStrategy: {
                                limit: retryLimit,
                                retryPolicy: "Always",
                                backoff: {duration: retryBackoffDuration}
                            }
                        }
                    }]
                },
                {
                    steps: [{
                        name: "check-resource",
                        inline: {
                            resource: resourceBody
                        }
                    }]
                }
            ]
        };
    }
}
