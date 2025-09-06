import {defineParam, InputParamDef, InputParametersRecord} from "@/schemas/parameterSchemas";
import {IMAGE_PULL_POLICY} from "@/schemas/containerBuilder";

export const CommonWorkflowParameters = {
    etcdEndpoints:       defineParam({ defaultValue: "http://etcd.ma.svc.cluster.local:2379" }),
    etcdUser:            defineParam({ defaultValue: "root" }),
    etcdPassword:        defineParam({ defaultValue: "password" }),
    etcdImage:           defineParam({ defaultValue: "migrations/migration_console:latest" }),
    s3SnapshotConfigMap: defineParam({ defaultValue: "s3-snapshot-config" })
} as const;

const LogicalOciImages = [
    "CaptureProxy",
    "TrafficReplayer",
    "ReindexFromSnapshot",
    "MigrationConsole",
    "EtcdUtils",
] as const;

export const ImageParameters =
    Object.fromEntries(
        LogicalOciImages.flatMap(k => [
            [`Image${k}Location`, defineParam({defaultValue: ""})],
            [`Image${k}ImagePullPolicy`, defineParam({defaultValue: "IF_NOT_PRESENT" as IMAGE_PULL_POLICY})]
        ])
    ) as Record<`Image${typeof LogicalOciImages[number]}Location`, InputParamDef<string,false>> &
        Record<`Image${typeof LogicalOciImages[number]}PullPolicy`, InputParamDef<IMAGE_PULL_POLICY,false>>;
console.log(ImageParameters.ImageCaptureProxyLocation)
