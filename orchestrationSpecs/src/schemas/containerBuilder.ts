import {OutputParamDef} from "@/schemas/parameterSchemas";
import {Scope, ExtendScope, AllowLiteralOrExpression, InputParamsToExpressions} from "@/schemas/workflowTypes";
import {z, ZodType} from "zod";
import {toEnvVarName, TypescriptError} from "@/utils";
import {IMAGE_PULL_POLICY} from "@/schemas/userSchemas";
import {TemplateBodyBuilder} from "@/schemas/templateBodyBuilder";
import {ScopeIsEmptyConstraint} from "@/schemas/scopeConstraints";

export function inputsToEnvVarNames<T extends Record<string, any>>(inputs: T): Record<string, T[keyof T]> {
    const result: Record<string, any> = {};
    Object.entries(inputs).forEach(([key, value]) => {
        result[toEnvVarName(key)] = value;
    });
    return result;
}

export class ContainerBuilder<
    ContextualScope extends Scope,
    InputParamsScope  extends Scope,
    ContainerScope extends Scope,
    EnvScope extends Scope,  // New scope for environment variables
    OutputParamsScope extends Scope
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

    addPathOutput<T, Name extends string>(name: Name, pathValue: string, t: ZodType<T>, descriptionValue?: string):
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

    addExpressionOutput<T, Name extends string>(name: Name, expression: string, t: ZodType<T>, descriptionValue?: string):
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

    addEnvVars<NewEnvScope extends Scope>(
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
    addInputsAsEnvVars<ModifiedInputs extends Record<string, AllowLiteralOrExpression<string>> = never>(
        modifierFn?: (inputs: InputParamsToExpressions<InputParamsScope>) => ModifiedInputs
    ): ScopeIsEmptyConstraint<EnvScope,
        ContainerBuilder<
            ContextualScope,
            InputParamsScope,
            ContainerScope,
            ModifiedInputs extends never
                ? Record<string, AllowLiteralOrExpression<string>>  // Default case - less specific
                : ModifiedInputs,  // Modified case - preserve specific keys
            OutputParamsScope
        >>
    {
        const baseInputs = this.inputs as Record<string, AllowLiteralOrExpression<string>>;
        const envVars = modifierFn
            ? modifierFn(this.inputs)
            : inputsToEnvVarNames(baseInputs);

        // Create new ContainerBuilder with envVars as the complete env scope
        return new ContainerBuilder(this.contextualScope, this.inputsScope, {
            ...this.bodyScope,
            env: envVars
        }, envVars as any, this.outputsScope) as any;
    }
}
