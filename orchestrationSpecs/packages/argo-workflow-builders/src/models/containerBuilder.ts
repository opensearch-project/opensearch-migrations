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
import {
    DataOrConfigMapScope,
    DataScope, ExpressionOrConfigMapValue,
    ExtendScope,
    GenericScope,
    InputParamsToExpressions, LowercaseOnly,
    WorkflowAndTemplatesScope,
    WorkflowInputsToExpressions
} from "./workflowTypes";
import {inputsToEnvVars, TypescriptError} from "../utils";
import {RetryParameters, RetryableTemplateBodyBuilder, RetryableTemplateRebinder} from "./templateBodyBuilder";
import {extendScope, FieldGroupConstraint, ScopeIsEmptyConstraint} from "./scopeConstraints";
import {PlainObject} from "./plainObject";
import {AllowLiteralOrExpression, BaseExpression, expr, toExpression} from "./expression";
import {TypeToken} from "./sharedTypes";
import {SynchronizationConfig} from "./synchronization";

export type IMAGE_PULL_POLICY = "Always" | "Never" | "IfNotPresent";

export type PodMetadata = {
    labels?: Record<string, AllowLiteralOrExpression<string>>;
    annotations?: Record<string, AllowLiteralOrExpression<string>>;
};

// Kubernetes types for pod scheduling
export type Toleration = {
    key?: AllowLiteralOrExpression<string>;
    operator?: AllowLiteralOrExpression<"Exists" | "Equal">;
    value?: AllowLiteralOrExpression<string>;
    effect?: AllowLiteralOrExpression<"NoSchedule" | "PreferNoSchedule" | "NoExecute">;
    tolerationSeconds?: AllowLiteralOrExpression<number>;
};

export type NodeSelector = Record<string, AllowLiteralOrExpression<string>>;

export type LabelSelector = {
    matchLabels?: Record<string, AllowLiteralOrExpression<string>>;
    matchExpressions?: Array<{
        key: AllowLiteralOrExpression<string>;
        operator: AllowLiteralOrExpression<"In" | "NotIn" | "Exists" | "DoesNotExist">;
        values?: AllowLiteralOrExpression<string>[];
    }>;
};

export type NodeSelectorTerm = {
    matchExpressions?: Array<{
        key: AllowLiteralOrExpression<string>;
        operator: AllowLiteralOrExpression<"In" | "NotIn" | "Exists" | "DoesNotExist" | "Gt" | "Lt">;
        values?: AllowLiteralOrExpression<string>[];
    }>;
    matchFields?: Array<{
        key: AllowLiteralOrExpression<string>;
        operator: AllowLiteralOrExpression<"In" | "NotIn" | "Exists" | "DoesNotExist" | "Gt" | "Lt">;
        values?: AllowLiteralOrExpression<string>[];
    }>;
};

export type Affinity = {
    nodeAffinity?: {
        requiredDuringSchedulingIgnoredDuringExecution?: { nodeSelectorTerms: NodeSelectorTerm[] };
        preferredDuringSchedulingIgnoredDuringExecution?: Array<{
            weight: AllowLiteralOrExpression<number>;
            preference: NodeSelectorTerm;
        }>;
    };
    podAffinity?: {
        requiredDuringSchedulingIgnoredDuringExecution?: Array<{
            labelSelector?: LabelSelector;
            topologyKey: AllowLiteralOrExpression<string>;
            namespaces?: AllowLiteralOrExpression<string>[];
        }>;
        preferredDuringSchedulingIgnoredDuringExecution?: Array<{
            weight: AllowLiteralOrExpression<number>;
            podAffinityTerm: {
                labelSelector?: LabelSelector;
                topologyKey: AllowLiteralOrExpression<string>;
                namespaces?: AllowLiteralOrExpression<string>[];
            };
        }>;
    };
    podAntiAffinity?: {
        requiredDuringSchedulingIgnoredDuringExecution?: Array<{
            labelSelector?: LabelSelector;
            topologyKey: AllowLiteralOrExpression<string>;
            namespaces?: AllowLiteralOrExpression<string>[];
        }>;
        preferredDuringSchedulingIgnoredDuringExecution?: Array<{
            weight: AllowLiteralOrExpression<number>;
            podAffinityTerm: {
                labelSelector?: LabelSelector;
                topologyKey: AllowLiteralOrExpression<string>;
                namespaces?: AllowLiteralOrExpression<string>[];
            };
        }>;
    };
};

