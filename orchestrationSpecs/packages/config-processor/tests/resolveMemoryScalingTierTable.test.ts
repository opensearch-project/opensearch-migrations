import {resolveMemoryScalingTierTable} from "../src/migrationConfigTransformer";

describe("resolveMemoryScalingTierTable", () => {
    const resources = {requests: {memory: "7000Mi"}, limits: {memory: "7000Mi"}};

    it("returns an empty record when memoryScaling is absent (feature entirely off)", () => {
        expect(resolveMemoryScalingTierTable(undefined, resources)).toEqual({});
    });

    it("computes the geometric table with the last entry clamped to exactly maxMemory", () => {
        const out = resolveMemoryScalingTierTable(
            {maxMemory: "56000Mi", growthFactor: 2}, resources);
        // 7000 -> 14000 -> 28000 -> (56000 == max, so the clamp IS the user's literal quantity)
        expect(out.memoryScalingTierTable).toEqual(["7000Mi", "14000Mi", "28000Mi", "56000Mi"]);
    });

    it("clamps a non-aligned maximum to the user's exact quantity", () => {
        const out = resolveMemoryScalingTierTable(
            {maxMemory: "20000Mi", growthFactor: 2}, resources);
        expect(out.memoryScalingTierTable).toEqual(["7000Mi", "14000Mi", "20000Mi"]);
    });

    it("keeps the tier-0 entry verbatim and rounds intermediate tiers up to whole Mi", () => {
        const out = resolveMemoryScalingTierTable(
            {maxMemory: "4Gi", growthFactor: 1.5},
            {requests: {memory: "1Gi"}, limits: {memory: "1Gi"}});
        // 1Gi=1024Mi -> 1536 -> 2304 -> 3456 -> clamp 4Gi
        expect(out.memoryScalingTierTable).toEqual(["1Gi", "1536Mi", "2304Mi", "3456Mi", "4Gi"]);
    });

    it("start == maxMemory yields a single-tier table", () => {
        const out = resolveMemoryScalingTierTable({maxMemory: "7000Mi"}, resources);
        expect(out.memoryScalingTierTable).toEqual(["7000Mi"]);
    });

    it("rejects maxMemory below the starting size", () => {
        expect(() => resolveMemoryScalingTierTable({maxMemory: "1Gi"}, resources))
            .toThrow(/at least the starting size/);
    });

    it("rejects diverging requests/limits memory", () => {
        expect(() => resolveMemoryScalingTierTable(
            {maxMemory: "56000Mi"},
            {requests: {memory: "7000Mi"}, limits: {memory: "9000Mi"}}))
            .toThrow(/requests.memory/);
    });

    it("rejects a missing starting size", () => {
        expect(() => resolveMemoryScalingTierTable({maxMemory: "56000Mi"}, {}))
            .toThrow(/requests.memory/);
    });
});
