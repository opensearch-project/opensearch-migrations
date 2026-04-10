import {defineParam} from "@opensearch-migrations/argo-workflow-builders";

export const CommonWorkflowParameters = {
    s3SnapshotConfigMap: defineParam({expression: "s3-snapshot-config"}),
    imageConfigMapName: defineParam({expression: "migration-image-config"}),
    approvalConfigMapName: defineParam({expression: "approval-config"})
} as const;