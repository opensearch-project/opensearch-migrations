/**
 * Retry strategy for resource steps that POLL a transient condition.
 *
 * Use when a template watches for successCondition/failureCondition on a
 * state that is expected to be temporarily unmet during normal startup
 * (e.g. Kafka NotReady, Deployment readyReplicas == 0, cert not yet issued).
 *
 * retryPolicy "Always" is required because Argo's "OnError" does NOT retry
 * when a failureCondition evaluates true (exit code 64) — it treats that as
 * a deliberate failure. Polling templates need to retry through those
 * transient states.
 */
export const K8S_POLLING_RETRY_STRATEGY = {
    limit: "6",
    retryPolicy: "Always",
    backoff: {duration: "10", factor: "2", cap: "60"}
};

/**
 * Retry strategy for resource CRUD operations (apply, get, patch, delete).
 *
 * retryPolicy "OnError" retries only on transient infrastructure errors
 * (API timeouts, network blips). It does NOT retry on failureCondition
 * matches or non-zero exit codes — this is intentional so that VAP
 * rejections (403) fail fast and route to the approval/suspend flow.
 */
export const K8S_RESOURCE_RETRY_STRATEGY = {
    limit: "6",
    retryPolicy: "OnError",
    backoff: {duration: "10", factor: "2", cap: "60"}
};
