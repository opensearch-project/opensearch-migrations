import { ComponentId } from "../src/types";
import {
    Mutator,
    MutatorRegistry,
    proxyNumThreadsMutator,
    snapshotMigrationMaxConnectionsMutator,
} from "../src/fixtures/mutators";

function fakeMutator(overrides: Partial<Mutator> = {}): Mutator {
    return {
        name: "test-mutator",
        changeClass: "safe",
        dependencyPattern: "subject-change",
        subject: "captureproxy:capture-proxy" as ComponentId,
        changedPaths: ["some.path"],
        apply: (config) => structuredClone(config),
        ...overrides,
    };
}

describe("MutatorRegistry", () => {
    it("registers and retrieves a mutator by name", () => {
        const reg = new MutatorRegistry();
        const m = fakeMutator();
        reg.register(m);
        expect(reg.get("test-mutator")).toBe(m);
        expect(reg.has("test-mutator")).toBe(true);
    });

    it("rejects duplicate registration", () => {
        const reg = new MutatorRegistry();
        reg.register(fakeMutator());
        expect(() => reg.register(fakeMutator())).toThrow(/already registered/);
    });

    it("returns undefined for unknown names", () => {
        const reg = new MutatorRegistry();
        expect(reg.get("nope")).toBeUndefined();
        expect(reg.has("nope")).toBe(false);
    });

    it("findBySubjectAndSelector matches on subject", () => {
        const reg = new MutatorRegistry();
        const m1 = fakeMutator({ name: "m1", subject: "captureproxy:capture-proxy" as ComponentId });
        const m2 = fakeMutator({ name: "m2", subject: "trafficreplay:replay1" as ComponentId });
        reg.register(m1);
        reg.register(m2);
        const found = reg.findBySubjectAndSelector("captureproxy:capture-proxy" as ComponentId);
        expect(found).toEqual([m1]);
    });

    it("findBySubjectAndSelector filters by changeClass", () => {
        const reg = new MutatorRegistry();
        const safe = fakeMutator({ name: "safe-m", changeClass: "safe" });
        const gated = fakeMutator({ name: "gated-m", changeClass: "gated" });
        reg.register(safe);
        reg.register(gated);
        const found = reg.findBySubjectAndSelector(
            "captureproxy:capture-proxy" as ComponentId,
            { changeClass: "safe" },
        );
        expect(found).toEqual([safe]);
    });

    it("findBySubjectAndSelector filters by pattern", () => {
        const reg = new MutatorRegistry();
        const sc = fakeMutator({ name: "sc", dependencyPattern: "subject-change" });
        const idc = fakeMutator({ name: "idc", dependencyPattern: "immediate-dependent-change" });
        reg.register(sc);
        reg.register(idc);
        const found = reg.findBySubjectAndSelector(
            "captureproxy:capture-proxy" as ComponentId,
            { pattern: "subject-change" },
        );
        expect(found).toEqual([sc]);
    });

    it("findBySubjectAndSelector filters by both changeClass and pattern", () => {
        const reg = new MutatorRegistry();
        const m1 = fakeMutator({ name: "m1", changeClass: "safe", dependencyPattern: "subject-change" });
        const m2 = fakeMutator({ name: "m2", changeClass: "gated", dependencyPattern: "subject-change" });
        reg.register(m1);
        reg.register(m2);
        const found = reg.findBySubjectAndSelector(
            "captureproxy:capture-proxy" as ComponentId,
            { changeClass: "safe", pattern: "subject-change" },
        );
        expect(found).toEqual([m1]);
    });
});

