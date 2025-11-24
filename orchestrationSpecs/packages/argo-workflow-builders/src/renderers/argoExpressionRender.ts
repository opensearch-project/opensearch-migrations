// Type-safe visitor pattern for conversion
import {
    ArrayIndexExpression,
    ArrayMakeExpression,
    AsStringExpression,
    BaseExpression,
    ComparisonExpression,
    ConcatExpression,
    DictMakeExpression,
    FromParameterExpression,
    FunctionExpression,
    InfixExpression,
    LiteralExpression,
    RecordFieldSelectExpression, RecordGetExpression, UnquotedTypeWrapper,
    TaskDataExpression,
    TemplateReplacementExpression,
    TernaryExpression,
    WorkflowValueExpression,
} from "../models/expression";

export const REMOVE_PREVIOUS_QUOTE_SENTINEL = "REMOVE_PREVIOUS_QUOTE_SENTINEL";
export const REMOVE_NEXT_QUOTE_SENTINEL = "REMOVE_NEXT_QUOTE_SENTINEL";

/** Lightweight erased type to avoid deep generic instantiation */
type AnyExpr = BaseExpression<any, any>;

export type ArgoFormatted = {
    text: string;
    /** True if the final expression used any compound operation (non-trivial operator/wrapper). */
    compound: boolean;
};

const formattedResult = (text: string, compound = false): ArgoFormatted => ({text, compound});

function formatArgoFormattedToString(useMarkers: boolean, expr: AnyExpr, renderedResult: ArgoFormatted) {
    return (useMarkers && !(isLiteralExpression(expr)))
        ? "{{" + (renderedResult.compound ? "=" : "") + renderedResult.text + "}}" : renderedResult.text;
}

export type MarkerStyle = "Outer" | "None" | "IdentifierOnly";

export function toArgoExpressionString(expr: AnyExpr, useMarkers: MarkerStyle = "Outer"): string {
    if (isTemplateExpression(expr)) {
        const f = expr as TemplateReplacementExpression;
        let result = f.template;
        for (const [key, value] of Object.entries(f.replacements)) {
            const expandedValue = formatArgoFormattedToString(true, expr, formatExpression(value, false));
            result = result.replaceAll(`{{${key}}}`, expandedValue);
        }
        return result;
    }

    const rval = formatExpression(expr, useMarkers === "IdentifierOnly", true);
    const formattedRval = formatArgoFormattedToString(useMarkers === "Outer", expr, rval);
    if (isSpecialStripQuotesDirective(expr)) {
        return `${REMOVE_PREVIOUS_QUOTE_SENTINEL}${formattedRval}${REMOVE_NEXT_QUOTE_SENTINEL}`;
    }
    return formattedRval;
}

