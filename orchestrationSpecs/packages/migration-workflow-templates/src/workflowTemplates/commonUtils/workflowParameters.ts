import {defineParam} from "@opensearch-migrations/argo-workflow-builders";

export const CommonWorkflowParameters = {
    etcdEndpoints: defineParam({expression: "http://etcd.ma.svc.cluster.local:2379"}),
    etcdUser: defineParam({expression: "root"}),
    etcdPassword: defineParam({expression: "password"}),
    s3SnapshotConfigMap: defineParam({expression: "s3-snapshot-config"}),
    imageConfigMapName: defineParam({expression: "migration-image-config"}),
    approvalConfigMapName: defineParam({expression: "approval-config"})
} as const;