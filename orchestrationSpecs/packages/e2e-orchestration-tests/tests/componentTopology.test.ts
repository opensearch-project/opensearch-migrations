import { buildTopology, TopologyError } from "../src/componentTopology";
import {
    registeredBaselines,
    requireTopologyForBaseline,
    resolveTopologyForBaseline,
    TopologyResolverError,
} from "../src/componentTopologyResolver";
import { ComponentId } from "../src/types";

describe("buildTopology", () => {
    const simple = () =>
        buildTopology({
            components: ["a:1", "b:1", "c:1", "d:1"] as ComponentId[],
            edges: [
                { from: "b:1", to: "a:1" } as any,
                { from: "c:1", to: "b:1" } as any,
                // d:1 is unrelated
            ],
        });

    it("computes transitive downstream closures", () => {
        const t = simple();
        expect([...t.downstreamOf("a:1" as ComponentId)].sort()).toEqual(["b:1", "c:1"]);
        expect([...t.downstreamOf("b:1" as ComponentId)]).toEqual(["c:1"]);
        expect([...t.downstreamOf("c:1" as ComponentId)]).toEqual([]);
    });

    it("computes transitive upstream closures", () => {
        const t = simple();
        expect([...t.upstreamOf("c:1" as ComponentId)].sort()).toEqual(["a:1", "b:1"]);
        expect([...t.upstreamOf("a:1" as ComponentId)]).toEqual([]);
    });

    it("returns independents excluding subject/upstream/downstream", () => {
        const t = simple();
        expect([...t.independentOf("b:1" as ComponentId)]).toEqual(["d:1"]);
        // d:1 has no edges, so everything except itself is independent.
        expect([...t.independentOf("d:1" as ComponentId)].sort()).toEqual([
            "a:1",
            "b:1",
            "c:1",
        ]);
    });

    it("rejects edges with unknown endpoints", () => {
        expect(() =>
            buildTopology({
                components: ["a:1"] as ComponentId[],
                edges: [{ from: "a:1", to: "b:1" } as any],
            }),
        ).toThrow(TopologyError);
    });

    it("rejects self-loops", () => {
        expect(() =>
            buildTopology({
                components: ["a:1"] as ComponentId[],
                edges: [{ from: "a:1", to: "a:1" } as any],
            }),
        ).toThrow(TopologyError);
    });

    it("rejects cycles", () => {
        expect(() =>
            buildTopology({
                components: ["a:1", "b:1"] as ComponentId[],
                edges: [
                    { from: "a:1", to: "b:1" } as any,
                    { from: "b:1", to: "a:1" } as any,
                ],
            }),
        ).toThrow(TopologyError);
    });

    it("throws on unknown subject in queries", () => {
        const t = simple();
        expect(() => t.downstreamOf("nope:1" as ComponentId)).toThrow(TopologyError);
        expect(() => t.upstreamOf("nope:1" as ComponentId)).toThrow(TopologyError);
        expect(() => t.independentOf("nope:1" as ComponentId)).toThrow(TopologyError);
    });
});

describe("componentTopologyResolver — fullMigrationWithTraffic", () => {
    // The expected shape of this topology is pinned by
    // `tests/topologyMatchesGenerator.test.ts`, which derives the
    // expected CRDs from the real sample via MigrationInitializer and
    // compares them to the hardcoded topology. If that test fails, the
    // assertions below are likely out of date too.
    const CAPTUREPROXY = "captureproxy:capture-proxy" as ComponentId;
    const KAFKA = "kafkacluster:default" as ComponentId;
    const CAPTURED = "capturedtraffic:capture-proxy-topic" as ComponentId;
    const DATA_SNAPSHOT = "datasnapshot:source-snap1" as ComponentId;
    const SNAP_MIGRATION =
        "snapshotmigration:source-target-snap1-migration-0" as ComponentId;
    const REPLAY =
        "trafficreplay:capture-proxy-target-replay1" as ComponentId;

    it("has the six expected components", () => {
        const t = requireTopologyForBaseline("fullMigrationWithTraffic.wf.yaml");
        expect([...t.components].sort()).toEqual(
            [KAFKA, CAPTURED, CAPTUREPROXY, DATA_SNAPSHOT, SNAP_MIGRATION, REPLAY].sort(),
        );
    });

    it("treats SnapshotMigration as independent of the proxy chain", () => {
        const t = requireTopologyForBaseline("fullMigrationWithTraffic.wf.yaml");
        // No dependsOn on SnapshotMigration means nothing upstream and
        // nothing downstream for it.
        expect([...t.upstreamOf(SNAP_MIGRATION)]).toEqual([]);
        expect([...t.downstreamOf(SNAP_MIGRATION)]).toEqual([]);

        // And every other component should consider it independent.
        for (const c of [CAPTUREPROXY, KAFKA, CAPTURED, DATA_SNAPSHOT, REPLAY]) {
            expect([...t.independentOf(c)]).toContain(SNAP_MIGRATION);
        }
    });

    it("places DataSnapshot and TrafficReplay downstream of CaptureProxy", () => {
        const t = requireTopologyForBaseline("fullMigrationWithTraffic.wf.yaml");
        expect([...t.downstreamOf(CAPTUREPROXY)].sort()).toEqual(
            [DATA_SNAPSHOT, REPLAY].sort(),
        );
    });

    it("puts Kafka and CapturedTraffic upstream of CaptureProxy", () => {
        const t = requireTopologyForBaseline("fullMigrationWithTraffic.wf.yaml");
        expect([...t.upstreamOf(CAPTUREPROXY)].sort()).toEqual([CAPTURED, KAFKA].sort());
    });

    it("cascades: mutating Kafka reaches every non-migration component", () => {
        const t = requireTopologyForBaseline("fullMigrationWithTraffic.wf.yaml");
        expect([...t.downstreamOf(KAFKA)].sort()).toEqual(
            [CAPTURED, CAPTUREPROXY, DATA_SNAPSHOT, REPLAY].sort(),
        );
        expect([...t.independentOf(KAFKA)]).toEqual([SNAP_MIGRATION]);
    });

    it("DataSnapshot sits beside TrafficReplay, not downstream of it", () => {
        const t = requireTopologyForBaseline("fullMigrationWithTraffic.wf.yaml");
        expect([...t.downstreamOf(DATA_SNAPSHOT)]).toEqual([]);
        expect([...t.downstreamOf(REPLAY)]).toEqual([]);
        // DataSnapshot and TrafficReplay are independent of each other
        // (both depend on CaptureProxy, but not on each other).
        expect([...t.independentOf(DATA_SNAPSHOT)]).toEqual(
            expect.arrayContaining([REPLAY, SNAP_MIGRATION]),
        );
    });

    it("returns null for unknown baselines", () => {
        expect(resolveTopologyForBaseline("never-heard-of.wf.yaml")).toBeNull();
    });

    it("throws via requireTopologyForBaseline for unknown baselines", () => {
        expect(() => requireTopologyForBaseline("never-heard-of.wf.yaml")).toThrow(
            TopologyResolverError,
        );
    });

    it("is case-insensitive on the baseline filename", () => {
        expect(resolveTopologyForBaseline("FullMigrationWithTraffic.WF.YAML")).not.toBeNull();
    });

    it("exposes the set of known baselines for debugging", () => {
        expect(registeredBaselines()).toEqual(
            expect.arrayContaining(["fullmigrationwithtraffic.wf.yaml"]),
        );
    });
});
