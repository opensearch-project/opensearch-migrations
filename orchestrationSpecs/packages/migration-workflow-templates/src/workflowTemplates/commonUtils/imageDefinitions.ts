import {defineRequiredParam, IMAGE_PULL_POLICY, InputParamDef} from "@opensearch-migrations/argo-workflow-builders";

export const LogicalOciImages = [
    "CaptureProxy",
    "TrafficReplayer",
    "ReindexFromSnapshot",
    "MigrationConsole"
] as const;
export type LogicalOciImagesKeys = typeof LogicalOciImages[number];

export function makeRequiredImageParametersForKeys<K extends LogicalOciImagesKeys, T extends readonly K[]>(keys: T) {
    return Object.fromEntries(
        keys.flatMap(k => [
            [`image${k}Location`, defineRequiredParam<string>()],
            [`image${k}PullPolicy`, defineRequiredParam<IMAGE_PULL_POLICY>()]
        ])
    ) as Record<`image${typeof keys[number]}Location`, InputParamDef<string, true>> &
        Record<`image${typeof keys[number]}PullPolicy`, InputParamDef<IMAGE_PULL_POLICY, true>>;
}

export const ImageParameters = makeRequiredImageParametersForKeys(LogicalOciImages);
