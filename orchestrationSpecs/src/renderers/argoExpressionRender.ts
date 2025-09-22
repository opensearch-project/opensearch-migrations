// Type-safe visitor pattern for conversion
import expression, {
    ArithmeticExpression,
    ArrayIndexExpression,
    ArrayMakeExpression,
    AsStringExpression,
    BaseExpression,
    ComparisonExpression,
    ConcatExpression,
    FromParameterExpression,
    LiteralExpression,
    WorkflowValueExpression,
    RecordFieldSelectExpression,
    TernaryExpression,
    TemplateReplacementExpression, TaskDataExpression, DictMakeExpression,
    FunctionExpression,
} from "@/schemas/expression";

/** Lightweight erased type to avoid deep generic instantiation */
type AnyExpr = BaseExpression<any, any>;

export type ArgoFormatted = {
    text: string;
    /** True if the final expression used any compound operation (non-trivial operator/wrapper). */
    compound: boolean;
};

const formattedResult = (text: string, compound = false): ArgoFormatted => ({ text, compound });

export function toArgoExpression(expr: AnyExpr, useMarkers=true): string {
    const rval = formatExpression(expr, true);
    return (useMarkers && !(isLiteralExpression(expr)))
        ? "{{" + (rval.compound ? "=" : "") + rval.text + "}}" : rval.text;
}

/** Returns the Argo-formatted string plus whether the expression was compound. */
function formatExpression(expr: AnyExpr, top=false): ArgoFormatted {
    if (isAsStringExpression(expr)) {
        return formatExpression(expr.source, true);
    }

    if (isLiteralExpression(expr)) {
        const le = expr as LiteralExpression<any>;
        if (typeof le.value === "string") {
            return top ? formattedResult(le.value) : formattedResult(`"${le.value}"`);
        } else if (typeof le.value === "number" || typeof le.value === "boolean") {
            return formattedResult(String(le.value));
        } else if (le.value === null) {
            return formattedResult("null");
        } else {
            return formattedResult(JSON.stringify(le.value));
        }
    }

    if (isConcatExpression(expr)) {
        const ce = expr as ConcatExpression<BaseExpression<string, any>[]>;
        const parts = ce.expressions.map(e=>formatExpression(e));
        const text = parts.map(p => p.text).join(ce.separator ? " + " + ce.separator + " + " : "+");
        const compound = (ce.expressions.length > 1) || !!ce.separator || parts.some(p => p.compound);
        return formattedResult(text, compound);
    }

    if (isTernaryExpression(expr)) {
        const te = expr as TernaryExpression<any, any, any, any>;
        const c = formatExpression(te.condition);
        const t = formatExpression(te.whenTrue);
        const f = formatExpression(te.whenFalse);
        return formattedResult(`((${c.text}) ? (${t.text}) : (${f.text}))`, true);
    }

    if (isFunction(expr)) {
        const e = expr as FunctionExpression<any, any>;
        if (e.functionName === "toJSON" && isParameterExpression(e.args[0] as AnyExpr)) {
            return formatExpression(e.args[0]);
        } else if (e.functionName === "fromJSON" && top) {
            return formatExpression(e.args[0]);
        }
        const formattedArgs = e.args.map(a=>formatExpression(a));
        const combinedFormatted = formattedArgs.map(f=>f.text).join(", ");
        const compound = formattedArgs.map(f=>f.compound).some(v=>v);
        return formattedResult(`${e.functionName}(${combinedFormatted})`, compound);
    }

    if (isComparisonExpression(expr)) {
        const ce = expr as ComparisonExpression<any, any, any>;
        const l = formatExpression(ce.left);
        const r = formatExpression(ce.right);
        return formattedResult(`${l.text} ${ce.operator} ${r.text}`, true);
    }

    if (isArithmeticExpression(expr)) {
        const ae = expr as ArithmeticExpression<any, any>;
        const l = formatExpression(ae.left);
        const r = formatExpression(ae.right);
        return formattedResult(`${l.text} ${ae.operator} ${r.text}`, true);
    }

    if (isPathExpression(expr)) {
        const pe = expr as RecordFieldSelectExpression<any, any, any>;
        const inner = formatExpression(pe.source);
        const source = inner.text;
        const jsonPath = pe.path.replace(/\[(\d+)\]/g, "[$1]").replace(/^/, "$.");
        return formattedResult(`jsonpath(${source}, '${jsonPath}')`, true);
    }

    if (isArrayIndexExpression(expr)) {
        const ie = expr as ArrayIndexExpression<any, any, any>;
        const arr = formatExpression(ie.array);
        const idx = formatExpression(ie.index);
        return formattedResult(`${arr.text}[${idx.text}]`, true);
    }

    if (isArrayMakeExpression(expr)) {
        const ae = expr as ArrayMakeExpression<any>;
        const inner = ae.elements.map((e: AnyExpr)=>formatExpression(e).text).join(", ");
        return formattedResult(`[${inner}]`, true);
    }

    // ---- NEW: Dict make ----------------------------------------------------
    if (isDictMakeExpression(expr)) {
        const de = expr as DictMakeExpression<Record<string, AnyExpr>>;
        const parts: string[] = [];
        for (const [k, v] of Object.entries(de.entries)) {
            const fv = formatExpression(v as any);
            parts.push(`"${k}"`, fv.text);
        }
        const args = parts.join(", ");
        // Using function-call form for consistency with other sprig.* calls here:
        // sprig.dict("k1", v1, "k2", v2)
        const text = parts.length ? `sprig.dict(${args})` : `sprig.dict()`;
        return formattedResult(text, true);
    }

    if (isParameterExpression(expr)) {
        const pe = expr as FromParameterExpression<any,any>;
        switch (pe.source.kind) {
            case "workflow":
                return formattedResult(`workflow.parameters.${pe.source.parameterName}`);
            case "input":
                return formattedResult(`inputs.parameters.${pe.source.parameterName}`);
            case "steps_output":
                return formattedResult(`steps.${pe.source.stepName}.outputs.parameters.${pe.source.parameterName}`);
            case "tasks_output":
                return formattedResult(`tasks.${pe.source.taskName}.outputs.parameters.${pe.source.parameterName}`);
            default:
                throw new Error(`Unknown parameter source: ${(pe.source as any).kind}`);
        }
    }

    if (isLoopItem(expr)) {
        return formattedResult("item");
    }

    if (isWorkflowValue(expr)) {
        const e = expr as WorkflowValueExpression;
        return formattedResult("workflow." + e.variable);
    }

    if (isTaskData(expr)) {
        const e = expr as TaskDataExpression<any>;
        return formattedResult(`${e.taskType}.${e.name}.${e.key}`)
    }

    if (isTemplateExpression(expr)) {
        const f = expr as TemplateReplacementExpression;
        let result = f.template;
        for (const [key, value] of Object.entries(f.replacements)) {
            const expandedValue = formatExpression(value).text;
            result = result.replaceAll(`{{${key}}}`, `" + ${expandedValue} + "`);
        }
        result = `"${result}"`;
        return formattedResult(result, true);
    }

    throw new Error(`Unsupported expression kind: ${(expr as any).kind}`);
}

