/**
 * Lifecycle actors — named side-effect callbacks the runner invokes at
 * spec-declared points (`lifecycle.setup`, `lifecycle.teardown`). This
 * is the minimum actor plumbing needed for teardown-on-failure in the
 * live runner. It deliberately does not implement the full Observer /
 * Checker / Provider fixture taxonomy from the design doc.
 *
 * Actors are looked up by name in an `ActorRegistry`. The runner passes
 * an `ActorContext` to each actor at invocation time. Failures during
 * teardown are captured as diagnostics and do **not** mask the original
 * error that triggered teardown.
 */

import { WorkflowCli } from "./workflowCli";
import { K8sClient } from "./k8sClient";

/**
 * Context handed to every actor call. More fields can be added as
 * actors need them — k8sClient and workflowCli cover the current
 * envelope.
 */
export interface ActorContext {
    workflowCli: WorkflowCli;
    k8sClient: K8sClient;
    namespace: string;
    /** Absolute path to the baseline config file for this run. */
    baselineConfigPath: string;
    /** Human-readable label of the phase this actor was invoked for. */
    phase: "setup" | "teardown";
}

export interface Actor {
    name: string;
    run(ctx: ActorContext): Promise<void>;
}

export class ActorRegistry {
    private readonly actors = new Map<string, Actor>();

    constructor(actors: readonly Actor[] = []) {
        for (const a of actors) this.register(a);
    }

    register(actor: Actor): void {
        if (this.actors.has(actor.name)) {
            throw new Error(`actor '${actor.name}' already registered`);
        }
        this.actors.set(actor.name, actor);
    }

    get(name: string): Actor | undefined {
        return this.actors.get(name);
    }

    has(name: string): boolean {
        return this.actors.has(name);
    }

    /**
     * Resolve an ordered list of actor names to the registered actors.
     * Throws on unknown names so typos fail fast rather than silently
     * skipping lifecycle actions.
     */
    resolveAll(names: readonly string[]): Actor[] {
        const missing = names.filter((n) => !this.actors.has(n));
        if (missing.length > 0) {
            throw new Error(
                `unknown actor(s) in lifecycle: ${missing.join(", ")}`,
            );
        }
        return names.map((n) => this.actors.get(n)!);
    }

    /**
     * Invoke each actor in order. Errors are accumulated into the
     * returned `diagnostics` list and, unless `stopOnError` is true,
     * the remaining actors still run. This is used for teardown:
     * partial cleanup is better than none.
     */
    async runAll(
        actors: readonly Actor[],
        ctx: ActorContext,
        opts: { stopOnError?: boolean } = {},
    ): Promise<{ diagnostics: string[] }> {
        const diagnostics: string[] = [];
        for (const actor of actors) {
            try {
                await actor.run(ctx);
            } catch (e) {
                const msg = (e as Error).message ?? String(e);
                diagnostics.push(`${ctx.phase} actor '${actor.name}' failed: ${msg}`);
                if (opts.stopOnError) break;
            }
        }
        return { diagnostics };
    }
}
