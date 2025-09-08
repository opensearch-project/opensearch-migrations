// Type-safe visitor pattern for conversion
import expression, {
    AnyExpression,
    ArithmeticExpression,
    ArrayIndexExpression,
    ArrayLengthExpression,
    AsStringExpression,
    BaseExpression,
    ComparisonExpression,
    ConcatExpression,
    ExpressionType,
    FromBase64Expression,
    FromConfigMapExpression,
    FromParameterExpression,
    WorkflowUuidExpression,
    LiteralExpression,
    RecordFieldSelectExpression,
    TernaryExpression,
    ToBase64Expression,
    SerializeJson
} from "@/schemas/expression";
import { PlainObject } from "@/schemas/plainObject";
import { LoopWithUnion } from "@/schemas/workflowTypes";

/** Lightweight erased type to avoid deep generic instantiation */
type AnyExpr = BaseExpression<any, any>;

export type ArgoFormatted = {
    text: string;
    /** True if the final expression used any compound operation (non-trivial operator/wrapper). */
    compound: boolean;
};

const formattedResult = (text: string, compound = false): ArgoFormatted => ({ text, compound });

/* ───────────────── toArgoExpression ───────────────── */

export function toArgoExpression<E extends AnyExpr>(expr: E): string {
    const rval = toArgoExpressionInner(expr);
    return "{{" + (rval.compound ? "=" : "") + rval.text + "}}";
}

/** Returns the Argo-formatted string plus whether the expression was compound. */
export function toArgoExpressionInner<E extends AnyExpr>(expr: E): ArgoFormatted {
    if (isAsStringExpression(expr)) {
        return toArgoExpressionInner(expr.source);
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
            return formattedResult(JSON.stringify(le.value)); // objects / arrays
        }
    }

    if (isConcatExpression(expr)) {
        const ce = expr as ConcatExpression<BaseExpression<string, any>[]>;
        const parts = ce.expressions.map(toArgoExpressionInner);
        const text = ce.separator
            ? parts.map(p => p.text).join(ce.separator)
            : parts.map(p => p.text).join("");
        const compound =
            (ce.expressions.length > 1) ||
            !!ce.separator ||
            parts.some(p => p.compound);
        return formattedResult(text, compound);
    }

    if (isTernaryExpression(expr)) {
        const te = expr as TernaryExpression<any, any, any, any>;
        const c = toArgoExpressionInner(te.condition);
        const t = toArgoExpressionInner(te.whenTrue);
        const f = toArgoExpressionInner(te.whenFalse);
        return formattedResult(`${c.text} ? ${t.text} : ${f.text}`, true);
    }

    if (isComparisonExpression(expr)) {
        const ce = expr as ComparisonExpression<any, any, any>;
        const l = toArgoExpressionInner(ce.left);
        const r = toArgoExpressionInner(ce.right);
        return formattedResult(`${l.text} ${ce.operator} ${r.text}`, true);
    }

    if (isArithmeticExpression(expr)) {
        const ae = expr as ArithmeticExpression<any, any>;
        const l = toArgoExpressionInner(ae.left);
        const r = toArgoExpressionInner(ae.right);
        return formattedResult(`${l.text} ${ae.operator} ${r.text}`, true);
    }

    if (isPathExpression(expr)) {
        const pe = expr as RecordFieldSelectExpression<any, any, any>;
        const inner = toArgoExpressionInner(pe.source);
        const needsFromJson = isParameterExpression(pe.source as unknown as AnyExpr);
        const source = needsFromJson ? `fromJSON(${inner.text})` : inner.text;
        const jsonPath = pe.path.replace(/\[(\d+)\]/g, "[$1]").replace(/^/, "$.");
        // jsonpath(...) is non-trivial => compound
        return formattedResult(`jsonpath(${source}, '${jsonPath}')`, true);
    }

    if (isJsonSerialize(expr)) {
        const se = expr as SerializeJson;
        const inner = toArgoExpressionInner(se.data);
        const needsFromJson = isParameterExpression(se.data as unknown as AnyExpr);
        const data = needsFromJson ? `fromJSON(${inner.text})` : inner.text;
        // toJSON(...) is non-trivial => compound
        return formattedResult(`toJSON(${data})`, true);
    }

    if (isArrayLengthExpression(expr)) {
        const le = expr as ArrayLengthExpression<any>;
        const arr = toArgoExpressionInner(le.array);
        // `(X | length)` is a jq-style transform => compound
        return formattedResult(`(${arr.text} | length)`, true);
    }

    if (isArrayIndexExpression(expr)) {
        const ie = expr as ArrayIndexExpression<any, any, any>;
        const arr = toArgoExpressionInner(ie.array);
        const idx = toArgoExpressionInner(ie.index);
        // indexing is non-trivial in Argo expression context => compound
        return formattedResult(`${arr.text}[${idx.text}]`, true);
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

    if (isConfigMapExpression(expr)) {
        const ce = expr as FromConfigMapExpression<any>;
        // adjust to your real templating for configmaps if different
        return formattedResult(`workflow.parameters.${ce.configMapName}-${ce.key}`);
    }

    if (isWorkflowUuid(expr)) {
        return formattedResult("workflow.uuid");
    }

    if (isFromBase64(expr)) {
        const e = expr as FromBase64Expression;
        const d = toArgoExpressionInner(e.data);
        return formattedResult(`fromBase64(${d.text})`, true);
    }

    if (isToBase64(expr)) {
        const e = expr as ToBase64Expression;
        const d = toArgoExpressionInner(e.data);
        return formattedResult(`toBase64(${d.text})`, true);
    }

    throw new Error(`Unsupported expression kind: ${(expr as any).kind}`);
}