/** Returns the Argo-formatted string plus whether the expression was compound. */
function formatExpression(expr: AnyExpr, useIdentifierMarkers: boolean, top = false): ArgoFormatted {
    if (isAsStringExpression(expr)) {
        return formatExpression(expr.source, useIdentifierMarkers, true);
    }

    if (isLiteralExpression(expr)) {
        const le = expr as LiteralExpression<any>;
        if (typeof le.value === "string") {
            return top ? formattedResult(le.value) : formattedResult(`'${le.value}'`);
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
        const parts = ce.expressions.map(e => formatExpression(e, useIdentifierMarkers));
        const text = parts.map(p => p.text).join(ce.separator ? " + '" + ce.separator + "' + " : "+");
        const compound = (ce.expressions.length > 1) || !!ce.separator || parts.some(p => p.compound);
        return formattedResult(text, compound);
    }

    if (isTernaryExpression(expr)) {
        const te = expr as TernaryExpression<any, any, any, any>;
        const c = formatExpression(te.condition, useIdentifierMarkers);
        const t = formatExpression(te.whenTrue, useIdentifierMarkers);
        const f = formatExpression(te.whenFalse, useIdentifierMarkers);
        return formattedResult(`((${c.text}) ? (${t.text}) : (${f.text}))`, true);
    }

    if (isFunctionExpression(expr)) {
        const e = expr as FunctionExpression<any, any>;
        if (e.functionName === "toJSON" && isParameterExpression(e.args[0] as AnyExpr)) {
            return formatExpression(e.args[0], useIdentifierMarkers);
        } else if (e.functionName === "fromJSON" && top) {
            return formatExpression(e.args[0], useIdentifierMarkers);
        }
        const formattedArgs =
            e.args.map(a => formatExpression(a, useIdentifierMarkers));
        const combinedFormatted = formattedArgs.map(f => f.text).join(", ");
        return formattedResult(`${e.functionName}(${combinedFormatted})`, true);
    }

    if (isComparisonExpression(expr)) {
        const ce = expr as ComparisonExpression<any, any, any>;
        const l = formatExpression(ce.left, useIdentifierMarkers);
        const r = formatExpression(ce.right, useIdentifierMarkers);
        return formattedResult(`${l.text} ${ce.operator} ${r.text}`, true);
    }

    if (isInfixExpression(expr)) {
        const ae = expr as InfixExpression<any, any, any>;
        const l = formatExpression(ae.left, useIdentifierMarkers);
        const r = formatExpression(ae.right, useIdentifierMarkers);
        return formattedResult(`${l.text} ${ae.operator} ${r.text}`, true);
    }

    if (isGetExpression(expr)) {
        const ge = expr as RecordGetExpression<any, any, any>;
        const inner = formatExpression(ge.source, useIdentifierMarkers);
        return formattedResult(`${inner.text}['${ge.key}']`, true);

    }

    if (isPathExpression(expr)) {
        const pe = expr as RecordFieldSelectExpression<any, any, any>;
        const inner = formatExpression(pe.source, useIdentifierMarkers);
        const source = inner.text;
        const jsonPath = pe.path.replace(/\[(\d+)\]/g, "[$1]").replace(/^/, "$.");
        return formattedResult(`jsonpath(${source}, '${jsonPath}')`, true);
    }

    if (isArrayIndexExpression(expr)) {
        const ie = expr as ArrayIndexExpression<any, any, any>;
        const arr = formatExpression(ie.array, useIdentifierMarkers);
        const idx = formatExpression(ie.index, useIdentifierMarkers);
        return formattedResult(`${arr.text}[${idx.text}]`, true);
    }

    if (isArrayMakeExpression(expr)) {
        const ae = expr as ArrayMakeExpression<any>;
        const inner = ae.elements
            .map((e: AnyExpr) => formatExpression(e, useIdentifierMarkers).text).join(", ");
        return formattedResult(`[${inner}]`, true);
    }

    if (isDictMakeExpression(expr)) {
        const de = expr as DictMakeExpression<Record<string, AnyExpr>>;
        const parts: string[] = [];
        for (const [k, v] of Object.entries(de.entries)) {
            const fv = formatExpression(v as any, useIdentifierMarkers);
            parts.push(`"${k}"`, fv.text);
        }
        const args = parts.join(", ");
        // Using function-call form for consistency with other sprig.* calls here:
        // sprig.dict("k1", v1, "k2", v2)
        const text = parts.length ? `sprig.dict(${args})` : `sprig.dict()`;
        return formattedResult(text, true);
    }

    if (isParameterExpression(expr)) {
        const pe = expr as FromParameterExpression<any, any>;
        const expandedName = (() => {
            switch (pe.source.kind) {
                case "workflow":
                    return `workflow.parameters.${pe.source.parameterName}`;
                case "input":
                    return (`inputs.parameters.${pe.source.parameterName}`);
                case "steps_output":
                    return (`steps.${pe.source.stepName}.outputs.parameters.${pe.source.parameterName}`);
                case "tasks_output":
                    return (`tasks.${pe.source.taskName}.outputs.parameters.${pe.source.parameterName}`);
                default:
                    throw new Error(`Unknown parameter source: ${(pe.source as any).kind}`);
            }
        }).call({});
        return formattedResult(useIdentifierMarkers ? `{{${expandedName}}}` : expandedName, false);
    }

    if (isLoopItem(expr)) {
        // item won't be available for when conditions, but to be complete,
        // it is an identifier and should be considered as a parameter
        return formattedResult(useIdentifierMarkers ? `{{item}}` : "item", false);
    }

    if (isWorkflowValue(expr)) {
        const e = expr as WorkflowValueExpression;
        const expandedName = "workflow." + e.variable;
        return formattedResult(useIdentifierMarkers ? `{{${expandedName}}}` : expandedName, false);
    }

    if (isTaskData(expr)) {
        const e = expr as TaskDataExpression<any>;
        const expandedName = `${e.taskType}.${e.name}.${e.key}`;
        return formattedResult(useIdentifierMarkers ? `{{${expandedName}}}` : expandedName, false);
    }

    if (isSpecialStripQuotesDirective(expr)) {
        const e = expr as UnquotedTypeWrapper<any>;
        return formatExpression(e.value, useIdentifierMarkers); // skip right by here
    }

    throw new Error(`Unsupported expression kind: ${(expr as any).kind}`);
}

/* ───────────────── Type guards ───────────────── */

export function isArrayIndexExpression(e: AnyExpr): e is ArrayIndexExpression<any, any, any> {
    return e.kind === "array_index";
}

export function isArrayMakeExpression(e: AnyExpr): e is ArrayMakeExpression<any> {
    return e.kind === "array_make";
}

export function isAsStringExpression(e: AnyExpr): e is AsStringExpression<any> {
    return e.kind === "as_string";
}

export function isComparisonExpression(e: AnyExpr): e is ComparisonExpression<any, any, any> {
    return e.kind === "comparison";
}

export function isConcatExpression(e: AnyExpr): e is ConcatExpression<any> {
    return e.kind === "concat";
}

export function isDictMakeExpression(e: AnyExpr): e is DictMakeExpression<any> {
    return e.kind === "dict_make";
}

export function isFunctionExpression(e: AnyExpr): e is FunctionExpression<any, any> {
    return e.kind === "function";
}

export function isInfixExpression(e: AnyExpr): e is InfixExpression<any, any, any> {
    return e.kind === "infix";
}

export function isLiteralExpression(e: AnyExpr): e is LiteralExpression<any> {
    return e.kind === "literal";
}

export function isLoopItem(e: AnyExpr): e is FromParameterExpression<any, any> {
    return e.kind === "loop_item";
}

export function isParameterExpression(e: AnyExpr): e is FromParameterExpression<any, any> {
    return e.kind === "parameter";
}

export function isPathExpression(e: AnyExpr): e is RecordFieldSelectExpression<any, any, any> {
    return e.kind === "path";
}

export function isGetExpression(e: AnyExpr): e is RecordGetExpression<any, any, any> {
    return e.kind === "get";
}

export function isTaskData(e: AnyExpr): e is TaskDataExpression<any> {
    return e.kind === "task_data";
}

export function isTemplateExpression(e: AnyExpr): e is TemplateReplacementExpression {
    return e.kind === "fillTemplate";
}

export function isTernaryExpression(e: AnyExpr): e is TernaryExpression<any, any, any, any> {
    return e.kind === "ternary";
}

export function isWorkflowValue(e: AnyExpr): e is WorkflowValueExpression {
    return e.kind === "workflow_value";
}

export function isSpecialStripQuotesDirective(e: AnyExpr): e is UnquotedTypeWrapper<any> {
    return e.kind === "strip_surrounding_quotes_in_serialized_output";
}
