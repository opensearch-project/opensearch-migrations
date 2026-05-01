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
    /** Takes raw user-config YAML-parsed object, returns mutated copy. */
    apply(config: unknown): unknown;
    /** User-config paths this mutator touches. */
    changedPaths: readonly string[];
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
