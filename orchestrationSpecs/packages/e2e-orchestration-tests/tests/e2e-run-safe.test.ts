import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";

import { runSafeCase, runExpandedCases, LiveRunnerDeps } from "../src/e2e-run";
import { buildTopology } from "../src/componentTopology";
import { WorkflowCli } from "../src/workflowCli";
import { K8sClient } from "../src/k8sClient";
import { ActorRegistry } from "../src/actors";
import { CaseSnapshot } from "../src/reportSchema";
import { ComponentId, ObservedComponent } from "../src/types";
import { ExpandedTestCase } from "../src/matrixExpander";
import { Mutator } from "../src/fixtures/mutators";

function readDetailSnapshot(reportPath: string): CaseSnapshot {
    const report = JSON.parse(fs.readFileSync(reportPath, "utf8")) as { detailPath: string };
    return JSON.parse(fs.readFileSync(report.detailPath, "utf8")) as CaseSnapshot;
}

/**
 * Topology for safe-case tests:
 *   kafka → capturedtraffic → captureproxy → datasnapshot
 *                                          → trafficreplay
 *   snapshotmigration (independent)
 *
 * Subject = captureproxy:capture-proxy
 * Downstream = datasnapshot:source-snap1, trafficreplay:capture-proxy-target-replay1
 * Independent = snapshotmigration:source-target-snap1-migration-0
 * Upstream = capturedtraffic:capture-proxy-topic, kafkacluster:default
 */
const SUBJECT = "captureproxy:capture-proxy" as ComponentId;
const KAFKA = "kafkacluster:default" as ComponentId;
const CAPTURED = "capturedtraffic:capture-proxy-topic" as ComponentId;
const SNAP = "datasnapshot:source-snap1" as ComponentId;
const REPLAY = "trafficreplay:capture-proxy-target-replay1" as ComponentId;
const SNAPMIG = "snapshotmigration:source-target-snap1-migration-0" as ComponentId;
const ALL = [KAFKA, CAPTURED, SUBJECT, SNAP, REPLAY, SNAPMIG];

const TOPOLOGY = buildTopology({
    components: ALL,
    edges: [
        { from: CAPTURED, to: KAFKA },
        { from: SUBJECT, to: CAPTURED },
        { from: SNAP, to: SUBJECT },
        { from: REPLAY, to: SUBJECT },
    ],
});

function safeMutator(): Mutator {
    return {
        name: "proxy-numThreads",
        changeClass: "safe",
        dependencyPattern: "subject-change",
        subject: SUBJECT,
        expectedRerunComponents: [SUBJECT],
        changedPaths: ["traffic.proxies.capture-proxy.proxyConfig.numThreads"],
        apply(config: unknown): unknown {
            const c = structuredClone(config) as Record<string, unknown>;
            // Just mark it as mutated for test detection
            c["__mutated"] = true;
            return c;
        },
    };
}

function expandedCase(overrides: Partial<ExpandedTestCase> = {}): ExpandedTestCase {
    const mutator = safeMutator();
    return {
        caseName: "captureproxy-capture-proxy-subject-change-proxy-numThreads",
        subject: SUBJECT,
        mutator,
        expectedRerunComponents: mutator.expectedRerunComponents ?? [SUBJECT],
        changedPaths: mutator.changedPaths,
        response: null,
        ...overrides,
    };
}

/**
 * Build injected deps for safe-case tests. `observationFactory` controls
 * what each readObservations call returns, keyed by call count.
 */
