import {defineParam, InputParamDef, InputParametersRecord,} from "@/schemas/parameterSchemas";
import {Scope, ScopeFn, ExtendScope, TemplateSigEntry, DuplicateTemplateError, DuplicateParamError} from "@/schemas/workflowTypes";

class ScopeBuilder<SigScope extends Scope = Scope> {
    constructor(protected readonly sigScope: SigScope) {}

    extendScope<AddSig extends Scope>(fn: ScopeFn<SigScope, AddSig>): ExtendScope<SigScope, AddSig> {
        return {
            ...this.sigScope,
            ...fn(this.sigScope)
        } as ExtendScope<SigScope, AddSig>;
    }

    getScope(): SigScope {
        return this.sigScope;
    }
}

export class WFBuilder<
    MetadataScope extends Scope = Scope,
    WorkflowInputsScope extends Scope = Scope,
    TemplateSigScope extends Scope = Scope,
    TemplateFullScope extends Scope = Scope
> {
    metadataScope: MetadataScope;
    inputsScope: WorkflowInputsScope;
    templateSigScope: TemplateSigScope;
    templateFullScope: TemplateFullScope;

    constructor(
        metadataScope: MetadataScope,
        inputsScope: WorkflowInputsScope,
        templateSigScope: TemplateSigScope,
        templateFullScope: TemplateFullScope
    ) {
        this.metadataScope = metadataScope;
        this.inputsScope = inputsScope;
        this.templateSigScope = templateSigScope;
        this.templateFullScope = templateFullScope;
    }

    static create(k8sResourceName: string) {
        return new WFBuilder({ name: k8sResourceName }, {}, {}, {});
    }

    addParams<P extends InputParametersRecord>(
        params: P
    ): WFBuilder<
        MetadataScope,
        ExtendScope<WorkflowInputsScope, P>,
        TemplateSigScope,
        TemplateFullScope
    > {
        const newInputs = { ...this.inputsScope, ...params } as ExtendScope<WorkflowInputsScope, P>;
        return new WFBuilder(
            this.metadataScope,
            newInputs,
            this.templateSigScope,
            this.templateFullScope
        );
    }

    addTemplate<
        Name extends string,
        TB extends TemplateBuilder<any, any>,
        FullTemplate extends ReturnType<TB["getFullTemplateScope"]>
    >(
        name: Name extends keyof TemplateSigScope ? never : Name,
        fn: (tb: TemplateBuilder<{
            workflowParameters: WorkflowInputsScope;
            templates: TemplateSigScope;
        }, {}>) => TB
    ): WFBuilder<
        MetadataScope,
        WorkflowInputsScope,
        ExtendScope<TemplateSigScope, { [K in Name]: TemplateSigEntry<FullTemplate> }>,
        ExtendScope<TemplateFullScope, { [K in Name]: FullTemplate }>
    > {
        const templateScope = {
            workflowParameters: this.inputsScope,
            templates: this.templateSigScope
        };

        const templateBuilder = fn(new TemplateBuilder(templateScope, {}));
        const fullTemplate = templateBuilder.getFullTemplateScope();

        const newSig = {
            [name]: {
                input: fullTemplate.inputs,
                output: (fullTemplate as any).outputs
            }
        } as { [K in Name]: TemplateSigEntry<FullTemplate> };

        const newFull = {[name]: fullTemplate} as { [K in Name]: FullTemplate };

        return new WFBuilder(
            this.metadataScope,
            this.inputsScope,
            {...this.templateSigScope, ...newSig},
            {...this.templateFullScope, ...newFull}
        );
    }

    getFullScope() {
        return {
            metadata: this.metadataScope,
            workflowParameters: this.inputsScope,
            templates: this.templateFullScope
        };
    }
}

/**
 * Maintains a scope of all previous public parameters (workflow and previous templates' inputs/outputs)
 * as well as newly created inputs/outputs for this template.  To define a specific template, use one of
 * the builder methods, which, like `addTemplate` will return a new builder for that type of template
 * receives the specification up to that point.
 */
export class TemplateBuilder<
    ContextualScope extends Scope,
    InputParamsScope extends Scope = Scope
> {
    readonly contextualScope: ContextualScope;
    private readonly scopeBuilder: ScopeBuilder<InputParamsScope>;

    constructor(contextualScope: ContextualScope, scope: InputParamsScope) {
        this.contextualScope = contextualScope;
        this.scopeBuilder = new ScopeBuilder(scope);
    }

    private extendWithParam<
        T,
        Name extends string,
        R extends boolean
    >(
        name: Name,
        param: InputParamDef<T, R>
    ): TemplateBuilder<
        ContextualScope,
        ExtendScope<InputParamsScope, { [K in Name]: InputParamDef<T, R> }>
    > {
        const newScope = this.scopeBuilder.extendScope(s =>
            ({ [name]: param })// as { [K in Name]: InputParamDef<T, R> }
        );

        return new TemplateBuilder(this.contextualScope, newScope);
    }

    addOptional<T, Name extends string>(
        name: Name extends keyof InputParamsScope ? never : Name,
        defaultValueFromScopeFn: (s: { context: ContextualScope; currentScope: InputParamsScope }) => T,
        description?: string
    ): TemplateBuilder<
        ContextualScope,
        ExtendScope<InputParamsScope, { [K in Name]: InputParamDef<T, false> }>
    > {
        const param = defineParam({
            defaultValue: defaultValueFromScopeFn({
                context: this.contextualScope,
                currentScope: this.getTemplateSignatureScope()
            }),
            description
        });

        return this.extendWithParam(name, param);
    }

    addRequired<
        Name extends string & keyof any
    >(
        name: Name extends keyof InputParamsScope ? never : Name,
        type: any,
        description?: string
    ): TemplateBuilder<
        ContextualScope,
        ExtendScope<InputParamsScope, { [K in Name]: InputParamDef<any, true> }>
    > {
        const param: InputParamDef<any, true> = {
            type,
            description
        };

        return this.extendWithParam(name, param);
    }

    getTemplateSignatureScope(): InputParamsScope {
        return this.scopeBuilder.getScope();
    }

    getFullTemplateScope(): { inputs: InputParamsScope } {
        return { inputs: this.scopeBuilder.getScope() };
    }
}



export class SpecificTemplateBuilder<S extends Scope> extends ScopeBuilder<S> {

}

export class StepsTemplateBuilder<S extends Scope>
    extends SpecificTemplateBuilder<S> {
    addStep() {
        return this;
    }
}

// export function callTemplate<
//     TClass extends Record<string, any>,
//     TKey extends Extract<keyof TClass, string>
// >(
//     classConstructor: TClass,
//     key: TKey,
//     params: z.infer<ReturnType<typeof paramsToCallerSchema<TClass[TKey]["inputs"]>>>
// ): WorkflowTask<TClass[TKey]["inputs"], TClass[TKey]["outputs"]> {
//     const value = classConstructor[key];
//     return {
//         templateRef: { key, value },
//         arguments: { parameters: params }
//     };
// }
