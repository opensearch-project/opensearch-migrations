// taskBuilderBase.ts
import {
    ExtendScope,
    IfNever,
    LoopWithUnion,
    ParamsWithLiteralsOrExpressions,
    TasksOutputsScope,
    TasksScopeToTasksWithOutputs,
    TasksWithOutputs,
    TemplateSignaturesScope,
    WorkflowAndTemplatesScope
} from "@/schemas/workflowTypes";
import {Workflow} from "@/schemas/workflowBuilder";
import {PlainObject} from "@/schemas/plainObject";
import {UniqueNameConstraintAtDeclaration, UniqueNameConstraintOutsideDeclaration} from "@/schemas/scopeConstraints";
import {CallerParams, InputParametersRecord, OutputParametersRecord} from "@/schemas/parameterSchemas";
import {SimpleExpression, stepOutput} from "@/schemas/expression";
import {TemplateDef} from "@/schemas/stepsBuilder";

/** Phantom brand to carry the *current* Scope S inside the subclass type. */
export type WithScope<Self, S extends TasksOutputsScope> =
    Self;// & { readonly __scopeBrand?: S };

/** The factory knows how to make the same subclass `Self` but rebound to a new scope `NS`. */
export interface TaskBuilderFactory<
    C extends WorkflowAndTemplatesScope,
    Self /* concrete subclass, e.g. StepsTaskBuilder<C, *> */
> {
    create<NS extends TasksOutputsScope>(
        context: C,
        scope: NS,
        tasks: NamedTask[]
    ): WithScope<Self, NS>;
}

/** Your existing shared task types (unchanged) */
export type WorkflowTask<
    IN extends InputParametersRecord,
    OUT extends OutputParametersRecord,
    LoopT extends PlainObject = never
> = {
    arguments?: { parameters?: Record<string, any> },
    when?: SimpleExpression<boolean>
} & (
    | { templateRef: { key: string; value: TemplateDef<IN, OUT> } }
    | { template: string }
    ) & IfNever<LoopT, {}, { withLoop: LoopT }>
    & {};

export type NamedTask<
    IN extends InputParametersRecord = InputParametersRecord,
    OUT extends OutputParametersRecord = OutputParametersRecord,
    LoopT extends PlainObject = never,
    Extra extends Record<string, any> = {}
> = { name: string } & WorkflowTask<IN, OUT, LoopT> & Extra;

export type ParamsFromContextFn<S extends TasksOutputsScope,
    TWorkflow extends Workflow<any, any, any>,
    TKey extends Extract<keyof TWorkflow["templates"], string>,
    LoopT extends PlainObject = never
> = (tasks: TasksScopeToTasksWithOutputs<S, LoopT>) =>
    ParamsWithLiteralsOrExpressions<CallerParams<TWorkflow["templates"][TKey]["inputs"]>>;

/**
 * Base builder:
 *  - C: contextual scope
 *  - S: current tasks scope
 *  - Self: the *concrete* subclass type (CRTP)
 *  - Factory: a factory that can rebind `Self` to a new `S`
 */
export class TaskBuilder<
    C extends WorkflowAndTemplatesScope,
    S extends TasksOutputsScope,
    Self,
    Factory extends TaskBuilderFactory<C, Self>
