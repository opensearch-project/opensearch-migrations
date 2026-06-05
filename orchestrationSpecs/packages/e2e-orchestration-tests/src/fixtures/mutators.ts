/**
 * Mutator fixtures — named config transformations the runner applies
 * to produce a mutated submission.
 *
 * Each mutator declares its change class, dependency pattern, target
 * component, and the user-config paths it touches. The `apply` method
 * takes a parsed YAML config object and returns a deep-cloned copy
 * with the mutation applied.
 *
 * `MutatorRegistry` mirrors `ActorRegistry`: register, get, has, and
 * a selector-based lookup for matrix expansion.
 */

import { ChangeClass, ComponentId, DependencyPattern } from "../types";

export interface Mutator {
    name: string;
    /**
     * Field-level change class before lifecycle state is considered.
     * For example, SnapshotMigration maxConnections is gated while the
     * resource is still running, but becomes effectively impossible once
     * lock-on-complete seals a Completed resource.
     */
    fieldChangeClass?: ChangeClass;
    changeClass: ChangeClass;
    /** Human-readable reason when changeClass differs from fieldChangeClass. */
    effectiveChangeReason?: string;
    dependencyPattern: DependencyPattern;
    /** The component this mutation targets. */
    subject: ComponentId;
    /**
     * Components expected to re-run when this mutation is submitted.
     *
     * This is deliberately separate from ComponentTopology. Topology
     * tells us who depends on whom; reconfiguringWorkflows.md says
     * downstream re-execution is driven by checksum materiality. Until
     * transition-tree mapping is generated, mutators carry the small
     * amount of materiality metadata needed by the live runner.
     *
     * If omitted, the runner assumes only `subject` should re-run.
     */
    expectedRerunComponents?: readonly ComponentId[];
    /** Takes raw user-config YAML-parsed object, returns mutated copy. */
    apply(config: unknown): unknown;
    /** User-config paths this mutator touches. */
    changedPaths: readonly string[];
    /** Mutation ApprovalGate pattern used by gated/impossible response actions. */
    approvalPattern?: string;
    /** Reset command metadata for impossible response actions. */
    reset?: {
        all?: boolean;
        cascade?: boolean;
        includeProxies?: boolean;
        deleteStorage?: boolean;
        path?: string;
    };
}

export interface MutatorSelectorFilter {
    changeClass?: ChangeClass;
    pattern?: DependencyPattern;
}

export class MutatorRegistry {
    private readonly mutators = new Map<string, Mutator>();

    register(mutator: Mutator): void {
        if (this.mutators.has(mutator.name)) {
            throw new Error(`mutator '${mutator.name}' already registered`);
        }
        this.mutators.set(mutator.name, mutator);
    }

    get(name: string): Mutator | undefined {
        return this.mutators.get(name);
    }

    has(name: string): boolean {
        return this.mutators.has(name);
    }

    /**
     * Find all mutators targeting `subject` whose changeClass and
     * dependencyPattern match the optional filter fields.
     */
    findBySubjectAndSelector(
        subject: ComponentId,
        filter: MutatorSelectorFilter = {},
    ): Mutator[] {
        const results: Mutator[] = [];
        for (const m of this.mutators.values()) {
            if (m.subject !== subject) continue;
            if (filter.changeClass !== undefined && m.changeClass !== filter.changeClass) continue;
            if (filter.pattern !== undefined && m.dependencyPattern !== filter.pattern) continue;
            results.push(m);
        }
        return results;
    }
}

/**
 * proxy-numThreads — canonical safe mutator for `captureproxy:capture-proxy`.
 *
 * Mutates `traffic.proxies.capture-proxy.proxyConfig.numThreads`.
 * This field has no `.changeRestriction()` annotation in userSchemas.ts,
 * so it defaults to `safe`. The mutation changes the value from its
 * default (1) to 2, which the migration framework should accept as a
 * safe in-place update — no gate, no block.
 */