/* ───────────────── Type guards ───────────────── */

export function isAsStringExpression(e: AnyExpr): e is AsStringExpression<any> { return e.kind === "as_string"; }
export function isLiteralExpression(e: AnyExpr): e is LiteralExpression<any> { return e.kind === "literal"; }
export function isPathExpression(e: AnyExpr): e is RecordFieldSelectExpression<any, any, any> { return e.kind === "path"; }
export function isConcatExpression(e: AnyExpr): e is ConcatExpression<any> { return e.kind === "concat"; }
export function isTernaryExpression(e: AnyExpr): e is TernaryExpression<any, any, any, any> { return e.kind === "ternary"; }
export function isFunction(e: AnyExpr): e is FunctionExpression<any, any> { return e.kind === "function"; }
export function isArithmeticExpression(e: AnyExpr): e is ArithmeticExpression<any, any> { return e.kind === "arithmetic"; }
export function isComparisonExpression(e: AnyExpr): e is ComparisonExpression<any, any, any> { return e.kind === "comparison"; }
export function isArrayIndexExpression(e: AnyExpr): e is ArrayIndexExpression<any, any, any> { return e.kind === "array_index"; }
export function isArrayMakeExpression(e: AnyExpr): e is ArrayMakeExpression<any> { return e.kind === "array_make"; }
export function isDictMakeExpression(e: AnyExpr): e is DictMakeExpression<any> { return e.kind === "dict_make"; }
export function isParameterExpression(e: AnyExpr): e is FromParameterExpression<any,any> { return e.kind === "parameter"; }
export function isLoopItem(e: AnyExpr): e is FromParameterExpression<any,any> { return e.kind === "loop_item"; }
export function isWorkflowValue(e: AnyExpr): e is WorkflowValueExpression { return e.kind === "workflow_value"; }
export function isTaskData(e: AnyExpr): e is TaskDataExpression<any> { return e.kind === "task_data"; }
function isTemplateExpression(e: AnyExpr): e is TemplateReplacementExpression { return e.kind === "fillTemplate"; }
