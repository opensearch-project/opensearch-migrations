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

/** How Kafka brokers, capture proxies and replayers spread across availability zones. */
export const WORKLOAD_ZONE_SPREAD_CONFIG_KEY = "workloadZoneSpread";
export const ZONE_TOPOLOGY_KEY = "topology.kubernetes.io/zone";

/**
 * Resolve the deployment-level zone spread policy at the template where a pod is created.
 *
 * The Helm chart publishes one of "required", "preferred" or "none". A missing key behaves as
 * "preferred", which balances across zones without ever blocking scheduling.
 */
export function workloadZoneSpreadResolvedInput(): { workloadZoneSpread: InputParamDef<string, false> } {
    return {
        workloadZoneSpread: defineParam({
            from: configMapKey(PROVIDER_CONFIG_MAP_NAME, WORKLOAD_ZONE_SPREAD_CONFIG_KEY),
            type: typeToken<string>(),
            expression: "preferred",
        }),
    };
}

/**
 * The zone topology spread constraints for one workload's pods.
 *
 * "required" refuses to schedule a pod that would put its zone more than one pod ahead of the least
 * populated zone; "preferred" only biases the scheduler; "none" adds no constraint. Nodes whose
 * taints the pod does not tolerate are left out of the skew calculation, so a tainted node group in
 * another zone cannot stall a hard spread.
 */
export function workloadZoneSpreadConstraints(
    zoneSpread: BaseExpression<string>,
    matchLabelKeys: string[],
): BaseExpression<any> {
    return expr.ternary(
        expr.equals(zoneSpread, "none"),
        expr.literal([]),
        expr.toArray(expr.makeDict({
            maxSkew: 1,
            topologyKey: ZONE_TOPOLOGY_KEY,
            whenUnsatisfiable: expr.ternary(
                expr.equals(zoneSpread, "required"),
                expr.literal("DoNotSchedule"),
                expr.literal("ScheduleAnyway"),
            ),
            nodeTaintsPolicy: "Honor",
            labelSelector: expr.makeDict({}),
            matchLabelKeys: expr.literal(matchLabelKeys),
        })),
    );
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

export function workloadNodeAffinity(
    nodePoolName: BaseExpression<string>,
) {
    return expr.ternary(
        expr.isEmpty(nodePoolName),
        expr.makeDict({}),
        expr.makeDict({
            nodeAffinity: expr.makeDict({
                requiredDuringSchedulingIgnoredDuringExecution: expr.makeDict({
                    nodeSelectorTerms: expr.toArray(expr.makeDict({
                        matchExpressions: expr.toArray(expr.makeDict({
                            key: WORKLOAD_NODE_POOL_LABEL,
                            operator: "In",
                            values: expr.toArray(nodePoolName),
                        })),
                    })),
                }),
            }),
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
