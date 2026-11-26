/**
 * Container names for migration workloads.
 *
 * These names are referenced in CloudWatch dashboard widgets:
 * - deployment/k8s/charts/aggregates/migrationAssistantWithArgo/files/cloudwatch-dashboards/reindex-from-snapshot-dashboard.json
 * - deployment/k8s/charts/aggregates/migrationAssistantWithArgo/files/cloudwatch-dashboards/capture-replay-dashboard.json
 *
 * If you change these, update the dashboard JSON files too.
 */
export const CONTAINER_NAMES = {
    BULK_LOADER: "bulk-loader",
    REPLAYER: "replayer",
    PROXY: "proxy",
} as const;
