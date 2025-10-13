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

import {InputParametersRecord} from "./parameterSchemas";
import {
    ExtendScope,
    GenericScope,
    LowercaseOnly,
    TemplateSigEntry,
    TemplateSignaturesScopeTyped
} from "./workflowTypes";
import {TypescriptError} from "../utils";
import {UniqueNameConstraintAtDeclaration, UniqueNameConstraintOutsideDeclaration} from "./scopeConstraints";
import {TemplateBuilder} from "./templateBuilder";
import {PlainObject} from "./plainObject";

type MetadataScopeBase = {
    k8sMetadata: { name: string } & GenericScope,
    entrypoint?: string,
    serviceAccountName?: string,
    parallelism?: number
};

type SuspendTemplateBodyT = { body: { suspend: {} }, inputs: [], outputs?: [] };

export class WorkflowBuilder<
    MetadataScope extends MetadataScopeBase,
    WorkflowInputsScope extends InputParametersRecord,
    TemplateSigScope extends TemplateSignaturesScopeTyped<any>,
    TemplateFullScope extends GenericScope = GenericScope
> {
    constructor(
        public readonly metadataScope: MetadataScope,
        public readonly inputsScope: WorkflowInputsScope,
        public readonly templateSigScope: TemplateSigScope,
        public readonly templateFullScope: TemplateFullScope) {
    }

    /**
     * I'm a bit torn about putting K8s specific data into this API.
     * Generally, it would be nice to be as Argo-independent as possible in this modeling layer.
     * Striving for models that abstract away K8s isn't going to be possible though since
     * many of the things being orchestrated are K8s resource (replicasets, services, etc).
     * @param opts
     */
    static create<K extends string>(opts: {
        k8sResourceName: LowercaseOnly<K>,
        k8sMetadata?: Record<string, PlainObject>,
        serviceAccountName?: string,
        parallelism?: number
    }) {
        return new WorkflowBuilder({
            k8sMetadata: {
                ...(opts.k8sMetadata ?? {}),
                name: opts.k8sResourceName
            },
            serviceAccountName: opts.serviceAccountName,
            parallelism: opts.parallelism
        }, {}, {}, {});
    }

    setEntrypoint<Name extends Extract<keyof TemplateSigScope, string>>(
        name: Name
    ) {
        return new WorkflowBuilder(
            {...this.metadataScope, entrypoint: name},
            this.inputsScope,
            this.templateSigScope,
            this.templateFullScope
        );
    }

    addParams<P extends InputParametersRecord>(
        params: P
    ): keyof WorkflowInputsScope & keyof P extends never
        ? WorkflowBuilder<
            MetadataScope,
            ExtendScope<WorkflowInputsScope, P>,
            TemplateSigScope,
            TemplateFullScope
        >
        : TypescriptError<`Parameter name '${keyof WorkflowInputsScope & keyof P & string}' already exists in workflow inputs`> {
        const newInputs = {...this.inputsScope, ...params} as ExtendScope<WorkflowInputsScope, P>;
        return new WorkflowBuilder(
            this.metadataScope,
            newInputs,
            this.templateSigScope,
            this.templateFullScope
        ) as any;
    }

    addSuspendTemplate<
        Name extends string
    >(
        name: Name
    ): UniqueNameConstraintOutsideDeclaration<Name, TemplateSigScope,
        WorkflowBuilder<
            MetadataScope,
            WorkflowInputsScope,
            ExtendScope<TemplateSigScope, { [K in Name]: (Name extends keyof TemplateSigScope ? Exclude<TemplateSigEntry<SuspendTemplateBodyT>, Name> : TemplateSigEntry<SuspendTemplateBodyT>) }>,
            ExtendScope<TemplateFullScope, { [K in Name]: SuspendTemplateBodyT }>
        >
    > {
        const newSig = {[name as string]: {}} as { [K in Name]: TemplateSigEntry<SuspendTemplateBodyT> };
        const newTemplate = {body: {suspend: {}}};

        const newFull = {[name as string]: newTemplate} as { [K in Name]: SuspendTemplateBodyT };

        return new WorkflowBuilder(
            this.metadataScope,
            this.inputsScope,
            {...this.templateSigScope, ...newSig},
            {...this.templateFullScope, ...newFull}
        ) as any;
    }

    addTemplate<
        Name extends string,
        TB extends { getFullTemplateScope(): any },
        FullTemplate extends ReturnType<TB["getFullTemplateScope"]>
    >(
        name: UniqueNameConstraintAtDeclaration<Name, TemplateSigScope>,
        builderFn: UniqueNameConstraintOutsideDeclaration<Name, TemplateSigScope, (tb: TemplateBuilder<{
            workflowParameters: WorkflowInputsScope;
            templates: TemplateSigScope;
        }, {}, {}, {}>) => TB>
    ): UniqueNameConstraintOutsideDeclaration<Name, TemplateSigScope,
        WorkflowBuilder<
            MetadataScope,
            WorkflowInputsScope,
            ExtendScope<TemplateSigScope, { [K in Name]: (Name extends keyof TemplateSigScope ? Exclude<TemplateSigEntry<FullTemplate>, Name> : TemplateSigEntry<FullTemplate>) }>,
            ExtendScope<TemplateFullScope, { [K in Name]: FullTemplate }>
        >
    > {
        const templateScope = {
            workflowParameters: this.inputsScope,
            templates: this.templateSigScope
        };

        // workaround type warning/breakage that I'm creating in the signature w/ `as any`
        const fn = builderFn as (tb: TemplateBuilder<{
            workflowParameters: WorkflowInputsScope;
            templates: TemplateSigScope;
        }, {}>) => TB;
        const templateBuilder = fn(new TemplateBuilder(templateScope, {}, {}, {}) as any);
        const fullTemplate = templateBuilder.getFullTemplateScope();

        const newSig = {
            [name as string]: {
                inputs: fullTemplate.inputs,
                outputs: (fullTemplate as any).outputs
            }
        } as { [K in Name]: TemplateSigEntry<FullTemplate> };

        const newFull = {[name as string]: fullTemplate} as { [K in Name]: FullTemplate };

        return new WorkflowBuilder(
            this.metadataScope,
            this.inputsScope,
            {...this.templateSigScope, ...newSig},
            {...this.templateFullScope, ...newFull}
        ) as any;
    }

    getFullScope(): Workflow<MetadataScope, WorkflowInputsScope, TemplateFullScope> {
        return {
            metadata: {name: "", ...this.metadataScope},//this.metadataScope.metadata,
            templates: this.templateFullScope,
            workflowParameters: this.inputsScope
        };
    }
}

export type Workflow<
    MetadataScope extends MetadataScopeBase,
    WorkflowInputsScope extends InputParametersRecord,
    TemplateScope extends Record<string, { inputs: any; outputs?: any }>
> = {
    metadata: MetadataScopeBase,
    entrypoint?: string,
    workflowParameters: WorkflowInputsScope,
    templates: TemplateScope
}