describe("proxyNumThreadsMutator", () => {
    const mutator = proxyNumThreadsMutator();

    it("has the expected metadata", () => {
        expect(mutator.name).toBe("proxy-numThreads");
        expect(mutator.changeClass).toBe("safe");
        expect(mutator.dependencyPattern).toBe("subject-change");
        expect(mutator.subject).toBe("captureproxy:capture-proxy");
        expect(mutator.expectedRerunComponents).toEqual([
            "captureproxy:capture-proxy",
        ]);
        expect(mutator.changedPaths).toEqual([
            "traffic.proxies.capture-proxy.proxyConfig.numThreads",
        ]);
    });

    it("apply() returns a cloned config that differs from the input", () => {
        const input = {
            traffic: {
                proxies: {
                    "capture-proxy": {
                        proxyConfig: { numThreads: 1, listenPort: 9201 },
                    },
                },
            },
        };
        const result = mutator.apply(input) as any;
        // Input unchanged
        expect(input.traffic.proxies["capture-proxy"].proxyConfig.numThreads).toBe(1);
        // Result mutated
        expect(result.traffic.proxies["capture-proxy"].proxyConfig.numThreads).toBe(2);
        // Other fields preserved
        expect(result.traffic.proxies["capture-proxy"].proxyConfig.listenPort).toBe(9201);
    });

    it("apply() toggles away from 2 if already 2", () => {
        const input = {
            traffic: {
                proxies: {
                    "capture-proxy": {
                        proxyConfig: { numThreads: 2 },
                    },
                },
            },
        };
        const result = mutator.apply(input) as any;
        expect(result.traffic.proxies["capture-proxy"].proxyConfig.numThreads).toBe(3);
    });

    it("apply() throws on missing traffic key", () => {
        expect(() => mutator.apply({})).toThrow(/missing 'traffic'/);
    });
});

describe("snapshotMigrationMaxConnectionsMutator", () => {
    const mutator = snapshotMigrationMaxConnectionsMutator();

    it("has the expected metadata", () => {
        expect(mutator.name).toBe("snapshotMigration-maxConnections");
        expect(mutator.changeClass).toBe("impossible");
        expect(mutator.dependencyPattern).toBe("subject-impossible-change");
        expect(mutator.subject).toBe("snapshotmigration:source-target-snap1-migration-0");
        expect(mutator.expectedRerunComponents).toEqual([
            "snapshotmigration:source-target-snap1-migration-0",
        ]);
        expect(mutator.changedPaths).toEqual([
            "snapshotMigrationConfigs.0.perSnapshotConfig.snap1.0.documentBackfillConfig.maxConnections",
        ]);
    });

    it("apply() returns a cloned config that differs from the input", () => {
        const input = {
            snapshotMigrationConfigs: [
                {
                    perSnapshotConfig: {
                        snap1: [
                            {
                                documentBackfillConfig: {
                                    maxConnections: 4,
                                    documentsPerBulkRequest: 1000,
                                },
                            },
                        ],
                    },
                },
            ],
        };
        const result = mutator.apply(input) as any;

        expect(
            input.snapshotMigrationConfigs[0].perSnapshotConfig.snap1[0]
                .documentBackfillConfig.maxConnections,
        ).toBe(4);
        expect(
            result.snapshotMigrationConfigs[0].perSnapshotConfig.snap1[0]
                .documentBackfillConfig.maxConnections,
        ).toBe(5);
        expect(
            result.snapshotMigrationConfigs[0].perSnapshotConfig.snap1[0]
                .documentBackfillConfig.documentsPerBulkRequest,
        ).toBe(1000);
    });

    it("apply() toggles away from 5 if already 5", () => {
        const input = {
            snapshotMigrationConfigs: [
                {
                    perSnapshotConfig: {
                        snap1: [
                            {
                                documentBackfillConfig: {
                                    maxConnections: 5,
                                },
                            },
                        ],
                    },
                },
            ],
        };
        const result = mutator.apply(input) as typeof input;
        expect(
            result.snapshotMigrationConfigs[0].perSnapshotConfig.snap1[0]
                .documentBackfillConfig.maxConnections,
        ).toBe(6);
    });

    it("apply() throws on missing snapshotMigrationConfigs key", () => {
        expect(() => mutator.apply({})).toThrow(/missing 'snapshotMigrationConfigs'/);
    });
});
