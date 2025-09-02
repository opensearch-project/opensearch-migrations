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
    FromParameterExpression,
    LiteralExpression,
    PathExpression,
    TernaryExpression
} from "@/schemas/expression";
import {PlainObject} from "@/schemas/plainObject";

export function toArgoExpression<T extends PlainObject>(expr: BaseExpression<T>): string {
    // Use type guards instead of switch statements with type assertions

    if (isAsStringExpression(expr)) {
        return toArgoExpression(expr.source);
    }

    if (isLiteralExpression(expr)) {
        if (typeof expr.value === 'string') {
            return `"${expr.value}"`;
        } else if (typeof expr.value === 'number' || typeof expr.value === 'boolean') {
            return String(expr.value);
        } else if (expr.value === null) {
            return 'null';
        } else {
            // Objects and arrays - serialize as JSON
            return JSON.stringify(expr.value);
        }
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

export function isAsStringExpression(expr: BaseExpression<any>): expr is AsStringExpression<any> {
    return expr.kind === 'as_string';
}

export function isLiteralExpression<T extends PlainObject>(expr: BaseExpression<T>): expr is LiteralExpression<T> {
    return expr.kind === 'literal';
}

export function isParameterExpression<T extends PlainObject>(expr: BaseExpression<T>): expr is FromParameterExpression<T> {
    return expr.kind === 'parameter';
}

export function isConfigMapExpression<T extends PlainObject>(expr: BaseExpression<T>): expr is FromConfigMapExpression<T> {
    return expr.kind === 'configmap';
}

export function isPathExpression<T extends PlainObject>(expr: BaseExpression<T>): expr is PathExpression<any> {
    return expr.kind === 'path';
}

export function isConcatExpression(expr: BaseExpression<any>): expr is ConcatExpression<any> {
    return expr.kind === 'concat';
}

export function isTernaryExpression<
    T extends AnyExpression<boolean>,
    L extends AnyExpression<OutT>,
    R extends AnyExpression<OutT>,
    OutT extends PlainObject,
    C extends ExpressionType>(expr: BaseExpression<any>): expr is TernaryExpression<any, any, any, any> {
    return expr.kind === 'ternary';
}

export function isArithmeticExpression(expr: BaseExpression<any>): expr is ArithmeticExpression<any, any> {
    return expr.kind === 'arithmetic';
}

export function isComparisonExpression(expr: BaseExpression<any>): expr is ComparisonExpression<any, any, any> {
    return expr.kind === 'comparison';
}

export function isArrayLengthExpression(expr: BaseExpression<any>): expr is ArrayLengthExpression<any> {
    return expr.kind === 'array_length';
}

export function isArrayIndexExpression<T extends PlainObject>(expr: BaseExpression<T>): expr is ArrayIndexExpression<any, any, T, any> {
    return expr.kind === 'array_index';
}
