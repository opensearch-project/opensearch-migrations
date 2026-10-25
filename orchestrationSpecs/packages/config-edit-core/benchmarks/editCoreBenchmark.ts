import {
    applyEditOperation,
    buildEditStateFromObject,
} from "../src";

function configWithSources(count: number) {
    return {
        sourceClusters: Object.fromEntries(Array.from({length: count}, (_, index) => [
            `source-${index}`,
            {
                endpoint: `https://source-${index}.example.com:9200`,
                version: "ES 7.10",
            },
        ])),
        targetClusters: {
            target: {endpoint: "https://target.example.com:9200"},
        },
        snapshotMigrationConfigs: [],
    };
}

for (const resourceCount of [10, 50, 100, 250]) {
    const config = configWithSources(resourceCount);
    const projectionStart = performance.now();
    buildEditStateFromObject(config);
    const projectionMs = performance.now() - projectionStart;

    const operationStart = performance.now();
    for (let iteration = 0; iteration < 100; iteration += 1) {
        applyEditOperation(config, {
            op: "set",
            path: ["sourceClusters", "source-0", "allowInsecure"],
            value: iteration % 2 === 0,
        });
    }
    const operationMs = (performance.now() - operationStart) / 100;

    console.log(JSON.stringify({
        resourceCount,
        projectionMs: Number(projectionMs.toFixed(2)),
        averageOperationMs: Number(operationMs.toFixed(3)),
    }));
}
