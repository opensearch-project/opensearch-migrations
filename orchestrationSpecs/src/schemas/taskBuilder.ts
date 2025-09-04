// taskBuilderBase.ts
import {
    ExtendScope, IfNever, LoopWithUnion, ParamsWithLiteralsOrExpressions,
    TasksOutputsScope, TasksScopeToTasksWithOutputs, TasksWithOutputs,
    TemplateSignaturesScope, WorkflowAndTemplatesScope
} from "@/schemas/workflowTypes";
import { Workflow } from "@/schemas/workflowBuilder";
import { PlainObject } from "@/schemas/plainObject";
import { UniqueNameConstraintAtDeclaration, UniqueNameConstraintOutsideDeclaration } from "@/schemas/scopeConstraints";
import { CallerParams, InputParametersRecord, OutputParametersRecord } from "@/schemas/parameterSchemas";
import { SimpleExpression, stepOutput } from "@/schemas/expression";

export type TaskOpts<LoopT extends PlainObject> = {
    loopWith?: LoopWithUnion<LoopT>,
    when?: SimpleExpression<boolean>
}

export type InputsOf<T> =
    T extends { inputs: infer I }
        ? I extends Record<string, any> ? I : never  // Keep it as Record<string, any> to preserve exact keys
        : never;

export type OutputsOf<T> =
    T extends { outputs: infer O }
        ? O extends OutputParametersRecord ? O : {}
        : {};

export type WorkflowTask<
    IN extends InputParametersRecord,
    OUT extends OutputParametersRecord,
    LoopT extends PlainObject = never
> = {
    args?: { parameters?: Record<string, any> },
    when?: SimpleExpression<boolean>
} & (
    | { templateRef: { name: string; template: string } }
    | { template: string }
    ) & IfNever<LoopT, {}, { withLoop: LoopT }>;

export type NamedTask<
    IN extends InputParametersRecord = InputParametersRecord,
    OUT extends OutputParametersRecord = OutputParametersRecord,
    LoopT extends PlainObject = never,
    Extra extends Record<string, any> = {}
> = { name: string } & WorkflowTask<IN, OUT, LoopT> & Extra;

export type ParamsFromContextFn<
    S extends TasksOutputsScope,
    TWorkflow extends Workflow<any, any, any>,
    TKey extends Extract<keyof TWorkflow["templates"], string>,
    LoopT extends PlainObject = never
> = (tasks: TasksScopeToTasksWithOutputs<S, LoopT>) =>
    ParamsWithLiteralsOrExpressions<CallerParams<TWorkflow["templates"][TKey]["inputs"]>>;

/** ---- NEW: type-level “rebinder” (a type constructor at the value level) ---- */
export type TaskRebinder<C extends WorkflowAndTemplatesScope> =
    <NS extends TasksOutputsScope>(context: C, scope: NS, tasks: NamedTask[]) => any;

/** Helper to “apply” the rebinder and get its precise return type */
export type ApplyRebinder<
    RB,
    C extends WorkflowAndTemplatesScope,
    NS extends TasksOutputsScope
> = RB extends (context: C, scope: NS, tasks: NamedTask[]) => infer R ? R : never;

/**
 * Base TaskBuilder THAT CAN REBIND ITS RETURN TYPE:
 * - C: contextual scope
 * - S: current tasks scope
 * - RB: a function type that, given a NEW scope NS, returns the concrete “next builder” type
 *
 * IMPORTANT: every method that “extends the scope” now returns ApplyRebinder<RB, C, NewScope>.
 */
export class TaskBuilder<
    C extends WorkflowAndTemplatesScope,
    S extends TasksOutputsScope,
    RB extends TaskRebinder<C>
