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

import {InputParametersRecord, OutputParamDef, OutputParametersRecord, TypeToken} from "@/schemas/parameterSchemas";
import {
    AllowLiteralOrExpression,
    DataScope,
    ExtendScope,
    GenericScope,
    InputParamsToExpressions,
    WorkflowAndTemplatesScope
} from "@/schemas/workflowTypes";
import { inputsToEnvVars, toEnvVarName, TypescriptError } from "@/utils";
import { TemplateBodyBuilder, TemplateRebinder } from "@/schemas/templateBodyBuilder"; // <-- import TemplateRebinder
import { ScopeIsEmptyConstraint } from "@/schemas/scopeConstraints";
import { PlainObject } from "@/schemas/plainObject";

export type IMAGE_PULL_POLICY = "ALWAYS" | "NEVER" | "IF_NOT_PRESENT";

export function inputsToEnvVarNames<T extends Record<string, AllowLiteralOrExpression<string>>>(
    inputs: T
): Record<string, AllowLiteralOrExpression<string>> {
    const result: Record<string, AllowLiteralOrExpression<string>> = {};
    Object.entries(inputs).forEach(([key, value]) => {
        result[toEnvVarName(key)] = value;
    });
    return result;
}

export class ContainerBuilder<
    ContextualScope extends WorkflowAndTemplatesScope,
    InputParamsScope extends InputParametersRecord,
    ContainerScope extends GenericScope,
    EnvScope extends DataScope,
    OutputParamsScope extends OutputParametersRecord
> extends TemplateBodyBuilder<
    ContextualScope,
    InputParamsScope,
    ContainerScope,
    OutputParamsScope,
    ContainerBuilder<ContextualScope, InputParamsScope, ContainerScope, EnvScope, any>,
    GenericScope
> {
    constructor(
        contextualScope: ContextualScope,
        inputsScope: InputParamsScope,
        bodyScope: ContainerScope,
        public readonly envScope: EnvScope,
        outputsScope: OutputParamsScope
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
            outputs: NewOutputScope
        ) =>
            new ContainerBuilder<
                ContextualScope,
                InputParamsScope,
                NewBodyScope,
                EnvScope,
                NewOutputScope
            >(ctx, inputs, body, this.envScope, outputs) as unknown as Self;

        super(contextualScope, inputsScope, bodyScope, outputsScope, rebind);
    }

    protected getBody() {
        return { container: this.bodyScope };
    }

    // Update existing methods to preserve EnvScope type parameter

    addImageInfo(image: AllowLiteralOrExpression<string>,
                 pullPolicy: AllowLiteralOrExpression<IMAGE_PULL_POLICY>):
        ContainerBuilder<
            ContextualScope,
            InputParamsScope,
            ExtendScope<ContainerScope, {
                image: AllowLiteralOrExpression<string>,
                pullPolicy: AllowLiteralOrExpression<string>
            }>,
            EnvScope,
            OutputParamsScope
        > {
        return new ContainerBuilder(
            this.contextualScope,
            this.inputsScope,
            { ...this.bodyScope, image, pullPolicy },
            this.envScope,
            this.outputsScope
        );
    }

    addCommand(s: string[]):
        ContainerBuilder<ContextualScope, InputParamsScope,
            ExtendScope<ContainerScope, { command: string[] }>, EnvScope, OutputParamsScope> {
        return new ContainerBuilder(this.contextualScope, this.inputsScope, {
                ...this.bodyScope,
                command: s
            },
            this.envScope,  // Preserve env scope
            this.outputsScope
        );
    }

    addArgs(a: string[]):
        ContainerBuilder<ContextualScope, InputParamsScope,
            ExtendScope<ContainerScope, { args: string[] }>, EnvScope, OutputParamsScope> {
        return new ContainerBuilder(this.contextualScope, this.inputsScope, {
                ...this.bodyScope,
                args: a
            },
            this.envScope,  // Preserve env scope
            this.outputsScope
        );
    }

    addPathOutput<T extends PlainObject, Name extends string>(
        name: Name, pathValue: string, t: TypeToken<T>, descriptionValue?: string
    ):
        ContainerBuilder<
            ContextualScope,
            InputParamsScope,
            ContainerScope,
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
            EnvScope,
            NewOut
        >(this.contextualScope, this.inputsScope, this.bodyScope, this.envScope, newOutputs);
    }

    addEnvVar<Name extends string>(
        name: string extends keyof EnvScope  // If EnvScope is Record<string, X>, disable checking
            ? Name
            : Name extends keyof EnvScope
                ? TypescriptError<`Environment variable '${Name}' already exists`>
                : Name,
        value: AllowLiteralOrExpression<string>
    ): ContainerBuilder<
        ContextualScope,
        InputParamsScope,
        ContainerScope,
        ExtendScope<EnvScope, { [K in Name]: AllowLiteralOrExpression<string> }>,
        OutputParamsScope
    > {
        return this.addEnvVarUnchecked(name as string, value) as any;
    }

    addEnvVars<NewEnvScope extends DataScope>(
        builderFn: (
            cb: ContainerBuilder<ContextualScope, InputParamsScope, ContainerScope, {}, OutputParamsScope>
        ) => ContainerBuilder<ContextualScope, InputParamsScope, ContainerScope, NewEnvScope, OutputParamsScope>
    ): ScopeIsEmptyConstraint<EnvScope,
        ContainerBuilder<ContextualScope, InputParamsScope, ContainerScope, NewEnvScope, OutputParamsScope>
    > {
        const emptyEnvBuilder = new ContainerBuilder(
            this.contextualScope,
            this.inputsScope,
            this.bodyScope,
            {},
            this.outputsScope
        );
        return builderFn(emptyEnvBuilder) as any;
    }

    /**
     * Internal method without constraint checking for use by other methods
     */
    private addEnvVarUnchecked<Name extends string>(
        name: Name,
        value: AllowLiteralOrExpression<string>
    ): ContainerBuilder<
        ContextualScope,
        InputParamsScope,
        ContainerScope,
        ExtendScope<EnvScope, { [K in Name]: AllowLiteralOrExpression<string> }>,
        OutputParamsScope
    > {
        const currentEnv = (this.bodyScope as any).env || {};
        const newEnvScope = {
            ...this.envScope,
            [name as string]: value
        } as ExtendScope<EnvScope, { [K in Name]: AllowLiteralOrExpression<string> }>;

        return new ContainerBuilder(
            this.contextualScope,
            this.inputsScope,
            { ...this.bodyScope, env: { ...currentEnv, [name as string]: value } },
            newEnvScope,
            this.outputsScope
        );
    }

    /**
     * Convenience method to map input parameters to environment variables
     */
    addInputsAsEnvVars<
        ModifiedInputs extends Record<string, AllowLiteralOrExpression<string>> =
            { [K in keyof InputParamsScope as Uppercase<string & K>]: AllowLiteralOrExpression<string> }
    >(
        modifierFn: (inputs: InputParamsToExpressions<InputParamsScope>) => ModifiedInputs =
        inputsToEnvVars as any
    ): ScopeIsEmptyConstraint<EnvScope,
        ContainerBuilder<
            ContextualScope,
            InputParamsScope,
            ContainerScope,
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
            envVars,
            this.outputsScope
        ) as any;
    }
}
