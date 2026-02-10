import {ExtendScope, GenericScope, WorkflowAndTemplatesScope} from "./workflowTypes";
import {InputParametersRecord, OutputParamDef, OutputParametersRecord} from "./parameterSchemas";
import {RetryableTemplateBodyBuilder, RetryableTemplateRebinder, RetryParameters} from "./templateBodyBuilder";
import {SynchronizationConfig} from "./synchronization";
import {K8sResourceBuilder, ResourceWorkflowDefinition} from "./k8sResourceBuilder";
import {PlainObject} from "./plainObject";
import {UniqueNameConstraintAtDeclaration} from "./scopeConstraints";
import {TypeToken} from "./sharedTypes";
import {StepsBuilder} from "./stepsBuilder";
import {INLINE} from "./taskBuilder";
import {AllowLiteralOrExpression} from "./expression";
import {IMAGE_PULL_POLICY} from "./containerBuilder";

export interface WaitForCreationOpts {
    kubectlImage: AllowLiteralOrExpression<string>;
    kubectlImagePullPolicy: AllowLiteralOrExpression<IMAGE_PULL_POLICY>;
    maxDuration?: number;
    maxKubeWaitDuration?: number;

    retryPolicy?: string;

    retryLimit?: number;
    retryInitialBackoffDuration?: number;
    retryFactor?: number;
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

        const DEFAULT_WAIT_DURATION = 300;
        let {
            kubectlImage,
            kubectlImagePullPolicy,
            retryPolicy = "Always",
            maxDuration = -1,
            maxKubeWaitDuration = -1,
            retryLimit = 5,
            retryInitialBackoffDuration = 10,
            retryFactor = 2
        } = this.waitForCreationOpts;

        if (maxDuration < 0) {
            if (maxKubeWaitDuration < 0) {
                maxKubeWaitDuration = DEFAULT_WAIT_DURATION;
            }
            maxDuration = maxKubeWaitDuration;
        } else if (maxKubeWaitDuration < 0) {
            maxKubeWaitDuration = maxDuration;
        }

        return new StepsBuilder(this.parentWorkflowScope, this.inputsScope, {}, [], {}, {}, undefined)
            .addStep("waitForCreate", INLINE, b=>b
                .addContainer((cb: any) => cb
                    .addImageInfo(kubectlImage, kubectlImagePullPolicy)
                    .addCommand(["kubectl"])
                    .addArgs(["wait", "--for=create",
                        `${kind}/${name}`,
                        `--timeout=${maxKubeWaitDuration}s`,
                        ...(namespace ? ["-n", namespace] : [])])
                )
                .addActiveDeadlineSeconds(maxDuration)
                .addRetryParameters({
                    limit: retryLimit, retryPolicy: retryPolicy,
                    backoff: {duration: retryInitialBackoffDuration, factor: retryFactor, cap: maxDuration}
                })
            ).getBody();
    }
}
