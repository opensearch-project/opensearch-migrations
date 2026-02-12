import {
    ExtendScope,
    GenericScope,
    InputParamsToExpressions,
    WorkflowAndTemplatesScope,
    WorkflowInputsToExpressions
} from "./workflowTypes";
import {InputParametersRecord, OutputParamDef, OutputParametersRecord} from "./parameterSchemas";
import {
    RetryableTemplateBodyBuilder,
    RetryableTemplateRebinder,
    RetryParameters,
    TemplateBodyBuilder, TemplateRebinder
} from "./templateBodyBuilder";
import {SynchronizationConfig} from "./synchronization";
import {K8sResourceBuilder, ResourceWorkflowDefinition} from "./k8sResourceBuilder";
import {PlainObject} from "./plainObject";
import {UniqueNameConstraintAtDeclaration} from "./scopeConstraints";
import {TypeToken} from "./sharedTypes";
import {StepsBuilder} from "./stepsBuilder";
import {INLINE} from "./taskBuilder";
import {AllowLiteralOrExpression, expr} from "./expression";
import {ContainerBuilder, IMAGE_PULL_POLICY} from "./containerBuilder";

type WaitForCreationOptions = {
    kubectlImage: AllowLiteralOrExpression<string>;
    kubectlImagePullPolicy: AllowLiteralOrExpression<IMAGE_PULL_POLICY>;
    maxDurationSeconds: AllowLiteralOrExpression<number | string>;
    maxKubeWaitDuration?: number;
    retryPolicy?: string;
    retryLimit?: number;
    retryInitialBackoffDuration?: number;
    retryFactor?: number;
};

export type WaitForNewResourceDefinition = {
    namespace?: AllowLiteralOrExpression<string>;
    resourceKindAndName: AllowLiteralOrExpression<string>;
    waitForCreation: WaitForCreationOptions;
};

export type WaitForConditions = {
    successCondition?: string;
    failureCondition?: string;
};

type ExistingResourceSpecifier = {
    kind: AllowLiteralOrExpression<string>;
    name: AllowLiteralOrExpression<string>;
    apiVersion: AllowLiteralOrExpression<string>;
}

export type WaitForExistingResourceDefinition = {
    resource: ExistingResourceSpecifier;
    conditions: WaitForConditions;
};

export class WaitForNewResourceBuilder<
    ParentWorkflowScope extends WorkflowAndTemplatesScope,
    InputParamsScope extends InputParametersRecord,
    BodyScope extends GenericScope,
    OutputParamsScope extends OutputParametersRecord
> extends TemplateBodyBuilder<
    ParentWorkflowScope,
    InputParamsScope,
    BodyScope,
    OutputParamsScope,
    WaitForNewResourceBuilder<ParentWorkflowScope, InputParamsScope, any, any>,
    GenericScope
