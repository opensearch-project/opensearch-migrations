import {
    BaseExpression,
    defineRequiredParam,
    expr,
    Serialized,
} from "@opensearch-migrations/argo-workflow-builders";

export const SCALABLE_WORKLOAD_DEFAULTS = {
    podReplicas: 1,
    minPodReplicas: 0,
} as const;

export const POD_REPLICAS_INPUTS = {
    podReplicas: defineRequiredParam<number>(),
};

export const MIN_POD_REPLICAS_INPUTS = {
    minPodReplicas: defineRequiredParam<number>(),
};

export const SCALABLE_WORKLOAD_INPUTS = {
    ...POD_REPLICAS_INPUTS,
    ...MIN_POD_REPLICAS_INPUTS,
};

export type ScalableWorkloadConfig = {
    podReplicas: BaseExpression<number>,
    minPodReplicas: BaseExpression<number>,
};

export function scalingFromRecord(
    options: BaseExpression<Record<string, any>>
): ScalableWorkloadConfig {
    return {
        podReplicas: expr.dig(options, ["podReplicas"], SCALABLE_WORKLOAD_DEFAULTS.podReplicas),
        minPodReplicas: expr.dig(options, ["minPodReplicas"], SCALABLE_WORKLOAD_DEFAULTS.minPodReplicas),
    };
}

export function scalingFromOptions<T extends Record<string, any>>(
    options: BaseExpression<Serialized<T>>
): ScalableWorkloadConfig {
    return scalingFromRecord(expr.deserializeRecord(options));
}

export function minAvailableForSingleReplicaDependency(
    minPodReplicas: BaseExpression<number>
): BaseExpression<number> {
    return expr.ternary(
        expr.greaterThan(minPodReplicas, expr.literal(0)),
        expr.literal(1),
        expr.literal(0)
    );
}

export function prefixedScalableWorkloadFields<Prefix extends string>(
    prefix: Prefix,
    options: BaseExpression<Record<string, any>>
): Record<`${Prefix}${"PodReplicas" | "MinPodReplicas"}`, BaseExpression<number>> {
    const scaling = scalingFromRecord(options);
    return {
        [`${prefix}PodReplicas`]: scaling.podReplicas,
        [`${prefix}MinPodReplicas`]: scaling.minPodReplicas,
    } as Record<`${Prefix}${"PodReplicas" | "MinPodReplicas"}`, BaseExpression<number>>;
}
