import {applyEditOperationToObject, buildEditStateFromObject} from "../src/editConfig";
import type {EditNode} from "../src/schemaEditModel";
import {buildUnifiedSchema, USER_PROXY_PROCESS_OPTION_KEYS, USER_PROXY_WORKFLOW_OPTION_KEYS} from "@opensearch-migrations/schemas";
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

function cleanLabel(node: EditNode | undefined): string {
    return (node?.label ?? "").replace(/^\[[^\]]+\]\s*/, "");
}

function withUnifiedSchemaFixture<T>(callback: () => T): T {
    const tempDir = mkdtempSync(path.join(tmpdir(), "edit-config-unified-schema-"));
    const schemaPath = path.join(tempDir, "workflowMigration.schema.json");
    const strimziFixturePath = path.resolve(__dirname, "../../schemas/tests/fixtures/strimzi/minimal-openapi.json");
    const previousPath = process.env.MIGRATION_UNIFIED_SCHEMA_PATH;
    try {
        writeFileSync(schemaPath, JSON.stringify(buildUnifiedSchema({strimziSchemaPath: strimziFixturePath}).schema));
        process.env.MIGRATION_UNIFIED_SCHEMA_PATH = schemaPath;
        return callback();
    } finally {
        if (previousPath === undefined) {
            delete process.env.MIGRATION_UNIFIED_SCHEMA_PATH;
        } else {
            process.env.MIGRATION_UNIFIED_SCHEMA_PATH = previousPath;
        }
        rmSync(tempDir, {recursive: true, force: true});
    }
}

