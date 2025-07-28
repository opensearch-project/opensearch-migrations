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

    addParams<P extends InputParametersRecord>(params: P) {
        return this.inputsScopeBuilder.addToBothWithCtor(
            () => params,
            ss =>
                new WFBuilder(
                    this.metadataScopeBuilder.getFullScope(),
                    ss,
                    this.templateScopeBuilder.getSigScope(),
                    this.templateScopeBuilder.getFullScope()));
    }

    addTemplate<
        Name extends string,
        TemplateInput extends Scope,
        TemplateOutput extends Scope,
        STB extends SpecificTemplateBuilder<TemplateInput, TemplateOutput>
    >(
        name: Name,
        fn: (tb: TemplateBuilder<{
            workflowParams: WorkflowInputsScope;
            templates: TemplateSigScope;
        }>) => STB
    ): WFBuilder<
        MetadataScope,
        WorkflowInputsScope,
        ExtendScope<TemplateSigScope, { [K in Name]: TemplateInput }>,
        ExtendScope<TemplateFullScope, { [K in Name]: TemplateOutput }>
    > {
        const templateScope = {
            workflowParams: this.inputsScopeBuilder.getSigScope(),
            templates: this.templateScopeBuilder.getSigScope(),
        };
        // start w/ the same public/full scope for a new TemplateBuilder
        const templateResult = fn(new TemplateBuilder(templateScope, {}));

        return this.templateScopeBuilder.addWithCtor(() => ({
                [name]: (templateResult.getFullScope().inputs || {})
            }),
            () => ({
                [name]: templateResult.getFullScope()
            }), (sigScope, fullScope) =>
                new WFBuilder(this.metadataScopeBuilder.getFullScope(),
                    this.inputsScopeBuilder.getFullScope(),
                    sigScope,
                    fullScope));
    }

    getSigScope() {
        return {
            workflowParams: this.inputsScopeBuilder.getSigScope(),
            templates: {
                ...this.templateScopeBuilder.getSigScope()
            }
        }
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
export class TemplateBuilder<ContextualScope extends Scope, SingleScope extends Scope = Scope> extends UnifiedScopeBuilder<SingleScope> {
    contextualScope: ContextualScope;
    constructor(contextualScope: ContextualScope, ss: SingleScope) {
        super(ss);
        this.contextualScope = contextualScope;
    }

    addOptional<
        T,
        Name extends string & keyof any = string
    >(name: Name,
      defaultValueFromScopeFn: (s: { context: ContextualScope, currentScope: SingleScope }) => T,
      description?: string
    ): TemplateBuilder<
        ContextualScope,
        ExtendScope<SingleScope, { [K in Name]: InputParamDef<T, false> }>
    > {
        const param = defineParam({
            defaultValue: defaultValueFromScopeFn({ context: this.contextualScope, currentScope: this.scope }),
            description: description
        });

        return super.addToBothWithCtor(
            () => ({
                [name]: param
            }) as { [K in Name]: InputParamDef<T, false> },
            (sigScope) =>
                new TemplateBuilder(this.contextualScope, sigScope)
        );
    }

    addRequired<Name extends string & keyof any>(
        name: Name,
        type: any,
        description?: string
    ): TemplateBuilder<
        ContextualScope,
        ExtendScope<SingleScope, { [K in Name]: InputParamDef<any, true> }>
    > {
        const param: InputParamDef<any, true> = {
            type: type,
            description
        };

        return super.addToBothWithCtor(
            () => ({
                [name]: param
            }) as { [K in Name]: InputParamDef<any, true> },
            (sigScope) =>
                new TemplateBuilder(this.contextualScope, sigScope)
        );
    }

    // addSteps<
    //     NSS extends Scope,
    //     NFS extends Scope,
    //     STB extends SpecificTemplateBuilder<NSS, NFS>
    // >(name: string, fn: (tb: StepsTemplateBuilder<SingleScope, SingleScope>) => STB) {
    //     const templateScope = {
    //         ...this.sigScope
    //     };
    //     // start w/ the same public/full scope for a new TemplateBuilder
    //     const templateResult = fn(new StepsTemplateBuilder(templateScope, templateScope));
    //
    //     return super.addWithCtor(() => (
    //         {
    //             steps: {
    //                 ...((this.sigScope as any).templates || {}),
    //                 [name]: "add template ref so that outputs can be resolved!"
    //             }
    //         }),
    //         () => ({
    //             "templates": {
    //                 ...((this.fullScope as any).templates || {}),
    //                 [name]: templateResult.getFullScope()
    //             }
    //         }), (sigScope, fullScope) => new TemplateChainer(sigScope, fullScope));
    // }
}

export class SpecificTemplateBuilder<
    SigScope extends Scope,
    FullScope extends Scope> extends ScopeBuilder<SigScope, FullScope> {

}

export class StepsTemplateBuilder<
    SigScope extends Scope,
    FullScope extends Scope>
    extends SpecificTemplateBuilder<SigScope, FullScope> {
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