> {
    constructor(
        protected readonly contextualScope: C,
        protected readonly tasksScope: S,
        protected readonly orderedTaskList: NamedTask[],
        protected readonly factory: Factory
    ) {}

    /** Hook for subclasses to tweak how a task is recorded (e.g., DAG adds `when`). */
    protected onTaskPushed(task: NamedTask, when?: SimpleExpression<boolean>): NamedTask {
        return when !== undefined ? {...task, "when": when} : task;
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
        loopWith?: LoopWithUnion<LoopT>,
        when?: SimpleExpression<boolean>
    ): UniqueNameConstraintOutsideDeclaration<
        Name,
        S,
        WithScope<Self, ExtendScope<S, { [K in Name]: TasksWithOutputs<Name, TWorkflow["templates"][TKey]["outputs"]> }>>
    > {
        const workflow = workflowIn as TWorkflow;
        const templateKey = key as TKey;
        const templateCall = this.callExternalTemplate(
            name as string,
            workflow,
            templateKey,
            (paramsFn as any)(this.buildTasksWithOutputs(loopWith))
        );
        return this.addTaskHelper(
            name,
            templateCall,
            workflow.templates[templateKey].outputs,
            when
        ) as any;
    }

    addInternalTask<
        Name extends string,
        TKey extends Extract<keyof C["templates"], string>,
        TTemplate extends C["templates"][TKey],
        TInput extends TTemplate extends { input: infer I }
            ? I extends InputParametersRecord ? I : InputParametersRecord
            : InputParametersRecord,
        TOutput extends TTemplate extends { output: infer O }
            ? O extends OutputParametersRecord ? O : {}
            : {},
        LoopT extends PlainObject = never
    >(
        name: UniqueNameConstraintAtDeclaration<Name, S>,
        templateKey: UniqueNameConstraintOutsideDeclaration<Name, S, TKey>,
        paramsFn: UniqueNameConstraintOutsideDeclaration<Name, S,
            (tasks: TasksScopeToTasksWithOutputs<S, LoopT>) =>
                ParamsWithLiteralsOrExpressions<CallerParams<TInput>>
        >,
        loopWith?: LoopWithUnion<LoopT>,
        when?: SimpleExpression<boolean>
    ): UniqueNameConstraintOutsideDeclaration<
        Name,
        S,
        WithScope<Self, ExtendScope<S, { [K in Name]: TasksWithOutputs<Name, TOutput> }>>
    > {
        const template = this.contextualScope.templates?.[templateKey as string];
        const outputs = (template && "output" in template ? template.output : {}) as TOutput;

        const templateCall = this.callTemplate(
            name,
            templateKey as string,
            (paramsFn as any)(this.buildTasksWithOutputs(loopWith)),
            loopWith
        );

        return this.addTaskHelper(name, templateCall, outputs, when) as any;
    }

    /** Core helper that extends scope and returns a *rebound* Self typed with the new scope. */
    protected addTaskHelper<
        TKey extends string,
        IN extends InputParametersRecord,
        OUT extends OutputParametersRecord,
        Task extends NamedTask<IN, OUT, LoopT>,
        LoopT extends PlainObject = never
    >(
        name: UniqueNameConstraintAtDeclaration<TKey, S>,
        templateCall: Task,
        outputs: OUT,
        when?: SimpleExpression<boolean>
    ): WithScope<Self, ExtendScope<S, { [K in TKey]: TasksWithOutputs<TKey, OUT> }>> {
        const nameStr = name as string;

        // runtime tasks list
        this.orderedTaskList.push(this.onTaskPushed(templateCall, when));

        // type-level output description
        const taskDef: TasksWithOutputs<TKey, OUT> = {
            name: nameStr as TKey,
            outputTypes: outputs
        };

        const nextScope = {
            ...this.tasksScope,
            [nameStr]: taskDef
        } as ExtendScope<S, { [K in TKey]: TasksWithOutputs<TKey, OUT> }>;

        // factory returns the SAME subclass type, rebound to nextScope
        return this.factory.create(this.contextualScope, nextScope, this.orderedTaskList);
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
            ...(loopWith ? {item: loopWith} : {})
        } as TasksScopeToTasksWithOutputs<S>;
    }

    public getTasks() {
        return {scope: this.tasksScope, taskList: this.orderedTaskList};
    }

    protected callTemplate<
        TKey extends Extract<keyof TemplateSignaturesScope, string>,
        LoopT extends PlainObject = never
    >(
        name: string,
        templateKey: TKey,
        params: CallerParams<TemplateSignaturesScope[TKey]["input"]>,
        loopWith?: LoopWithUnion<LoopT>
    ): NamedTask<
        TemplateSignaturesScope[TKey]["input"],
        TemplateSignaturesScope[TKey]["output"] extends OutputParametersRecord ? TemplateSignaturesScope[TKey]["output"] : {}
    > {
        return {
            name,
            template: templateKey,
            ...(loopWith ? {loopWith} : {}),
            arguments: {parameters: params}
        };
    }

    protected callExternalTemplate<
        WF extends Workflow<any, any, any>,
        TKey extends Extract<keyof WF["templates"], string>
    >(
        name: string,
        wf: WF,
        templateKey: TKey,
        params: CallerParams<WF["templates"][TKey]["inputs"]>
    ): NamedTask<
        WF["templates"][TKey]["inputs"],
        WF["templates"][TKey]["outputs"] extends OutputParametersRecord ? WF["templates"][TKey]["outputs"] : {}
    > {
        return {
            "name": name,
            templateRef: {
                name: templateKey,
                template: wf.metadata.k8sMetadata.name
            },
            arguments: {parameters: params}
        } as any;
    }
}
