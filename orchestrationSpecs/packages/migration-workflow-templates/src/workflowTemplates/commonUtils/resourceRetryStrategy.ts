export const K8S_RESOURCE_RETRY_STRATEGY = {
    limit: "6",
    retryPolicy: "OnError",
    backoff: {duration: "10", factor: "2", cap: "60"}
};
