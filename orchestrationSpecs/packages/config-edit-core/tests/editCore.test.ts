import {
    annotateDraftChanges,
    applyEditOperation,
    buildEditStateFromObject,
    externalResourceSelectionOperations,
    projectConfigYaml,
    validationForConfig,
} from "../src";
import {describe, expect, it} from "@jest/globals";

describe("browser-safe configuration editing", () => {
    const config = {
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
        snapshotMigrationConfigs: [],
    };

    it("applies operations without mutating the caller's document", () => {
        const updated = applyEditOperation(config, {
            op: "set",
            path: ["sourceClusters", "source", "allowInsecure"],
            value: true,
        }) as typeof config & {
            sourceClusters: {source: {allowInsecure?: boolean}};
        };

        expect(updated.sourceClusters.source.allowInsecure).toBe(true);
        expect("allowInsecure" in config.sourceClusters.source).toBe(false);
    });

    it("can preserve downstream configuration while clearing deleted references", () => {
        const trafficConfig = {
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
            snapshotMigrationConfigs: [],
            traffic: {
                kafkaClusters: {
                    kafka: {
                        autoCreate: {},
                        topics: {
                            capture: {},
                        },
                    },
                },
                proxies: {
                    capture: {
                        source: "source",
                        kafka: "kafka",
                        kafkaTopic: "capture",
                        proxyConfig: {},
                    },
                },
                replayers: {
                    replay: {
                        fromCapturedTraffic: "capture",
                        toTarget: "target",
                    },
                },
            },
        };

        const withoutCluster = applyEditOperation(trafficConfig, {
            op: "removeConfig",
            path: ["traffic", "kafkaClusters", "kafka"],
            referencingResources: "clear-references",
        }) as any;
        expect(withoutCluster.traffic.kafkaClusters.kafka).toBeUndefined();
        expect(withoutCluster.traffic.proxies.capture).toEqual({
            source: "source",
            proxyConfig: {},
        });
        expect(withoutCluster.traffic.replayers.replay).toEqual({
            fromCapturedTraffic: "capture",
            toTarget: "target",
        });

        const withoutProxy = applyEditOperation(trafficConfig, {
            op: "removeConfig",
            path: ["traffic", "proxies", "capture"],
            referencingResources: "clear-references",
        }) as any;
        expect(withoutProxy.traffic.proxies.capture).toBeUndefined();
        expect(withoutProxy.traffic.replayers.replay).toEqual({
            toTarget: "target",
        });
    });

    it("renames a Kafka topic key and updates producer references", () => {
        const trafficConfig = {
            traffic: {
                kafkaClusters: {
                    kafka: {
                        autoCreate: {},
                        topics: {
                            capture: {
                                specOverrides: {
                                    partitions: 2,
                                },
                            },
                        },
                    },
                },
                proxies: {
                    capture: {
                        kafka: "kafka",
                        kafkaTopic: "capture",
                    },
                },
                s3Sources: {
                    imported: {
                        kafka: "kafka",
                        kafkaTopic: "capture",
                    },
                },
            },
        };

        const renamed = applyEditOperation(trafficConfig, {
            op: "renameConfig",
            path: [
                "traffic",
                "kafkaClusters",
                "kafka",
                "topics",
                "capture",
            ],
            newName: "renamed-capture",
        }) as any;

        expect(renamed.traffic.kafkaClusters.kafka.topics.capture)
            .toBeUndefined();
        expect(renamed.traffic.kafkaClusters.kafka.topics["renamed-capture"])
            .toEqual({
                specOverrides: {
                    partitions: 2,
                },
            });
        expect(renamed.traffic.proxies.capture.kafkaTopic)
            .toBe("renamed-capture");
        expect(renamed.traffic.s3Sources.imported.kafkaTopic)
            .toBe("renamed-capture");
    });

    it("projects and validates without Node runtime services", () => {
        expect(validationForConfig(config).valid).toBe(true);
        expect(buildEditStateFromObject(config).provenance).toMatchObject({
            source: "pending-yaml",
            mode: "structured",
            lossy: false,
        });
    });

    it("projects raw YAML and preserves malformed documents for repair", () => {
        const projected = projectConfigYaml(`
sourceClusters:
  source:
    endpoint: https://source.example.com:9200
    version: ES 7.10
`);
        expect(projected.config).toMatchObject({
            sourceClusters: {
                source: {
                    endpoint: "https://source.example.com:9200",
                },
            },
        });
        expect(projected.editState.provenance.mode).toBe("structured");

        const malformed = projectConfigYaml("sourceClusters: [");
        expect(malformed.config).toBeNull();
        expect(malformed.editState.provenance.mode).toBe("raw");
        expect(malformed.editState.validation.valid).toBe(false);
    });

    it("annotates local changes against the saved projection", () => {
        const base = buildEditStateFromObject(config);
        const updated = buildEditStateFromObject(applyEditOperation(config, {
            op: "set",
            path: ["sourceClusters", "source", "allowInsecure"],
            value: true,
        }));
        const annotated = annotateDraftChanges(updated, base);
        const visit = (nodes: typeof annotated.nodes): typeof annotated.nodes =>
            nodes.flatMap(node => [node, ...visit(node.children ?? [])]);
        const allNodes = visit(annotated.nodes);
        const source = allNodes.find(
            node => node.id === "edit:sourceClusters.source",
        );
        const allowInsecure = allNodes.find(
            node => node.id === "edit:sourceClusters.source.allowInsecure",
        );

        expect(allowInsecure?.draftChange).toEqual({
            kind: "modified",
            previousValue: false,
            previousValuePresent: true,
        });
        expect(source?.draftChangeCount).toBeGreaterThan(0);
    });

    it("converts descriptor-driven external selections into edit operations", () => {
        expect(externalResourceSelectionOperations({
            id: "edit:file",
            path: ["traffic", "transform", "configMap"],
            label: "ConfigMap",
            valueKind: "scalar",
            externalRef: {
                kind: "kubernetesResource",
                purpose: "file-ref-config-map",
                displayName: "ConfigMap",
                selection: {
                    target: "fileRefConfigMap",
                    nameField: "configMap",
                    pathField: "path",
                },
            },
        }, {
            group: "",
            key: "transform.js",
            kind: "ConfigMap",
            name: "transform-code",
        })).toEqual([
            {
                op: "set",
                path: ["traffic", "transform", "configMap"],
                value: "transform-code",
            },
            {
                op: "set",
                path: ["traffic", "transform", "path"],
                value: "transform.js",
            },
        ]);
    });
});
