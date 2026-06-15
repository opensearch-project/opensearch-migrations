import {applyEditOperationToObject, buildEditStateFromObject, EditNode} from "../src/editConfig";
import {parse} from "yaml";
import {spawnSync} from "child_process";
import path from "path";
import {mkdtempSync, rmSync, writeFileSync} from "fs";
import {tmpdir} from "os";

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

    it("shows optional empty source endpoint as unset, not required", () => {
        const state = buildEditStateFromObject({
            sourceClusters: {
                legacy: {
                    endpoint: "",
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
        const source = findNode(state.nodes, "edit:sourceClusters.legacy");

        expect(endpoint?.status).toBe("ok");
        expect(endpoint?.label).toContain("endpoint: <unset>");
        expect(endpoint?.label).not.toContain("<required>");
        expect(source?.status).toBe("ok");
    });

    it("propagates whole-config validation diagnostics into visible tree status", () => {
        const state = buildEditStateFromObject({});

        const sourceClusters = findNode(state.nodes, "edit:sourceClusters");
        const targetClusters = findNode(state.nodes, "edit:targetClusters");

        expect(state.validation.valid).toBe(false);
        expect(sourceClusters?.status).toBe("required");
        expect(sourceClusters?.label).toContain("[REQ 1]");
        expect(targetClusters?.status).toBe("required");
    });

    it("returns regex validation metadata and marks invalid scalar values", () => {
        const state = buildEditStateFromObject({
            sourceClusters: {
                legacy: {
                    endpoint: "https://legacy.example.com:9200",
                    version: "Elasticsearch seven",
                },
            },
            targetClusters: {
                prod: {
                    endpoint: "prod.example.com:9200",
                },
            },
            snapshotMigrationConfigs: [],
        });

        const version = findNode(state.nodes, "edit:sourceClusters.legacy.version");
        const endpoint = findNode(state.nodes, "edit:targetClusters.prod.endpoint");

        expect(version?.inputHint).toMatchObject({kind: "text", format: "cluster-version"});
        expect(version?.validation?.pattern).toContain("ES");
        expect(version?.status).toBe("error");
        expect(version?.diagnostics?.[0].message).toContain("Use '<ENGINE> <VERSION>'");
        expect(endpoint?.inputHint).toMatchObject({kind: "text", format: "http-endpoint"});
        expect(endpoint?.validation?.message).toContain("http:// or https://");
        expect(endpoint?.status).toBe("error");
    });

    it("returns structured validation diagnostics from schema refinements", () => {
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
            traffic: {
                proxies: {
                    capture: {source: "missing-source", proxyConfig: {listenPort: 9201}},
                },
                replayers: {},
            },
            snapshotMigrationConfigs: [],
        });

        expect(state.validation.valid).toBe(false);
        expect(state.validation.diagnostics).toEqual(expect.arrayContaining([
            expect.objectContaining({
                severity: "error",
                path: ["traffic", "proxies", "capture", "source"],
            }),
        ]));
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

    it("applies hyphenated source references in snapshot and traffic configs", () => {
        const config = {
            sourceClusters: {
                "aux-source": {
                    endpoint: "",
                    version: "ES 7.10.2",
                },
            },
            targetClusters: {
                prod: {endpoint: "https://prod.example.com:9200"},
            },
            traffic: {
                proxies: {
                    capture: {source: ""},
                },
                replayers: {},
            },
            snapshotMigrationConfigs: [
                {fromSource: "", toTarget: "prod", perSnapshotConfig: {}},
            ],
        };

        const snapshotResult = applyEditOperationToObject(config, {
            op: "set",
            path: ["snapshotMigrationConfigs", "0", "fromSource"],
            value: "aux-source",
        });
        const trafficResult = applyEditOperationToObject(parse(snapshotResult.yaml), {
            op: "set",
            path: ["traffic", "proxies", "capture", "source"],
            value: "aux-source",
        });

        expect(snapshotResult.yaml).toContain("fromSource: aux-source");
        expect(trafficResult.yaml).toContain("source: aux-source");
    });

    it("applies hyphenated source references through the editConfig apply CLI", () => {
        const tempDir = mkdtempSync(path.join(tmpdir(), "edit-config-"));
        try {
            const configPath = path.join(tempDir, "config.yaml");
            const operationPath = path.join(tempDir, "operation.yaml");
            writeFileSync(configPath, [
                "sourceClusters:",
                "  aux-source:",
                "    endpoint: \"\"",
                "    version: ES 7.10.2",
                "targetClusters:",
                "  prod:",
                "    endpoint: https://prod.example.com:9200",
                "snapshotMigrationConfigs:",
                "  - fromSource: \"\"",
                "    toTarget: prod",
                "    perSnapshotConfig: {}",
                "",
            ].join("\n"));
            writeFileSync(operationPath, JSON.stringify({
                op: "set",
                path: ["snapshotMigrationConfigs", "0", "fromSource"],
                value: "aux-source",
            }));

            const cliPath = path.resolve(__dirname, "../src/cliRouter.ts");
            const result = spawnSync(
                process.execPath,
                [
                    "--import",
                    "tsx",
                    cliPath,
                    "editConfig",
                    "apply",
                    "--pending-config",
                    configPath,
                    "--operation",
                    operationPath,
                ],
                {encoding: "utf8"}
            );

            expect(result.status).toBe(0);
            expect(result.stderr).not.toContain("Usage: editConfig apply");
            expect(JSON.parse(result.stdout).yaml).toContain("fromSource: aux-source");
        } finally {
            rmSync(tempDir, {recursive: true, force: true});
        }
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
                s3Sources: {
                    archive: {s3Uri: "s3://bucket/path/export.proto.gz", awsRegion: "us-east-1", sourceLabel: "legacy"},
                },
                replayers: {
                    replay: {fromCapturedTraffic: "archive", toTarget: "prod"},
                },
            },
            snapshotMigrationConfigs: [{fromSource: "legacy", toTarget: "prod", perSnapshotConfig: {}}],
        });

        expect(findNode(state.nodes, "edit:kafkaClusterConfiguration.default")?.label).toContain("kafka: default");
        expect(findNode(state.nodes, "edit:traffic.proxies.capture")?.label).toContain("capture proxy: capture");
        expect(findNode(state.nodes, "edit:traffic.s3Sources.archive")?.label).toContain("S3 captured traffic source: archive");
        expect(findNode(state.nodes, "edit:traffic.replayers.replay")?.label).toContain("traffic replay: replay");
        expect(findNode(state.nodes, "edit:snapshotMigrationConfigs.0")?.label).toContain("snapshot migration: legacy -> prod");
        expect(findNode(state.nodes, "edit:snapshotMigrationConfigs:add")?.label).toContain("+ Add snapshot migration");
        expect(findNode(state.nodes, "edit:traffic.proxies.capture.source")?.inputHint).toMatchObject({
            kind: "reference",
            sourcePath: ["sourceClusters"],
            options: [{label: "legacy", value: "legacy"}],
        });
        expect(findNode(state.nodes, "edit:traffic.replayers.replay.fromCapturedTraffic")?.inputHint).toMatchObject({
            kind: "reference",
            sourcePath: ["traffic", "proxies"],
            options: [{label: "archive", value: "archive"}, {label: "capture", value: "capture"}],
        });
        expect(findNode(state.nodes, "edit:traffic.replayers.replay.toTarget")?.inputHint).toMatchObject({
            kind: "reference",
            sourcePath: ["targetClusters"],
            options: [{label: "prod", value: "prod"}],
        });
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
        const addedS3Source = applyEditOperationToObject(parse(addedProxy.yaml), {
            op: "add",
            path: ["traffic", "s3Sources"],
            value: {name: "archive"},
        });
        const addedReplayer = applyEditOperationToObject(parse(addedS3Source.yaml), {
            op: "add",
            path: ["traffic", "replayers"],
            value: {name: "replay"},
        });
        const removedProxy = applyEditOperationToObject(parse(addedProxy.yaml), {
            op: "removeConfig",
            path: ["traffic", "proxies", "capture"],
        });

        expect(existingKafka.yaml).toContain("existing: {}");
        expect(addedProxy.yaml).toContain("capture:");
        expect(addedS3Source.yaml).toContain("archive:");
        expect(addedReplayer.yaml).toContain("fromCapturedTraffic: \"\"");
        expect(removedProxy.yaml).not.toContain("capture:");
    });

    it("appends snapshot migration configs without a resource name", () => {
        const result = applyEditOperationToObject({
            sourceClusters: {},
            targetClusters: {},
            snapshotMigrationConfigs: [],
        }, {
            op: "add",
            path: ["snapshotMigrationConfigs"],
            value: {},
        });

        expect(result.yaml).toContain("fromSource: \"\"");
        expect(result.yaml).toContain("toTarget: \"\"");
        expect(findNode(result.editState.nodes, "edit:snapshotMigrationConfigs.0")?.status).toBe("required");
    });

    it("keeps CLI stdout parseable when validation emits diagnostics", () => {
        const cliPath = path.resolve(__dirname, "../src/cliRouter.ts");
        const samplePath = path.resolve(__dirname, "../scripts/samples/proxyWithoutTlsNoAuth.wf.yaml");
        const result = spawnSync(
            process.execPath,
            ["--import", "tsx", cliPath, "editConfig", "state", "--pending-config", samplePath],
            {encoding: "utf8"}
        );

        expect(result.status).toBe(0);
        expect(result.stdout.trimStart().startsWith("{")).toBe(true);
        expect(() => JSON.parse(result.stdout)).not.toThrow();
        expect(result.stderr).toContain("TLS was auto-configured");
    });
});