describe("editConfig state", () => {
    it("uses the same top-level grouping as the resource view", () => {
        const state = buildEditStateFromObject({
            sourceClusters: {
                source: {endpoint: "https://source.example.com:9200", version: "ES 7.10"},
            },
            targetClusters: {
                target: {endpoint: "https://target.example.com:9200"},
            },
            kafkaClusterConfiguration: {kafka: {autoCreate: {}}},
            traffic: {proxies: {}, s3Sources: {}, replayers: {}},
            snapshotMigrationConfigs: [{fromSource: "source", toTarget: "target"}],
        });

        expect(state.nodes.map(cleanLabel)).toEqual([
            "Workflow Configuration",
            "Snapshot Migration",
            "Live Traffic Migration",
        ]);
        expect((state.nodes[0].children ?? []).map(cleanLabel)).toEqual([
            "Kafka Clusters",
            "Sources",
            "Targets",
        ]);
        expect((state.nodes[1].children ?? []).map(cleanLabel)).toEqual(["Backfill"]);
        expect((state.nodes[2].children ?? []).map(cleanLabel)).toEqual([
            "Capture",
            "Buffer",
            "Replay",
        ]);
    });

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
        expect(source?.statusCounts?.required).toBe(1);
        expect(auth?.status).toBe("required");
        expect(auth?.label).toContain("authConfig: < basic >");
        expect(secretName?.status).toBe("required");
        expect(secretName?.label).toContain("secretName: <required>");
        expect(secretName?.externalRef).toMatchObject({
            kind: "kubernetesResource",
            purpose: "http-basic-auth",
            matchProfiles: ["http-basic-auth-secret"],
            selection: {target: "scalarName"},
            k8s: {
                resourceTypes: [{group: "", version: "v1", kind: "Secret", namespaced: true}],
                match: {
                    requiredKeys: ["username", "password"],
                    acceptedSecretTypes: ["kubernetes.io/basic-auth", "Opaque"],
                },
            },
            create: {
                label: "HTTP Basic Auth Secret",
                apply: {target: "scalarName", nameField: "secretName"},
            },
        });
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
        expect(auth?.label).toContain("authConfig: < none >");
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
        expect(sourceClusters?.statusCounts?.required).toBe(1);
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

    it("unsets scalar values without creating missing parent objects", () => {
        const config = {
            sourceClusters: {
                legacy: {
                    endpoint: "https://legacy.example.com:9200",
                    allowInsecure: false,
                    version: "ES 7.10.2",
                },
            },
            targetClusters: {prod: {endpoint: "https://prod.example.com:9200"}},
            kafkaClusterConfiguration: {kafka: {autoCreate: {}}},
            snapshotMigrationConfigs: [],
        };

        const removedScalar = applyEditOperationToObject(config, {
            op: "unset",
            path: ["sourceClusters", "legacy", "allowInsecure"],
        });
        const removedMissingNested = applyEditOperationToObject(parse(removedScalar.yaml), {
            op: "unset",
            path: [
                "kafkaClusterConfiguration",
                "kafka",
                "autoCreate",
                "clusterSpecOverrides",
                "kafka",
                "replicas",
            ],
        });

        expect(removedScalar.yaml).not.toContain("allowInsecure:");
        expect(removedMissingNested.yaml).not.toContain("clusterSpecOverrides:");
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

        expect(cleanLabel(findNode(state.nodes, "edit:kafkaClusterConfiguration.default"))).toBe("default");
        expect(findNode(state.nodes, "edit:kafkaClusterConfiguration.default.autoCreate.auth")).toMatchObject({
            valueKind: "union",
            value: "unset",
            effectiveDefault: {
                label: "scram-sha-512",
                source: "workflow policy",
            },
        });
        expect(findNode(state.nodes, "edit:kafkaClusterConfiguration.default.autoCreate.auth")?.variants?.map(variant => variant.value)).toEqual([
            "unset",
            "none",
            "scram-sha-512",
        ]);
        expect(findNode(state.nodes, "edit:kafkaClusterConfiguration.default.autoCreate.auth")?.variants?.[0].label).toBe("default (scram-sha-512)");
        expect(findNode(state.nodes, "edit:kafkaClusterConfiguration.default.autoCreate.auth")?.label).toContain("auth: < default: scram-sha-512 >");
        expect(findNode(state.nodes, "edit:kafkaClusterConfiguration.default.autoCreate.clusterSpecOverrides")).toMatchObject({
            valueKind: "object",
            presence: "optional",
        });
        expect(findNode(state.nodes, "edit:kafkaClusterConfiguration.default.autoCreate.nodePoolSpecOverrides")).toMatchObject({
            valueKind: "object",
            presence: "optional",
        });
        expect(findNode(state.nodes, "edit:kafkaClusterConfiguration.default.autoCreate.topicSpecOverrides")).toMatchObject({
            valueKind: "object",
            presence: "optional",
        });
        expect(cleanLabel(findNode(state.nodes, "edit:traffic.proxies.capture"))).toBe("capture");
        expect(cleanLabel(findNode(state.nodes, "edit:traffic.s3Sources.archive"))).toBe("archive");
        expect(cleanLabel(findNode(state.nodes, "edit:traffic.replayers.replay"))).toBe("replay");
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

    it("renders generic object override fields from the unified JSON schema", () => withUnifiedSchemaFixture(() => {
        const state = buildEditStateFromObject({
            sourceClusters: {legacy: {endpoint: "https://legacy.example.com:9200", version: "ES 7.10.2"}},
            targetClusters: {prod: {endpoint: "https://prod.example.com:9200"}},
            kafkaClusterConfiguration: {
                kafka: {autoCreate: {}},
            },
            snapshotMigrationConfigs: [],
        });

        expect(findNode(state.nodes, "edit:kafkaClusterConfiguration.kafka.autoCreate.clusterSpecOverrides.kafka")).toMatchObject({
            valueKind: "object",
            presence: "optional",
        });
        expect(findNode(state.nodes, "edit:kafkaClusterConfiguration.kafka.autoCreate.clusterSpecOverrides.kafka.config.min.insync.replicas")).toMatchObject({
            valueKind: "scalar",
            valueType: "number",
        });
        expect(findNode(state.nodes, "edit:kafkaClusterConfiguration.kafka.autoCreate.nodePoolSpecOverrides.storage")).toMatchObject({
            valueKind: "union",
            value: "unset",
        });
        expect(findNode(state.nodes, "edit:kafkaClusterConfiguration.kafka.autoCreate.topicSpecOverrides.config.cleanup.policy")).toMatchObject({
            valueKind: "union",
        });
        expect(findNode(state.nodes, "edit:kafkaClusterConfiguration.kafka.autoCreate.nodePoolSpecOverrides.roles")).toMatchObject({
            valueKind: "array",
            presence: "optional",
        });
        expect(findNode(state.nodes, "edit:kafkaClusterConfiguration.kafka.autoCreate.nodePoolSpecOverrides.roles:add")).toMatchObject({
            valueKind: "command",
        });

        const compactTopic = applyEditOperationToObject({
            kafkaClusterConfiguration: {kafka: {autoCreate: {}}},
            snapshotMigrationConfigs: [],
        }, {
            op: "set",
            path: ["kafkaClusterConfiguration", "kafka", "autoCreate", "topicSpecOverrides", "config", "cleanup.policy"],
            value: "compact",
        });
        const persistentStorage = applyEditOperationToObject(parse(compactTopic.yaml), {
            op: "set",
            path: ["kafkaClusterConfiguration", "kafka", "autoCreate", "nodePoolSpecOverrides", "storage"],
            value: "persistent-claim",
        });

        expect(compactTopic.yaml).toContain("cleanup.policy: compact");
        expect(persistentStorage.yaml).toContain("type: persistent-claim");
        expect(findNode(persistentStorage.editState.nodes, "edit:kafkaClusterConfiguration.kafka.autoCreate.nodePoolSpecOverrides.storage.size")).toMatchObject({
            valueKind: "scalar",
            presence: "optional",
        });

        const addedRole = applyEditOperationToObject({
            kafkaClusterConfiguration: {kafka: {autoCreate: {}}},
            snapshotMigrationConfigs: [],
        }, {
            op: "add",
            path: ["kafkaClusterConfiguration", "kafka", "autoCreate", "nodePoolSpecOverrides", "roles"],
            value: {},
        });
        const roleItem = findNode(addedRole.editState.nodes, "edit:kafkaClusterConfiguration.kafka.autoCreate.nodePoolSpecOverrides.roles.0");
        expect(roleItem).toMatchObject({
            valueKind: "union",
            status: "required",
            collapsed: true,
        });
        expect(roleItem?.variants?.map(variant => variant.value)).toEqual(["broker", "controller"]);

        const appendedRole = applyEditOperationToObject({
            kafkaClusterConfiguration: {
                kafka: {
                    autoCreate: {
                        nodePoolSpecOverrides: {roles: ["broker"]},
                    },
                },
            },
            snapshotMigrationConfigs: [],
        }, {
            op: "add",
            path: ["kafkaClusterConfiguration", "kafka", "autoCreate", "nodePoolSpecOverrides", "roles"],
            value: {},
        });
        expect(parse(appendedRole.yaml).kafkaClusterConfiguration.kafka.autoCreate.nodePoolSpecOverrides.roles).toEqual(["broker", ""]);

        const setRole = applyEditOperationToObject(parse(addedRole.yaml), {
            op: "set",
            path: ["kafkaClusterConfiguration", "kafka", "autoCreate", "nodePoolSpecOverrides", "roles", "0"],
            value: "broker",
        });
        expect(setRole.yaml).toContain("- broker");

        const removedRole = applyEditOperationToObject(parse(setRole.yaml), {
            op: "removeConfig",
            path: ["kafkaClusterConfiguration", "kafka", "autoCreate", "nodePoolSpecOverrides", "roles", "0"],
        });
        expect(parse(removedRole.yaml).kafkaClusterConfiguration.kafka.autoCreate.nodePoolSpecOverrides.roles).toEqual([]);
    }));

    it("renders missing capture proxy options as visible required fields", () => {
        const state = buildEditStateFromObject({
            sourceClusters: {source: {endpoint: "", version: "ES 7.10.2"}},
            targetClusters: {},
            kafkaClusterConfiguration: {
                default: {autoCreate: {}},
            },
            traffic: {
                proxies: {
                    cap: {source: "source"},
                },
                replayers: {},
            },
            snapshotMigrationConfigs: [],
        });

        const captureGroup = findNode(state.nodes, "edit:traffic.proxies");
        const proxy = findNode(state.nodes, "edit:traffic.proxies.cap");
        const proxyConfig = findNode(state.nodes, "edit:traffic.proxies.cap.proxyConfig");
        const kafka = findNode(state.nodes, "edit:traffic.proxies.cap.kafka");
        const listenPort = findNode(state.nodes, "edit:traffic.proxies.cap.proxyConfig.listenPort");
        const kafkaTopic = findNode(state.nodes, "edit:traffic.proxies.cap.kafkaTopic");
        const podReplicas = findNode(state.nodes, "edit:traffic.proxies.cap.proxyConfig.podReplicas");
        const serviceType = findNode(state.nodes, "edit:traffic.proxies.cap.proxyConfig.serviceType");
        const tls = findNode(state.nodes, "edit:traffic.proxies.cap.proxyConfig.tls");
        const setHeader = findNode(state.nodes, "edit:traffic.proxies.cap.proxyConfig.setHeader");
        const addProxy = findNode(state.nodes, "edit:traffic.proxies:add");

        const expectedOptionKeys = [
            ...USER_PROXY_WORKFLOW_OPTION_KEYS,
            ...USER_PROXY_PROCESS_OPTION_KEYS,
        ].map(String);
        for (const key of expectedOptionKeys) {
            expect(findNode(state.nodes, `edit:traffic.proxies.cap.proxyConfig.${key}`)).toBeDefined();
        }
        expect(proxy?.status).toBe("required");
        expect(proxy?.statusCounts?.required).toBe(1);
        expect(proxyConfig?.status).toBe("required");
        expect(proxyConfig?.statusCounts?.required).toBe(1);
        expect(proxyConfig?.required).toBe(true);
        expect(proxyConfig?.presence).toBe("required");
        expect(kafka).toMatchObject({status: "ok", presence: "optional", value: "default", valueDefaulted: true});
        expect(kafka?.label).toContain("kafka: default");
        expect(kafkaTopic?.valueDefaulted).toBeUndefined();
        expect(listenPort?.status).toBe("required");
        expect(listenPort?.presence).toBe("required");
        expect(listenPort?.valueType).toBe("number");
        expect(listenPort?.label).toContain("listenPort: <required>");
        expect(podReplicas).toMatchObject({status: "ok", presence: "optional", expert: false, valueType: "number"});
        expect(serviceType).toMatchObject({status: "ok", presence: "optional", expert: true});
        expect(tls).toMatchObject({presence: "optional", valueKind: "union", value: "unset"});
        expect(setHeader).toMatchObject({presence: "optional", valueKind: "array"});
        expect(kafkaTopic?.status).toBe("ok");
        expect(kafkaTopic?.label).toContain("kafkaTopic: <unset>");
        expect(captureGroup?.statusCounts?.required).toBe(1);
        expect(addProxy?.status).toBe("ok");
        expect(addProxy?.label).toContain("+ Add capture proxy");
    });

    it("does not require replay config when traffic capture is configured alone", () => {
        const state = buildEditStateFromObject({
            sourceClusters: {source: {endpoint: "", version: "ES 7.10.2"}},
            targetClusters: {},
            traffic: {
                proxies: {
                    cap: {source: "source", proxyConfig: {listenPort: 9201}},
                },
            },
            snapshotMigrationConfigs: [],
        });

        const traffic = findNode(state.nodes, "edit:traffic");
        const replayGroup = findNode(state.nodes, "edit:traffic.replayers");
        const addReplay = findNode(state.nodes, "edit:traffic.replayers:add");

        expect(state.validation.valid).toBe(true);
        expect(traffic?.status).toBe("ok");
        expect(replayGroup?.status).toBe("ok");
        expect(addReplay?.status).toBe("ok");
    });

    it("renders proxy TLS existingSecret with an external Secret reference", () => {
        const state = buildEditStateFromObject({
            sourceClusters: {source: {endpoint: "", version: "ES 7.10.2"}},
            targetClusters: {},
            traffic: {
                proxies: {
                    cap: {
                        source: "source",
                        proxyConfig: {
                            listenPort: 9201,
                            tls: {mode: "existingSecret"},
                        },
                    },
                },
            },
            snapshotMigrationConfigs: [],
        });

        const tls = findNode(state.nodes, "edit:traffic.proxies.cap.proxyConfig.tls");
        const secretName = findNode(state.nodes, "edit:traffic.proxies.cap.proxyConfig.tls.secretName");

        expect(tls).toMatchObject({valueKind: "union", value: "existingSecret", status: "required"});
        expect(tls?.variants?.map(variant => variant.value)).toEqual([
            "unset",
            "existingSecret",
            "certManager",
            "plaintext",
        ]);
        expect(secretName).toMatchObject({
            valueKind: "scalar",
            required: true,
            status: "required",
            externalRef: {
                kind: "kubernetesResource",
                purpose: "proxy-server-tls",
                matchProfiles: ["tls-secret"],
                selection: {target: "scalarName"},
                k8s: {
                    resourceTypes: [{group: "", version: "v1", kind: "Secret", namespaced: true}],
                    match: {
                        requiredKeys: ["tls.crt", "tls.key"],
                        acceptedSecretTypes: ["kubernetes.io/tls", "Opaque"],
                    },
                },
                create: {
                    label: "TLS Certificate Secret",
                    apply: {target: "scalarName", nameField: "secretName"},
                },
            },
        });
    });

    it("renders proxy TLS certManager issuerRef as a Kubernetes object reference", () => {
        const state = buildEditStateFromObject({
            sourceClusters: {source: {endpoint: "", version: "ES 7.10.2"}},
            targetClusters: {},
            traffic: {
                proxies: {
                    cap: {
                        source: "source",
                        proxyConfig: {
                            listenPort: 9201,
                            tls: {
                                mode: "certManager",
                                issuerRef: {name: "migrations-ca", kind: "ClusterIssuer"},
                                dnsNames: ["cap.default.svc.cluster.local"],
                            },
                        },
                    },
                },
            },
            snapshotMigrationConfigs: [],
        });

        const issuerRef = findNode(state.nodes, "edit:traffic.proxies.cap.proxyConfig.tls.issuerRef");
        const dnsNames = findNode(state.nodes, "edit:traffic.proxies.cap.proxyConfig.tls.dnsNames");
        const dnsName = findNode(state.nodes, "edit:traffic.proxies.cap.proxyConfig.tls.dnsNames.0");
        const addDnsName = findNode(state.nodes, "edit:traffic.proxies.cap.proxyConfig.tls.dnsNames:add");

        expect(issuerRef).toMatchObject({
            valueKind: "object",
            value: {name: "migrations-ca", kind: "ClusterIssuer"},
            externalRef: {
                kind: "kubernetesResource",
                purpose: "cert-manager-issuer",
                selection: {target: "objectRef"},
                k8s: {
                    resourceTypes: [
                        {group: "cert-manager.io", version: "v1", kind: "Issuer", namespaced: true},
                        {group: "cert-manager.io", version: "v1", kind: "ClusterIssuer", namespaced: false},
                        {group: "awspca.cert-manager.io", version: "v1beta1", kind: "AWSPCAClusterIssuer", namespaced: false},
                    ],
                },
            },
        });
        expect(issuerRef?.label).toContain("issuerRef: migrations-ca (ClusterIssuer)");
        expect(dnsNames).toMatchObject({
            valueKind: "array",
            required: true,
            status: "ok",
        });
        expect(dnsNames?.label).toContain("dnsNames: 1 item");
        expect(dnsName).toMatchObject({
            valueKind: "scalar",
            value: "cap.default.svc.cluster.local",
            valueType: "string",
            collapsed: true,
        });
        expect(dnsName?.label).toContain("DNS name 1: cap.default.svc.cluster.local");
        expect(addDnsName).toMatchObject({
            valueKind: "command",
            label: "+ Add DNS name",
            command: {requiresName: false},
        });
    });

    it("applies proxy TLS mode changes and refreshes required children", () => {
        const result = applyEditOperationToObject({
            sourceClusters: {source: {endpoint: "", version: "ES 7.10.2"}},
            targetClusters: {},
            traffic: {
                proxies: {
                    cap: {source: "source", proxyConfig: {listenPort: 9201}},
                },
            },
            snapshotMigrationConfigs: [],
        }, {
            op: "set",
            path: ["traffic", "proxies", "cap", "proxyConfig", "tls"],
            value: "existingSecret",
        });

        const tls = findNode(result.editState.nodes, "edit:traffic.proxies.cap.proxyConfig.tls");
        const secretName = findNode(result.editState.nodes, "edit:traffic.proxies.cap.proxyConfig.tls.secretName");

        expect(result.yaml).toContain("mode: existingSecret");
        expect(result.yaml).toContain("secretName: \"\"");
        expect(tls?.label).toContain("tls: < existingSecret >");
        expect(secretName?.status).toBe("required");
    });

    it("edits cert-manager DNS names as a schema-driven string list", () => {
        const certManager = applyEditOperationToObject({
            sourceClusters: {source: {endpoint: "", version: "ES 7.10.2"}},
            targetClusters: {},
            traffic: {
                proxies: {
                    cap: {source: "source", proxyConfig: {listenPort: 9201}},
                },
            },
            snapshotMigrationConfigs: [],
        }, {
            op: "set",
            path: ["traffic", "proxies", "cap", "proxyConfig", "tls"],
            value: "certManager",
        });

        const emptyDnsNames = findNode(certManager.editState.nodes, "edit:traffic.proxies.cap.proxyConfig.tls.dnsNames");
        const addDnsName = findNode(certManager.editState.nodes, "edit:traffic.proxies.cap.proxyConfig.tls.dnsNames:add");

        expect(parse(certManager.yaml).traffic.proxies.cap.proxyConfig.tls.dnsNames).toEqual([]);
        expect(emptyDnsNames).toMatchObject({
            valueKind: "array",
            status: "required",
            diagnostics: expect.arrayContaining([
                expect.objectContaining({severity: "required"}),
            ]),
        });
        expect(addDnsName?.label).toBe("+ Add DNS name");

        const added = applyEditOperationToObject(parse(certManager.yaml), {
            op: "add",
            path: ["traffic", "proxies", "cap", "proxyConfig", "tls", "dnsNames"],
            value: {},
        });
        const addedItem = findNode(added.editState.nodes, "edit:traffic.proxies.cap.proxyConfig.tls.dnsNames.0");

        expect(parse(added.yaml).traffic.proxies.cap.proxyConfig.tls.dnsNames).toEqual([""]);
        expect(addedItem).toMatchObject({
            valueKind: "scalar",
            status: "required",
            value: "",
        });
        expect(addedItem?.label).toContain("DNS name 1: <required>");

        const set = applyEditOperationToObject(parse(added.yaml), {
            op: "set",
            path: ["traffic", "proxies", "cap", "proxyConfig", "tls", "dnsNames", "0"],
            value: "cap.default.svc.cluster.local",
        });
        expect(parse(set.yaml).traffic.proxies.cap.proxyConfig.tls.dnsNames).toEqual(["cap.default.svc.cluster.local"]);

        const removed = applyEditOperationToObject(parse(set.yaml), {
            op: "removeConfig",
            path: ["traffic", "proxies", "cap", "proxyConfig", "tls", "dnsNames", "0"],
        });
        expect(parse(removed.yaml).traffic.proxies.cap.proxyConfig.tls.dnsNames).toEqual([]);
    });

    it("renders proxy clientAuth with console client certificate Secret reference", () => {
        const pem = [
            "-----BEGIN CERTIFICATE-----",
            "abc",
            "-----END CERTIFICATE-----",
        ].join("\n");
        const state = buildEditStateFromObject({
            sourceClusters: {source: {endpoint: "", version: "ES 7.10.2"}},
            targetClusters: {},
            traffic: {
                proxies: {
                    cap: {
                        source: "source",
                        proxyConfig: {
                            listenPort: 9201,
                            tls: {
                                mode: "existingSecret",
                                secretName: "proxy-tls",
                                clientAuth: {
                                    trustedClientCaPem: pem,
                                    consoleClientSecretName: "console-client-cert",
                                },
                            },
                        },
                    },
                },
            },
            snapshotMigrationConfigs: [],
        });

        const clientAuth = findNode(state.nodes, "edit:traffic.proxies.cap.proxyConfig.tls.clientAuth");
        const consoleSecret = findNode(
            state.nodes,
            "edit:traffic.proxies.cap.proxyConfig.tls.clientAuth.consoleClientSecretName"
        );

        expect(clientAuth).toMatchObject({valueKind: "union", value: "enabled", status: "ok"});
        expect(clientAuth?.variants?.map(variant => variant.value)).toEqual(["disabled", "enabled"]);
        expect(consoleSecret).toMatchObject({
            valueKind: "scalar",
            value: "console-client-cert",
            presence: "optional",
            externalRef: {
                kind: "kubernetesResource",
                purpose: "proxy-console-client-tls",
                matchProfiles: ["tls-secret"],
                selection: {target: "scalarName"},
                k8s: {
                    resourceTypes: [{group: "", version: "v1", kind: "Secret", namespaced: true}],
                    match: {
                        requiredKeys: ["tls.crt", "tls.key"],
                        acceptedSecretTypes: ["kubernetes.io/tls", "Opaque"],
                    },
                },
                create: {
                    label: "Proxy Client Certificate Secret",
                    apply: {target: "scalarName", nameField: "secretName"},
                },
            },
        });
    });

    it("applies proxy clientAuth mode changes without dropping the TLS mode", () => {
        const result = applyEditOperationToObject({
            sourceClusters: {source: {endpoint: "", version: "ES 7.10.2"}},
            targetClusters: {},
            traffic: {
                proxies: {
                    cap: {
                        source: "source",
                        proxyConfig: {
                            listenPort: 9201,
                            tls: {mode: "existingSecret", secretName: "proxy-tls"},
                        },
                    },
                },
            },
            snapshotMigrationConfigs: [],
        }, {
            op: "set",
            path: ["traffic", "proxies", "cap", "proxyConfig", "tls", "clientAuth"],
            value: "enabled",
        });

        const tls = findNode(result.editState.nodes, "edit:traffic.proxies.cap.proxyConfig.tls");
        const clientAuth = findNode(result.editState.nodes, "edit:traffic.proxies.cap.proxyConfig.tls.clientAuth");

        expect(result.yaml).toContain("mode: existingSecret");
        expect(result.yaml).toContain("clientAuth:");
        expect(result.yaml).toContain("required: true");
        expect(tls?.label).toContain("tls: < existingSecret >");
        expect(clientAuth).toMatchObject({valueKind: "union", value: "enabled"});
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
        const scramKafka = applyEditOperationToObject(parse(existingKafka.yaml), {
            op: "set",
            path: ["kafkaClusterConfiguration", "default", "existing", "auth"],
            value: "scram-sha-512",
        });
        const defaultAuthKafka = applyEditOperationToObject(parse(addedKafka.yaml), {
            op: "set",
            path: ["kafkaClusterConfiguration", "default", "autoCreate", "auth"],
            value: "scram-sha-512",
        });
        const resetDefaultAuthKafka = applyEditOperationToObject(parse(defaultAuthKafka.yaml), {
            op: "set",
            path: ["kafkaClusterConfiguration", "default", "autoCreate", "auth"],
            value: "unset",
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
        expect(findNode(existingKafka.editState.nodes, "edit:kafkaClusterConfiguration.default.existing.kafkaConnection")).toMatchObject({
            status: "required",
            required: true,
        });
        expect(findNode(existingKafka.editState.nodes, "edit:kafkaClusterConfiguration.default.existing.kafkaTopic")).toMatchObject({
            valueKind: "scalar",
            presence: "optional",
        });
        expect(findNode(existingKafka.editState.nodes, "edit:kafkaClusterConfiguration.default.existing.auth")).toMatchObject({
            valueKind: "union",
            value: "none",
        });
        expect(scramKafka.yaml).toContain("type: scram-sha-512");
        expect(findNode(scramKafka.editState.nodes, "edit:kafkaClusterConfiguration.default.existing.auth.secretName")).toMatchObject({
            status: "required",
            required: true,
            externalRef: {
                kind: "kubernetesResource",
                purpose: "kafka-scram-password",
                matchProfiles: ["kafka-scram-password-secret"],
                selection: {target: "scalarName"},
                k8s: {
                    resourceTypes: [{group: "", version: "v1", kind: "Secret", namespaced: true}],
                    match: {
                        requiredKeys: ["password"],
                    },
                },
                create: {
                    label: "Kafka SCRAM Password Secret",
                    apply: {target: "scalarName", nameField: "secretName"},
                },
            },
        });
        expect(findNode(scramKafka.editState.nodes, "edit:kafkaClusterConfiguration.default.existing.auth.caSecretName")).toMatchObject({
            status: "ok",
            required: false,
            presence: "optional",
            externalRef: {
                kind: "kubernetesResource",
                purpose: "kafka-ca",
                matchProfiles: ["kafka-ca-secret"],
                selection: {target: "scalarName"},
                k8s: {
                    resourceTypes: [{group: "", version: "v1", kind: "Secret", namespaced: true}],
                    match: {
                        requiredKeys: ["ca.crt"],
                        contentValidationIds: ["pem-certificate-chain"],
                    },
                },
                create: {
                    label: "Kafka CA Secret",
                    apply: {target: "scalarName", nameField: "secretName"},
                },
            },
        });
        expect(findNode(scramKafka.editState.nodes, "edit:kafkaClusterConfiguration.default.existing.auth.kafkaUserName")).toMatchObject({
            status: "required",
            required: true,
            inputHint: {
                kind: "text",
                format: "k8s-name",
            },
        });
        expect(defaultAuthKafka.yaml).toContain("auth:");
        expect(defaultAuthKafka.yaml).toContain("type: scram-sha-512");
        expect(resetDefaultAuthKafka.yaml).not.toContain("auth:");
        expect(addedProxy.yaml).toContain("capture:");
        expect(addedProxy.yaml).toContain("proxyConfig: {}");
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
            {encoding: "utf8", maxBuffer: 5 * 1024 * 1024}
        );

        expect(result.status).toBe(0);
        expect(result.stdout.trimStart().startsWith("{")).toBe(true);
        expect(() => JSON.parse(result.stdout)).not.toThrow();
        expect(result.stderr).toContain("TLS was auto-configured");
    });
});
