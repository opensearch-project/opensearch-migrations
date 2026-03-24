export const K8S_RESOURCE_RETRY_STRATEGY = {
    limit: "6",
    retryPolicy: "Always",
    backoff: {duration: "10", factor: "2", cap: "60"}
};
