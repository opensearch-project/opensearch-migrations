import {ExtendScope, GenericScope, WorkflowAndTemplatesScope} from "./workflowTypes";
import {InputParametersRecord, OutputParamDef, OutputParametersRecord} from "./parameterSchemas";
import {RetryableTemplateBodyBuilder, RetryableTemplateRebinder, RetryParameters} from "./templateBodyBuilder";
import {SynchronizationConfig} from "./synchronization";
import {K8sResourceBuilder, ResourceWorkflowDefinition} from "./k8sResourceBuilder";
import {PlainObject} from "./plainObject";
import {UniqueNameConstraintAtDeclaration} from "./scopeConstraints";
import {TypeToken} from "./sharedTypes";
import {StepsBuilder} from "./stepsBuilder";

export interface WaitForCreationOpts {
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
        private readonly k8sResourceBuilder: K8sResourceBuilder<ParentWorkflowScope, InputParamsScope, ResourceScope, OutputParamsScope>,
        retryParameters: RetryParameters,
        synchronization: SynchronizationConfig | undefined,
        private readonly waitForCreationOpts?: WaitForCreationOpts
    ) {
        const templateRebind: RetryableTemplateRebinder<ParentWorkflowScope, InputParamsScope, GenericScope> = (
            ctx, inputs, body, outputs, retry, sync
        ) => {
            const newK8sBuilder = new K8sResourceBuilder(ctx, inputs, body, outputs, retry, sync);
            return new WaitForResourceBuilder(ctx, inputs, newK8sBuilder, retry, sync, this.waitForCreationOpts) as any;
        };

        super(
            parentWorkflowScope,
            inputsScope,
            k8sResourceBuilder['bodyScope'],
            k8sResourceBuilder['outputsScope'],
            retryParameters,
            synchronization,
            templateRebind
        );
    }

    public setDefinition(
        workflowDefinition: ResourceWorkflowDefinition
    ): WaitForResourceBuilder<
        ParentWorkflowScope,
        InputParamsScope,
        ResourceScope & ResourceWorkflowDefinition,
        OutputParamsScope
    > {
        const newK8sBuilder = this.k8sResourceBuilder.setDefinition(workflowDefinition);
        return new WaitForResourceBuilder(
            this.parentWorkflowScope,
            this.inputsScope,
            newK8sBuilder,
            this.retryParameters,
            this.synchronization,
            this.waitForCreationOpts
        );
    }

    public setWaitForCreation(
        opts: WaitForCreationOpts
    ): WaitForResourceBuilder<ParentWorkflowScope, InputParamsScope, ResourceScope, OutputParamsScope> {
        return new WaitForResourceBuilder(
            this.parentWorkflowScope,
            this.inputsScope,
            this.k8sResourceBuilder,
            this.retryParameters,
            this.synchronization,
            opts
        );
    }

    public addJsonPathOutput<T extends PlainObject, Name extends string>(
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
        const newK8sBuilder = this.k8sResourceBuilder.addJsonPathOutput(name, pathValue, _t, descriptionValue);
        return new WaitForResourceBuilder(
            this.parentWorkflowScope,
            this.inputsScope,
            newK8sBuilder,
            this.retryParameters,
            this.synchronization,
            this.waitForCreationOpts
        );
    }

    protected getBody() {
        if (!this.waitForCreationOpts) {
            return this.k8sResourceBuilder['getBody']();
        }

        const resourceBody = this.k8sResourceBuilder['getBody']().resource;
        const manifest = resourceBody.manifest;
        const kind = manifest?.kind;
        const name = manifest?.metadata?.name;
        const namespace = manifest?.metadata?.namespace;

        if (!kind || !name) {
            throw new Error("waitForCreation requires manifest to have kind and metadata.name");
        }

        const {
            kubectlImage = "bitnami/kubectl:latest",
            retryLimit = 12,
            retryBackoffDuration = "30s",
            timeout = "60s"
        } = this.waitForCreationOpts;

        let stepsBuilder =
            new StepsBuilder(this.parentWorkflowScope, this.inputsScope, {}, [], {}, {}, undefined);

        const withSteps = stepsBuilder
            .addStepGroup(gb => gb)
            .addStepGroup(gb => gb);

        // Inject inline templates into the step groups
        const stepGroups = withSteps['stepGroups'];
        stepGroups[0].steps = [{
            name: "wait-for-create",
            inline: {
                container: {
                    name: "main",
                    image: kubectlImage,
                    command: ["kubectl"],
                    args: [
                        "wait", "--for=create",
                        `${kind}/${name}`,
                        `--timeout=${timeout}`,
                        ...(namespace ? ["-n", namespace] : [])
                    ],
                    resources: {}
                },
                retryStrategy: {
                    limit: retryLimit,
                    retryPolicy: "Always",
                    backoff: {duration: retryBackoffDuration}
                }
            }
        } as any];
        
        stepGroups[1].steps = [{
            name: "check-resource",
            inline: {
                resource: resourceBody
            }
        } as any];

        return withSteps['getBody']();
    }
}
