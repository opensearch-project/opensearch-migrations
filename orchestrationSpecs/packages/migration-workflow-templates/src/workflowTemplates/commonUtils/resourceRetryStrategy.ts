export const K8S_RESOURCE_RETRY_STRATEGY = {
    limit: "6",
    retryPolicy: "OnError",
    backoff: {duration: "10", factor: "2", cap: "60"}
};

// For Argo resource-template waits, limit is the number of replacement attempts
// after the wait pod exits; it is not the normal elapsed wait duration. A single
// attempt can continue watching until its resource condition succeeds or fails.
// Keep this high enough for pod eviction/API blips, but do not use huge values
// as a substitute for an intentional timeout policy.
export const K8S_INDEFINITE_RESOURCE_WAIT_RETRY_STRATEGY = {
    limit: "20",
    retryPolicy: "Always",
    backoff: {duration: "10", factor: "2", cap: "120"}
};

// User approval waits are intentionally open-ended in elapsed time. These
// values are only the retry budget for replacing the wait pod after eviction or
// transient API interruption, and are deliberately independent from resource
// dependency wait settings.
export const K8S_USER_APPROVAL_WAIT_RETRY_STRATEGY = {
    limit: "20",
    retryPolicy: "Always",
    backoff: {duration: "15", factor: "2", cap: "180"}
};

export const K8S_INFRA_READY_TIMEOUT_SECONDS = 20 * 60;

// Leaf infrastructure readiness should be bounded: Kafka, topics, certs,
// secrets, and deployments should either become ready or expose a real rollout
// issue instead of turning into an indefinite dependency wait.
export const K8S_INFRA_READY_RETRY_STRATEGY = {
    limit: "6",
    retryPolicy: "Always",
    backoff: {duration: "10", factor: "2", cap: "60"}
};

export const K8S_SECRET_READY_RETRY_STRATEGY = {
    limit: "10",
    retryPolicy: "Always",
    backoff: {duration: "5", factor: "2", cap: "30"}
};

export const CERT_MANAGER_WEBHOOK_RETRY_STRATEGY = {
    limit: "3",
    retryPolicy: "Always",
    expression: "lastRetry.message matches 'failed calling webhook|no endpoints available|connection refused|context deadline exceeded|Client\\\\.Timeout exceeded|net/http: request canceled'",
    backoff: {duration: "10", factor: "2", cap: "60"}
};

// For one-shot .addContainer templates. Do NOT apply to runMigrationCommandForStatus —
// it is wrapped by Always/limit:200 steps-templates and nesting compounds retries.
export const CONTAINER_TEMPLATE_RETRY_STRATEGY = {
    limit: "3",
    retryPolicy: "OnError",
    backoff: {duration: "10", factor: "2", cap: "60"}
};
