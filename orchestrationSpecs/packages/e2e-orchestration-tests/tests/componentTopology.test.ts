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

describe("componentTopologyResolver", () => {
    it("has a hardcoded topology for fullMigrationWithTraffic", () => {
        const t = requireTopologyForBaseline("/wherever/fullMigrationWithTraffic.wf.yaml");
        expect(t.components.length).toBeGreaterThan(0);
    });

    it("derives expected dependency chains for the proxy/kafka/replay branch", () => {
        const t = requireTopologyForBaseline("fullMigrationWithTraffic.wf.yaml");
        const kafka = "kafkacluster:default" as ComponentId;
        const captured = "capturedtraffic:source-proxy-topic" as ComponentId;
        const proxy = "captureproxy:source-proxy" as ComponentId;
        const replay = "trafficreplay:source-proxy-target-target-replay" as ComponentId;
        expect([...t.downstreamOf(kafka)].sort()).toEqual([captured, proxy, replay].sort());
        expect([...t.downstreamOf(proxy)]).toEqual([replay]);
    });

    it("identifies the snapshot branch as independent of the proxy branch", () => {
        const t = requireTopologyForBaseline("fullMigrationWithTraffic.wf.yaml");
        const proxy = "captureproxy:source-proxy" as ComponentId;
        const indep = [...t.independentOf(proxy)].sort();
        expect(indep).toEqual([
            "datasnapshot:source-snap1",
            "snapshotmigration:source-target-snap1-migration-0",
        ]);
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
