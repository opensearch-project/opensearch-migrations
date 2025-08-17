import {OutputParamDef, InputParametersRecord, OutputParametersRecord} from "@/schemas/parameterSchemas";
import {
    DataScope,
    ExtendScope,
    AllowLiteralOrExpression,
    InputParamsToExpressions,
    WorkflowAndTemplatesScope, GenericScope
} from "@/schemas/workflowTypes";
import {z, ZodType} from "zod";
import {toEnvVarName, TypescriptError} from "@/utils";
import {IMAGE_PULL_POLICY} from "@/schemas/userSchemas";
import {TemplateBodyBuilder} from "@/schemas/templateBodyBuilder";
import {ScopeIsEmptyConstraint} from "@/schemas/scopeConstraints";
import {PlainObject} from "@/schemas/plainObject";

export function inputsToEnvVars<T extends Record<string, AllowLiteralOrExpression<string>>>(
    inputs: T
): { [K in keyof T as Uppercase<string & K>]: T[K] } {
    const result: Record<string, AllowLiteralOrExpression<string>> = {};
    Object.entries(inputs).forEach(([key, value]) => {
        result[toEnvVarName(key)] = value;
    });
    return result as any;
}

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
> extends TemplateBodyBuilder<ContextualScope, "container", InputParamsScope, ContainerScope, OutputParamsScope,
    ContainerBuilder<ContextualScope, InputParamsScope, ContainerScope, EnvScope, any>>
{
    constructor(contextualScope: ContextualScope,
                inputsScope: InputParamsScope,
                bodyScope: ContainerScope,
                public readonly envScope: EnvScope,  // Add env scope to constructor
                outputsScope: OutputParamsScope) {
        super("container", contextualScope, inputsScope, bodyScope, outputsScope)
    }

    // Update existing methods to preserve EnvScope type parameter

    addImageInfo(image: AllowLiteralOrExpression<string>,
                 pullPolicy: AllowLiteralOrExpression<z.infer<typeof IMAGE_PULL_POLICY>>):
        ContainerBuilder<
            ContextualScope,
            InputParamsScope,
            ExtendScope<ContainerScope, {
                image: AllowLiteralOrExpression<string>,
                pullPolicy: AllowLiteralOrExpression<string>
            }>,
            EnvScope,  // Preserve env scope
            OutputParamsScope>
    {
        return new ContainerBuilder(this.contextualScope, this.inputsScope, {
            ...this.bodyScope,
            'image': image,
            'pullPolicy': pullPolicy
        }, this.envScope, this.outputsScope);
    }

    addCommand(s: string[]):
        ContainerBuilder<ContextualScope, InputParamsScope,
            ExtendScope<ContainerScope, {command: string[]}>, EnvScope, OutputParamsScope>
    {
        return new ContainerBuilder(this.contextualScope, this.inputsScope, {
                ...this.bodyScope,
                command: s},
            this.envScope,  // Preserve env scope
            this.outputsScope
        );
    }

    addArgs(a: string[]):
        ContainerBuilder<ContextualScope, InputParamsScope,
            ExtendScope<ContainerScope, {args: string[]}>, EnvScope, OutputParamsScope>
    {
        return new ContainerBuilder(this.contextualScope, this.inputsScope, {
                ...this.bodyScope,
                args: a},
            this.envScope,  // Preserve env scope
            this.outputsScope
        );
    }

    addPathOutput<T extends PlainObject, Name extends string>(name: Name, pathValue: string, t: ZodType<T>, descriptionValue?: string):
        ContainerBuilder<ContextualScope, InputParamsScope, ContainerScope, EnvScope,
            ExtendScope<OutputParamsScope, { [K in Name]: OutputParamDef<T> }>> {
        return new ContainerBuilder(this.contextualScope, this.inputsScope, this.bodyScope, this.envScope, {
            ...this.outputsScope,
            [name as string]: {
                type: t,
                fromWhere: "path" as const,
                path: pathValue,
                description: descriptionValue
            }
        });
    }

    addExpressionOutput<T extends PlainObject, Name extends string>(name: Name, expression: string, t: ZodType<T>, descriptionValue?: string):
        ContainerBuilder<ContextualScope, InputParamsScope, ContainerScope, EnvScope,
            ExtendScope<OutputParamsScope, { [K in Name]: OutputParamDef<T> }>> {
        return new ContainerBuilder(this.contextualScope, this.inputsScope, this.bodyScope, this.envScope, {
            ...this.outputsScope,
            [name as string]: {
                type: t,
                fromWhere: "expression" as const,
                expression: expression,
                description: descriptionValue
            }
        });
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
    >
    {
        return this.addEnvVarUnchecked(name as string, value) as any;
    }

    addEnvVars<NewEnvScope extends DataScope>(
        builderFn: (
            cb: ContainerBuilder<ContextualScope, InputParamsScope, ContainerScope, {}, OutputParamsScope>
        ) => ContainerBuilder<ContextualScope, InputParamsScope, ContainerScope, NewEnvScope, OutputParamsScope>
    ): ScopeIsEmptyConstraint<EnvScope,
        ContainerBuilder<ContextualScope, InputParamsScope, ContainerScope, NewEnvScope, OutputParamsScope>
    >
    {
        const emptyEnvBuilder = new ContainerBuilder(
            this.contextualScope,
            this.inputsScope,
            this.bodyScope,
            {},
            this.outputsScope
        );

        return builderFn(emptyEnvBuilder) as any;
    }

    // Internal method without constraint checking for use by other methods
    private addEnvVarUnchecked<Name extends string>(
        name: Name,
        value: AllowLiteralOrExpression<string>
    ): ContainerBuilder<
        ContextualScope,
        InputParamsScope,
        ContainerScope,
        ExtendScope<EnvScope, { [K in Name]: AllowLiteralOrExpression<string> }>,
        OutputParamsScope
    >
    {
        const currentEnv = (this.bodyScope as any).env || {};
        const newEnvScope = { ...this.envScope, [name as string]: value } as ExtendScope<EnvScope, { [K in Name]: AllowLiteralOrExpression<string> }>;

        return new ContainerBuilder(this.contextualScope, this.inputsScope, {
            ...this.bodyScope,
            env: { ...currentEnv, [name as string]: value }
        }, newEnvScope, this.outputsScope);
    }

    // Convenience method to map input parameters to environment variables
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
        >>
    {
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
