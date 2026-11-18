import {
    applyEditOperation as applyCoreEditOperation,
    buildEditStateFromObject as buildCoreEditStateFromObject,
} from "@opensearch-migrations/config-edit-core";
import {loadUnifiedSchema} from "@opensearch-migrations/schemas";
import {
    applyEditOperationToObject,
    buildEditStateFromObject,
    validationForConfig,
} from "../src/editConfig";
import type {EditOperation} from "../src/schemaEditModel";
import {parse} from "yaml";

const representativeConfig = {
    sourceClusters: {
        source: {
            endpoint: "https://source.example.com:9200",
            version: "ES 7.10",
        },
    },
    targetClusters: {
        target: {
            endpoint: "https://target.example.com:9200",
        },
    },
    traffic: {
        kafkaClusters: {
            default: {autoCreate: {}},
        },
        proxies: {},
        s3Sources: {},
        replayers: {},
    },
    snapshotMigrationConfigs: [],
};

function coreOptions() {
    return {unifiedSchema: loadUnifiedSchema().schema};
}

describe("config-edit-core adapter parity", () => {
    it("projects the same edit state through the Node adapter and shared core", () => {
        const adapterState = buildEditStateFromObject(representativeConfig);
        const coreState = buildCoreEditStateFromObject(
            representativeConfig,
            validationForConfig(representativeConfig),
            coreOptions(),
        );
        expect(coreState).toEqual(adapterState);
    });

    it.each<EditOperation>([
        {
            op: "set",
            path: ["sourceClusters", "source", "allowInsecure"],
            value: true,
        },
        {
            op: "add",
            path: ["targetClusters"],
            value: {name: "second-target"},
        },
        {
            op: "renameConfig",
            path: ["sourceClusters", "source"],
            newName: "renamed-source",
        },
    ])("applies $op identically", operation => {
        const adapterResult = applyEditOperationToObject(representativeConfig, operation);
        const coreConfig = applyCoreEditOperation(representativeConfig, operation, coreOptions());
        expect(coreConfig).toEqual(parse(adapterResult.yaml));
        expect(buildCoreEditStateFromObject(
            coreConfig,
            validationForConfig(coreConfig),
            coreOptions(),
        )).toEqual(adapterResult.editState);
    });
});
