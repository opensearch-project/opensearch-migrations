import {
    BaseExpression,
    configMapKey,
    defineParam,
    expr,
    InputParamDef,
    typeToken,
} from "@opensearch-migrations/argo-workflow-builders";
import {PROVIDER_CONFIG_MAP_NAME} from "./workflowParameters";

export const WORKLOAD_NODE_POOL_LABEL = "migrations.opensearch.org/workload-pool";

type WorkloadNodePoolConfigKey =
    "kafkaNodePoolName"
    | "proxyNodePoolName"
    | "replayerNodePoolName";

/**
 * Resolve deployment-level workload placement at the template where a pod is created.
 *
 * The Helm chart always publishes these keys. An empty value means that custom NodePools are
 * disabled and the generated pod should retain ordinary Kubernetes scheduling.
 */
export function workloadNodePoolResolvedInput(
    configKey: WorkloadNodePoolConfigKey,
): { workloadNodePoolName: InputParamDef<string, false> } {
    return {
        workloadNodePoolName: defineParam({
            from: configMapKey(PROVIDER_CONFIG_MAP_NAME, configKey),
            type: typeToken<string>(),
            expression: "",
        }),
    };
}

export function workloadNodeSelector(
    nodePoolName: BaseExpression<string>,
) {
    return expr.ternary(
        expr.isEmpty(nodePoolName),
        expr.makeDict({}),
        expr.makeDict({
            [WORKLOAD_NODE_POOL_LABEL]: nodePoolName,
        }),
    );
}

export function workloadNodeTolerations(
    nodePoolName: BaseExpression<string>,
): BaseExpression<any> {
    return expr.ternary(
        expr.isEmpty(nodePoolName),
        expr.literal([]),
        expr.toArray(expr.makeDict({
            key: WORKLOAD_NODE_POOL_LABEL,
            operator: "Equal",
            value: nodePoolName,
            effect: "NoSchedule",
        })),
    );
}
