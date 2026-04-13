// Short retry for resource CRUD (apply, get, patch, delete).
// Fails fast (~4 min) so misconfigurations surface quickly.
export const K8S_RESOURCE_RETRY_STRATEGY = {
    limit: "6",
    retryPolicy: "Always",
    backoff: {duration: "10", factor: "2", cap: "60"}
};

// Long retry for operations that poll a successCondition (example: waiting for
// Kafka readiness, pod rollout, CRD status transitions). These legitimately
// take minutes and should not fail prematurely.
export const K8S_LONG_RUNNING_RETRY_STRATEGY = {
    limit: "60",
    retryPolicy: "Always",
    backoff: {duration: "10", factor: "2", cap: "60"}
};
