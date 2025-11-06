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

import {ConfigMapKeySelector, InputParametersRecord, OutputParamDef, OutputParametersRecord} from "./parameterSchemas";
import {
    DataOrConfigMapScope,
    DataScope, ExpressionOrConfigMapValue,
    ExtendScope,
    GenericScope,
    InputParamsToExpressions, LowercaseOnly,
    WorkflowAndTemplatesScope
} from "./workflowTypes";
import {inputsToEnvVars, TypescriptError} from "../utils";
import {RetryParameters, TemplateBodyBuilder, TemplateRebinder} from "./templateBodyBuilder"; // <-- import TemplateRebinder
import {extendScope, FieldGroupConstraint, ScopeIsEmptyConstraint} from "./scopeConstraints";
import {PlainObject} from "./plainObject";
import {AllowLiteralOrExpression, BaseExpression, expr, toExpression} from "./expression";
import {TypeToken} from "./sharedTypes";
import { DEFAULT_RESOURCES } from "@opensearch-migrations/schemas";

export type IMAGE_PULL_POLICY = "Always" | "Never" | "IfNotPresent";

export class ContainerBuilder<
    ContextualScope extends WorkflowAndTemplatesScope,
    InputParamsScope extends InputParametersRecord,
    ContainerScope extends GenericScope,
    VolumeScope extends GenericScope,
    EnvScope extends DataOrConfigMapScope,
    OutputParamsScope extends OutputParametersRecord
> extends TemplateBodyBuilder<
    ContextualScope,
    InputParamsScope,
    ContainerScope,
    OutputParamsScope,
    ContainerBuilder<ContextualScope, InputParamsScope, ContainerScope, VolumeScope, EnvScope, any>,
    GenericScope