> {
    constructor(
        protected readonly contextualScope: C,
        protected readonly tasksScope: S,
        protected readonly orderedTaskList: NamedTask[],
        /** the rebinder function that constructs the next instance (subclass, wrapper, etc.) */
        private readonly rebind: RB
    ) {}

    /** Hook for subclasses/wrappers to customize task creation (e.g. DAG adds `when`). */
    protected onTaskPushed(task: NamedTask, when?: SimpleExpression<boolean>): NamedTask {
        return when !== undefined ? { ...task, when } : task;
    }

    addExternalTask<
        Name extends string,
        TWorkflow extends Workflow<any, any, any>,
        TKey extends Extract<keyof TWorkflow["templates"], string>,
        LoopT extends PlainObject = never
    >(
        name: UniqueNameConstraintAtDeclaration<Name, S>,
        workflowIn: UniqueNameConstraintOutsideDeclaration<Name, S, TWorkflow>,
        key: UniqueNameConstraintOutsideDeclaration<Name, S, TKey>,
        paramsFn: UniqueNameConstraintOutsideDeclaration<Name, S, ParamsFromContextFn<S, TWorkflow, TKey, LoopT>>,
        opts?: TaskOpts<LoopT>
    ): ApplyRebinder<RB, C,
        ExtendScope<S, { [K in Name]: TasksWithOutputs<Name, TWorkflow["templates"][TKey]["outputs"]> }>
    > {
        const workflow = workflowIn as TWorkflow;
        const templateKey = key as TKey;
        const templateCall = this.callExternalTemplate(
            name as string,
            workflow,
            templateKey,
            (paramsFn as any)(this.buildTasksWithOutputs(opts?.loopWith))
        );

        return this.addTaskHelper(name, templateCall, workflow.templates[templateKey].outputs, opts?.when);
    }

    addInternalTask<
        Name extends string,
        TKey extends Extract<keyof C["templates"], string>,
        TTemplate extends C["templates"][TKey],
        LoopT extends PlainObject = never
    >(
        name: UniqueNameConstraintAtDeclaration<Name, S>,
        templateKey: UniqueNameConstraintOutsideDeclaration<Name, S, TKey>,
        paramsFn: UniqueNameConstraintOutsideDeclaration<Name, S,
            (tasks: TasksScopeToTasksWithOutputs<S, LoopT>) =>
                ParamsWithLiteralsOrExpressions<CallerParams<InputsOf<TTemplate>>>
        >,
        opts?: TaskOpts<LoopT>
    ): ApplyRebinder<RB, C,
        ExtendScope<S, { [K in Name]: TasksWithOutputs<Name, OutputsOf<TTemplate>> }>
    > {
        const template = this.contextualScope.templates?.[templateKey as string];
        const outputs = (template && "outputs" in template ? template.outputs : {}) as OutputsOf<TTemplate>;

        const templateCall = this.callTemplate(
            name as string,
            templateKey as string,
            (paramsFn as any)(this.buildTasksWithOutputs(opts?.loopWith)),
            opts?.loopWith
        );

        return this.addTaskHelper(name, templateCall, outputs, opts?.when);
    }

    /** Core helper extends scope and returns the REBOUND type for the new scope. */
    protected addTaskHelper<
        TKey extends string,
        IN extends InputParametersRecord,
        OUT extends OutputParametersRecord,
        LoopT extends PlainObject = never
    >(
        name: UniqueNameConstraintAtDeclaration<TKey, S>,
        templateCall: NamedTask<IN, OUT, LoopT>,
        outputs: OUT,
        when?: SimpleExpression<boolean>
    ): ApplyRebinder<RB, C, ExtendScope<S, { [K in TKey]: TasksWithOutputs<TKey, OUT> }>> {
        const nameStr = name as string;

        this.orderedTaskList.push(this.onTaskPushed(templateCall, when));

        const taskDef: TasksWithOutputs<TKey, OUT> = {
            name: nameStr as TKey,
            outputTypes: outputs
        };

        const nextScope = {
            ...this.tasksScope,
            [nameStr]: taskDef
        } as ExtendScope<S, { [K in TKey]: TasksWithOutputs<TKey, OUT> }>;

        // Use the rebinder to produce the precise next instance
        return this.rebind(this.contextualScope, nextScope, this.orderedTaskList) as any;
    }

    protected buildTasksWithOutputs<LoopT extends PlainObject = never>(
        loopWith?: LoopWithUnion<LoopT>
    ): TasksScopeToTasksWithOutputs<S> {
        const tasks: any = {};
        Object.entries(this.tasksScope).forEach(([taskName, taskDef]: [string, any]) => {
            if (taskDef.outputTypes) {
                tasks[taskName] = {};
                Object.entries(taskDef.outputTypes).forEach(([outputName, outputParamDef]: [string, any]) => {
                    tasks[taskName][outputName] = stepOutput(taskName, outputName, outputParamDef);
                });
            }
        });
        return {
            tasks,
            ...(loopWith ? { item: loopWith } : {})
        } as TasksScopeToTasksWithOutputs<S>;
    }

    public getTasks() {
        return { scope: this.tasksScope, taskList: this.orderedTaskList };
    }

    protected callTemplate<
        TKey extends Extract<keyof TemplateSignaturesScope, string>,
        LoopT extends PlainObject = never
    >(
        name: string,
        templateKey: TKey,
        params: CallerParams<TemplateSignaturesScope[TKey]["inputs"]>,
        loopWith?: LoopWithUnion<LoopT>
    ): NamedTask<
        TemplateSignaturesScope[TKey]["inputs"],
        TemplateSignaturesScope[TKey]["outputs"] extends OutputParametersRecord ? TemplateSignaturesScope[TKey]["outputs"] : {}
    > {
        return {
            name,
            template: templateKey,
            ...(loopWith ? { withLoop: loopWith } : {}),
            args: params
        };
    }

    protected callExternalTemplate<
        WF extends Workflow<any, any, any>,
        TKey extends Extract<keyof WF["templates"], string>,
        LoopT extends PlainObject = never
    >(
        name: string,
        wf: WF,
        templateKey: TKey,
        params: CallerParams<WF["templates"][TKey]["inputs"]>,
        loopWith?: LoopWithUnion<LoopT>
    ): NamedTask<
        WF["templates"][TKey]["inputs"],
        WF["templates"][TKey]["outputs"] extends OutputParametersRecord ? WF["templates"][TKey]["outputs"] : {}
    > {
        return {
            name,
            templateRef: { name: templateKey, template: wf.metadata.k8sMetadata.name },
            ...(loopWith ? { withLoop: loopWith } : {}),
            args: params
        } as any;
    }
}