function makeRunnerTestDeps(opts: {
    observationFactory?: (callCount: number) => Record<ComponentId, ObservedComponent>;
} = {}): {
    deps: LiveRunnerDeps;
    calls: { args: readonly string[]; input?: string }[];
    tmpDir: string;
} {
    const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "safe-run-"));
    const baselinePath = path.join(tmpDir, "baseline.wf.yaml");
    // Minimal YAML that the mutator can parse
    fs.writeFileSync(baselinePath, "proxy:\n  captureConfig: {}\n", "utf8");

    const calls: { args: readonly string[]; input?: string }[] = [];
    const workflowCli = new WorkflowCli({
        runner: (args, runOpts) => {
            calls.push({ args: [...args], input: runOpts?.input });
            return { stdout: "", stderr: "", exitCode: 0 };
        },
        namespace: "ma",
    });

    const k8sClient = new K8sClient({
        namespace: "ma",
        runner: () => ({ stdout: "", stderr: "", exitCode: 0 }),
    });

    let callCount = 0;
    const defaultFactory = (): Record<ComponentId, ObservedComponent> => {
        const components: Record<ComponentId, ObservedComponent> = {};
        for (const c of ALL) {
            components[c] = {
                componentId: c,
                phase: "Ready",
                configChecksum: `cs-${c}`,
                uid: `uid-${c}`,
            };
        }
        return components;
    };

    const factory = opts.observationFactory ?? defaultFactory;

    const readObservations: LiveRunnerDeps["readObservations"] = async () => {
        callCount += 1;
        return { components: factory(callCount) };
    };

    let suffixSeq = 0;
    const deps: LiveRunnerDeps = {
        workflowCli,
        k8sClient,
        namespace: "ma",
        readObservations,
        topology: TOPOLOGY,
        actorRegistry: new ActorRegistry(),
        spec: {
            baseConfig: "./baseline.wf.yaml",
            phaseCompletionTimeoutSeconds: 5,
            matrix: { subject: SUBJECT },
            lifecycle: { setup: [], teardown: [] },
            approvalGates: [],
            fixtures: {},
        },
        specPath: path.join(tmpDir, "test.yaml"),
        baselineConfigPath: baselinePath,
        outputDir: path.join(tmpDir, "snapshots"),
        workflowNameSuffix: () => `s${++suffixSeq}`,
    };
    return { deps, calls, tmpDir };
}

describe("runSafeCase — happy path", () => {
    it("runs 4 submissions and writes a passed snapshot with all runs", async () => {
        // Observations: baseline (calls 1-2), noop-pre (3-4), mutated (5-6), noop-post (7-8).
        // For noop-pre and noop-post to pass, checksums must be unchanged from prior.
        // `proxy-numThreads` is operational: only the proxy's self-checksum is material.
        // Downstream snapshots/replays should stay skipped.
        const { deps, calls, tmpDir } = makeRunnerTestDeps({
            observationFactory: (n) => {
                const components: Record<ComponentId, ObservedComponent> = {};
                // Calls 1-2: baseline (phase-wait + final read)
                // Calls 3-4: noop-pre — same checksums as baseline → skipped
                // Calls 5-6: mutated — subject+downstream change, independent stays
                // Calls 7-8: noop-post — same as mutated → skipped
                const isMutatedOrAfter = n >= 5;
                for (const c of ALL) {
                    const cs = isMutatedOrAfter && c === SUBJECT
                        ? `mutated-cs-${c}`
                        : `baseline-cs-${c}`;
                    components[c] = {
                        componentId: c,
                        phase: "Ready",
                        configChecksum: cs,
                        uid: `uid-${c}`,
                    };
                }
                return components;
            },
        });

        try {
            const outPath = await runSafeCase(deps, expandedCase());
            const snap: CaseSnapshot = readDetailSnapshot(outPath);

            expect(snap.outcome).toBe("passed");
            expect(Object.keys(snap.runs).sort()).toEqual(
                ["baseline", "mutated", "noop-post", "noop-pre"],
            );
            expect(snap.violations).toEqual([]);

            // 4 configure + 4 submit calls
            const configures = calls.filter(c => c.args[0] === "configure");
            const submits = calls.filter(c => c.args[0] === "submit");
            expect(configures).toHaveLength(4);
            expect(submits).toHaveLength(4);

            const configureEvents = snap.events.filter((e) => e.action === "configure");
            expect(configureEvents).toHaveLength(4);
            expect(configureEvents.every((e) => e.configSha256?.length === 64)).toBe(true);
            expect(configureEvents[0].configSha256).toBe(configureEvents[1].configSha256);
            expect(configureEvents[2].configSha256).toBe(configureEvents[3].configSha256);
            expect(configureEvents[2].configSha256).not.toBe(configureEvents[0].configSha256);
        } finally {
            fs.rmSync(tmpDir, { recursive: true, force: true });
        }
    });

    it("rejects a schema-invalid mutated config when validation is enabled", async () => {
        const { deps, tmpDir } = makeRunnerTestDeps();
        deps.validateMutatedConfig = true;

        try {
            await expect(runSafeCase(deps, expandedCase())).rejects.toThrow(
                /failed OVERALL_MIGRATION_CONFIG validation/,
            );
        } finally {
            fs.rmSync(tmpDir, { recursive: true, force: true });
        }
    });
});

