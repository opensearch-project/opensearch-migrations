import {
    collectProjectedFields,
    collectRestrictedProjectedFields,
    specPathKey,
} from "../src/migrationResourceProjections";

describe("migration resource projections", () => {
    const projected = collectProjectedFields();
    const projectedByPath = new Map(projected.map(field => [specPathKey(field), field]));

    test("projects current schema-gated fields that hand-written VAPs miss today", () => {
        expect(projectedByPath.get("CaptureProxy:setHeader")?.changeRestriction).toBe("gated");
        expect(projectedByPath.get("CaptureProxy:maxTrafficBufferSize")?.changeRestriction).toBe("gated");
        expect(projectedByPath.get("TrafficReplay:tupleMaxBufferSeconds")?.changeRestriction).toBe("gated");
        expect(projectedByPath.get("TrafficReplay:tupleMaxFileSizeMb")?.changeRestriction).toBe("gated");
    });

    test("projects safe user-visible fields without making them VAP fields", () => {
        const speedupFactor = projectedByPath.get("TrafficReplay:speedupFactor");
        expect(speedupFactor).toBeDefined();
        expect(speedupFactor?.changeRestriction).toBe("safe");

        const restrictedPaths = collectRestrictedProjectedFields().map(specPathKey);
        expect(restrictedPaths).not.toContain("TrafficReplay:speedupFactor");
    });

    test("projects prefixed SnapshotMigration fields from existing schemas", () => {
        expect(projectedByPath.get("SnapshotMigration:documentBackfillMaxConnections")?.changeRestriction)
            .toBe("gated");
        expect(projectedByPath.get("SnapshotMigration:documentBackfillIndexAllowlist")?.changeRestriction)
            .toBe("impossible");
        expect(projectedByPath.get("SnapshotMigration:metadataMigrationEnableSourcelessMigrations"))
            .toBeDefined();
    });

    test("includes internal CRD fields needed for current VAP behavior", () => {
        expect(projectedByPath.get("ApprovalGate:dependsOn")).toBeUndefined();

        expect(projectedByPath.get("CapturedTraffic:kafkaClusterName")?.changeRestriction).toBe("impossible");
        expect(projectedByPath.get("CapturedTraffic:topicName")?.changeRestriction).toBe("impossible");

        const partitions = projectedByPath.get("CapturedTraffic:partitions");
        expect(partitions?.changeRestriction).toBe("gated");
        expect(partitions?.invariant).toBe("nonDecreasing");

        expect(projectedByPath.get("KafkaCluster:auth.type")?.changeRestriction).toBe("impossible");
        expect(projectedByPath.get("KafkaCluster:nodePool.storage.size")?.changeRestriction).toBe("gated");
    });

    test("every checksum-relevant projected field has an explicit change policy decision", () => {
        const checksumFields = projected.filter(field => field.checksumFor && field.checksumFor.length > 0);
        expect(checksumFields.length).toBeGreaterThan(0);
        expect(checksumFields.map(field => [specPathKey(field), field.changeRestriction]))
            .not.toContainEqual([expect.any(String), undefined]);
    });

    test("every restricted field has a concrete CRD spec path", () => {
        for (const field of collectRestrictedProjectedFields()) {
            expect(field.resourceKind).toBeTruthy();
            expect(field.specPath.length).toBeGreaterThan(0);
            expect(field.specPath.every(pathPart => pathPart.length > 0)).toBe(true);
        }
    });

    test("projected CRD spec paths are unique", () => {
        const keys = projected.map(specPathKey);
        expect(new Set(keys).size).toBe(keys.length);
    });
});
