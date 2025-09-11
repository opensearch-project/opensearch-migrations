// Type-safe visitor pattern for conversion
import expression, {
    AnyExpression,
    ArithmeticExpression,
    ArrayIndexExpression,
    ArrayLengthExpression,
    ArrayMakeExpression,
    AsStringExpression,
    BaseExpression,
    ComparisonExpression,
    ConcatExpression,
    ExpressionType,
    FromBase64Expression,
    FromParameterExpression,
    WorkflowValueExpression,
    LiteralExpression,
    RecordFieldSelectExpression,
    TernaryExpression,
    ToBase64Expression,
    SerializeJson,
    NotExpression,
    DeserializeJson,
    TemplateReplacementExpression,
} from "@/schemas/expression";
import { PlainObject } from "@/schemas/plainObject";

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
    return useMarkers ? "{{=" + rval.text + "}}" : rval.text;
}

/** Returns the Argo-formatted string plus whether the expression was compound. */
function formatExpression(expr: AnyExpr, top=false): ArgoFormatted {
    if (isAsStringExpression(expr)) {
        return formatExpression(expr.source);
    }

    if (isLiteralExpression(expr)) {
        const le = expr as LiteralExpression<any>;
        if (typeof le.value === "string") {
            return formattedResult(`"${le.value}"`);
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
        return formattedResult(`${c.text} ? ${t.text} : ${f.text}`, true);
    }

    if (isComparisonExpression(expr)) {
        const ce = expr as ComparisonExpression<any, any, any>;
        const l = formatExpression(ce.left);
        const r = formatExpression(ce.right);
        return formattedResult(`${l.text} ${ce.operator} ${r.text}`, true);
    }

    if (isNotExpression(expr)) {
        const n = expr as NotExpression<any>;
        const f = formatExpression(n.boolValue);
        return formattedResult(`!(${f.text})`, true);
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
        const needsFromJson = isParameterExpression(pe.source as AnyExpr);
        const source = needsFromJson ? `fromJSON(${inner.text})` : inner.text;
        const jsonPath = pe.path.replace(/\[(\d+)\]/g, "[$1]").replace(/^/, "$.");
        return formattedResult(`jsonpath(${source}, '${jsonPath}')`, true);
    }

    if (isJsonDeserialize(expr)) {
        const se = expr as DeserializeJson<any>;
        const formattedInner = formatExpression(se.data);
        if (top) {
            return formattedInner;
        }
        return formattedResult(`fromJSON(${formattedInner.text})`, true);
    }

    if (isJsonSerialize(expr)) {
        const se = expr as SerializeJson;
        const inner = formatExpression(se.data);
        const finalText = isParameterExpression(se.data as AnyExpr)
            ? inner.text : `toJSON(${inner.text})`;
        return formattedResult(finalText, true);
    }

    if (isArrayLengthExpression(expr)) {
        const le = expr as ArrayLengthExpression<any>;
        const arr = formatExpression(le.array);
        return formattedResult(`(${arr.text} | length)`, true);
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

    if (isParameterExpression(expr)) {
        const pe = expr as FromParameterExpression<any>;
        switch (pe.source.kind) {
            case "workflow":
                return formattedResult(`workflow.parameters.${pe.source.parameterName}`);
            case "input":
                return formattedResult(`inputs.parameters.${pe.source.parameterName}`);
            case "step_output":
                return formattedResult(`steps.${pe.source.stepName}.outputs.parameters.${pe.source.parameterName}`);
            case "task_output":
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

    if (isFromBase64(expr)) {
        const e = expr as FromBase64Expression;
        const d = formatExpression(e.data);
        return formattedResult(`fromBase64(${d.text})`, true);
    }

    if (isToBase64(expr)) {
        const e = expr as ToBase64Expression;
        const d = formatExpression(e.data);
        return formattedResult(`toBase64(${d.text})`, true);
    }

    if (isTemplateExpression(expr)) {
        const f = expr as TemplateReplacementExpression;
        let result = expr.template;
        for (const [key, value] of Object.entries(expr.replacements)) {
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
export function isJsonSerialize(e: AnyExpr): e is SerializeJson { return e.kind === "serialize_json"; }
export function isJsonDeserialize(e: AnyExpr): e is DeserializeJson<any> { return e.kind === "deserialize_json"; }
export function isConcatExpression(e: AnyExpr): e is ConcatExpression<any> { return e.kind === "concat"; }
export function isTernaryExpression(e: AnyExpr): e is TernaryExpression<any, any, any, any> { return e.kind === "ternary"; }
export function isArithmeticExpression(e: AnyExpr): e is ArithmeticExpression<any, any> { return e.kind === "arithmetic"; }
export function isComparisonExpression(e: AnyExpr): e is ComparisonExpression<any, any, any> { return e.kind === "comparison"; }
export function isNotExpression(e: AnyExpr): e is NotExpression<any> { return e.kind === "not"; }
export function isArrayLengthExpression(e: AnyExpr): e is ArrayLengthExpression<any> { return e.kind === "array_length"; }
export function isArrayIndexExpression(e: AnyExpr): e is ArrayIndexExpression<any, any, any> { return e.kind === "array_index"; }
export function isArrayMakeExpression(e: AnyExpr): e is ArrayMakeExpression<any> { return e.kind === "array_make"; }
export function isParameterExpression(e: AnyExpr): e is FromParameterExpression<any> { return e.kind === "parameter"; }
export function isLoopItem(e: AnyExpr): e is FromParameterExpression<any> { return e.kind === "loop_item"; }
export function isWorkflowValue(e: AnyExpr): e is WorkflowValueExpression { return e.kind === "workflow_value"; }
export function isFromBase64(e: AnyExpr): e is FromBase64Expression { return e.kind === "from_base64"; }
export function isToBase64(e: AnyExpr): e is ToBase64Expression { return e.kind === "to_base64"; }
function isTemplateExpression(e: AnyExpr): e is TemplateReplacementExpression { return e.kind === "fillTemplate"; }
