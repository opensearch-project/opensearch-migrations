import {
    ExtendScope, IfNever,
    LoopWithUnion,
    ParamsWithLiteralsOrExpressions,
    TasksOutputsScope,
    TasksScopeToTasksWithOutputs, TasksWithOutputs, TemplateSignaturesScope,
    WorkflowAndTemplatesScope
} from "@/schemas/workflowTypes";
import {Workflow} from "@/schemas/workflowBuilder";
import {PlainObject} from "@/schemas/plainObject";
import {UniqueNameConstraintAtDeclaration, UniqueNameConstraintOutsideDeclaration} from "@/schemas/scopeConstraints";
import {z} from "zod/index";
import {InputParametersRecord, OutputParametersRecord, paramsToCallerSchema} from "@/schemas/parameterSchemas";
import {BaseExpression, SimpleExpression, stepOutput} from "@/schemas/expression";
import {TemplateDef} from "@/schemas/stepsBuilder";

export type WorkflowTask<
    IN extends InputParametersRecord,
    OUT extends OutputParametersRecord,
    LoopT extends PlainObject = never
> = {
    arguments?: { parameters?: Record<string, any>; }
} & ({
    templateRef: { key: string, value: TemplateDef<IN, OUT> };
} | {
    template: string;
})
    & IfNever<LoopT, {}, { withLoop: LoopT}> & {};

export type NamedTask<
    IN extends InputParametersRecord = InputParametersRecord,
    OUT extends OutputParametersRecord = OutputParametersRecord,
    LoopT extends PlainObject = never,
    Extra extends Record<string, any> = {}
> = { name: string } & WorkflowTask<IN, OUT, LoopT> & Extra;

export class TaskBuilder<
    ContextualScope extends WorkflowAndTemplatesScope,
    TasksScope extends TasksOutputsScope
