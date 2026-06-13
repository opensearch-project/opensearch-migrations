import {OVERALL_MIGRATION_CONFIG, zodSchemaToJsonSchema} from "../src";

describe("workflow schema UI hints", () => {
    const schema = zodSchemaToJsonSchema(OVERALL_MIGRATION_CONFIG);

    it("exports top-level collection add hints", () => {
        expect(schema.properties.sourceClusters["x-ui-hint"]).toMatchObject({
            kind: "record",
            addLabel: "source cluster",
        });
        expect(schema.properties.targetClusters["x-ui-hint"]).toMatchObject({
            kind: "record",
            addLabel: "target cluster",
        });
        expect(schema.properties.kafkaClusterConfiguration["x-ui-hint"]).toMatchObject({
            kind: "record",
            addLabel: "Kafka cluster",
            keyFormat: "k8s-name",
        });
        expect(schema.properties.snapshotMigrationConfigs["x-ui-hint"]).toMatchObject({
            kind: "array",
            addLabel: "snapshot migration",
        });
    });

    it("exports scalar and reference edit hints for nested fields", () => {
        expect(schema.properties.sourceClusters.additionalProperties.properties.endpoint["x-ui-hint"]).toMatchObject({
            kind: "text",
            format: "optional-http-endpoint",
        });
        expect(schema.properties.targetClusters.additionalProperties.properties.endpoint["x-ui-hint"]).toMatchObject({
            kind: "text",
            format: "http-endpoint",
        });
        expect(schema.properties.sourceClusters.additionalProperties.properties.version["x-ui-hint"]).toMatchObject({
            kind: "text",
            format: "cluster-version",
        });
        expect(schema.properties.traffic.properties.proxies.additionalProperties.properties.source["x-ui-hint"]).toMatchObject({
            kind: "reference",
            sourcePath: ["sourceClusters"],
        });
        expect(schema.properties.traffic.properties.replayers.additionalProperties.properties.toTarget["x-ui-hint"]).toMatchObject({
            kind: "reference",
            sourcePath: ["targetClusters"],
        });
    });
});
