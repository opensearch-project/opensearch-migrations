import {defineParam, defineRequiredParam} from "@/schemas/workflowSchemas";
import {z} from "zod";
import {ZodType, ZodTypeAny} from "zod";

// @ts-ignore
export const CommonWorkflowParameters = {
    etcdEndpoints:       defineParam({
        defaultValue: "http://etcd.ma.svc.cluster.local:2379"
    }),
    etcdUser:            defineRequiredParam({type: z.string() }),
    etcdPassword:        defineParam({ defaultValue: "password" }),
    etcdImage:           defineParam({ defaultValue: "migrations/migration_console:latest" }),
    s3SnapshotConfigMap: defineParam({ defaultValue: "s3-snapshot-config" })
} as const;