export function proxyNumThreadsMutator(): Mutator {
    return {
        name: "proxy-numThreads",
        changeClass: "safe",
        dependencyPattern: "subject-change",
        subject: "captureproxy:capture-proxy" as ComponentId,
        expectedRerunComponents: ["captureproxy:capture-proxy" as ComponentId],
        changedPaths: ["traffic.proxies.capture-proxy.proxyConfig.numThreads"],
        apply(config: unknown): unknown {
            const cloned = structuredClone(config);
            const root = cloned as Record<string, unknown>;
            const traffic = root["traffic"] as Record<string, unknown> | undefined;
            if (!traffic) throw new Error("proxy-numThreads mutator: missing 'traffic' key");
            const proxies = traffic["proxies"] as Record<string, unknown> | undefined;
            if (!proxies) throw new Error("proxy-numThreads mutator: missing 'traffic.proxies' key");
            const proxy = proxies["capture-proxy"] as Record<string, unknown> | undefined;
            if (!proxy) throw new Error("proxy-numThreads mutator: missing 'traffic.proxies.capture-proxy' key");
            const proxyConfig = proxy["proxyConfig"] as Record<string, unknown> | undefined;
            if (!proxyConfig) throw new Error("proxy-numThreads mutator: missing 'traffic.proxies.capture-proxy.proxyConfig' key");
            const current = typeof proxyConfig["numThreads"] === "number" ? proxyConfig["numThreads"] : 1;
            proxyConfig["numThreads"] = current === 2 ? 3 : 2;
            return cloned;
        },
    };
}

/**
 * snapshotMigrationMaxConnectionsMutator — completed-subject
 * lock-on-complete mutator for the reliable basic snapshot workflow.
 *
 * Mutates
 * `snapshotMigrationConfigs[0].perSnapshotConfig.snap1[0].documentBackfillConfig.maxConnections`.
 * The field-level rule is gated. When the SnapshotMigration has already
 * reached `Completed`, lock-on-complete seals the resource and makes
 * the effective change impossible without reset. Unlike DataSnapshot,
 * SnapshotMigration is already wrapped by the workflow's ApprovalGate
 * retry loop, so it is the right first live completed-subject target
 * for this test framework.
 */
export function snapshotMigrationMaxConnectionsMutator(): Mutator {
    return snapshotMigrationMaxConnectionsBaseMutator({
        name: "snapshotMigration-maxConnections",
        fieldChangeClass: "gated",
        changeClass: "impossible",
        effectiveChangeReason: "completed-subject-lock-on-complete",
        dependencyPattern: "subject-impossible-change",
    });
}

/**
 * Same user-config field as the terminal impossible mutator, but tagged
 * with its declared schema class for in-progress state-control cases.
 * The completed-subject case remains impossible because the terminal
 * SnapshotMigration spec is sealed after completion.
 */
export function snapshotMigrationMaxConnectionsGatedMutator(): Mutator {
    return snapshotMigrationMaxConnectionsBaseMutator({
        name: "snapshotMigration-maxConnections-gated",
        fieldChangeClass: "gated",
        changeClass: "gated",
        dependencyPattern: "subject-gated-change",
    });
}

