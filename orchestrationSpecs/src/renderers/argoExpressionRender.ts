// Type-safe visitor pattern for conversion
import {
    ArithmeticExpression,
    ArrayIndexExpression,
    ArrayLengthExpression, AsStringExpression,
    ComparisonExpression,
    ConcatExpression,
    Expression,
    FromConfigMapExpression,
    FromParameterExpression,
    LiteralExpression,
    PathExpression,
    TernaryExpression
} from "@/schemas/expression";

export function toArgoExpression<T>(expr: Expression<T>): string {
    // Use type guards instead of switch statements with type assertions

    if (isAsStringExpression(expr)) {
        return toArgoExpression(expr.source);
    }

    if (isLiteralExpression(expr)) {
        return typeof expr.value === 'string'
            ? `"${expr.value}"`
            : String(expr.value);
    }

    if (isParameterExpression(expr)) {
        switch (expr.source.kind) {
            case 'workflow':
                return `{{workflow.parameters.${expr.source.parameterName}}}`;
            case 'input':
                return `{{inputs.parameters.${expr.source.parameterName}}}`;
            case 'step_output':
                return `{{steps.${expr.source.stepName}.outputs.parameters.${expr.source.parameterName}}}`;
            case 'task_output':
                return `{{tasks.${expr.source.taskName}.outputs.parameters.${expr.source.parameterName}}}`;
            default:
                throw new Error(`Unknown parameter source: ${(expr.source as any).kind}`);
        }
    }

    if (isConfigMapExpression(expr)) {
        return `{{workflow.parameters.${expr.configMapName}-${expr.key}}}`;
    }

    if (isPathExpression(expr)) {
        const sourceArgo = toArgoExpression(expr.source);
        // Convert path to JSONPath format
        const jsonPath = expr.path.replace(/\[(\d+)\]/g, '[$1]').replace(/^/, '$.');
        return `{{${sourceArgo} | jsonpath('${jsonPath}')}}`;
    }

    if (isConcatExpression(expr)) {
        const parts = expr.expressions.map(toArgoExpression);
        return expr.separator
            ? parts.join(expr.separator)
            : parts.join('');
    }

    if (isTernaryExpression(expr)) {
        return `{{${toArgoExpression(expr.condition)} ? ${toArgoExpression(expr.whenTrue)} : ${toArgoExpression(expr.whenFalse)}}}`;
    }

    if (isArithmeticExpression(expr)) {
        return `{{${toArgoExpression(expr.left)} ${expr.operator} ${toArgoExpression(expr.right)}}}`;
    }

    if (isComparisonExpression(expr)) {
        return `{{${toArgoExpression(expr.left)} ${expr.operator} ${toArgoExpression(expr.right)}}}`;
    }

    if (isArrayLengthExpression(expr)) {
        return `{{${toArgoExpression(expr.array)} | length}}`;
    }

    if (isArrayIndexExpression(expr)) {
        return `{{${toArgoExpression(expr.array)}[${toArgoExpression(expr.index)}]}}`;
    }

    throw new Error(`Unsupported expression kind: ${(expr as any).kind}`);
}

export function isAsStringExpression(expr: Expression<any>): expr is AsStringExpression<any> {
    return expr.kind === 'as_string';
}

export function isLiteralExpression<T>(expr: Expression<T>): expr is LiteralExpression<T> {
    return expr.kind === 'literal';
}

export function isParameterExpression<T>(expr: Expression<T>): expr is FromParameterExpression<T> {
    return expr.kind === 'parameter';
}

export function isConfigMapExpression<T>(expr: Expression<T>): expr is FromConfigMapExpression<T> {
    return expr.kind === 'configmap';
}

export function isPathExpression<T>(expr: Expression<T>): expr is PathExpression<any, T> {
    return expr.kind === 'path';
}

export function isConcatExpression(expr: Expression<any>): expr is ConcatExpression {
    return expr.kind === 'concat';
}

export function isTernaryExpression<T>(expr: Expression<T>): expr is TernaryExpression<T> {
    return expr.kind === 'ternary';
}

export function isArithmeticExpression(expr: Expression<any>): expr is ArithmeticExpression {
    return expr.kind === 'arithmetic';
}

export function isComparisonExpression(expr: Expression<any>): expr is ComparisonExpression {
    return expr.kind === 'comparison';
}

export function isArrayLengthExpression(expr: Expression<any>): expr is ArrayLengthExpression<any> {
    return expr.kind === 'array_length';
}

export function isArrayIndexExpression<T>(expr: Expression<T>): expr is ArrayIndexExpression<T> {
    return expr.kind === 'array_index';
}