> {
    constructor(
        contextualScope: ContextualScope,
        inputsScope: InputParamsScope,
        bodyScope: ContainerScope,
        public readonly volumeScope: VolumeScope,
        public readonly envScope: EnvScope,
        outputsScope: OutputParamsScope,
        retryParameters: RetryParameters
    ) {
        // REBINDER: must accept any NewBodyScope extends GenericScope and return ContainerBuilder
        const rebind: TemplateRebinder<
            ContextualScope,
            InputParamsScope,
            GenericScope
        > = <
            NewBodyScope extends GenericScope,
            NewOutputScope extends OutputParametersRecord,
            Self extends TemplateBodyBuilder<
                ContextualScope,
                InputParamsScope,
                NewBodyScope,
                NewOutputScope,
                any,
                GenericScope
            >
        >(
            ctx: ContextualScope,
            inputs: InputParamsScope,
            body: NewBodyScope,
            outputs: NewOutputScope,
            retry: RetryParameters
        ) =>
            new ContainerBuilder<
                ContextualScope,
                InputParamsScope,
                NewBodyScope,
                VolumeScope,
                EnvScope,
                NewOutputScope
            >(ctx, inputs, body, this.volumeScope, this.envScope, outputs, retry) as unknown as Self;

        super(contextualScope, inputsScope, bodyScope, outputsScope, retryParameters, rebind);
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
            ContextualScope,
            InputParamsScope,
            ExtendScope<ContainerScope, {
                image: AllowLiteralOrExpression<string>,
                imagePullPolicy: AllowLiteralOrExpression<string>
            }>,
            VolumeScope,
            EnvScope,
            OutputParamsScope
        > {
        return new ContainerBuilder(
            this.contextualScope,
            this.inputsScope,
            {...this.bodyScope, image, imagePullPolicy},
            this.volumeScope,
            this.envScope,
            this.outputsScope,
            this.retryParameters
        );
    }

    addCommand(strArr: AllowLiteralOrExpression<string>[]):
        ContainerBuilder<
            ContextualScope,
            InputParamsScope,
            ExtendScope<ContainerScope, { command: BaseExpression<string>[] }>,
            VolumeScope,
            EnvScope,
            OutputParamsScope
        > {
        return new ContainerBuilder(this.contextualScope, this.inputsScope, {
                ...this.bodyScope,
                command: strArr.map(s=>toExpression(s))
            },
            this.volumeScope,
            this.envScope,  // Preserve env scope
            this.outputsScope,
            this.retryParameters
        );
    }

    addArgs(a: AllowLiteralOrExpression<string>[]):
        ContainerBuilder<
            ContextualScope,
            InputParamsScope,
            ExtendScope<ContainerScope, { args: AllowLiteralOrExpression<string>[] }>,
            VolumeScope,
            EnvScope,
            OutputParamsScope
        > {
        return new ContainerBuilder(this.contextualScope, this.inputsScope, {
                ...this.bodyScope,
                args: a
            },
            this.volumeScope,
            this.envScope,  // Preserve env scope
            this.outputsScope,
            this.retryParameters
        );
    }

    addPathOutput<T extends PlainObject, Name extends string>(
        name: Name, pathValue: string, t: TypeToken<T>, descriptionValue?: string
    ):
        ContainerBuilder<
            ContextualScope,
            InputParamsScope,
            ContainerScope,
            VolumeScope,
            EnvScope,
            ExtendScope<OutputParamsScope, { [K in Name]: OutputParamDef<T> }>
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
            ContextualScope,
            InputParamsScope,
            ContainerScope,
            VolumeScope,
            EnvScope,
            NewOut
        >(
            this.contextualScope,
            this.inputsScope,
            this.bodyScope,
            this.volumeScope,
            this.envScope,
            newOutputs,
            this.retryParameters);
    }

    addVolumesFromRecord<
        R extends { [K in keyof R & string as LowercaseOnly<K>]: VolumeScope[K] }
    >(
        volumes: FieldGroupConstraint<VolumeScope, R>
    ): ContainerBuilder<
        ContextualScope,
        InputParamsScope,
        ContainerScope,
        ExtendScope<VolumeScope, R>,
        EnvScope,
        OutputParamsScope
    > {
        return new ContainerBuilder(
            this.contextualScope,
            this.inputsScope,
            this.bodyScope,
            extendScope(this.volumeScope, () => volumes as R),
            this.envScope,
            this.outputsScope,
            this.retryParameters);
    }

    addEnvVar<Name extends string>(
        name: string extends keyof EnvScope  // If EnvScope is Record<string, X>, disable checking
            ? Name
            : Name extends keyof EnvScope
                ? TypescriptError<`Environment variable '${Name}' already exists`>
                : Name,
        value: ExpressionOrConfigMapValue<string>
    ): ContainerBuilder<
        ContextualScope,
        InputParamsScope,
        ContainerScope,
        VolumeScope,
        ExtendScope<EnvScope, { [K in Name]: ExpressionOrConfigMapValue<string> }>,
        OutputParamsScope
    > {
        return this.addEnvVarUnchecked(name as string, value) as any;
    }

    addEnvVarsFromRecord<
        R extends { [K in keyof R & string]: EnvScope[K] }
    >(
        envVars: FieldGroupConstraint<EnvScope, R>
    ): ContainerBuilder<
        ContextualScope,
        InputParamsScope,
        ContainerScope,
        VolumeScope,
        ExtendScope<EnvScope, R>,
        OutputParamsScope
    > {
        return new ContainerBuilder(
            this.contextualScope,
            this.inputsScope,
            this.bodyScope,
            this.volumeScope,
            extendScope(this.envScope, () => envVars as R),
            this.outputsScope,
            this.retryParameters);
    }

    addEnvVars<NewEnvScope extends DataOrConfigMapScope>(
        builderFn: (
            cb: ContainerBuilder<ContextualScope, InputParamsScope, ContainerScope, {}, {}, OutputParamsScope>
        ) => ContainerBuilder<ContextualScope, InputParamsScope, ContainerScope, {}, NewEnvScope, OutputParamsScope>
    ): ScopeIsEmptyConstraint<EnvScope,
        ContainerBuilder<ContextualScope, InputParamsScope, ContainerScope, {}, NewEnvScope, OutputParamsScope>
    > {
        const emptyEnvBuilder = new ContainerBuilder(
            this.contextualScope,
            this.inputsScope,
            this.bodyScope,
            this.volumeScope,
            {},
            this.outputsScope,
            this.retryParameters
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
        ContextualScope,
        InputParamsScope,
        ContainerScope,
        VolumeScope,
        ExtendScope<EnvScope, { [K in Name]: ExpressionOrConfigMapValue<string> }>,
        OutputParamsScope
    > {
        const currentEnv = (this.bodyScope as any).env || {};
        const newEnvScope = {
            ...this.envScope,
            [name as string]: value
        } as ExtendScope<EnvScope, { [K in Name]: ExpressionOrConfigMapValue<string> }>;

        return new ContainerBuilder(
            this.contextualScope,
            this.inputsScope,
            {...this.bodyScope, env: {...currentEnv, [name as string]: value}},
            this.volumeScope,
            newEnvScope,
            this.outputsScope,
            this.retryParameters
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
            ContextualScope,
            InputParamsScope,
            ContainerScope,
            VolumeScope,
            ModifiedInputs,
            OutputParamsScope
        >> {
        const envVars = modifierFn(this.inputs);

        return new ContainerBuilder(
            this.contextualScope,
            this.inputsScope,
            {
                ...this.bodyScope,
                env: envVars
            },
            this.volumeScope,
            envVars,
            this.outputsScope,
            this.retryParameters
        ) as any;
    }

    /**
     * Add resource requirements (CPU, memory limits and requests) to the container.
     * 
     * @param resources - Resource requirements (limits and requests for CPU, memory, ephemeral-storage)
     * @returns New ContainerBuilder with resources applied and branded
     */
    addResources(
        // Merged Resources failing due to argo validation that is not allowing expressions in resources
        // Solve this by requesting an argo change https://github.com/argoproj/argo-workflows/issues/15005
        // When we have this we will be able to use
        // resources: AllowLiteralOrExpression<Record<string, any>> 
        // and
        // const mergedResources = expr.serialize(expr.mergeDicts(
        //     expr.literal(this.bodyScope?.resources || {}),
        //     toExpression(resources)));
        resources: Record<string, any>
    ): ContainerBuilder<
        ContextualScope,
        InputParamsScope,
        ExtendScope<ContainerScope, { resources: AllowLiteralOrExpression<Record<string, any>> }>,
        VolumeScope,
        EnvScope,
        OutputParamsScope
    > {
        const mergedResources = resources;
        return new ContainerBuilder(
            {...this.contextualScope},
            this.inputsScope,
            {...this.bodyScope, resources: mergedResources} as ExtendScope<ContainerScope, { resources: AllowLiteralOrExpression<Record<string, any>> }>,
            this.volumeScope,
            this.envScope,
            this.outputsScope,
            this.retryParameters
        );
    }
}