function snapshotMigrationMaxConnectionsBaseMutator(opts: {
    name: string;
    fieldChangeClass?: ChangeClass;
    changeClass: ChangeClass;
    effectiveChangeReason?: string;
    dependencyPattern: DependencyPattern;
}): Mutator {
    return {
        name: opts.name,
        fieldChangeClass: opts.fieldChangeClass,
        changeClass: opts.changeClass,
        effectiveChangeReason: opts.effectiveChangeReason,
        dependencyPattern: opts.dependencyPattern,
        subject: "snapshotmigration:source-target-snap1-migration-0" as ComponentId,
        expectedRerunComponents: [
            "snapshotmigration:source-target-snap1-migration-0" as ComponentId,
        ],
        changedPaths: [
            "snapshotMigrationConfigs.0.perSnapshotConfig.snap1.0.documentBackfillConfig.maxConnections",
        ],
        approvalPattern: "snapshotmigration.source-target-snap1-migration-0",
        reset: {
            path: "source-target-snap1-migration-0",
            cascade: true,
        },
        apply(config: unknown): unknown {
            const cloned = structuredClone(config);
            const root = cloned as Record<string, unknown>;
            const configs = root["snapshotMigrationConfigs"];
            if (!Array.isArray(configs)) {
                throw new Error("snapshotMigration-maxConnections mutator: missing 'snapshotMigrationConfigs' array");
            }
            const firstConfig = configs[0] as Record<string, unknown> | undefined;
            if (!firstConfig) {
                throw new Error("snapshotMigration-maxConnections mutator: missing 'snapshotMigrationConfigs.0'");
            }
            const perSnapshotConfig = firstConfig["perSnapshotConfig"] as Record<string, unknown> | undefined;
            if (!perSnapshotConfig) {
                throw new Error("snapshotMigration-maxConnections mutator: missing 'snapshotMigrationConfigs.0.perSnapshotConfig'");
            }
            const snap1Configs = perSnapshotConfig["snap1"];
            if (!Array.isArray(snap1Configs)) {
                throw new Error("snapshotMigration-maxConnections mutator: missing 'snapshotMigrationConfigs.0.perSnapshotConfig.snap1' array");
            }
            const snap1 = snap1Configs[0] as Record<string, unknown> | undefined;
            if (!snap1) {
                throw new Error("snapshotMigration-maxConnections mutator: missing 'snapshotMigrationConfigs.0.perSnapshotConfig.snap1.0'");
            }
            const documentBackfillConfig = snap1["documentBackfillConfig"] as Record<string, unknown> | undefined;
            if (!documentBackfillConfig) {
                throw new Error("snapshotMigration-maxConnections mutator: missing 'snapshotMigrationConfigs.0.perSnapshotConfig.snap1.0.documentBackfillConfig'");
            }
            const current =
                typeof documentBackfillConfig["maxConnections"] === "number"
                    ? documentBackfillConfig["maxConnections"]
                    : 4;
            documentBackfillConfig["maxConnections"] = current === 5 ? 6 : 5;
            return cloned;
        },
    };
}

/**
 * dataSnapshotMaxSnapshotRateMutator — simple safe mutator for the
 * basic snapshot workflow's DataSnapshot subject. It changes the
 * create-snapshot rate limit, leaving the poison pill free to control
 * source/snapshot-repository availability. When the snapshot is still
 * in progress, the resulting snapshot checksum is material to the
 * downstream SnapshotMigration, so both components are expected to run.
 */
export function dataSnapshotMaxSnapshotRateMutator(): Mutator {
    return {
        name: "dataSnapshot-maxSnapshotRate",
        changeClass: "safe",
        dependencyPattern: "subject-change",
        subject: "datasnapshot:source-snap1" as ComponentId,
        expectedRerunComponents: [
            "datasnapshot:source-snap1" as ComponentId,
            "snapshotmigration:source-target-snap1-migration-0" as ComponentId,
        ],
        changedPaths: [
            "sourceClusters.source.snapshotInfo.snapshots.snap1.config.createSnapshotConfig.maxSnapshotRateMbPerNode",
        ],
        apply(config: unknown): unknown {
            const cloned = structuredClone(config);
            const root = cloned as Record<string, unknown>;
            const sourceClusters = root["sourceClusters"] as Record<string, unknown> | undefined;
            if (!sourceClusters) throw new Error("dataSnapshot-maxSnapshotRate mutator: missing 'sourceClusters'");
            const source = sourceClusters["source"] as Record<string, unknown> | undefined;
            if (!source) throw new Error("dataSnapshot-maxSnapshotRate mutator: missing 'sourceClusters.source'");
            const snapshotInfo = source["snapshotInfo"] as Record<string, unknown> | undefined;
            if (!snapshotInfo) throw new Error("dataSnapshot-maxSnapshotRate mutator: missing 'sourceClusters.source.snapshotInfo'");
            const snapshots = snapshotInfo["snapshots"] as Record<string, unknown> | undefined;
            if (!snapshots) throw new Error("dataSnapshot-maxSnapshotRate mutator: missing 'sourceClusters.source.snapshotInfo.snapshots'");
            const snap1 = snapshots["snap1"] as Record<string, unknown> | undefined;
            if (!snap1) throw new Error("dataSnapshot-maxSnapshotRate mutator: missing 'sourceClusters.source.snapshotInfo.snapshots.snap1'");
            const snapConfig = snap1["config"] as Record<string, unknown> | undefined;
            if (!snapConfig) throw new Error("dataSnapshot-maxSnapshotRate mutator: missing 'sourceClusters.source.snapshotInfo.snapshots.snap1.config'");
            const createSnapshotConfig =
                snapConfig["createSnapshotConfig"] as Record<string, unknown> | undefined;
            if (!createSnapshotConfig) {
                throw new Error("dataSnapshot-maxSnapshotRate mutator: missing 'sourceClusters.source.snapshotInfo.snapshots.snap1.config.createSnapshotConfig'");
            }
            const current =
                typeof createSnapshotConfig["maxSnapshotRateMbPerNode"] === "number"
                    ? createSnapshotConfig["maxSnapshotRateMbPerNode"]
                    : 0;
            createSnapshotConfig["maxSnapshotRateMbPerNode"] = current === 1 ? 2 : 1;
            return cloned;
        },
    };
}

