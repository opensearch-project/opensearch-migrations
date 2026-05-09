export const K8S_RESOURCE_RETRY_STRATEGY = {
    limit: "6",
    retryPolicy: "OnError",
    backoff: {duration: "10", factor: "2", cap: "60"}
};

// For one-shot .addContainer templates. Do NOT apply to runMigrationCommandForStatus —
// it is wrapped by Always/limit:200 steps-templates and nesting compounds retries.
export const CONTAINER_TEMPLATE_RETRY_STRATEGY = {
    limit: "3",
    retryPolicy: "OnError",
    backoff: {duration: "10", factor: "2", cap: "60"}
};
