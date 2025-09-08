// Type-safe visitor pattern for conversion
import {
    AnyExpression,
    ArithmeticExpression,
    ArrayIndexExpression,
    ArrayLengthExpression,
    AsStringExpression,
    BaseExpression,
    ComparisonExpression,
    ConcatExpression,
    ExpressionType,
    FromConfigMapExpression,
    FromParameterExpression, FromWorkflowUuid,
    LiteralExpression,
    PathExpression,
    TernaryExpression
} from "@/schemas/expression";
import { PlainObject } from "@/schemas/plainObject";
import {LoopWithUnion} from "@/schemas/workflowTypes";

/** Lightweight erased type to avoid deep generic instantiation */
type AnyExpr = BaseExpression<any, any>;

/* ───────────────── toArgoExpression ───────────────── */

// Overload (nice for call sites — return stays string)
export function toArgoExpression<E extends AnyExpr>(expr: E): string;

// Implementation uses erased types to prevent TS2589
export function toArgoExpression(expr: AnyExpr): string {
    if (isAsStringExpression(expr)) {
        return toArgoExpression(expr.source);
    }

    if (isLiteralExpression(expr)) {
        const le = expr as LiteralExpression<any>;
        if (typeof le.value === "string") return `"${le.value}"`;
        if (typeof le.value === "number" || typeof le.value === "boolean") return String(le.value);
        if (le.value === null) return "null";
        return JSON.stringify(le.value); // objects / arrays
    }

    if (isParameterExpression(expr)) {
        const pe = expr as FromParameterExpression<any>;
        switch (pe.source.kind) {
            case "workflow":
                return `{{workflow.parameters.${pe.source.parameterName}}}`;
            case "input":
                return `{{inputs.parameters.${pe.source.parameterName}}}`;
            case "step_output":
                return `{{steps.${pe.source.stepName}.outputs.parameters.${pe.source.parameterName}}}`;
            case "task_output":
                return `{{tasks.${pe.source.taskName}.outputs.parameters.${pe.source.parameterName}}}`;
            default:
                throw new Error(`Unknown parameter source: ${(pe.source as any).kind}`);
        }
    }

    if (isLoopItem(expr)) {
        return "{{item}}"
    }

    if (isWorkflowUuid(expr)) {
        return '{{workflow.uuid}}';
    }

    if (isConfigMapExpression(expr)) {
        const ce = expr as FromConfigMapExpression<any>;
        // adjust to your real templating for configmaps if different
        return `{{workflow.parameters.${ce.configMapName}-${ce.key}}}`;
    }

    if (isPathExpression(expr)) {
        const pe = expr as PathExpression<AnyExpr, any, string, any>;
        const sourceArgo = toArgoExpression(pe.source);
        // Convert path to JSONPath format (your paths already like a.b or [0].c)
        const jsonPath = pe.path.replace(/\[(\d+)\]/g, "[$1]").replace(/^/, "$.");
        return `{{${sourceArgo} | jsonpath('${jsonPath}')}}`;
    }

    if (isConcatExpression(expr)) {
        const ce = expr as ConcatExpression<any>;
        const parts = ce.expressions.map(toArgoExpression);
        return ce.separator ? parts.join(ce.separator) : parts.join("");
    }

    if (isTernaryExpression(expr)) {
        const te = expr as TernaryExpression<any, any, any, any>;
        return `{{${toArgoExpression(te.condition)} ? ${toArgoExpression(te.whenTrue)} : ${toArgoExpression(te.whenFalse)}}}`;
    }

    if (isArithmeticExpression(expr)) {
        const ae = expr as ArithmeticExpression<any, any>;
        return `{{${toArgoExpression(ae.left)} ${ae.operator} ${toArgoExpression(ae.right)}}}`;
    }

    if (isComparisonExpression(expr)) {
        const ce = expr as ComparisonExpression<any, any, any>;
        return `{{${toArgoExpression(ce.left)} ${ce.operator} ${toArgoExpression(ce.right)}}}`;
    }

    if (isArrayLengthExpression(expr)) {
        const le = expr as ArrayLengthExpression<any>;
        return `{{${toArgoExpression(le.array)} | length}}`;
    }

    if (isArrayIndexExpression(expr)) {
        const ie = expr as ArrayIndexExpression<any, any>;
        return `{{${toArgoExpression(ie.array)}[${toArgoExpression(ie.index)}]}}`;
    }

    throw new Error(`Unsupported expression kind: ${(expr as any).kind}`);
}

/* ───────────────── Lightweight type guards ─────────────────
   Each guard narrows to a minimal, erased version of the node
   to avoid recursive generic re-instantiation. */

export function isAsStringExpression(e: AnyExpr): e is AsStringExpression<any> {
    return e.kind === "as_string";
}

export function isLiteralExpression(e: AnyExpr): e is LiteralExpression<any> {
    return e.kind === "literal";
}

export function isParameterExpression(e: AnyExpr): e is FromParameterExpression<any> {
    return e.kind === "parameter";
}

export function isLoopItem(e: AnyExpr): e is FromParameterExpression<any> {
    return e.kind === "loop_item";
}

export function isWorkflowUuid(e: AnyExpr): e is FromWorkflowUuid {
    return e.kind === "workflow_uuid";
}

export function isConfigMapExpression(e: AnyExpr): e is FromConfigMapExpression<any> {
    return e.kind === "configmap";
}

export function isPathExpression(e: AnyExpr): e is PathExpression<AnyExpr, any, string, any> {
    return e.kind === "path";
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

export function isArrayIndexExpression(e: AnyExpr): e is ArrayIndexExpression<any, any> {
    return e.kind === "array_index";
}