/**
 * proxyClientAuthMutator — gated mutator for `captureproxy:capture-proxy`
 * that exercises capture-proxy mutual-TLS (clientAuth).
 *
 * Sets `proxyConfig.tls.clientAuth` with an inline trusted client CA PEM
 * (the form that regressed in #3069 / fixed in #3071). clientAuth lives in
 * the gated `tls` subtree of USER_PROXY_OPTIONS, so changing it is a gated
 * change: the workflow must surface the proxy retry/approval gate before
 * proceeding. This case also guards the underlying apply path — before the
 * fix, the resolved mTLS fields leaked into the CaptureProxy CR and the
 * upsert step failed outright, which this spec would catch as a non-terminal
 * failure rather than a gate.
 *
 * The mutation toggles `clientAuth.required` so a re-submission produces a
 * detectable diff in the gated subtree.
 */
export function proxyClientAuthMutator(): Mutator {
    const CLIENT_CA_PEM = [
        "-----BEGIN CERTIFICATE-----",
        "MIIBkTCB+wIJAJ2e2e2e2e2eMA0GCSqGSIb3DQEBCwUAMA0xCzAJBgNVBAYTAlVT",
        "-----END CERTIFICATE-----",
    ].join("\n");
    return {
        name: "proxy-clientAuth",
        fieldChangeClass: "gated",
        changeClass: "gated",
        dependencyPattern: "subject-gated-change",
        subject: "captureproxy:capture-proxy" as ComponentId,
        expectedRerunComponents: ["captureproxy:capture-proxy" as ComponentId],
        changedPaths: ["traffic.proxies.capture-proxy.proxyConfig.tls.clientAuth"],
        approvalPattern: "captureproxy.capture-proxy",
        apply(config: unknown): unknown {
            const cloned = structuredClone(config);
            const root = cloned as Record<string, unknown>;
            const traffic = root["traffic"] as Record<string, unknown> | undefined;
            if (!traffic) throw new Error("proxy-clientAuth mutator: missing 'traffic' key");
            const proxies = traffic["proxies"] as Record<string, unknown> | undefined;
            if (!proxies) throw new Error("proxy-clientAuth mutator: missing 'traffic.proxies' key");
            const proxy = proxies["capture-proxy"] as Record<string, unknown> | undefined;
            if (!proxy) throw new Error("proxy-clientAuth mutator: missing 'traffic.proxies.capture-proxy' key");
            const proxyConfig = proxy["proxyConfig"] as Record<string, unknown> | undefined;
            if (!proxyConfig) throw new Error("proxy-clientAuth mutator: missing 'traffic.proxies.capture-proxy.proxyConfig' key");
            const existingTls = proxyConfig["tls"] as Record<string, unknown> | undefined;
            const existingClientAuth = existingTls?.["clientAuth"] as Record<string, unknown> | undefined;
            // Toggle `required` so a resubmission of an already-mTLS proxy is a real diff.
            const required = existingClientAuth?.["required"] === true ? false : true;
            proxyConfig["tls"] = {
                mode: "existingSecret",
                secretName: "proxy-tls",
                clientAuth: {
                    required,
                    trustedClientCaPem: CLIENT_CA_PEM,
                },
            };
            return cloned;
        },
    };
}
