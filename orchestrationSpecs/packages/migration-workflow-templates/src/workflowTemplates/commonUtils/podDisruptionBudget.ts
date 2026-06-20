import {
    BaseExpression,
    makeDirectTypeProxy,
    makeStringTypeProxy,
} from "@opensearch-migrations/argo-workflow-builders";
import {OwnerReference} from "@opensearch-migrations/k8s-types";

export type PodDisruptionBudgetManifest = {
    apiVersion: "policy/v1",
    kind: "PodDisruptionBudget",
    metadata: {
        name: string | object,
        ownerReferences?: OwnerReference[],
        labels?: Record<string, string | object>,
    },
    spec: {
        minAvailable: number | object,
        selector: {
            matchLabels: Record<string, string | object>,
        },
    },
};

function stringifyLabels(labels: Record<string, string | BaseExpression<string>>): Record<string, string | object> {
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
}): PodDisruptionBudgetManifest {
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
