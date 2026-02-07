import {GenericScope, WorkflowAndTemplatesScope} from "./workflowTypes";
import {InputParametersRecord, OutputParametersRecord} from "./parameterSchemas";
import {TemplateBodyBuilder, TemplateRebinder} from "./templateBodyBuilder";
import {SynchronizationConfig} from "./synchronization";

export interface WaitForCreationOpts {
    resourceType: string;
    resourceName: string;
    namespace: string;
    kubectlImage?: string;
    retryLimit?: number;
    retryBackoffDuration?: string;
    timeout?: string;
}

export interface WaitForResourceOpts {
    manifest: Record<string, any>;
    successCondition?: string;
    failureCondition?: string;
    waitForCreation?: WaitForCreationOpts;
}

export class WaitForResourceBuilder<
    ParentWorkflowScope extends WorkflowAndTemplatesScope,
    InputParamsScope extends InputParametersRecord,
    OutputParamsScope extends OutputParametersRecord
> extends TemplateBodyBuilder<
    ParentWorkflowScope,
    InputParamsScope,
    WaitForResourceOpts,
    OutputParamsScope,
    WaitForResourceBuilder<ParentWorkflowScope, InputParamsScope, any>,
    GenericScope
> {
    constructor(
        parentWorkflowScope: ParentWorkflowScope,
        inputsScope: InputParamsScope,
        bodyScope: WaitForResourceOpts,
        outputsScope: OutputParamsScope,
        synchronization: SynchronizationConfig | undefined
    ) {
        const templateRebind: TemplateRebinder<ParentWorkflowScope, InputParamsScope, GenericScope> = (
            ctx, inputs, body, outputs, sync
        ) => new WaitForResourceBuilder(ctx, inputs, body as unknown as WaitForResourceOpts, outputs, sync) as any;

        super(parentWorkflowScope, inputsScope, bodyScope, outputsScope, synchronization, templateRebind);
    }

    protected getBody() {
        const {manifest, successCondition, failureCondition, waitForCreation} = this.bodyScope;

        const resourceBody = {
            action: "get" as const,
            ...(successCondition && {successCondition}),
            ...(failureCondition && {failureCondition}),
            manifest
        };

        if (!waitForCreation) {
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
        } = waitForCreation;

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