/* ───────────────── Lightweight type guards ───────────────── */

export function isAsStringExpression(e: AnyExpr): e is AsStringExpression<any> {
    return e.kind === "as_string";
}
export function isLiteralExpression(e: AnyExpr): e is LiteralExpression<any> {
    return e.kind === "literal";
}
export function isPathExpression(e: AnyExpr): e is RecordFieldSelectExpression<any, any, any> {
    return e.kind === "path";
}
export function isJsonSerialize(e: AnyExpr): e is SerializeJson {
    return e.kind === "serialize_json";
}
export function isConcatExpression(e: AnyExpr): e is ConcatExpression<any> {
    return e.kind === "concat";
}
export function isTernaryExpression(e: AnyExpr): e is TernaryExpression<any, any, any, any> {
    return e.kind === "ternary";
}
export function isArithmeticExpression(e: AnyExpr): e is ArithmeticExpression<any, any> {
    return e.kind === "arithmetic";
}
export function isComparisonExpression(e: AnyExpr): e is ComparisonExpression<any, any, any> {
    return e.kind === "comparison";
}
export function isArrayLengthExpression(e: AnyExpr): e is ArrayLengthExpression<any> {
    return e.kind === "array_length";
}
export function isArrayIndexExpression(e: AnyExpr): e is ArrayIndexExpression<any, any, any> {
    return e.kind === "array_index";
}
export function isParameterExpression(e: AnyExpr): e is FromParameterExpression<any> {
    return e.kind === "parameter";
}
export function isLoopItem(e: AnyExpr): e is FromParameterExpression<any> {
    return e.kind === "loop_item";
}
export function isConfigMapExpression(e: AnyExpr): e is FromConfigMapExpression<any> {
    return e.kind === "configmap";
}
export function isWorkflowUuid(e: AnyExpr): e is WorkflowUuidExpression {
    return e.kind === "workflow_uuid";
}
export function isFromBase64(e: AnyExpr): e is FromBase64Expression {
    return e.kind === "from_base64";
}
export function isToBase64(e: AnyExpr): e is ToBase64Expression {
    return e.kind === "to_base64";
}
