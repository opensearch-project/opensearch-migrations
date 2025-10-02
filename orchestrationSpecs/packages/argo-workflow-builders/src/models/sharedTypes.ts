/**
 * Shared types to break circular dependencies between modules.
 *
 * This file contains types that are needed by multiple modules to prevent
 * circular import dependencies that break TypeScript's type resolution.
 */
import {InputParametersRecord, OutputParametersRecord} from "@/argoWorkflowBuilders/models/parameterSchemas";
import {PlainObject} from "@/argoWorkflowBuilders/models/plainObject";
import {SimpleExpression, TemplateExpression} from "@/argoWorkflowBuilders/models/expression";
import {IfNever} from "@/argoWorkflowBuilders/models/workflowTypes";

export type TaskType = "tasks" | "steps";

export type StripUndefined<T> = Exclude<T, undefined>;

// Zero-runtime “type witness” object that carries a generic T.
export declare const __type_token__: unique symbol;
export type TypeToken<T> = {
    readonly [__type_token__]?: (x: T) => T; // phantom to make T invariant
};
export const typeToken = <T>(): TypeToken<T> => ({}) as TypeToken<T>;

export type WorkflowTask<
    IN extends InputParametersRecord,
    OUT extends OutputParametersRecord,
    LoopT extends PlainObject = never
> = {
    args?: { parameters?: Record<string, any> },
    when?: SimpleExpression<boolean> | { templateExp: TemplateExpression<boolean> }
} & (
    | { templateRef: { name: string; template: string } }
    | { template: string }
    ) & IfNever<LoopT, {}, { withLoop: LoopT }>;

export type NamedTask<
    IN extends InputParametersRecord = InputParametersRecord,
    OUT extends OutputParametersRecord = OutputParametersRecord,
    LoopT extends PlainObject = never,
    Extra extends Record<string, any> = {}
> = { name: string } & (
        | { template: string; templateRef?: never }
        | { template?: never; templateRef: { template: string, name: string } }
    ) &
    WorkflowTask<IN, OUT, LoopT> & Extra;