> {
    constructor(protected readonly contextualScope: ContextualScope,
                protected readonly tasksScope: TasksScope,
                protected readonly tasks: NamedTask[]) {
    }

    addExternalTask<
        Name extends string,
        TWorkflow extends Workflow<any, any, any>,
        TKey extends Extract<keyof TWorkflow["templates"], string>,
        LoopT extends PlainObject = never
    >(
        name: UniqueNameConstraintAtDeclaration<Name, TasksScope>,
        workflowIn: UniqueNameConstraintOutsideDeclaration<Name, TasksScope, TWorkflow>,
        key: UniqueNameConstraintOutsideDeclaration<Name, TasksScope, TKey>,
        paramsFn: UniqueNameConstraintOutsideDeclaration<Name, TasksScope,
            (tasks: TasksScopeToTasksWithOutputs<TasksScope, LoopT>) =>
                ParamsWithLiteralsOrExpressions<
                    z.infer<ReturnType<typeof paramsToCallerSchema<TWorkflow["templates"][TKey]["inputs"]>>
                    >>
        >,
        loopWith?: LoopWithUnion<LoopT>,
        when?: SimpleExpression<boolean>
    ): UniqueNameConstraintOutsideDeclaration<Name, TasksScope,
        StepGroupBuilder<ContextualScope, ExtendScope<TasksScope, {
            [K in Name]: TasksWithOutputs<Name, TWorkflow["templates"][TKey]["outputs"]>
        }>>> {
        const workflow = workflowIn as TWorkflow;
        const templateKey = key as TKey;
        // Call callTemplate to get the template reference and arguments
        const templateCall = this.callExternalTemplate(
            name as string,
            workflow,
            templateKey,
            (paramsFn as any)(this.buildStepsWithOutputs(loopWith))
        );

        return this.addTaskHelper(name, templateCall, workflow.templates[templateKey].outputs, when);
    }

    addInternalTask<
        Name extends string,
        TKey extends Extract<keyof ContextualScope["templates"], string>,
        TTemplate extends ContextualScope["templates"][TKey],
        TInput extends TTemplate extends {
            input: infer I
        } ? I extends InputParametersRecord ? I : InputParametersRecord : InputParametersRecord,
        TOutput extends TTemplate extends { output: infer O } ? O extends OutputParametersRecord ? O : {} : {},
        LoopT extends PlainObject = never
    >(
        name: UniqueNameConstraintAtDeclaration<Name, TasksScope>,
        templateKey: UniqueNameConstraintOutsideDeclaration<Name, TasksScope, TKey>,
        paramsFn: UniqueNameConstraintOutsideDeclaration<Name, TasksScope,
            (tasks: TasksScopeToTasksWithOutputs<TasksScope, LoopT>) =>
                ParamsWithLiteralsOrExpressions<z.infer<ReturnType<typeof paramsToCallerSchema<TInput>>>>
        >,
        loopWith?: LoopWithUnion<LoopT>,
        when?: SimpleExpression<boolean>
    ): UniqueNameConstraintOutsideDeclaration<
        Name,
        TasksScope,
        StepGroupBuilder<
            ContextualScope,
            ExtendScope<
                TasksScope,
                { [K in Name]: TasksWithOutputs<Name, TOutput> }
            >
        >
    > {
        const template = this.contextualScope.templates?.[templateKey as string];
        const outputs = (template && 'output' in template ? template.output : {}) as TOutput;

        const templateCall = this.callTemplate(
            name,
            templateKey as string,
            (paramsFn as any)(this.buildStepsWithOutputs(loopWith)),
            loopWith
        );

        return this.addTaskHelper(name, templateCall, outputs, when) as any;
    }

    private addTaskHelper<
        TKey extends string,
        IN extends InputParametersRecord,
        OUT extends OutputParametersRecord,
        Task extends NamedTask<IN, OUT, LoopT>,
        LoopT extends PlainObject = never
    >(
        name: UniqueNameConstraintAtDeclaration<TKey, TasksScope>,
        templateCall: Task,
        outputs: OUT,
        when?: BaseExpression<boolean>
    ): UniqueNameConstraintOutsideDeclaration<TKey, TasksScope,
        StepGroupBuilder<ContextualScope, ExtendScope<TasksScope, {
            [K in TKey]: TasksWithOutputs<TKey, OUT>
        }>>> {
        const nameStr = name as string;

        // Add to runtime structure
        this.tasks.push({
            ...templateCall,
            ...(when !== undefined ? {"when": when} : {}),
        });

        // Create the new step definition with output types
        const taskDef: TasksWithOutputs<TKey, OUT> = {
            name: nameStr as TKey,
            // template: name,
            outputTypes: outputs
        };

        // Return new builder with extended scope
        return new StepGroupBuilder(
            this.contextualScope,
            {...this.tasksScope, [nameStr]: taskDef} as ExtendScope<TasksScope, {
                [K in TKey]: TasksWithOutputs<TKey, OUT>
            }>,
            this.tasks
        ) as any;
    }

    private buildTasksWithOutputs<LoopT extends PlainObject = never>(loopWith?: LoopWithUnion<LoopT>):
        TasksScopeToTasksWithOutputs<TasksScope> {
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
            "tasks": tasks,
            ...(loopWith ? {item: loopWith} : {})
        } as TasksScopeToTasksWithOutputs<TasksScope>;
    }

    getTasks() {
        return {scope: this.tasksScope, taskList: this.tasks};
    }

    callTemplate<
        TKey extends Extract<keyof TemplateSignaturesScope, string>,
        LoopT extends PlainObject = never,
    >(
        name: string,
        templateKey: TKey,
        params: z.infer<ReturnType<typeof paramsToCallerSchema<TemplateSignaturesScope[TKey]["input"]>>>,
        loopWith?: LoopWithUnion<LoopT>
    ): NamedTask<
        TemplateSignaturesScope[TKey]["input"],
        TemplateSignaturesScope[TKey]["output"] extends OutputParametersRecord ? TemplateSignaturesScope[TKey]["output"] : {}
    > {
        return {
            "name": name,
            template: templateKey,
            ...(loopWith ? {"loopWith": loopWith} : {}),
            arguments: {parameters: params}
        };
    }

    private callExternalTemplate<
        WF extends Workflow<any, any, any>,
        TKey extends Extract<keyof WF["templates"], string>
    >(
        name: string,
        wf: WF,
        templateKey: TKey,
        params: z.infer<ReturnType<typeof paramsToCallerSchema<WF["templates"][TKey]["inputs"]>>>
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