describe("runSafeCase — cascade violation", () => {
    it("flags cascade when a material downstream component stayed skipped", async () => {
        const { deps, tmpDir } = makeRunnerTestDeps({
            observationFactory: (n) => {
                const components: Record<ComponentId, ObservedComponent> = {};
                const isMutatedOrAfter = n >= 5;
                for (const c of ALL) {
                    // Only subject changes checksum; downstream stays same → skipped
                    const isSubject = c === SUBJECT;
                    const cs = isMutatedOrAfter && isSubject
                        ? `mutated-cs-${c}`
                        : `baseline-cs-${c}`;
                    components[c] = {
                        componentId: c,
                        phase: "Ready",
                        configChecksum: cs,
                        uid: `uid-${c}`,
                    };
                }
                return components;
            },
        });

        try {
            const outPath = await runSafeCase(deps, expandedCase({
                expectedRerunComponents: [SUBJECT, SNAP],
            }));
            const snap: CaseSnapshot = readDetailSnapshot(outPath);

            expect(snap.outcome).toBe("partial");
            const cascadeViolations = snap.violations.filter(v => v.type === "cascade");
            expect(cascadeViolations.length).toBeGreaterThanOrEqual(1);
            // SNAP was declared material to this changed path.
            const cascadeIds = cascadeViolations.map(v => v.componentId);
            expect(cascadeIds).toEqual(expect.arrayContaining([SNAP]));
        } finally {
            fs.rmSync(tmpDir, { recursive: true, force: true });
        }
    });
});

describe("runSafeCase — independence violation", () => {
    it("flags independence when an independent component reran", async () => {
        const { deps, tmpDir } = makeRunnerTestDeps({
            observationFactory: (n) => {
                const components: Record<ComponentId, ObservedComponent> = {};
                const isMutatedOrAfter = n >= 5;
                for (const c of ALL) {
                    const isIndependent = c === SNAPMIG;
                    // Subject changes correctly, but independent also changes (wrong).
                    const cs = isMutatedOrAfter && (c === SUBJECT || isIndependent)
                        ? `mutated-cs-${c}`
                        : `baseline-cs-${c}`;
                    components[c] = {
                        componentId: c,
                        phase: "Ready",
                        configChecksum: cs,
                        uid: `uid-${c}`,
                    };
                }
                return components;
            },
        });

        try {
            const outPath = await runSafeCase(deps, expandedCase());
            const snap: CaseSnapshot = readDetailSnapshot(outPath);

            expect(snap.outcome).toBe("partial");
            const indViolations = snap.violations.filter(v => v.type === "independence");
            expect(indViolations).toHaveLength(1);
            expect(indViolations[0].componentId).toBe(SNAPMIG);
        } finally {
            fs.rmSync(tmpDir, { recursive: true, force: true });
        }
    });
});

