import {
    InputParametersRecord,
    OutputParamDef,
    OutputParametersRecord,
    paramsToCallerSchema,
} from "@/schemas/parameterSchemas";
import {
    ExtendScope,
    TasksOutputsScope,
    WorkflowAndTemplatesScope,
    LoopWithUnion,
} from "@/schemas/workflowTypes";
import { z, ZodType } from "zod";
import { TemplateBodyBuilder } from "@/schemas/templateBodyBuilder";
import { PlainObject } from "@/schemas/plainObject";
import { BaseExpression, SimpleExpression } from "@/schemas/expression";
import {
    buildContainerWithOutputs,
    makeExternalTemplateRefCall,
    makeInternalTemplateCall,
} from "@/schemas/taskBuilder";
import { UniqueNameConstraintAtDeclaration, UniqueNameConstraintOutsideDeclaration } from "@/schemas/scopeConstraints";
import { Workflow } from "@/schemas/workflowBuilder";

/** --- Types kept lean; same shapes you used in Steps --- */
export type DagTask<
    IN extends InputParametersRecord = InputParametersRecord,
    OUT extends OutputParametersRecord = OutputParametersRecord,
    LoopT extends PlainObject = never
> = {
    name: string;
    dependencies?: string[];
    when?: BaseExpression<boolean>;
} & (
    | { templateRef: { name: string; template: string }; arguments?: { parameters?: Record<string, any> } }
    | { template: string; arguments?: { parameters?: Record<string, any> } }
) & (LoopT extends never ? {} : { loopWith: LoopWithUnion<LoopT> });

export type DagScopeToTasksWithOutputs<
    Scope extends TasksOutputsScope,
    LoopT extends PlainObject = never
> = ReturnType<typeof buildContainerWithOutputs<Scope, LoopT, "tasks">>;

export class DagBuilder<
    ContextualScope extends WorkflowAndTemplatesScope,
    InputParamsScope extends InputParametersRecord,
    DagScope extends TasksOutputsScope,
    OutputParamsScope extends OutputParametersRecord
> extends TemplateBodyBuilder<
    ContextualScope,
    "dag",
    InputParamsScope,
    DagScope,
    OutputParamsScope,
    DagBuilder<ContextualScope, InputParamsScope, DagScope, any>
