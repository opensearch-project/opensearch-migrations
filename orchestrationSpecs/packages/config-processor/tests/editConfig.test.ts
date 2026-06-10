import {applyEditOperationToObject, buildEditStateFromObject, EditNode} from "../src/editConfig";
import {parse} from "yaml";

function findNode(nodes: EditNode[], id: string): EditNode | undefined {
    const stack = [...nodes];
    while (stack.length) {
        const node = stack.pop()!;
        if (node.id === id) {
            return node;
        }
        stack.push(...(node.children ?? []));
    }
    return undefined;
}

describe("editConfig state", () => {
    it("shows missing basic auth children as required on the branch and parent", () => {
        const state = buildEditStateFromObject({
            sourceClusters: {
                legacy: {
                    endpoint: "https://legacy.example.com:9200",
                    version: "ES 7.10.2",
                    authConfig: {basic: {}},
                },
            },
            targetClusters: {
                prod: {
                    endpoint: "https://prod.example.com:9200",
                },
            },
            snapshotMigrationConfigs: [],
        });

        const source = findNode(state.nodes, "edit:sourceClusters.legacy");
        const auth = findNode(state.nodes, "edit:sourceClusters.legacy.authConfig");
        const secretName = findNode(state.nodes, "edit:sourceClusters.legacy.authConfig.basic.secretName");

        expect(source?.status).toBe("required");
        expect(source?.label).toContain("[REQ 1]");
        expect(auth?.status).toBe("required");
        expect(auth?.label).toContain("authConfig: < basic >");
        expect(secretName?.status).toBe("required");
        expect(secretName?.label).toContain("secretName: <required>");
    });

    it("treats omitted authConfig as a complete none variant", () => {
        const state = buildEditStateFromObject({
            sourceClusters: {
                legacy: {
                    endpoint: "https://legacy.example.com:9200",
                    version: "ES 7.10.2",
                },
            },
            targetClusters: {
                prod: {
                    endpoint: "https://prod.example.com:9200",
                },
            },
            snapshotMigrationConfigs: [],
        });

        const auth = findNode(state.nodes, "edit:sourceClusters.legacy.authConfig");

        expect(auth?.status).toBe("ok");
        expect(auth?.label).toContain("[OK] authConfig: < none >");
        expect(auth?.children).toHaveLength(0);
    });

    it("returns schema descriptions for Python to render without knowing fields", () => {
        const state = buildEditStateFromObject({
            sourceClusters: {
                legacy: {
                    endpoint: "https://legacy.example.com:9200",
                    version: "ES 7.10.2",
                },
            },
            targetClusters: {
                prod: {
                    endpoint: "https://prod.example.com:9200",
                },
            },
            snapshotMigrationConfigs: [],
        });

        const endpoint = findNode(state.nodes, "edit:sourceClusters.legacy.endpoint");

        expect(endpoint?.description).toContain("HTTP(S) endpoint URL");
    });

    it("applies auth variant changes and refreshes required children", () => {
        const result = applyEditOperationToObject({
            sourceClusters: {
                legacy: {
                    endpoint: "https://legacy.example.com:9200",
                    version: "ES 7.10.2",
                    authConfig: {basic: {secretName: "legacy-basic"}},
                },
            },
            targetClusters: {prod: {endpoint: "https://prod.example.com:9200"}},
            snapshotMigrationConfigs: [],
        }, {
            op: "set",
            path: ["sourceClusters", "legacy", "authConfig"],
            value: "sigv4",
        });

        const auth = findNode(result.editState.nodes, "edit:sourceClusters.legacy.authConfig");
        const region = findNode(result.editState.nodes, "edit:sourceClusters.legacy.authConfig.sigv4.region");

        expect(result.yaml).toContain("sigv4:");
        expect(result.yaml).not.toContain("basic:");
        expect(auth?.label).toContain("authConfig: < sigv4 >");
        expect(region?.status).toBe("required");
    });

    it("applies scalar and boolean changes", () => {
        const config = {
            sourceClusters: {
                legacy: {
                    endpoint: "",
                    allowInsecure: false,
                    version: "ES 7.10.2",
                },
            },
            targetClusters: {prod: {endpoint: "https://prod.example.com:9200"}},
            snapshotMigrationConfigs: [],
        };

        const endpointResult = applyEditOperationToObject(config, {
            op: "set",
            path: ["sourceClusters", "legacy", "endpoint"],
            value: "https://legacy.example.com:9200",
        });
        const toggleResult = applyEditOperationToObject(
            // Reparse through YAML to keep this test focused on public result shape.
            parse(endpointResult.yaml),
            {
                op: "set",
                path: ["sourceClusters", "legacy", "allowInsecure"],
                value: true,
            }
        );

        expect(toggleResult.yaml).toContain("endpoint: https://legacy.example.com:9200");
        expect(toggleResult.yaml).toContain("allowInsecure: true");
    });

    it("adds and removes virtual cluster config entries", () => {
        const added = applyEditOperationToObject({
            sourceClusters: {},
            targetClusters: {},
            snapshotMigrationConfigs: [],
        }, {
            op: "add",
            path: ["sourceClusters"],
            value: {name: "legacy"},
        });

        expect(added.yaml).toContain("legacy:");
        expect(findNode(added.editState.nodes, "edit:sourceClusters.legacy")?.status).toBe("required");

        const removed = applyEditOperationToObject(parse(added.yaml), {
            op: "removeConfig",
            path: ["sourceClusters", "legacy"],
        });

        expect(removed.yaml).not.toContain("legacy:");
        expect(findNode(removed.editState.nodes, "edit:sourceClusters.legacy")).toBeUndefined();
    });

    it("renders and applies map-backed resource config groups", () => {
        const state = buildEditStateFromObject({
            sourceClusters: {legacy: {endpoint: "https://legacy.example.com:9200", version: "ES 7.10.2"}},
            targetClusters: {prod: {endpoint: "https://prod.example.com:9200"}},
            kafkaClusterConfiguration: {
                default: {autoCreate: {}},
            },
            traffic: {
                proxies: {
                    capture: {source: "legacy"},
                },
                replayers: {
                    replay: {fromProxy: "capture", toTarget: "prod"},
                },
            },
            snapshotMigrationConfigs: [{fromSource: "legacy", toTarget: "prod", perSnapshotConfig: {}}],
        });

        expect(findNode(state.nodes, "edit:kafkaClusterConfiguration.default")?.label).toContain("kafka: default");
        expect(findNode(state.nodes, "edit:traffic.proxies.capture")?.label).toContain("capture proxy: capture");
        expect(findNode(state.nodes, "edit:traffic.replayers.replay")?.label).toContain("traffic replay: replay");
        expect(findNode(state.nodes, "edit:snapshotMigrationConfigs.0")?.label).toContain("snapshot migration: legacy -> prod");
    });

    it("adds/removes nested traffic resources and switches Kafka mode", () => {
        const config = {
            sourceClusters: {},
            targetClusters: {},
            snapshotMigrationConfigs: [],
        };

        const addedKafka = applyEditOperationToObject(config, {
            op: "add",
            path: ["kafkaClusterConfiguration"],
            value: {name: "default"},
        });
        const existingKafka = applyEditOperationToObject(parse(addedKafka.yaml), {
            op: "set",
            path: ["kafkaClusterConfiguration", "default", "mode"],
            value: "existing",
        });
        const addedProxy = applyEditOperationToObject(parse(existingKafka.yaml), {
            op: "add",
            path: ["traffic", "proxies"],
            value: {name: "capture"},
        });
        const removedProxy = applyEditOperationToObject(parse(addedProxy.yaml), {
            op: "removeConfig",
            path: ["traffic", "proxies", "capture"],
        });

        expect(existingKafka.yaml).toContain("existing: {}");
        expect(addedProxy.yaml).toContain("capture:");
        expect(removedProxy.yaml).not.toContain("capture:");
    });
});