describe("runSafeCase — noop-post catches residual state", () => {
    it("noop-post flags spurious reran even if mutated-complete passed", async () => {
        const { deps, tmpDir } = makeRunnerTestDeps({
            observationFactory: (n) => {
                const components: Record<ComponentId, ObservedComponent> = {};
                const isMutatedOrAfter = n >= 5;
                // noop-post reads are calls 7-8; make checksums change again
                const isNoopPost = n >= 7;
                for (const c of ALL) {
                    let cs: string;
                    if (isNoopPost && c === SUBJECT) {
                        // Checksums change again at noop-post → reran → violation
                        cs = `post-cs-${c}`;
                    } else if (isMutatedOrAfter && c === SUBJECT) {
                        cs = `mutated-cs-${c}`;
                    } else {
                        cs = `baseline-cs-${c}`;
                    }
                    components[c] = {
                        componentId: c,
                        phase: "Ready",
                        configChecksum: cs,
                        uid: `uid-${c}`,
                    };
                }
                return components;
            },
        });

        try {
            const outPath = await runSafeCase(deps, expandedCase());
            const snap: CaseSnapshot = readDetailSnapshot(outPath);

            expect(snap.outcome).toBe("partial");
            // noop-post should have noop-not-skipped violations
            const noopPostViolations = snap.runs["noop-post"].checkpoints[0].violations;
            expect(noopPostViolations.length).toBeGreaterThan(0);
            expect(noopPostViolations.every(v => v.type === "noop-not-skipped")).toBe(true);
        } finally {
            fs.rmSync(tmpDir, { recursive: true, force: true });
        }
    });
});

