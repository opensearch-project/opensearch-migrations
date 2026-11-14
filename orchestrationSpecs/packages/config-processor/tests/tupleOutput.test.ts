import {MigrationConfigTransformer, buildResolvedMigrationResources} from "../src";

function configWithTupleOutput(tupleOutput?: unknown) {
    return {
        sourceClusters: {
            source: {version: "ES 7.10.2", endpoint: "http://source:9200"},
        },
        targetClusters: {
            target: {endpoint: "http://target:9200"},
        },
        snapshotMigrationConfigs: [],
        traffic: {
            proxies: {
                capture: {source: "source", proxyConfig: {listenPort: 9201}},
            },
            replayers: {
                replay: {
                    fromCapturedTraffic: "capture",
                    toTarget: "target",
                    replayerConfig: {
                        ...(tupleOutput === undefined ? {} : {tupleOutput}),
                        tupleMaxBufferSeconds: 5,
                    },
                },
            },
        },
    };
}

describe("tuple output destination", () => {
    const transformer = new MigrationConfigTransformer();

    it("lowers a typed destination into the existing replayer and TrafficReplay fields", async () => {
        const config = await transformer.processFromObject(configWithTupleOutput({
            type: "s3",
            bucket: "tuple-results",
            prefix: "custom/tuples/",
            region: "us-west-2",
            endpoint: "http://localhost:4566",
        }));
        const expected = {
            tupleS3Bucket: "tuple-results",
            tupleS3Prefix: "custom/tuples/",
            tupleS3Region: "us-west-2",
            tupleS3Endpoint: "http://localhost:4566",
            tupleMaxBufferSeconds: 5,
        };
        expect(config.trafficReplays[0].replayerConfig).toMatchObject(expected);
        expect(config.trafficReplays[0].replayerConfig).not.toHaveProperty("tupleOutput");

        const resources = buildResolvedMigrationResources(config, "workflow");
        const replay = resources.resources.find(resource => resource.kind === "TrafficReplay");
        expect(replay?.parameters).toMatchObject(expected);
        expect(replay?.parameters).not.toHaveProperty("tupleOutput");
    });

    it("retains tuple defaults when only the bucket and region are supplied", async () => {
        const config = await transformer.processFromObject(configWithTupleOutput({
            type: "s3", bucket: "tuple-results", region: "us-west-2",
        }));
        expect(config.trafficReplays[0].replayerConfig).toMatchObject({
            tupleS3Prefix: "tuples/",
            tupleS3Endpoint: "",
            tupleMaxFileSizeMb: 256,
            tupleMaxPerFile: 0,
        });
    });

    it.each([undefined, {type: "disabled"}])("retains logger output for %j", async tupleOutput => {
        const config = await transformer.processFromObject(configWithTupleOutput(tupleOutput));
        expect(config.trafficReplays[0].replayerConfig.tupleS3Bucket).toBeUndefined();
        expect(config.trafficReplays[0].replayerConfig.tupleS3Region).toBeUndefined();
    });

    it("keeps the same replay identity when S3 persistence is omitted or explicitly disabled", async () => {
        const omitted = await transformer.processFromObject(configWithTupleOutput());
        const disabled = await transformer.processFromObject(configWithTupleOutput({type: "disabled"}));
        expect(disabled.trafficReplays[0]).toEqual(omitted.trafficReplays[0]);
    });

    it("includes the destination in the replay config checksum", async () => {
        const first = await transformer.processFromObject(configWithTupleOutput({
            type: "s3", bucket: "tuple-results", region: "us-west-2",
        }));
        const second = await transformer.processFromObject(configWithTupleOutput({
            type: "s3", bucket: "other-results", region: "us-west-2",
        }));
        expect(second.trafficReplays[0].configChecksum).not.toBe(first.trafficReplays[0].configChecksum);
    });

    it.each([
        {type: "s3", bucket: "tuple-results"},
        {type: "s3", bucket: "tuple-results", region: "  "},
        {type: "s3", bucket: "  ", region: "us-west-2"},
        {type: "s3", bucket: "tuple-results", region: "us-west-2", endpoint: "not-an-endpoint"},
        {type: "disabled", bucket: "tuple-results"},
        {type: "gcs", bucket: "tuple-results"},
        {type: "azure", container: "tuple-results"},
    ])("rejects invalid or unsupported destinations: %j", async tupleOutput => {
        await expect(transformer.processFromObject(configWithTupleOutput(tupleOutput))).rejects.toThrow();
    });

    it("rejects the former flat user fields", async () => {
        const config = configWithTupleOutput();
        Object.assign(config.traffic.replayers.replay.replayerConfig, {
            tupleS3Bucket: "tuple-results",
            tupleS3Region: "us-west-2",
        });
        await expect(transformer.processFromObject(config)).rejects.toThrow(/tupleS3/);
    });
});
