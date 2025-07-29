import {
    defineParam,
    InputParamDef,
    InputParametersRecord,
} from "@/schemas/parameterSchemas";

export type Scope = Record<string, any>;
export type ExtendScope<S extends Scope, ADDITIONS extends Scope> = S & ADDITIONS;
export type ScopeFn<S extends Scope, ADDITIONS extends Scope> = (scope: Readonly<S>) => ADDITIONS;

class ScopeBuilder<SigScope extends Scope = Scope, FullScope extends Scope = Scope> {
    constructor(
        protected readonly sigScope: SigScope,
        protected readonly fullScope: FullScope
    ) {}

    addWithCtor<
        AddSig extends Scope,//((keyof SigScope & keyof AddSig) extends never ? Scope : never),
        AddFull extends Scope,//((keyof FullScope & keyof AddFull) extends never ? Scope : never),
        B
    >(
        sigFn: ScopeFn<SigScope, AddSig>,
        fullFn: ScopeFn<FullScope, AddFull>,
        builderCtor: (
            sigScope: Readonly<ExtendScope<SigScope, AddSig>>,
            fullScope: Readonly<ExtendScope<FullScope, AddFull>>
        ) => B
    ): B {
        const newSigScope = {
            ...this.sigScope,
            ...sigFn(this.sigScope)
        } as ExtendScope<SigScope, AddSig>;

        const newFullScope = {
            ...this.fullScope,
            ...fullFn(this.fullScope)
        } as ExtendScope<FullScope, AddFull>;

        return builderCtor(newSigScope, newFullScope);
    }

    getSigScope(): SigScope {
        return this.sigScope;
    }
    getFullScope(): FullScope {
        return this.fullScope;
    }
}

class UnifiedScopeBuilder<SingleScope extends Scope = Scope> extends ScopeBuilder<SingleScope, SingleScope> {
    constructor(protected readonly scope: SingleScope) {
        super(scope, scope);
    }

    addToBothWithCtor<
        Add extends Scope,
        B
    >(
        fn: ScopeFn<SingleScope, Add>,
        builderCtor: (scope: ExtendScope<SingleScope, Add>) => B
    ): B {
        const newScope = { ...this.sigScope, ...fn(this.sigScope) } as ExtendScope<SingleScope, Add>;
        return builderCtor(newScope);
    }
}

/**
 * Builds the top-level workflow one template at a time, via a TemplateBuilder instance. This implementation tracks two different scopes - a public one that is pushed down to all new Templates
 * to be constructed that includes previous template's inputs/outputs + workflow parameters as well as the
 * entire scope.  The first scope is passed to the TemplateBuilder so that it can be used to compile check
 * all the templates, asserting that they only use exposed variables.  The second scope is used to generate
 * the final resource.
 */


/**
 * Entry-point to build a top-level K8s resource.  Allows the addition of workflow parameters
 * and then transitions to the repeated addition of templates with the TemplateChainer
 */
export class WFBuilder<
    MetadataScope extends Scope = Scope,
    WorkflowInputsScope extends Scope = Scope,
    TemplateSigScope extends Scope = Scope,
    TemplateFullScope extends Scope = Scope
