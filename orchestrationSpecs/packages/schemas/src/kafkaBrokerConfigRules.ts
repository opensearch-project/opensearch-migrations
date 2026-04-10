/*
 * These rules are intentionally hardcoded because Strimzi does not expose them
 * as a machine-readable schema for `Kafka.spec.kafka.config`.
 *
 * Source of truth:
 * - Strimzi operator docs for `Kafka.spec.kafka.config`
 * - specifically the documented forbidden prefixes / exact keys and the small
 *   set of documented exceptions that Strimzi still forwards to Kafka
 *
 * Important limitation:
 * - Kafka broker definitions can be re-derived from Kafka itself
 * - Strimzi policy cannot currently be re-derived from the CRD/model because
 *   the CRD leaves `spec.kafka.config` open-ended
 * - that means Strimzi upgrades will require a human review of these rules
 *   against the Strimzi docs and, ideally, real operator behavior
 *
 * Honest assessment:
 * - this is maintainable, but it is not free
 * - Kafka version bumps are mostly mechanical once the generator exists
 * - Strimzi version bumps remain a policy-review task with some regression risk
 *   because upstream does not publish this contract as a tight schema
 */

export const STRIMZI_DOCUMENTED_FORBIDDEN_PREFIXES = [
    "advertised.",
    "authorizer.",
    "broker.",
    "controller",
    "cruise.control.metrics.reporter.bootstrap.",
    "cruise.control.metrics.topic",
    "listener.",
    "listeners.",
    "password.",
    "sasl.",
    "security.",
    "ssl.",
] as const;

export const STRIMZI_DOCUMENTED_FORBIDDEN_EXACT_KEYS = new Set([
    "host.name",
    "inter.broker.listener.name",
    "listeners",
    "log.dir",
    "log.dirs",
    "node.id",
    "port",
    "process.roles",
    "super.user",
    "super.users",
]);

export const STRIMZI_DOCUMENTED_EXCEPTION_EXACT_KEYS = new Set([
    "controller.quorum.election.backoff.max.ms",
    "controller.quorum.election.timeout.ms",
    "controller.quorum.fetch.timeout.ms",
    "cruise.control.metrics.topic.auto.create.retries",
    "cruise.control.metrics.topic.auto.create.timeout.ms",
    "cruise.control.metrics.topic.min.insync.replicas",
    "cruise.control.metrics.topic.num.partitions",
    "cruise.control.metrics.topic.replication.factor",
    "cruise.control.metrics.topic.retention.ms",
    "ssl.cipher.suites",
    "ssl.enabled.protocols",
    "ssl.protocol",
]);

export const STRIMZI_DOCUMENTED_EXCEPTION_LISTENER_SUFFIXES = [
    "connections.max.reauth.ms",
    "max.connection.creation.rate",
    "max.connections",
    "max.connections.per.ip",
    "max.connections.per.ip.overrides",
] as const;

export function isDocumentedStrimziExceptionListenerKey(key: string) {
    const prefix = "listener.name.";
    if (!key.startsWith(prefix)) {
        return false;
    }
    const suffix = key.slice(prefix.length);
    const lastDot = suffix.indexOf(".");
    if (lastDot === -1) {
        return false;
    }
    const configSuffix = suffix.slice(lastDot + 1);
    return STRIMZI_DOCUMENTED_EXCEPTION_LISTENER_SUFFIXES.includes(configSuffix as typeof STRIMZI_DOCUMENTED_EXCEPTION_LISTENER_SUFFIXES[number]);
}

export function isStrimziAllowedKafkaBrokerConfigKey(key: string) {
    if (STRIMZI_DOCUMENTED_EXCEPTION_EXACT_KEYS.has(key) || isDocumentedStrimziExceptionListenerKey(key)) {
        return true;
    }
    if (STRIMZI_DOCUMENTED_FORBIDDEN_EXACT_KEYS.has(key)) {
        return false;
    }
    return !STRIMZI_DOCUMENTED_FORBIDDEN_PREFIXES.some(prefix => key.startsWith(prefix));
}