> {
    constructor(
        parentWorkflowScope: ParentWorkflowScope,
        inputsScope: InputParamsScope,
        bodyScope: BodyScope,
        outputsScope: OutputParamsScope,
        synchronization: SynchronizationConfig | undefined
    ) {
        const templateRebind: TemplateRebinder<
            ParentWorkflowScope,
            InputParamsScope,
            GenericScope
        > = (ctx, inputs, body, outputs, sync) =>
            new WaitForNewResourceBuilder(ctx, inputs, body as any, outputs as any, sync) as any;

        super(parentWorkflowScope, inputsScope, bodyScope, outputsScope, synchronization, templateRebind);
    }

    setDefinition(
        def: WaitForNewResourceDefinition
    ):
        WaitForNewResourceBuilder<
            ParentWorkflowScope,
            InputParamsScope,
            WaitForNewResourceDefinition,
            OutputParamsScope
    > {
        return new WaitForNewResourceBuilder(
            this.parentWorkflowScope,
            this.inputsScope,
            def,
            this.outputsScope,
            this.synchronization
        );
    }

    protected getBody() {
        const def: WaitForNewResourceDefinition = this.bodyScope as any;
        const waitOpts = def.waitForCreation;

        const retryPolicy = waitOpts.retryPolicy ?? "Always";
        const retryLimit = waitOpts.retryLimit ?? 1_000_000; // you used “let active deadline handle it”
        const retryInitialBackoffDuration = waitOpts.retryInitialBackoffDuration ?? 10;
        const retryFactor = waitOpts.retryFactor ?? 2;

        // reconcile durations like your commented code did
        const DEFAULT_WAIT_DURATION = 300;
        const maxKformatBodubeWaitDuration =
            waitOpts.maxKubeWaitDuration ??
            (typeof waitOpts.maxDurationSeconds === "number" ? waitOpts.maxDurationSeconds : DEFAULT_WAIT_DURATION);

        const maxDurationSeconds = waitOpts.maxDurationSeconds ?? waitOpts.maxKubeWaitDuration;

        const namespaceArgs =
            def.namespace !== undefined ? ["-n", def.namespace] : [];

        return new ContainerBuilder(this.parentWorkflowScope, this.inputsScope, {}, {}, {}, {}, {}, this.synchronization)
            .addImageInfo(waitOpts.kubectlImage, waitOpts.kubectlImagePullPolicy)
            .addCommand(["kubectl"])
            .addArgs([
                "wait",
                "--for=create",
                def.resourceKindAndName,
                `--timeout=${waitOpts.maxKubeWaitDuration}s`,
                ...namespaceArgs
            ])
            .addActiveDeadlineSeconds(() => expr.literal(maxDurationSeconds as any))
            .addRetryParameters({
                limit: `${retryLimit}`,
                retryPolicy: `${retryPolicy}`,
                backoff: {
                    duration: `${retryInitialBackoffDuration}`,
                    factor: `${retryFactor}`,
                    cap: `${maxDurationSeconds as any}`
                }
            })
            .addResources({
                limits: { cpu: "50m", memory: "32Mi" },
                requests: { cpu: "50m", memory: "32Mi" }
            }).getBody();
    }
}


export class WaitForExistingResourceBuilder<
    ParentWorkflowScope extends WorkflowAndTemplatesScope,
    InputParamsScope extends InputParametersRecord,
    BodyScope extends GenericScope,
    OutputParamsScope extends OutputParametersRecord
> extends TemplateBodyBuilder<
    ParentWorkflowScope,
    InputParamsScope,
    BodyScope,
    OutputParamsScope,
    WaitForExistingResourceBuilder<ParentWorkflowScope, InputParamsScope, any, any>,
    GenericScope
> {
    constructor(
        parentWorkflowScope: ParentWorkflowScope,
        inputsScope: InputParamsScope,
        bodyScope: BodyScope,
        outputsScope: OutputParamsScope,
        synchronization: SynchronizationConfig | undefined
    ) {
        const templateRebind: TemplateRebinder<
            ParentWorkflowScope,
            InputParamsScope,
            GenericScope
        > = (ctx, inputs, body, outputs, sync) =>
            new WaitForExistingResourceBuilder(ctx, inputs, body as any, outputs as any, sync) as any;

        super(parentWorkflowScope, inputsScope, bodyScope, outputsScope, synchronization, templateRebind);
    }

    setDefinition(
        def: WaitForExistingResourceDefinition
    ): WaitForExistingResourceBuilder<
        ParentWorkflowScope,
        InputParamsScope,
        WaitForExistingResourceDefinition,
        OutputParamsScope
    > {
        return new WaitForExistingResourceBuilder(
            this.parentWorkflowScope,
            this.inputsScope,
            def,
            this.outputsScope,
            this.synchronization
        );
    }

    protected getBody() {
        const def: WaitForExistingResourceDefinition = this.bodyScope as any;
        const m = def.resource;

        return new K8sResourceBuilder(this.parentWorkflowScope, this.inputsScope, {}, {}, {})
            .setDefinition({
                action: "get",
                manifest: {
                    apiVersion: m.apiVersion,
                    kind: m.kind,
                    metadata: {
                        name: m.name
                    }
                },
                ...(def.conditions.successCondition ? { successCondition: def.conditions.successCondition!} : {}),
                ...(def.conditions.failureCondition ? { failureCondition: def.conditions.failureCondition!} : {})
            })
            .getBody();
    }
}