> {
    metadataScopeBuilder: UnifiedScopeBuilder<MetadataScope>;
    inputsScopeBuilder: UnifiedScopeBuilder<WorkflowInputsScope>;
    templateScopeBuilder: ScopeBuilder<TemplateSigScope, TemplateFullScope>;
    constructor(metadataScope: MetadataScope,
                inputsScope: WorkflowInputsScope,
                templateSigScope: TemplateSigScope,
                templateFullScope: TemplateFullScope)
    {
        this.metadataScopeBuilder = new UnifiedScopeBuilder(metadataScope);
        this.inputsScopeBuilder = new UnifiedScopeBuilder(inputsScope);
        this.templateScopeBuilder = new ScopeBuilder(templateSigScope, templateFullScope);
    }

    static create(k8sResourceName: string) {
        return new WFBuilder({name: k8sResourceName}, {}, {}, {});
    }

    addParams<
        P extends InputParametersRecord
    >(
        params: P
    ): WFBuilder<
        MetadataScope,
        ExtendScope<WorkflowInputsScope, P>,
        TemplateSigScope,
        TemplateFullScope
    > {
        return this.inputsScopeBuilder.addToBothWithCtor(
            () => params,
            (newInputs): WFBuilder<
                MetadataScope,
                ExtendScope<WorkflowInputsScope, P>,
                TemplateSigScope,
                TemplateFullScope
            > =>
                new WFBuilder(
                    this.metadataScopeBuilder.getFullScope(),
                    newInputs,
                    this.templateScopeBuilder.getSigScope(),
                    this.templateScopeBuilder.getFullScope()
                )
        );
    }

    addTemplate<
        Name extends string,
        TB extends TemplateBuilder<any, any>,
        FullTemplate extends ReturnType<TB["getFullTemplateScope"]>,
        NewSig extends Scope = {
            [K in Name]: {
                input: FullTemplate["inputs"];
                output?: FullTemplate extends { outputs: infer O } ? O : never;
            }
        },
        NewFull extends Scope = {
            [K in Name]: FullTemplate;
        }
    >(
        name: Name,
        fn: (tb: TemplateBuilder<{
            workflowParams: WorkflowInputsScope;
            templates: TemplateSigScope;
        }, {}>) => TB
    ): WFBuilder<
        MetadataScope,
        WorkflowInputsScope,
        ExtendScope<TemplateSigScope, NewSig>,
        ExtendScope<TemplateFullScope, NewFull>
    > {
        const templateScope = {
            workflowParams: this.inputsScopeBuilder.getSigScope(),
            templates: this.templateScopeBuilder.getSigScope()
        };

        const templateBuilder = fn(new TemplateBuilder(templateScope, {}));
        const fullTemplate = templateBuilder.getFullTemplateScope();

        return this.templateScopeBuilder.addWithCtor(
            () =>
                ({
                    [name]: {
                        input: fullTemplate.inputs,
                        output: (fullTemplate as any).outputs
                    }
                }) as NewSig,
            () =>
                ({
                    [name]: fullTemplate
                }) as NewFull,
            (sigScope, fullScope): WFBuilder<
                MetadataScope,
                WorkflowInputsScope,
                ExtendScope<TemplateSigScope, NewSig>,
                ExtendScope<TemplateFullScope, NewFull>
            > =>
                new WFBuilder(
                    this.metadataScopeBuilder.getFullScope(),
                    this.inputsScopeBuilder.getFullScope(),
                    sigScope,
                    fullScope
                )
        );
    }

    getFullScope() {
        return {
            metadata: this.metadataScopeBuilder.getFullScope(),
            workflowParams: this.inputsScopeBuilder.getFullScope(),
            templates: this.templateScopeBuilder.getFullScope()
        }
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
    private readonly scopeBuilder: UnifiedScopeBuilder<InputParamsScope>;
    readonly contextualScope: ContextualScope;

    constructor(contextualScope: ContextualScope, scope: InputParamsScope) {
        this.contextualScope = contextualScope;
        this.scopeBuilder = new UnifiedScopeBuilder(scope);
    }

    addOptional<
        T,
        Name extends string & keyof any = string
    >(
        name: Name,
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

        return this.scopeBuilder.addToBothWithCtor(
            () => ({ [name]: param }) as { [K in Name]: InputParamDef<T, false> },
            newScope => new TemplateBuilder(this.contextualScope, newScope)
        );
    }

    addRequired<
        Name extends string & keyof any
    >(
        name: Name,
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

        return this.scopeBuilder.addToBothWithCtor(
            () => ({ [name]: param }) as { [K in Name]: InputParamDef<any, true> },
            newScope => new TemplateBuilder(this.contextualScope, newScope)
        );
    }

    getFullTemplateScope(): { inputs: InputParamsScope } {
        return { inputs: this.scopeBuilder.getFullScope() };
    }

    getTemplateSignatureScope(): InputParamsScope {
        return this.scopeBuilder.getSigScope();
    }
}


export class SpecificTemplateBuilder<S extends Scope> extends UnifiedScopeBuilder<S> {

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