> {
    constructor(
        readonly contextualScope: ContextualScope,
        readonly inputsScope: InputParamsScope,
        readonly bodyScope: DagScope,
        protected readonly tasks: DagTask[],
        readonly outputsScope: OutputParamsScope
    ) {
        super("dag", contextualScope, inputsScope, bodyScope, outputsScope);
    }

    addTask<
        Name extends string,
        TWorkflow extends Workflow<any, any, any>,
        TKey extends Extract<keyof TWorkflow["templates"], string>,
        LoopT extends PlainObject = never,
        Deps extends ReadonlyArray<Extract<keyof DagScope, string>> = []
    >(
        name: UniqueNameConstraintAtDeclaration<Name, DagScope>,
        workflowIn: UniqueNameConstraintOutsideDeclaration<Name, DagScope, TWorkflow>,
        key: UniqueNameConstraintOutsideDeclaration<Name, DagScope, TKey>,
        paramsFn: UniqueNameConstraintOutsideDeclaration<
            Name,
            DagScope,
            (tasks: DagScopeToTasksWithOutputs<DagScope, LoopT>) => z.infer<
                ReturnType<typeof paramsToCallerSchema<TWorkflow["templates"][TKey]["inputs"]>>
            >
        >,
        opts?: {
            dependsOn?: Deps;
            loopWith?: LoopWithUnion<LoopT>;
            when?: SimpleExpression<boolean>;
        }
    ): UniqueNameConstraintOutsideDeclaration<
        Name,
        DagScope,
        DagBuilder<
            ContextualScope,
            InputParamsScope,
            ExtendScope<DagScope, { [K in Name]: { name: Name; outputTypes: TWorkflow["templates"][TKey]["outputs"] } }>,
            OutputParamsScope
        >
    > {
        const workflow = workflowIn as TWorkflow;
        const templateKey = key as TKey;

        const templateCall = makeExternalTemplateRefCall(
            name as string,
            workflow,
            templateKey,
            (paramsFn as any)(this.buildTasksWithOutputs(opts?.loopWith))
        );

        return this.addTaskHelper(
            name as string,
            templateCall as DagTask<any, any, LoopT>,
            workflow.templates[templateKey].outputs,
            opts?.dependsOn as string[] | undefined,
            opts?.when
        ) as any;
    }

    addInternalTask<
        Name extends string,
        TKey extends Extract<keyof ContextualScope["templates"], string>,
        TTemplate extends ContextualScope["templates"][TKey],
        TInput extends TTemplate extends { input: infer I }
            ? I extends InputParametersRecord
                ? I
                : InputParametersRecord
            : InputParametersRecord,
        TOutput extends TTemplate extends { output: infer O }
            ? O extends OutputParametersRecord
                ? O
                : {}
            : {},
        LoopT extends PlainObject = never,
        Deps extends ReadonlyArray<Extract<keyof DagScope, string>> = []
    >(
        name: UniqueNameConstraintAtDeclaration<Name, DagScope>,
        templateKey: UniqueNameConstraintOutsideDeclaration<Name, DagScope, TKey>,
        paramsFn: UniqueNameConstraintOutsideDeclaration<
            Name,
            DagScope,
            (tasks: DagScopeToTasksWithOutputs<DagScope, LoopT>) => z.infer<
                ReturnType<typeof paramsToCallerSchema<TInput>>
            >
        >,
        opts?: {
            dependsOn?: Deps;
            loopWith?: LoopWithUnion<LoopT>;
            when?: SimpleExpression<boolean>;
        }
    ): UniqueNameConstraintOutsideDeclaration<
        Name,
        DagScope,
        DagBuilder<
            ContextualScope,
            InputParamsScope,
            ExtendScope<DagScope, { [K in Name]: { name: Name; outputTypes: TOutput } }>,
            OutputParamsScope
        >
    > {
        const templateSig = (this.contextualScope.templates as any)?.[templateKey as string];
        const outputs = (templateSig && "output" in templateSig ? templateSig.output : {}) as TOutput;

        const templateCall = makeInternalTemplateCall(
            name as string,
            templateKey as string,
            (paramsFn as any)(this.buildTasksWithOutputs(opts?.loopWith)),
            opts?.loopWith
        );

        return this.addTaskHelper(
            name as string,
            templateCall as DagTask<any, any, LoopT>,
            outputs,
            opts?.dependsOn as string[] | undefined,
            opts?.when
        ) as any;
    }

    private addTaskHelper<
        IN extends InputParametersRecord,
        OUT extends OutputParametersRecord,
        LoopT extends PlainObject = never
    >(
        name: string,
        templateCall: DagTask<IN, OUT, LoopT>,
        outputs: OUT,
        dependencies?: string[],
        when?: BaseExpression<boolean>
    ) {
        const task: DagTask<IN, OUT, LoopT> = {
            ...templateCall,
            ...(dependencies?.length ? { dependencies } : {}),
            ...(when !== undefined ? { when } : {}),
            name,
        };

        const newScope = {
            ...this.bodyScope,
            [name]: { name, outputTypes: outputs },
        } as ExtendScope<DagScope, { [K in typeof name]: { name: typeof name; outputTypes: OUT } }>;

        return new DagBuilder(
            this.contextualScope,
            this.inputsScope,
            newScope,
            [...this.tasks, task],
            this.outputsScope
        );
    }

    private buildTasksWithOutputs<LoopT extends PlainObject = never>(
        loopWith?: LoopWithUnion<LoopT>
    ): DagScopeToTasksWithOutputs<DagScope, LoopT> {
        return buildContainerWithOutputs<DagScope, LoopT, "tasks">(this.bodyScope, "tasks", loopWith);
    }

    addParameterOutput<T extends PlainObject, Name extends string>(
        name: Name,
        parameter: string,
        t: ZodType<T>,
        descriptionValue?: string
    ): DagBuilder<
        ContextualScope,
        InputParamsScope,
        DagScope,
        ExtendScope<OutputParamsScope, { [K in Name]: OutputParamDef<T> }>
    > {
        return new DagBuilder(this.contextualScope, this.inputsScope, this.bodyScope, this.tasks, {
            ...this.outputsScope,
            [name as string]: {
                type: t,
                fromWhere: "parameter" as const,
                parameter,
                description: descriptionValue,
            },
        });
    }

    addExpressionOutput<T extends PlainObject, Name extends string>(
        name: Name,
        expression: string,
        t: ZodType<T>,
        descriptionValue?: string
    ): DagBuilder<
        ContextualScope,
        InputParamsScope,
        DagScope,
        ExtendScope<OutputParamsScope, { [K in Name]: OutputParamDef<T> }>
    > {
        return new DagBuilder(this.contextualScope, this.inputsScope, this.bodyScope, this.tasks, {
            ...this.outputsScope,
            [name as string]: {
                type: t,
                fromWhere: "expression" as const,
                expression,
                description: descriptionValue,
            },
        });
    }

    getBody(): { body: { dag: { tasks: Record<string, any>[] } } } {
        return { body: { dag: { tasks: this.tasks } } };
    }
}