export type PodSecurityContext = {
    runAsUser?: AllowLiteralOrExpression<number>;
    runAsGroup?: AllowLiteralOrExpression<number>;
    runAsNonRoot?: AllowLiteralOrExpression<boolean>;
    fsGroup?: AllowLiteralOrExpression<number>;
    supplementalGroups?: AllowLiteralOrExpression<number>[];
    seccompProfile?: {
        type: AllowLiteralOrExpression<"Unconfined" | "RuntimeDefault" | "Localhost">;
        localhostProfile?: AllowLiteralOrExpression<string>;
    };
};

export type HostAlias = {
    ip: AllowLiteralOrExpression<string>;
    hostnames: AllowLiteralOrExpression<string>[];
};

// Brand types for tracking which pod config fields have been set
type HasMetadata = { __hasMetadata: true };
type HasTolerations = { __hasTolerations: true };
type HasNodeSelector = { __hasNodeSelector: true };
type HasActiveDeadlineSeconds = { __hasActiveDeadlineSeconds: true };
type HasAffinity = { __hasAffinity: true };
type HasSchedulerName = { __hasSchedulerName: true };
type HasPriorityClassName = { __hasPriorityClassName: true };
type HasServiceAccountName = { __hasServiceAccountName: true };
type HasAutomountServiceAccountToken = { __hasAutomountServiceAccountToken: true };
type HasSecurityContext = { __hasSecurityContext: true };
type HasHostAliases = { __hasHostAliases: true };
type HasPodSpecPatch = { __hasPodSpecPatch: true };
type HasRetryStrategy = { __hasRetryStrategy: true };
type HasSynchronization = { __hasSynchronization: true };

// Runtime storage for pod config (not tracked in type system individually)
type PodConfigData = {
    metadata?: PodMetadata;
    tolerations?: Toleration[];
    nodeSelector?: NodeSelector;
    activeDeadlineSeconds?: AllowLiteralOrExpression<number | string>;
    affinity?: Affinity;
    schedulerName?: AllowLiteralOrExpression<string>;
    priorityClassName?: AllowLiteralOrExpression<string>;
    serviceAccountName?: AllowLiteralOrExpression<string>;
    automountServiceAccountToken?: AllowLiteralOrExpression<boolean>;
    securityContext?: PodSecurityContext;
    hostAliases?: HostAlias[];
    podSpecPatch?: AllowLiteralOrExpression<string>;
};

export class ContainerBuilder<
    ParentWorkflowScope extends WorkflowAndTemplatesScope,
    InputParamsScope extends InputParametersRecord,
    ContainerScope extends GenericScope,
    VolumeScope extends GenericScope,
    EnvScope extends DataOrConfigMapScope,
    OutputParamsScope extends OutputParametersRecord,
    PodConfigBrands extends {} = {}
> extends RetryableTemplateBodyBuilder<
    ParentWorkflowScope,
    InputParamsScope,
    ContainerScope,
    OutputParamsScope,
    ContainerBuilder<ParentWorkflowScope, InputParamsScope, ContainerScope, VolumeScope, EnvScope, any, PodConfigBrands>,
    GenericScope
