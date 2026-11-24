import {z} from "zod";
import {ARGO_RFS_OPTIONS, USER_RFS_OPTIONS} from "../src";

// memoryScaling turns on memory-tier scaling for the RFS backfill. Validation rules:
// maxMemory must cover the starting size, requests.memory must equal limits.memory while the
// feature is on (each tier sets both to the same value), and maxTotalPods only applies with
// memoryScaling. Feature off (absent) must change nothing about the parsed config.
describe("documentBackfillConfig.memoryScaling", () => {
    const base = {
        maxShardSizeBytes: 1024,
        failedDocumentStream: {type: "disabled"},
    };

    it("defaults growthFactor to 2 and escalationApproval to auto", () => {
        const parsed = USER_RFS_OPTIONS.parse({
            ...base,
            memoryScaling: {maxMemory: "56000Mi"},
        });
        expect(parsed.memoryScaling).toEqual({
            growthFactor: 2,
            maxMemory: "56000Mi",
            escalationApproval: "auto",
        });
    });

    it("accepts manual escalation approval and a custom growth factor", () => {
        const parsed = USER_RFS_OPTIONS.parse({
            ...base,
            maxTotalPods: 24,
            memoryScaling: {maxMemory: "56Gi", growthFactor: 1.5, escalationApproval: "manual"},
        });
        expect(parsed.memoryScaling?.growthFactor).toBe(1.5);
        expect(parsed.memoryScaling?.escalationApproval).toBe("manual");
        expect(parsed.maxTotalPods).toBe(24);
    });

    it("requires maxMemory to be at least the starting size", () => {
        const result = USER_RFS_OPTIONS.safeParse({
            ...base,
            resources: {requests: {memory: "8Gi"}, limits: {memory: "8Gi"}},
            memoryScaling: {maxMemory: "4Gi"},
        });
        expect(result.success).toBe(false);
        if (!result.success) {
            expect(result.error.issues).toEqual(expect.arrayContaining([
                expect.objectContaining({
                    path: ["memoryScaling", "maxMemory"],
                    message: expect.stringContaining("at least the starting size"),
                }),
            ]));
        }
    });

    it("locks requests.memory == limits.memory while the feature is on", () => {
        const result = USER_RFS_OPTIONS.safeParse({
            ...base,
            resources: {requests: {memory: "4Gi"}, limits: {memory: "8Gi"}},
            memoryScaling: {maxMemory: "56Gi"},
        });
        expect(result.success).toBe(false);
        if (!result.success) {
            expect(result.error.issues).toEqual(expect.arrayContaining([
                expect.objectContaining({
                    path: ["memoryScaling"],
                    message: expect.stringContaining("requests.memory"),
                }),
            ]));
        }
    });

    it("permits unequal requests/limits memory when the feature is off", () => {
        const result = USER_RFS_OPTIONS.safeParse({
            ...base,
            resources: {requests: {memory: "4Gi"}, limits: {memory: "8Gi"}},
        });
        expect(result.success).toBe(true);
    });

    it("rejects maxTotalPods without memoryScaling", () => {
        const result = USER_RFS_OPTIONS.safeParse({...base, maxTotalPods: 24});
        expect(result.success).toBe(false);
        if (!result.success) {
            expect(result.error.issues).toEqual(expect.arrayContaining([
                expect.objectContaining({path: ["maxTotalPods"]}),
            ]));
        }
    });

    it("rejects growthFactor <= 1 and non-positive maxTotalPods", () => {
        expect(USER_RFS_OPTIONS.safeParse({
            ...base,
            memoryScaling: {maxMemory: "56Gi", growthFactor: 1},
        }).success).toBe(false);
        expect(USER_RFS_OPTIONS.safeParse({
            ...base,
            memoryScaling: {maxMemory: "56Gi"},
            maxTotalPods: 0,
        }).success).toBe(false);
    });

    it("feature off parses exactly as before (no new keys appear)", () => {
        const parsed = USER_RFS_OPTIONS.parse({...base});
        expect(parsed).not.toHaveProperty("memoryScaling");
        expect(parsed).not.toHaveProperty("maxTotalPods");
    });

    it("the ARGO schema carries only the lowered tier-table fields, never the user block", () => {
        const argoShape = (ARGO_RFS_OPTIONS as z.ZodObject<z.ZodRawShape>).shape;
        expect(argoShape).not.toHaveProperty("memoryScaling");
        expect(argoShape).toHaveProperty("memoryScalingTierTable");
        expect(argoShape).toHaveProperty("memoryScalingEscalationApproval");
        expect(argoShape).toHaveProperty("maxTotalPods");
    });
});
