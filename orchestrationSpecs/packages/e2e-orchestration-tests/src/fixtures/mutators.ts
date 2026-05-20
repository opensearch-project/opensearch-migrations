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
    changeClass: ChangeClass;
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
 * snapshotMigrationMaxConnectionsMutator — minimal impossible mutator
 * for the reliable basic snapshot workflow.
 *
 * Mutates
 * `snapshotMigrationConfigs[0].perSnapshotConfig.snap1[0].documentBackfillConfig.maxConnections`.
 * The field is operationally simple, but SnapshotMigration is a
 * terminal resource; once it reaches `Completed`, lock-on-complete
 * semantics make any spec change impossible without reset. Unlike
 * DataSnapshot, SnapshotMigration is already wrapped by the workflow's
 * ApprovalGate retry loop, so it is the right first live impossible
 * target for this test framework.
 */
export function snapshotMigrationMaxConnectionsMutator(): Mutator {
    return {
        name: "snapshotMigration-maxConnections",
        changeClass: "impossible",
        dependencyPattern: "subject-impossible-change",
        subject: "snapshotmigration:source-target-snap1-migration-0" as ComponentId,
        expectedRerunComponents: [
            "snapshotmigration:source-target-snap1-migration-0" as ComponentId,
        ],
        changedPaths: [
            "snapshotMigrationConfigs.0.perSnapshotConfig.snap1.0.documentBackfillConfig.maxConnections",
        ],
        approvalPattern: "source-target-snap1-migration-0.vapretry",
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
