import {InputParametersRecord} from "@/schemas/parameterSchemas";
import {Scope, ExtendScope, TemplateSigEntry,} from "@/schemas/workflowTypes";
import {TypescriptError} from "@/utils";
import {UniqueNameConstraintAtDeclaration, UniqueNameConstraintOutsideDeclaration} from "@/schemas/scopeConstraints";
import {TemplateBuilder} from "@/schemas/templateBuilder";

export class WorkflowBuilder<
    MetadataScope extends Scope = Scope,
    WorkflowInputsScope extends Scope = Scope,
    TemplateSigScope extends Scope = Scope,
    TemplateFullScope extends Scope = Scope
> {
    constructor(
        protected readonly metadataScope: MetadataScope,
        protected readonly inputsScope: WorkflowInputsScope,
        protected readonly templateSigScope: TemplateSigScope,
        protected readonly templateFullScope: TemplateFullScope) {
    }

    static create(k8sResourceName: string) {
        return new WorkflowBuilder({ name: k8sResourceName }, {}, {}, {});
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
        const newInputs = { ...this.inputsScope, ...params } as ExtendScope<WorkflowInputsScope, P>;
        return new WorkflowBuilder(
            this.metadataScope,
            newInputs,
            this.templateSigScope,
            this.templateFullScope
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
            // update the next line to use the macro
            ExtendScope<TemplateSigScope, { [K in Name]: (Name extends keyof TemplateSigScope ? Exclude<TemplateSigEntry<FullTemplate>, Name> : TemplateSigEntry<FullTemplate>)}>,
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
                input: fullTemplate.inputs,
                output: (fullTemplate as any).outputs
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

    getFullScope() {
        return {
            metadata: this.metadataScope,
            workflowParameters: this.inputsScope,
            templates: this.templateFullScope
        };
    }
}
