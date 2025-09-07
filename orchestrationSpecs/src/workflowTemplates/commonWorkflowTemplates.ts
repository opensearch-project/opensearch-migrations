import {defineParam, defineRequiredParam, InputParamDef, InputParametersRecord} from "@/schemas/parameterSchemas";
import {IMAGE_PULL_POLICY} from "@/schemas/containerBuilder";
import {z} from "zod/index";
import {CLUSTER_CONFIG, S3_CONFIG} from "@/workflowTemplates/userSchemas";

export const CommonWorkflowParameters = {
    etcdEndpoints:       defineParam({ defaultValue: "http://etcd.ma.svc.cluster.local:2379" }),
    etcdUser:            defineParam({ defaultValue: "root" }),
    etcdPassword:        defineParam({ defaultValue: "password" }),
    etcdImage:           defineParam({ defaultValue: "migrations/migration_console:latest" }),
    s3SnapshotConfigMap: defineParam({ defaultValue: "s3-snapshot-config" })
} as const;

export const LogicalOciImages = [
    "CaptureProxy",
    "TrafficReplayer",
    "ReindexFromSnapshot",
    "MigrationConsole",
    "EtcdUtils",
] as const;
export type LogicalOciImagesKeys = typeof LogicalOciImages[number];

export function makeImageParametersForKeys<K extends LogicalOciImagesKeys, T extends readonly K[]>(keys: T) {
    return Object.fromEntries(
        keys.flatMap(k => [
            [`image${k}Location`, defineParam({defaultValue: ""})],
            [`image${k}PullPolicy`, defineParam({defaultValue: "IF_NOT_PRESENT" as IMAGE_PULL_POLICY})]
        ])
    ) as Record<`image${typeof keys[number]}Location`, InputParamDef<string,false>> &
        Record<`image${typeof keys[number]}PullPolicy`, InputParamDef<IMAGE_PULL_POLICY,false>>;
}
export const ImageParameters = makeImageParametersForKeys(LogicalOciImages);

export const s3ConfigParam = {
    s3Config: defineRequiredParam<z.infer<typeof S3_CONFIG>[]>({
        description: "S3 connection info (region, endpoint, etc)"})
};
