import {
    BaseExpression,
    makeDirectTypeProxy,
    makeStringTypeProxy,
    ResourceWorkflowDefinition,
} from "@opensearch-migrations/argo-workflow-builders";
import {OwnerReference, PodDisruptionBudget} from "@opensearch-migrations/k8s-types";

function stringifyLabels(labels: Record<string, string | BaseExpression<string>>): Record<string, string> {
    return Object.fromEntries(
        Object.entries(labels).map(([key, value]) => [
            key,
            typeof value === "string" ? value : makeStringTypeProxy(value)
        ])
    );
}

export function makePodDisruptionBudgetManifest(args: {
    name: BaseExpression<string>,
    minAvailable: BaseExpression<number>,
    matchLabels: Record<string, string | BaseExpression<string>>,
    labels?: Record<string, string | BaseExpression<string>>,
    ownerReferences?: OwnerReference[],
}): PodDisruptionBudget {
    return {
        apiVersion: "policy/v1",
        kind: "PodDisruptionBudget",
        metadata: {
            name: makeStringTypeProxy(args.name),
            ...(args.ownerReferences && {ownerReferences: args.ownerReferences}),
            ...(args.labels && {labels: stringifyLabels(args.labels)}),
        },
        spec: {
            minAvailable: makeDirectTypeProxy(args.minAvailable),
            selector: {
                matchLabels: stringifyLabels(args.matchLabels),
            },
        },
    };
}

export function makePodDisruptionBudgetDefinition(
    args: Parameters<typeof makePodDisruptionBudgetManifest>[0]
): ResourceWorkflowDefinition {
    return {
        action: "apply",
        setOwnerReference: false,
        manifest: makePodDisruptionBudgetManifest(args),
    };
}