> {
    constructor(
        parentWorkflowScope: ParentWorkflowScope,
        inputsScope: InputParamsScope,
        bodyScope: ContainerScope,
        public readonly volumeScope: VolumeScope,
        public readonly envScope: EnvScope,
        outputsScope: OutputParamsScope,
        retryParameters: RetryParameters,
        synchronization: SynchronizationConfig | undefined,
        public readonly podConfig: PodConfigData = {}
    ) {
        const templateRebind: RetryableTemplateRebinder<
            ParentWorkflowScope,
            InputParamsScope,
            GenericScope
        > = (ctx, inputs, body, outputs, retry, synchronization) =>
            new ContainerBuilder(
                ctx, inputs, body, this.volumeScope, this.envScope, outputs, retry, synchronization, this.podConfig
            ) as any;

        super(parentWorkflowScope, inputsScope, bodyScope, outputsScope, retryParameters, synchronization, templateRebind);
    }

    /** Helper to create a new builder with updated fields */
    private withUpdates<
        NewBody extends GenericScope = ContainerScope,
        NewVolume extends GenericScope = VolumeScope,
        NewEnv extends DataOrConfigMapScope = EnvScope,
        NewOutput extends OutputParametersRecord = OutputParamsScope,
        NewBrands extends {} = PodConfigBrands
    >(updates: {
        body?: NewBody;
        volumes?: NewVolume;
        env?: NewEnv;
        outputs?: NewOutput;
        retry?: RetryParameters;
        sync?: SynchronizationConfig | undefined;
        podConfig?: PodConfigData;
    }): ContainerBuilder<ParentWorkflowScope, InputParamsScope, NewBody, NewVolume, NewEnv, NewOutput, NewBrands> {
        return new ContainerBuilder(
            this.parentWorkflowScope,
            this.inputsScope,
            updates.body ?? this.bodyScope as unknown as NewBody,
            updates.volumes ?? this.volumeScope as unknown as NewVolume,
            updates.env ?? this.envScope as unknown as NewEnv,
            updates.outputs ?? this.outputsScope as unknown as NewOutput,
            updates.retry ?? this.retryParameters,
            updates.sync !== undefined ? updates.sync : this.synchronization,
            updates.podConfig ?? this.podConfig
        );
    }

    protected getBody() {
        const volumes = Object.entries(this.volumeScope).map(([name, config]) => ({
            name,
            configMap: config.configMap
        }));
        const volumeMounts = Object.entries(this.volumeScope).map(([name, config]) => ({
        name,
            mountPath: config.mountPath,
            readOnly: config.readOnly
        }));
        return {
            ...(this.podConfig.metadata && { metadata: this.podConfig.metadata }),
            ...(this.podConfig.tolerations && { tolerations: this.podConfig.tolerations }),
            ...(this.podConfig.nodeSelector && { nodeSelector: this.podConfig.nodeSelector }),
            ...(this.podConfig.activeDeadlineSeconds !== undefined && { activeDeadlineSeconds: this.podConfig.activeDeadlineSeconds }),
            ...(this.podConfig.affinity && { affinity: this.podConfig.affinity }),
            ...(this.podConfig.schedulerName && { schedulerName: this.podConfig.schedulerName }),
            ...(this.podConfig.priorityClassName && { priorityClassName: this.podConfig.priorityClassName }),
            ...(this.podConfig.serviceAccountName && { serviceAccountName: this.podConfig.serviceAccountName }),
            ...(this.podConfig.automountServiceAccountToken !== undefined && { automountServiceAccountToken: this.podConfig.automountServiceAccountToken }),
            ...(this.podConfig.securityContext && { securityContext: this.podConfig.securityContext }),
            ...(this.podConfig.hostAliases && { hostAliases: this.podConfig.hostAliases }),
            ...(this.podConfig.podSpecPatch && { podSpecPatch: this.podConfig.podSpecPatch }),
            ...(volumes.length > 0 && { volumes }),
            container: {
                ...this.bodyScope,
                env: this.envScope as Record<string, ExpressionOrConfigMapValue<any>>,
                ...(volumeMounts.length > 0 && { volumeMounts })
            }
        };
    }

    // Update existing methods to preserve EnvScope type parameter

    addImageInfo(image: AllowLiteralOrExpression<string>,
                 imagePullPolicy: AllowLiteralOrExpression<IMAGE_PULL_POLICY>):
        ContainerBuilder<
            ParentWorkflowScope,
            InputParamsScope,
            ExtendScope<ContainerScope, {
                image: AllowLiteralOrExpression<string>,
                imagePullPolicy: AllowLiteralOrExpression<string>
            }>,
            VolumeScope,
            EnvScope,
            OutputParamsScope,
            PodConfigBrands
        > {
        return new ContainerBuilder(
            this.parentWorkflowScope,
            this.inputsScope,
            {...this.bodyScope, image, imagePullPolicy},
            this.volumeScope,
            this.envScope,
            this.outputsScope,
            this.retryParameters,
            this.synchronization,
            this.podConfig
        );
    }

    addCommand(strArr: AllowLiteralOrExpression<string>[]):
        ContainerBuilder<
            ParentWorkflowScope,
            InputParamsScope,
            ExtendScope<ContainerScope, { command: BaseExpression<string>[] }>,
            VolumeScope,
            EnvScope,
            OutputParamsScope,
            PodConfigBrands
        > {
        return new ContainerBuilder(this.parentWorkflowScope, this.inputsScope, {
                ...this.bodyScope,
                command: strArr.map(s=>toExpression(s))
            },
            this.volumeScope,
            this.envScope,
            this.outputsScope,
            this.retryParameters,
            this.synchronization,
            this.podConfig
        );
    }

    addArgs(a: AllowLiteralOrExpression<string>[]):
        ContainerBuilder<
            ParentWorkflowScope,
            InputParamsScope,
            ExtendScope<ContainerScope, { args: AllowLiteralOrExpression<string>[] }>,
            VolumeScope,
            EnvScope,
            OutputParamsScope,
            PodConfigBrands
        > {
        return new ContainerBuilder(this.parentWorkflowScope, this.inputsScope, {
                ...this.bodyScope,
                args: a
            },
            this.volumeScope,
            this.envScope,
            this.outputsScope,
            this.retryParameters,
            this.synchronization,
            this.podConfig
        );
    }

    addPathOutput<T extends PlainObject, Name extends string>(
        name: Name, pathValue: string, t: TypeToken<T>, descriptionValue?: string
    ):
        ContainerBuilder<
            ParentWorkflowScope,
            InputParamsScope,
            ContainerScope,
            VolumeScope,
            EnvScope,
            ExtendScope<OutputParamsScope, { [K in Name]: OutputParamDef<T> }>,
            PodConfigBrands
        > {
        type NewOut = ExtendScope<OutputParamsScope, { [K in Name]: OutputParamDef<T> }>;
        const newOutputs = {
            ...this.outputsScope,
            [name as string]: {
                fromWhere: "path" as const,
                path: pathValue,
                description: descriptionValue
            }
        } as NewOut;

        return new ContainerBuilder<
            ParentWorkflowScope,
            InputParamsScope,
            ContainerScope,
            VolumeScope,
            EnvScope,
            NewOut,
            PodConfigBrands
        >(
            this.parentWorkflowScope,
            this.inputsScope,
            this.bodyScope,
            this.volumeScope,
            this.envScope,
            newOutputs,
            this.retryParameters,
            this.synchronization,
            this.podConfig);
    }

    addVolumesFromRecord<
        R extends { [K in keyof R & string as LowercaseOnly<K>]: VolumeScope[K] }
    >(
        volumes: FieldGroupConstraint<VolumeScope, R>
    ): ContainerBuilder<
        ParentWorkflowScope,
        InputParamsScope,
        ContainerScope,
        ExtendScope<VolumeScope, R>,
        EnvScope,
        OutputParamsScope,
        PodConfigBrands
    > {
        return new ContainerBuilder(
            this.parentWorkflowScope,
            this.inputsScope,
            this.bodyScope,
            extendScope(this.volumeScope, () => volumes as R),
            this.envScope,
            this.outputsScope,
            this.retryParameters,
            this.synchronization,
            this.podConfig);
    }

    addEnvVar<Name extends string>(
        name: string extends keyof EnvScope  // If EnvScope is Record<string, X>, disable checking
            ? Name
            : Name extends keyof EnvScope
                ? TypescriptError<`Environment variable '${Name}' already exists`>
                : Name,
        value: ExpressionOrConfigMapValue<string>
    ): ContainerBuilder<
        ParentWorkflowScope,
        InputParamsScope,
        ContainerScope,
        VolumeScope,
        ExtendScope<EnvScope, { [K in Name]: ExpressionOrConfigMapValue<string> }>,
        OutputParamsScope,
        PodConfigBrands
    > {
        return this.addEnvVarUnchecked(name as string, value) as any;
    }

    addEnvVarsFromRecord<
        R extends { [K in keyof R & string]: EnvScope[K] }
    >(
        envVars: FieldGroupConstraint<EnvScope, R>
    ): ContainerBuilder<
        ParentWorkflowScope,
        InputParamsScope,
        ContainerScope,
        VolumeScope,
        ExtendScope<EnvScope, R>,
        OutputParamsScope,
        PodConfigBrands
    > {
        return new ContainerBuilder(
            this.parentWorkflowScope,
            this.inputsScope,
            this.bodyScope,
            this.volumeScope,
            extendScope(this.envScope, () => envVars as R),
            this.outputsScope,
            this.retryParameters,
            this.synchronization,
            this.podConfig);
    }

    addEnvVars<NewEnvScope extends DataOrConfigMapScope>(
        builderFn: (
            cb: ContainerBuilder<ParentWorkflowScope, InputParamsScope, ContainerScope, {}, {}, OutputParamsScope, PodConfigBrands>
        ) => ContainerBuilder<ParentWorkflowScope, InputParamsScope, ContainerScope, {}, NewEnvScope, OutputParamsScope, PodConfigBrands>
    ): ScopeIsEmptyConstraint<EnvScope,
        ContainerBuilder<ParentWorkflowScope, InputParamsScope, ContainerScope, {}, NewEnvScope, OutputParamsScope, PodConfigBrands>
    > {
        const emptyEnvBuilder = new ContainerBuilder(
            this.parentWorkflowScope,
            this.inputsScope,
            this.bodyScope,
            this.volumeScope,
            {},
            this.outputsScope,
            this.retryParameters,
            this.synchronization,
            this.podConfig
        );
        return builderFn(emptyEnvBuilder) as any;
    }

    /**
     * Internal method without constraint checking for use by other methods
     */
    private addEnvVarUnchecked<Name extends string>(
        name: Name,
        value: ExpressionOrConfigMapValue<string>
    ): ContainerBuilder<
        ParentWorkflowScope,
        InputParamsScope,
        ContainerScope,
        VolumeScope,
        ExtendScope<EnvScope, { [K in Name]: ExpressionOrConfigMapValue<string> }>,
        OutputParamsScope,
        PodConfigBrands
    > {
        const currentEnv = (this.bodyScope as any).env || {};
        const newEnvScope = {
            ...this.envScope,
            [name as string]: value
        } as ExtendScope<EnvScope, { [K in Name]: ExpressionOrConfigMapValue<string> }>;

        return new ContainerBuilder(
            this.parentWorkflowScope,
            this.inputsScope,
            {...this.bodyScope, env: {...currentEnv, [name as string]: value}},
            this.volumeScope,
            newEnvScope,
            this.outputsScope,
            this.retryParameters,
            this.synchronization,
            this.podConfig
        );
    }

    /**
     * Convenience method to map input parameters to environment variables
     */
    addInputsAsEnvVars<
        ModifiedInputs extends Record<string, AllowLiteralOrExpression<string>> =
            { [K in keyof InputParamsScope as Uppercase<string & K>]: AllowLiteralOrExpression<string> }
    >(
        mode: {
            prefix: string,
            suffix: string
        },
        modifierFn: (inputs: InputParamsToExpressions<InputParamsScope>) => ModifiedInputs =
            (inputs: InputParamsToExpressions<InputParamsScope>) => inputsToEnvVars(inputs, mode) as any
    ): ScopeIsEmptyConstraint<EnvScope,
        ContainerBuilder<
            ParentWorkflowScope,
            InputParamsScope,
            ContainerScope,
            VolumeScope,
            ModifiedInputs,
            OutputParamsScope,
            PodConfigBrands
        >> {
        const envVars = modifierFn(this.inputs);

        return new ContainerBuilder(
            this.parentWorkflowScope,
            this.inputsScope,
            {
                ...this.bodyScope,
                env: envVars
            },
            this.volumeScope,
            envVars,
            this.outputsScope,
            this.retryParameters,
            this.synchronization,
            this.podConfig
        ) as any;
    }

    /**
     * Add resource requirements (CPU, memory limits and requests) to the container.
     */
    addResources(
        resources: Record<string, any>
    ): ContainerBuilder<
        ParentWorkflowScope,
        InputParamsScope,
        ExtendScope<ContainerScope, { resources: AllowLiteralOrExpression<Record<string, any>> }>,
        VolumeScope,
        EnvScope,
        OutputParamsScope,
        PodConfigBrands
    > {
        const mergedResources = resources;
        return new ContainerBuilder(
            {...this.parentWorkflowScope},
            this.inputsScope,
            {...this.bodyScope, resources: mergedResources} as ExtendScope<ContainerScope, { resources: AllowLiteralOrExpression<Record<string, any>> }>,
            this.volumeScope,
            this.envScope,
            this.outputsScope,
            this.retryParameters,
            this.synchronization,
            this.podConfig
        );
    }

    /**
     * Add metadata (labels and annotations) to the pod created by this container template.
     * Can only be called once per builder chain.
     */
    addPodMetadata(
        this: PodConfigBrands extends HasMetadata ? never : this,
        builderFn: (ctx: { inputs: InputParamsToExpressions<InputParamsScope>, workflowInputs: WorkflowInputsToExpressions<ParentWorkflowScope> }) => PodMetadata
    ): ContainerBuilder<ParentWorkflowScope, InputParamsScope, ContainerScope, VolumeScope, EnvScope, OutputParamsScope, PodConfigBrands & HasMetadata> {
        return this.withUpdates({ podConfig: { ...this.podConfig, metadata: builderFn({ inputs: this.inputs, workflowInputs: this.workflowInputs }) } });
    }

    addTolerations(
        this: PodConfigBrands extends HasTolerations ? never : this,
        builderFn: (ctx: { inputs: InputParamsToExpressions<InputParamsScope>, workflowInputs: WorkflowInputsToExpressions<ParentWorkflowScope> }) => Toleration[]
    ): ContainerBuilder<ParentWorkflowScope, InputParamsScope, ContainerScope, VolumeScope, EnvScope, OutputParamsScope, PodConfigBrands & HasTolerations> {
        return this.withUpdates({ podConfig: { ...this.podConfig, tolerations: builderFn({ inputs: this.inputs, workflowInputs: this.workflowInputs }) } });
    }

    addNodeSelector(
        this: PodConfigBrands extends HasNodeSelector ? never : this,
        builderFn: (ctx: { inputs: InputParamsToExpressions<InputParamsScope>, workflowInputs: WorkflowInputsToExpressions<ParentWorkflowScope> }) => NodeSelector
    ): ContainerBuilder<ParentWorkflowScope, InputParamsScope, ContainerScope, VolumeScope, EnvScope, OutputParamsScope, PodConfigBrands & HasNodeSelector> {
        return this.withUpdates({ podConfig: { ...this.podConfig, nodeSelector: builderFn({ inputs: this.inputs, workflowInputs: this.workflowInputs }) } });
    }

    addActiveDeadlineSeconds(
        this: PodConfigBrands extends HasActiveDeadlineSeconds ? never : this,
        builderFn: (ctx: { inputs: InputParamsToExpressions<InputParamsScope>, workflowInputs: WorkflowInputsToExpressions<ParentWorkflowScope> }) => AllowLiteralOrExpression<number | string>
    ): ContainerBuilder<ParentWorkflowScope, InputParamsScope, ContainerScope, VolumeScope, EnvScope, OutputParamsScope, PodConfigBrands & HasActiveDeadlineSeconds> {
        return this.withUpdates({ podConfig: { ...this.podConfig, activeDeadlineSeconds: builderFn({ inputs: this.inputs, workflowInputs: this.workflowInputs }) } });
    }

    addAffinity(
        this: PodConfigBrands extends HasAffinity ? never : this,
        builderFn: (ctx: { inputs: InputParamsToExpressions<InputParamsScope>, workflowInputs: WorkflowInputsToExpressions<ParentWorkflowScope> }) => Affinity
    ): ContainerBuilder<ParentWorkflowScope, InputParamsScope, ContainerScope, VolumeScope, EnvScope, OutputParamsScope, PodConfigBrands & HasAffinity> {
        return this.withUpdates({ podConfig: { ...this.podConfig, affinity: builderFn({ inputs: this.inputs, workflowInputs: this.workflowInputs }) } });
    }

    addSchedulerName(
        this: PodConfigBrands extends HasSchedulerName ? never : this,
        builderFn: (ctx: { inputs: InputParamsToExpressions<InputParamsScope>, workflowInputs: WorkflowInputsToExpressions<ParentWorkflowScope> }) => AllowLiteralOrExpression<string>
    ): ContainerBuilder<ParentWorkflowScope, InputParamsScope, ContainerScope, VolumeScope, EnvScope, OutputParamsScope, PodConfigBrands & HasSchedulerName> {
        return this.withUpdates({ podConfig: { ...this.podConfig, schedulerName: builderFn({ inputs: this.inputs, workflowInputs: this.workflowInputs }) } });
    }

    addPriorityClassName(
        this: PodConfigBrands extends HasPriorityClassName ? never : this,
        builderFn: (ctx: { inputs: InputParamsToExpressions<InputParamsScope>, workflowInputs: WorkflowInputsToExpressions<ParentWorkflowScope> }) => AllowLiteralOrExpression<string>
    ): ContainerBuilder<ParentWorkflowScope, InputParamsScope, ContainerScope, VolumeScope, EnvScope, OutputParamsScope, PodConfigBrands & HasPriorityClassName> {
        return this.withUpdates({ podConfig: { ...this.podConfig, priorityClassName: builderFn({ inputs: this.inputs, workflowInputs: this.workflowInputs }) } });
    }

    addServiceAccountName(
        this: PodConfigBrands extends HasServiceAccountName ? never : this,
        builderFn: (ctx: { inputs: InputParamsToExpressions<InputParamsScope>, workflowInputs: WorkflowInputsToExpressions<ParentWorkflowScope> }) => AllowLiteralOrExpression<string>
    ): ContainerBuilder<ParentWorkflowScope, InputParamsScope, ContainerScope, VolumeScope, EnvScope, OutputParamsScope, PodConfigBrands & HasServiceAccountName> {
        return this.withUpdates({ podConfig: { ...this.podConfig, serviceAccountName: builderFn({ inputs: this.inputs, workflowInputs: this.workflowInputs }) } });
    }

    addAutomountServiceAccountToken(
        this: PodConfigBrands extends HasAutomountServiceAccountToken ? never : this,
        builderFn: (ctx: { inputs: InputParamsToExpressions<InputParamsScope>, workflowInputs: WorkflowInputsToExpressions<ParentWorkflowScope> }) => AllowLiteralOrExpression<boolean>
    ): ContainerBuilder<ParentWorkflowScope, InputParamsScope, ContainerScope, VolumeScope, EnvScope, OutputParamsScope, PodConfigBrands & HasAutomountServiceAccountToken> {
        return this.withUpdates({ podConfig: { ...this.podConfig, automountServiceAccountToken: builderFn({ inputs: this.inputs, workflowInputs: this.workflowInputs }) } });
    }

    addSecurityContext(
        this: PodConfigBrands extends HasSecurityContext ? never : this,
        builderFn: (ctx: { inputs: InputParamsToExpressions<InputParamsScope>, workflowInputs: WorkflowInputsToExpressions<ParentWorkflowScope> }) => PodSecurityContext
    ): ContainerBuilder<ParentWorkflowScope, InputParamsScope, ContainerScope, VolumeScope, EnvScope, OutputParamsScope, PodConfigBrands & HasSecurityContext> {
        return this.withUpdates({ podConfig: { ...this.podConfig, securityContext: builderFn({ inputs: this.inputs, workflowInputs: this.workflowInputs }) } });
    }

    addHostAliases(
        this: PodConfigBrands extends HasHostAliases ? never : this,
        builderFn: (ctx: { inputs: InputParamsToExpressions<InputParamsScope>, workflowInputs: WorkflowInputsToExpressions<ParentWorkflowScope> }) => HostAlias[]
    ): ContainerBuilder<ParentWorkflowScope, InputParamsScope, ContainerScope, VolumeScope, EnvScope, OutputParamsScope, PodConfigBrands & HasHostAliases> {
        return this.withUpdates({ podConfig: { ...this.podConfig, hostAliases: builderFn({ inputs: this.inputs, workflowInputs: this.workflowInputs }) } });
    }

    addPodSpecPatch(
        this: PodConfigBrands extends HasPodSpecPatch ? never : this,
        builderFn: (ctx: { inputs: InputParamsToExpressions<InputParamsScope>, workflowInputs: WorkflowInputsToExpressions<ParentWorkflowScope> }) => AllowLiteralOrExpression<string>
    ): ContainerBuilder<ParentWorkflowScope, InputParamsScope, ContainerScope, VolumeScope, EnvScope, OutputParamsScope, PodConfigBrands & HasPodSpecPatch> {
        return this.withUpdates({ podConfig: { ...this.podConfig, podSpecPatch: builderFn({ inputs: this.inputs, workflowInputs: this.workflowInputs }) } });
    }

    override addRetryParameters(
        this: PodConfigBrands extends HasRetryStrategy ? never : this,
        retryParameters: GenericScope
    ): ContainerBuilder<ParentWorkflowScope, InputParamsScope, ContainerScope, VolumeScope, EnvScope, OutputParamsScope, PodConfigBrands & HasRetryStrategy> {
        return this.withUpdates({ retry: retryParameters });
    }

    override addSynchronization(
        this: PodConfigBrands extends HasSynchronization ? never : this,
        synchronizationBuilderFn: (b: { inputs: InputParamsToExpressions<InputParamsScope> }) => SynchronizationConfig
    ): ContainerBuilder<ParentWorkflowScope, InputParamsScope, ContainerScope, VolumeScope, EnvScope, OutputParamsScope, PodConfigBrands & HasSynchronization> {
        return this.withUpdates({ sync: synchronizationBuilderFn({ inputs: this.inputs }) });
    }
}
