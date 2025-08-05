import {defineParam} from "@/schemas/parameterSchemas";

export const CommonWorkflowParameters = {
    etcdEndpoints:       defineParam({ defaultValue: "http://etcd.ma.svc.cluster.local:2379" }),
    etcdUser:            defineParam({ defaultValue: "root" }),
    etcdPassword:        defineParam({ defaultValue: "password" }),
    etcdImage:           defineParam({ defaultValue: "migrations/migration_console:latest" }),
    s3SnapshotConfigMap: defineParam({ defaultValue: "s3-snapshot-config" })
} as const;