describe("runExpandedCases", () => {
    it("writes one snapshot per expanded case and returns every path", async () => {
        // Build deps whose spec triggers expansion to a single case
        // via a mutator registry we control. We use the existing
        // makeRunnerTestDeps happy-path observations so every case passes.
        const { deps, tmpDir } = makeRunnerTestDeps();
        try {
            const { runExpandedCases } = await import("../src/e2e-run");
            const { MutatorRegistry } = await import("../src/fixtures/mutators");
            const registry = new MutatorRegistry();
            registry.register(safeMutator());

            const paths = await runExpandedCases(deps, registry);
            expect(paths).toHaveLength(1);
            expect(fs.existsSync(paths[0])).toBe(true);

            const snap: CaseSnapshot = readDetailSnapshot(paths[0]);
            expect(snap.case).toBe(
                "captureproxy-capture-proxy-subject-change-proxy-numThreads",
            );
        } finally {
            fs.rmSync(tmpDir, { recursive: true, force: true });
        }
    });

    it("writes one snapshot per case when the registry has multiple mutators for the subject", async () => {
        const { deps, tmpDir } = makeRunnerTestDeps();
        try {
            const { MutatorRegistry } = await import("../src/fixtures/mutators");

            const secondMutator: Mutator = {
                ...safeMutator(),
                name: "proxy-otherKnob",
                changedPaths: ["traffic.proxies.capture-proxy.proxyConfig.otherKnob"],
            };

            const registry = new MutatorRegistry();
            registry.register(safeMutator());
            registry.register(secondMutator);

            const paths = await runExpandedCases(deps, registry);
            expect(paths).toHaveLength(2);
            const names = paths.map((p) => path.basename(p, ".json"));
            expect(names.sort()).toEqual([
                "captureproxy-capture-proxy-subject-change-proxy-numThreads",
                "captureproxy-capture-proxy-subject-change-proxy-otherKnob",
            ]);
        } finally {
            fs.rmSync(tmpDir, { recursive: true, force: true });
        }
    });

    it("runs a gated approve case through before-approval and after-approval checkpoints", async () => {
        const { deps, calls, tmpDir } = makeRunnerTestDeps({
            observationFactory: (n) => {
                const components: Record<ComponentId, ObservedComponent> = {};
                const beforeApproval = n >= 5 && n <= 6;
                const afterApproval = n >= 7;
                for (const c of ALL) {
                    components[c] = {
                        componentId: c,
                        phase: beforeApproval && c === SUBJECT
                            ? "Paused"
                            : c === SNAP || c === REPLAY
                                ? "Skipped"
                                : "Ready",
                        configChecksum: afterApproval && c === SUBJECT
                            ? `approved-cs-${c}`
                            : `baseline-cs-${c}`,
                        uid: `uid-${c}`,
                    };
                }
                return components;
            },
        });
        deps.spec.matrix.select = [
            {
                changeClass: "gated",
                patterns: ["subject-gated-change"],
                response: "approve",
            },
        ];

        const gatedMutator: Mutator = {
            ...safeMutator(),
            name: "proxy-gated-toggle",
            changeClass: "gated",
            dependencyPattern: "subject-gated-change",
            changedPaths: ["traffic.proxies.capture-proxy.proxyConfig.enabled"],
            approvalPattern: "capture-proxy.captureproxy.vapretry",
        };

        try {
            const { MutatorRegistry } = await import("../src/fixtures/mutators");
            const registry = new MutatorRegistry();
            registry.register(gatedMutator);

            const paths = await runExpandedCases(deps, registry);
            const snap = readDetailSnapshot(paths[0]);

            expect(snap.outcome).toBe("passed");
            expect(snap.runs["mutated"].checkpoints.map((cp) => cp.checkpoint)).toEqual([
                "before-approval",
                "after-approval",
            ]);
            expect(calls.map((c) => c.args[0])).toEqual([
                "configure",
                "submit",
                "configure",
                "submit",
                "configure",
                "submit",
                "approve",
                "configure",
                "submit",
            ]);
            expect(calls.find((c) => c.args[0] === "approve")?.args).toEqual([
                "approve",
                "capture-proxy.captureproxy.vapretry",
                "--namespace",
                "ma",
            ]);
        } finally {
            fs.rmSync(tmpDir, { recursive: true, force: true });
        }
    });

    it("runs an impossible leave-blocked case through the on-blocked checkpoint", async () => {
        const { deps, calls, tmpDir } = makeRunnerTestDeps({
            observationFactory: (n) => {
                const components: Record<ComponentId, ObservedComponent> = {};
                const mutated = n >= 5;
                for (const c of ALL) {
                    components[c] = {
                        componentId: c,
                        phase: mutated && c === SNAPMIG ? "Blocked" : "Ready",
                        configChecksum: `baseline-cs-${c}`,
                        uid: `uid-${c}`,
                    };
                }
                return components;
            },
        });
        deps.spec.matrix.subject = SNAPMIG;
        deps.spec.matrix.select = [
            {
                changeClass: "impossible",
                patterns: ["subject-impossible-change"],
                response: "leave-blocked",
            },
        ];

        const impossibleMutator: Mutator = {
            ...safeMutator(),
            name: "snapshotMigration-maxConnections",
            changeClass: "impossible",
            dependencyPattern: "subject-impossible-change",
            subject: SNAPMIG,
            expectedRerunComponents: [SNAPMIG],
            changedPaths: [
                "snapshotMigrationConfigs.0.perSnapshotConfig.snap1.0.documentBackfillConfig.maxConnections",
            ],
        };

        try {
            const { MutatorRegistry } = await import("../src/fixtures/mutators");
            const registry = new MutatorRegistry();
            registry.register(impossibleMutator);

            const paths = await runExpandedCases(deps, registry);
            const snap = readDetailSnapshot(paths[0]);

            expect(snap.outcome).toBe("passed");
            expect(Object.keys(snap.runs)).toEqual(["baseline", "noop-pre", "mutated"]);
            expect(snap.runs["mutated"].checkpoints.map((cp) => cp.checkpoint)).toEqual([
                "on-blocked",
            ]);
            expect(calls.map((c) => c.args[0])).toEqual([
                "configure",
                "submit",
                "configure",
                "submit",
                "configure",
                "submit",
            ]);
        } finally {
            fs.rmSync(tmpDir, { recursive: true, force: true });
        }
    });
});
