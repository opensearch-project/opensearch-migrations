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

    protected addWithCtor<
        AddSig extends Scope,
        AddFull extends Scope,
        B extends ScopeBuilder<ExtendScope<SigScope, AddSig>, ExtendScope<FullScope, AddFull>>
    >(
        sigFn: ScopeFn<SigScope, AddSig>,
        fullFn: ScopeFn<FullScope, AddFull>,
        builderCtor: (sigScope: Readonly<ExtendScope<SigScope, AddSig>>, fullScope: Readonly<ExtendScope<FullScope, AddFull>>) => B
    ): B {
        const newSigScope = { ...this.sigScope, ...sigFn(this.sigScope) } as ExtendScope<SigScope, AddSig>;
        const newFullScope = { ...this.fullScope, ...fullFn(this.fullScope) } as ExtendScope<FullScope, AddFull>;
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

    protected addToBothWithCtor<
        Add extends Scope,
        B extends ScopeBuilder<ExtendScope<SingleScope, Add>, ExtendScope<SingleScope, Add>>
    >(
        fn: ScopeFn<SingleScope, Add>,
        builderCtor: (scope: ExtendScope<SingleScope, Add>) => B
    ): B {
        const newScope = { ...this.sigScope, ...fn(this.sigScope) } as ExtendScope<SingleScope, Add>;
        return builderCtor(newScope);
    }
}

/**
 * Entry-point to build a top-level K8s resource.  Allows the addition of workflow parameters
 * and then transitions to the repeated addition of templates with the TemplateChainer
 */
export class WFBuilder<S extends Scope = Scope> extends UnifiedScopeBuilder<S> {
    static create(k8sResourceName: string) {
        return new WFBuilder({name: k8sResourceName});
    }

    addParams<P extends InputParametersRecord>(params: P) {
        const wfp = ({"workflowParams": params});
        return super.addToBothWithCtor(
            () => wfp,
            ss => new TemplateChainer(ss,ss)
        );
    }
}

/**
 * Builds the top-level workflow one template at a time, via a TemplateBuilder instance. This implementation tracks two different scopes - a public one that is pushed down to all new Templates
 * to be constructed that includes previous template's inputs/outputs + workflow parameters as well as the
 * entire scope.  The first scope is passed to the TemplateBuilder so that it can be used to compile check
 * all the templates, asserting that they only use exposed variables.  The second scope is used to generate
 * the final resource.
 */
export class TemplateChainer<SigScope extends Scope = Scope, FullScope extends Scope = Scope>
    extends ScopeBuilder<SigScope, FullScope>
{
    addTemplate<
        NSS extends Scope,
        NFS extends Scope,
        STB extends SpecificTemplateBuilder<NSS, NFS>
    >(name: string, fn: (tb: TemplateBuilder<SigScope>) => STB) {
        // Create a template-scoped builder that exposes workflowParams and inputs separately
        const templateScope = {
            ...this.sigScope,
            inputs: {} // Start with empty inputs for the template
        };
        // start w/ the same public/full scope for a new TemplateBuilder
        const templateResult = fn(new TemplateBuilder(templateScope));

        return super.addWithCtor(() => ({
                "templates": {
                    ...((this.sigScope as any).templates || {}),
                    [name]: {
                        input: (templateResult.getSigScope().inputs || {})
                    }
                }
            }),
            () => ({
                "templates": {
                    ...((this.fullScope as any).templates || {}),
                    [name]: templateResult.getFullScope()
                }
            }), (sigScope, fullScope) => new TemplateChainer(sigScope, fullScope));
    }


}

/**
 * Maintains a scope of all previous public parameters (workflow and previous templates' inputs/outputs)
 * as well as newly created inputs/outputs for this template.  To define a specific template, use one of
 * the builder methods, which, like `addTemplate` will return a new builder for that type of template
 * receives the specification up to that point.
 */
export class TemplateBuilder<SingleScope extends Scope = Scope> extends UnifiedScopeBuilder<SingleScope> {
    addOptional<T>(name: string,
        defaultValueFromScopeFn: (scope: SingleScope) => T,
        description?: string
    ) {
        const param = defineParam({
            defaultValue: defaultValueFromScopeFn(this.scope),
            description: description
        });

        return super.addToBothWithCtor(() => ({
            inputs: {
                ...((this.sigScope as any).inputs || {}),
                [name]: param
            }
        }), (sigScope) => new TemplateBuilder(sigScope));
    }

    addRequired(name: string, type: any, description?: string) {
        const param: InputParamDef<any, true> = {
            type: type as any,
            description: description,
        };
        return super.addToBothWithCtor(() => ({
            inputs: {
                ...((this.sigScope as any).inputs || {}),
                [name]: param
            }
        }), (sigScope) => new TemplateBuilder(sigScope));
    }

    // addSteps<
    //     NSS extends Scope,
    //     NFS extends Scope
    // >(fn: (tb: StepsTemplateBuilder<SingleScope, SingleScope>) => StepsTemplateBuilder<NSS, NFS>) {
    //     const templateScope = {
    //         ...this.sigScope
    //     };
    //     // start w/ the same public/full scope for a new TemplateBuilder
    //     const templateResult = fn(new StepsTemplateBuilder(templateScope, templateScope));
    //
    //     return super.addWithCtor(() => (
    //         // TODO - look this over = just copied from above
    //         {
    //             "templates": {
    //                 ...((this.sigScope as any).templates || {}),
    //                 [name]: {
    //                     input: (templateResult.getSigScope().inputs || {})
    //                 }
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
