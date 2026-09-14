import {
    applyEditOperationToObject,
    buildEditStateFromObject as buildEditStateFromObjectRaw,
    buildEditStateFromObjectForSubmit as buildEditStateFromObjectForSubmitRaw,
} from "../src/editConfig";
import type {EditNode} from "../src/schemaEditModel";
import {buildUnifiedSchema, DNS_NAME_PATTERN, USER_PROXY_PROCESS_OPTION_KEYS, USER_PROXY_WORKFLOW_OPTION_KEYS,} from "@opensearch-migrations/schemas";
import {parse} from "yaml";
import {spawnSync} from "child_process";
import path from "path";
import {mkdtempSync, rmSync, writeFileSync} from "fs";
import {tmpdir} from "os";
import {pathToFileURL} from "url";

const TSX_IMPORT = pathToFileURL(require.resolve("tsx")).href;

function withExplicitKafkaTopics<T>(value: T): T {
    const config = structuredClone(value) as any;
    const traffic = config?.traffic;
    if (!traffic) return config;
    const clusters = traffic.kafkaClusters ?? {};
    for (const cluster of Object.values(clusters) as any[]) {
        cluster.topics ??= {};
    }
    for (const [name, source] of Object.entries({
        ...(traffic.proxies ?? {}),
        ...(traffic.s3Sources ?? {}),
    }) as [string, any][]) {
        if (!source.kafka || !clusters[source.kafka]) continue;
        source.kafkaTopic ??= name;
        clusters[source.kafka].topics[source.kafkaTopic] ??= {};
    }
    return config;
}

function buildEditStateFromObject(
    value: unknown,
    validationOverride?: Parameters<typeof buildEditStateFromObjectRaw>[1],
) {
    return buildEditStateFromObjectRaw(
        withExplicitKafkaTopics(value),
        validationOverride,
    );
}

function buildEditStateFromObjectForSubmit(value: unknown) {
    return buildEditStateFromObjectForSubmitRaw(withExplicitKafkaTopics(value));
}

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

function withUnifiedSchemaFixture<T>(callback: (schemaPath: string) => T): T {
    const tempDir = mkdtempSync(path.join(tmpdir(), "edit-config-unified-schema-"));
    const schemaPath = path.join(tempDir, "workflowMigration.schema.json");
    const strimziFixturePath = path.resolve(__dirname, "../../schemas/tests/fixtures/strimzi/minimal-openapi.json");
    const previousPath = process.env.MIGRATION_UNIFIED_SCHEMA_PATH;
    try {
        writeFileSync(schemaPath, JSON.stringify(buildUnifiedSchema({strimziSchemaPath: strimziFixturePath}).schema));
        process.env.MIGRATION_UNIFIED_SCHEMA_PATH = schemaPath;
        return callback(schemaPath);
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
    it("surfaces unknown keys inside wrapped schema branches", () => {
        const state = withUnifiedSchemaFixture(() => buildEditStateFromObject({
            sourceClusters: {
                source: {
                    endpoint: "http://example.com",
                    version: "ES 7.10",
                    snapshotInfo: {
                        repos: {
                            repo: {awsRegion: "us-east-1", repoPathUri: "s3://bucket/path",},
                        },
                        snapshots: {
                            snap1: {
                                repoName: "repo",
                                config: {externallyManagedSnapshotName: "snap-1"},
                            },
                        },
                    },
                },
            },
            targetClusters: {
                target: {endpoint: "https://target.example.com:9200"},
            },
            snapshotMigrationConfigs: [{
                fromSource: "source",
                toTarget: "target",
                fromSnapshot: "snap1",
                slice: "slice-0",
                documentBackfillConfig: {
                    podReplicas: 1,
                    documentBackfillPodReplicas: 2,
                },
            }],
        }),);

        expect(state.validation.valid).toBe(false);
        expect(state.validation.diagnostics).toEqual(expect.arrayContaining([
            expect.objectContaining({
                severity: "error",
                message: "Unrecognized key 'documentBackfillPodReplicas'",
                path: [
                    "snapshotMigrationConfigs",
                    "0",
                    "documentBackfillConfig",
                    "documentBackfillPodReplicas",
                ],
            }),
        ]),);

        const backfillConfig = findNode(
            state.nodes, "edit:snapshotMigrationConfigs.0.documentBackfillConfig"
        );
        const unknownField = findNode(
            state.nodes,
            "edit:snapshotMigrationConfigs.0.documentBackfillConfig.documentBackfillPodReplicas",
        );

        expect(backfillConfig).toMatchObject({
            status: "error",
            statusCounts: {errors: 1},
        });
        expect(unknownField).toMatchObject({
            label: "documentBackfillPodReplicas: 2",
            status: "error",
            diagnostics: expect.arrayContaining([
                expect.objectContaining({
                    message: "Unrecognized key 'documentBackfillPodReplicas'",
                }),
            ]),
        });
    });

    it("uses the same top-level grouping as the resource view", () => {
        const state = buildEditStateFromObject({
            sourceClusters: {
                source: {endpoint: "https://source.example.com:9200", version: "ES 7.10",},
            },
            targetClusters: {
                target: {endpoint: "https://target.example.com:9200"},
            },
            traffic: {kafkaClusters: {kafka: {autoCreate: {}}}, proxies: {}, s3Sources: {}, replayers: {},},
            snapshotMigrationConfigs: [{fromSource: "source", toTarget: "target",
                    fromSnapshot: "",
                    slices: [],},],
        });

        expect(state.nodes.map(cleanLabel)).toEqual([
            "Workflow Configuration",
            "Snapshot Migration",
            "Live Traffic Migration",
        ]);
        expect((state.nodes[0].children ?? []).map(cleanLabel)).toEqual([
            "Sources",
            "Targets"
        ]);
        expect((state.nodes[1].children ?? []).map(cleanLabel)).toEqual(["Snapshot migrations"]);
        expect((state.nodes[2].children ?? []).map(cleanLabel)).toEqual([
            "Buffer",
            "Capture",
            "Replay"
        ]);
        expect(findNode(state.nodes, "edit:traffic.kafkaClusters")).toMatchObject({
            essential: true,
        });
        expect(findNode(state.nodes, "edit:sourceClusters")?.inputHint).toMatchObject({
            kind: "record",
            keyFormat: "k8s-name",
            keyPattern: expect.any(String),
            message: expect.stringContaining("alias"),
            resourceCollection: {
                navigation: {
                    sectionId: "section:Sources",
                    sectionLabel: "Sources",
                    sectionOrder: 0,
                    groupId: "group:Sources:Sources",
                    groupLabel: "Sources",
                    groupOrder: 0,
                },
                resource: {
                    plural: "sourceconfigs",
                    typeLabel: "Source cluster",
                    identity: {kind: "named"},
                },
            },
        });
        expect(findNode(state.nodes, "edit:sourceClusters:add")).toMatchObject({
            inputHint: {
                kind: "text",
                format: "k8s-name",
                pattern: expect.any(String),
                message: expect.stringContaining("alias"),
            },
            validation: {
                pattern: expect.any(String),
                message: expect.stringContaining("alias"),
            },
        });
        expect(findNode(state.nodes, "edit:traffic.s3Sources")?.inputHint).toMatchObject({
            resourceCollection: {
                navigation: {
                    sectionId: "section:Live Traffic Migration",
                    groupId: "group:Live Traffic Migration:Buffer:Previously Captured Traffic",
                    groupLabel: "Previously Captured Traffic",
                    groupOrder: 1,
                    parentGroupId: "group:Live Traffic Migration:Buffer",
                    parentGroupLabel: "Buffer",
                    parentGroupOrder: 0,
                },
                resource: {
                    plural: "capturedtraffics",
                    typeLabel: "Previously captured traffic",
                    identity: {kind: "named", suffix: "-topic"},
                },
            },
        });
        expect(findNode(state.nodes, "edit:snapshotMigrationConfigs")?.inputHint).toMatchObject({
            resourceCollection: {
                navigation: {
                    sectionId: "section:Snapshot Migration",
                    groupId: "group:Snapshot Migration:Backfill",
                    addControlId: "section:Snapshot Migration",
                },
                resource: {
                    plural: "snapshotmigrations",
                    typeLabel: "Snapshot migration",
                    identity: {
                        kind: "indexed-config",
                        prefix: "slice-",
                        firstIndex: 0,
                    },
                },
            },
        });
        expect(findNode(state.nodes, "edit:traffic.s3Sources")).toMatchObject({
            expert: true,
        });
        expect(findNode(state.nodes, "edit:traffic.s3Sources:add")).toMatchObject({
            expert: true,
            label: "+ Add optional S3 archive source (no capture proxy)",
        });
        expect(findNode(state.nodes, "edit:snapshotMigrationConfigs:add")).toMatchObject({
            label: "+ Add snapshot migration",
            command: {
                requiresName: true,
                autoEditAdded: true,
            },
            validation: {
                pattern: expect.any(String),
                message: expect.stringContaining("alias"),
            },
        });
        expect(findNode(state.nodes, "edit:snapshotMigrationConfigs")).toMatchObject({
            status: "required",
        });
        expect(findNode(state.nodes, "edit:snapshotMigrationConfigs.0")).toMatchObject({
            status: "required",
            diagnostics: expect.arrayContaining([
                expect.objectContaining({
                    message: "Add metadata migration, document backfill, or both.",
                }),
            ]),
        });
    });

    it("does not synthesize an unreferenced default Kafka cluster", () => {
        const config = {
            sourceClusters: {},
            targetClusters: {},
            traffic: {
                kafkaClusters: {},
                proxies: {},
                s3Sources: {},
                replayers: {},
            },
            snapshotMigrationConfigs: [],
        };

        const state = buildEditStateFromObject(config);
        expect(findNode(
            state.nodes,
            "edit:traffic.kafkaClusters.default",
        )).toBeUndefined();
        expect(findNode(
            state.nodes,
            "edit:traffic.kafkaClusters:add",
        )).toMatchObject({
            label: "+ Add Kafka cluster",
            command: {
                requiresName: true,
            },
        });
        expect(Object.hasOwn(config.traffic.kafkaClusters, "default"))
            .toBe(false);
    });

    it("offers every source for snapshot migration before snapshots are configured", () => {
        const state = buildEditStateFromObject({
            sourceClusters: {
                foo: {
                    endpoint: "https://foo.example.com:9200",
                    version: "ES 7.10.2",
                },
                ready: {
                    endpoint: "https://ready.example.com:9200",
                    version: "ES 7.10.2",
                    snapshotInfo: {
                        snapshots: {
                            snap1: {
                                repoName: "",
                                config: {externallyManagedSnapshotName: "snap1"},
                            },
                        },
                    },
                },
            },
            targetClusters: {
                target: {endpoint: "https://target.example.com:9200"},
            },
            snapshotMigrationConfigs: [{
                fromSource: "",
                toTarget: "target",
                    fromSnapshot: "",
                    slices: [],},],
        });

        expect(findNode(
            state.nodes,
            "edit:snapshotMigrationConfigs.0.fromSource"
        )?.inputHint).toMatchObject({
            kind: "reference",
            options: [
                {
                    label: "foo",
                    value: "foo",
                    description: "No snapshots defined",
                },
                {
                    label: "ready",
                    value: "ready",
                    description: "1 snapshot defined",
                },
            ],
        });
    });

    it("requires a snapshot migration to contain metadata or document backfill work", () => {
        const state = buildEditStateFromObject({
            sourceClusters: {
                foo: {
                    endpoint: "https://foo.example.com:9200",
                    version: "ES 7.10.2",
                    snapshotInfo: {
                        snapshots: {
                            snap: {
                                repoName: "",
                                config: {externallyManagedSnapshotName: "snap"},
                            },
                        },
                    },
                },
            },
            targetClusters: {
                target: {endpoint: "https://target.example.com:9200"},
            },
            snapshotMigrationConfigs: [{
                fromSource: "foo",
                toTarget: "target",
                    fromSnapshot: "snap",
                    slices: {"slice-0": {}},},],
        });

        expect(state.validation.valid).toBe(false);
        expect(findNode(
            state.nodes, "edit:snapshotMigrationConfigs.0"
        )).toMatchObject({
            status: "required",
            diagnostics: expect.arrayContaining([
                expect.objectContaining({
                    severity: "required",
                    message: "Add metadata migration, document backfill, or both.",
                }),
            ]),
        });
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

    it("does not mark snapshot migration as required when live traffic is configured", () => {
        const state = buildEditStateFromObject({
            sourceClusters: {
                source: {
                    endpoint: "https://source.example.com:9200",
                    version: "ES 7.10.2",
                },
            },
            targetClusters: {},
            traffic: {
                kafkaClusters: {default: {autoCreate: {}}},
                proxies: {
                    capture: {
                        source: "source",
                        kafka: "default",
                        proxyConfig: {listenPort: 9201},
                    },
                },
            },
        });

        expect(state.validation.valid).toBe(true);
        expect(findNode(state.nodes, "edit:snapshotMigrationConfigs")).toMatchObject({
            status: "ok",
        });
        expect(findNode(state.nodes, "edit:snapshotMigrationConfigs")?.required).not.toBe(true);
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

    it("keeps full-transform diagnostics visible when they do not map to an edit path", () => {
        const state = buildEditStateFromObject({
            sourceClusters: {source: {endpoint: "https://source.example.com:9200", version: "ES 7.10.2",},},
            targetClusters: {target: {endpoint: "https://target.example.com:9200"},},
            snapshotMigrationConfigs: [],
        }, {
            valid: false,
            errors: ["Transformed workflow config is invalid."],
            diagnostics: [{
                severity: "error",
                message: "Transformed workflow config is invalid.",
                path: ["workflow", "generated", "field"],
            },],
        },);

        expect(state.nodes[0]).toMatchObject({
            status: "error",
            essential: true,
            diagnostics: expect.arrayContaining([
                expect.objectContaining({
                    message: "Transformed workflow config is invalid.",
                    path: ["workflow", "generated", "field"],
                }),
            ]),
        });
    });

    it("attaches submit validation diagnostics for duplicate source proxies to the visible capture group", async () => {
        const state = await buildEditStateFromObjectForSubmit({
            sourceClusters: {
                source: {
                    endpoint: "https://source.example.com:9200",
                    version: "ES 7.10.2",
                    snapshotInfo: {
                        repos: {
                            repo: {repoPathUri: "s3://bucket/path", awsRegion: "us-east-1"},
                        },
                        snapshots: {
                            s1: {
                                repoName: "repo",
                                config: {createSnapshotConfig: {}},
                            },
                        },
                    },
                },
            },
            targetClusters: {target: {endpoint: "https://target.example.com:9200"},},
            traffic: {
                kafkaClusters: {
                    default: {
                        autoCreate: {
                            auth: {type: "none"},
                        },
                    },
                },
                proxies: {
                    cap: {source: "source", kafka: "default", proxyConfig: {listenPort: 9201}},
                    c2: {source: "source", kafka: "default", proxyConfig: {listenPort: 9202}},
                },
                s3Sources: {},
                replayers: {},
            },
            snapshotMigrationConfigs: [],
        });

        const capture = findNode(state.nodes, "edit:traffic.proxies");

        expect(state.validation.valid).toBe(false);
        expect(state.validation.diagnostics).toEqual(expect.arrayContaining([
            expect.objectContaining({
                path: ["traffic", "proxies"],
                message: expect.stringContaining("maps to multiple proxies"),
            }),
        ]),);
        expect(capture).toMatchObject({
            status: "error",
            diagnostics: expect.arrayContaining([
                expect.objectContaining({
                    path: ["traffic", "proxies"],
                    message: expect.stringContaining("maps to multiple proxies"),
                }),
            ]),
        });
    });

    it("validates LocalStack repositories without resolving cluster DNS", async () => {
        const state = await buildEditStateFromObjectForSubmit({
            sourceClusters: {
                source: {
                    endpoint: "https://source.example.com:9200",
                    version: "ES 7.10.2",
                    snapshotInfo: {
                        repos: {
                            repo: {
                                repoPathUri: "s3://bucket/path",
                                endpoint: "localstack://not-resolvable.invalid:4566",
                                awsRegion: "us-east-1",
                            },
                        },
                        snapshots: {
                            s1: {
                                repoName: "repo",
                                config: {createSnapshotConfig: {}},
                            },
                        },
                    },
                },
            },
            targetClusters: {
                target: {endpoint: "https://target.example.com:9200"},
            },
            snapshotMigrationConfigs: [],
        });

        expect(state.validation).toEqual({
            valid: true,
            errors: [],
        });
    });

    it("marks the AWS region as required for an S3 snapshot repository", () => {
        const state = buildEditStateFromObject({
            sourceClusters: {
                source: {
                    endpoint: "https://source.example.com:9200",
                    version: "ES 7.10.2",
                    snapshotInfo: {
                        repos: {
                            repo: {
                                repoPathUri: "s3://bucket/path",
                            },
                        },
                    },
                },
            },
            targetClusters: {},
            snapshotMigrationConfigs: [],
        });

        expect(state.validation.valid).toBe(false);
        expect(findNode(
            state.nodes,
            "edit:sourceClusters.source.snapshotInfo.repos.repo.awsRegion"
        )).toMatchObject({
            presence: "required",
            required: true,
            status: "required",
            diagnostics: expect.arrayContaining([
                expect.objectContaining({
                    message: "AWS region is required for s3:// snapshot repositories.",
                }),
            ]),
        });
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

        expect(version?.inputHint).toMatchObject({kind: "text", format: "cluster-version",});
        expect(version?.validation?.pattern).toContain("ES");
        expect(version?.status).toBe("error");
        expect(version?.diagnostics?.[0].message).toContain("Use '<ENGINE> <VERSION>'");
        expect(endpoint?.inputHint).toMatchObject({kind: "text", format: "http-endpoint",});
        expect(endpoint?.validation?.message).toContain("http:// or https://");
        expect(endpoint?.status).toBe("error");
    });

    it("keeps clusters and invalid snapshot repository names editable", async () => {
        const state = await buildEditStateFromObjectForSubmit({
            sourceClusters: {
                source: {
                    endpoint: "https://source.example.com:9200",
                    version: "OS 2.19",
                    snapshotInfo: {
                        repos: {
                            "r a": {
                                awsRegion: "us-east-1",
                                repoPathUri: "s3://bucket/path",
                            },
                        },
                        snapshots: {
                            snap1: {
                                repoName: "r a",
                                config: {externallyManagedSnapshotName: "snapshot-1"},
                            },
                        },
                    },
                },
            },
            targetClusters: {
                target: {endpoint: "https://target.example.com:9200"},
            },
            snapshotMigrationConfigs: [],
        });

        expect(findNode(state.nodes, "edit:sourceClusters.source")).toBeDefined();
        expect(findNode(state.nodes, "edit:targetClusters.target")).toBeDefined();
        expect(findNode(
            state.nodes,
            "edit:sourceClusters.source.snapshotInfo.repos.r a"
        )).toMatchObject({
            status: "error",
            diagnostics: expect.arrayContaining([
                expect.objectContaining({
                    message: expect.stringContaining("repository name"),
                    path: ["sourceClusters", "source", "snapshotInfo", "repos", "r a"],
                }),
            ]),
        });
        expect(findNode(
            state.nodes,
            "edit:sourceClusters.source.snapshotInfo.snapshots.snap1.repoName"
        )).toMatchObject({
            value: "r a",
            status: "error",
        },);
        expect(state.validation.valid).toBe(false);
        expect(state.provenance.mode).toBe("structured");
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
                kafkaClusters: {default: {autoCreate: {}}},
                proxies: {
                    capture: {
                        source: "missing-source",
                        kafka: "default",
                        proxyConfig: {listenPort: 9201},
                    },
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
        ]),);
    });

    it("requires source endpoint when a capture proxy references the source", () => {
        const state = buildEditStateFromObject({
            sourceClusters: {
                source: {
                    endpoint: "",
                    version: "ES 7.10.2",
                },
            },
            targetClusters: {},
            traffic: {
                kafkaClusters: {default: {autoCreate: {}}},
                proxies: {
                    cap: {source: "source", kafka: "default", proxyConfig: {listenPort: 9201}},
                },
            },
            snapshotMigrationConfigs: [],
        });

        const endpoint = findNode(state.nodes, "edit:sourceClusters.source.endpoint");
        const source = findNode(state.nodes, "edit:sourceClusters.source");

        expect(state.validation.valid).toBe(false);
        expect(state.validation.diagnostics).toEqual(expect.arrayContaining([
            expect.objectContaining({
                severity: "required",
                path: ["sourceClusters", "source", "endpoint"],
                message: "Source endpoint is required because traffic.proxies.cap references this source.",
            }),
        ]),);
        expect(endpoint).toMatchObject({
            essential: true,
            status: "required",
            diagnostics: expect.arrayContaining([
                expect.objectContaining({
                    message: "Source endpoint is required because traffic.proxies.cap references this source.",
                }),
            ]),
        });
        expect(source?.status).toBe("required");
        expect(source?.essential).toBe(true);
    });

    it("surfaces kafka cluster union validation on the editable cluster node", () => {
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
                kafkaClusters: {
                    default: {
                        autoCreate: {},
                        existing: {
                            kafkaConnection: "broker:9092",
                        },
                    },
                },
                proxies: {
                    capture: {source: "legacy", kafka: "default", proxyConfig: {listenPort: 9201}},
                },
                replayers: {},
            },
            snapshotMigrationConfigs: [],
        });

        const cluster = findNode(state.nodes, "edit:traffic.kafkaClusters.default");

        expect(state.validation.valid).toBe(false);
        expect(state.validation.diagnostics).toEqual(expect.arrayContaining([
            expect.objectContaining({
                severity: "error",
                path: ["traffic", "kafkaClusters", "default"],
                message: "Kafka cluster configuration must define exactly one of 'existing' or 'autoCreate'",
            }),
        ]),);
        expect(cluster?.status).toBe("error");
        expect(cluster?.diagnostics).toEqual(expect.arrayContaining([
            expect.objectContaining({
                message: "Kafka cluster configuration must define exactly one of 'existing' or 'autoCreate'",
            }),
        ]),);
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
        },);

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
            },
        );

        expect(toggleResult.yaml).toContain("endpoint: https://legacy.example.com:9200");
        expect(toggleResult.yaml).toContain("allowInsecure: true");
    });

    it("applies and clears enum choices", () => {
        const config = {
            sourceClusters: {source: {endpoint: "", version: "ES 7.10.2"}},
            targetClusters: {},
            traffic: {
                kafkaClusters: {default: {autoCreate: {}}},
                proxies: {
                    cap: {source: "source", kafka: "default", proxyConfig: {listenPort: 9201}},
                },
            },
            snapshotMigrationConfigs: [],
        };

        const clusterIp = applyEditOperationToObject(config, {
            op: "set",
            path: ["traffic", "proxies", "cap", "proxyConfig", "serviceType"],
            value: "ClusterIP",
        });
        const reset = applyEditOperationToObject(parse(clusterIp.yaml), {
            op: "set",
            path: ["traffic", "proxies", "cap", "proxyConfig", "serviceType"],
            value: "unset",
        });

        expect(parse(clusterIp.yaml).traffic.proxies.cap.proxyConfig.serviceType).toBe("ClusterIP");
        expect(parse(reset.yaml).traffic.proxies.cap.proxyConfig.serviceType).toBeUndefined();
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
            traffic: {kafkaClusters: {kafka: {autoCreate: {}}}},
            snapshotMigrationConfigs: [],
        };

        const removedScalar = applyEditOperationToObject(config, {
            op: "unset",
            path: ["sourceClusters", "legacy", "allowInsecure"],
        });
        const removedMissingNested = applyEditOperationToObject(parse(removedScalar.yaml), {
            op: "unset",
            path: [
                "traffic",
                "kafkaClusters",
                "kafka",
                "autoCreate",
                "clusterSpecOverrides",
                "kafka",
                "replicas"
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
                {fromSource: "", toTarget: "prod", fromSnapshot: "", slices: []}
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

    it("applies hyphenated source references through the editConfig apply CLI", () => withUnifiedSchemaFixture((schemaPath) => {
        const tempDir = mkdtempSync(path.join(tmpdir(), "edit-config-"));
        try {
            const configPath = path.join(tempDir, "config.yaml");
            const operationPath = path.join(tempDir, "operation.yaml");
            writeFileSync(configPath, [
                "sourceClusters:",
                "  aux-source:",
                    '    endpoint: ""',
                "    version: ES 7.10.2",
                "targetClusters:",
                "  prod:",
                "    endpoint: https://prod.example.com:9200",
                "snapshotMigrationConfigs:",
                    '  - fromSource: ""',
                "    toTarget: prod",
                    '    fromSnapshot: ""',
                    "    slices: []",
                "",
            ].join("\n"),);
            writeFileSync(operationPath, JSON.stringify({
                op: "set",
                path: ["snapshotMigrationConfigs", "0", "fromSource"],
                value: "aux-source",
            }),);

            const cliPath = path.resolve(__dirname, "../src/cliRouter.ts");
            const result = spawnSync(
                process.execPath,
                [
                    "--import",
                    TSX_IMPORT,
                    cliPath,
                    "editConfig",
                    "apply",
                    "--pending-config",
                    configPath,
                    "--operation",
                    operationPath,
                ],
                {
                    encoding: "utf8",
                    env: {...process.env, MIGRATION_UNIFIED_SCHEMA_PATH: schemaPath},
                    maxBuffer: 10 * 1024 * 1024,
                },
            );

            expect(result.status).toBe(0);
            expect(result.stderr).not.toContain("Usage: editConfig apply");
            expect(JSON.parse(result.stdout).yaml).toContain("fromSource: aux-source");
        } finally {
            rmSync(tempDir, {recursive: true, force: true});
        }
    }));

    it("adds and removes virtual cluster config entries", () => {
        const added = applyEditOperationToObject({
            sourceClusters: {},
            targetClusters: {},
            snapshotMigrationConfigs: [],
        }, {
            op: "add",
            path: ["sourceClusters"],
            value: {name: "legacy"},
        },);

        expect(added.yaml).toContain("legacy:");
        expect(findNode(added.editState.nodes, "edit:sourceClusters.legacy")?.status).toBe("required");

        const removed = applyEditOperationToObject(parse(added.yaml), {
            op: "removeConfig",
            path: ["sourceClusters", "legacy"],
        });

        expect(removed.yaml).not.toContain("legacy:");
        expect(findNode(removed.editState.nodes, "edit:sourceClusters.legacy")).toBeUndefined();
    });

    it("fills sole reference choices on newly added configurations", () => {
        const baseConfig = {
            sourceClusters: {
                source: {
                    endpoint: "https://source.example.com:9200",
                    version: "ES 7.10.2",
                    snapshotInfo: {
                        repos: {
                            repo: {
                                awsRegion: "us-east-1",
                                repoPathUri: "s3://snapshots/source",
                            },
                        },
                        snapshots: {
                            snap1: {
                                repoName: "repo",
                                config: {externallyManagedSnapshotName: "snapshot-1"},
                            },
                        },
                    },
                },
            },
            targetClusters: {
                target: {endpoint: "https://target.example.com:9200"},
            },
            traffic: {
                kafkaClusters: {default: {autoCreate: {}}},
                proxies: {},
                s3Sources: {},
                replayers: {},
            },
            snapshotMigrationConfigs: [],
        };

        const migrationAdded = applyEditOperationToObject(baseConfig, {
            op: "add",
            path: ["snapshotMigrationConfigs"],
            value: {name: "initial"},
        });
        expect(parse(migrationAdded.yaml).snapshotMigrationConfigs).toEqual([{
            fromSource: "source",
            toTarget: "target",
            fromSnapshot: "snap1",
            slice: "initial",
        }]);

        const proxyAdded = applyEditOperationToObject(parse(migrationAdded.yaml), {
            op: "add",
            path: ["traffic", "proxies"],
            value: {name: "capture"},
        });
        expect(parse(proxyAdded.yaml).traffic.proxies.capture).toEqual({
            source: "source",
            proxyConfig: {},
            kafka: "default",
            kafkaTopic: "",
        });

        const replayerAdded = applyEditOperationToObject(parse(proxyAdded.yaml), {
            op: "add",
            path: ["traffic", "replayers"],
            value: {name: "replay"},
        });
        expect(parse(replayerAdded.yaml).traffic.replayers.replay).toEqual({
            fromCapturedTraffic: "capture",
            toTarget: "target",
        });

        const dependencyAdded = applyEditOperationToObject(parse(replayerAdded.yaml), {
            op: "add",
            path: ["traffic", "replayers", "replay", "dependsOnSnapshotMigrations"],
            value: {},
        });
        expect(parse(dependencyAdded.yaml).traffic.replayers.replay.dependsOnSnapshotMigrations).toEqual([{
            source: "source",
            snapshot: "snap1",
        },]);

        const snapshotAdded = applyEditOperationToObject(parse(dependencyAdded.yaml), {
            op: "add",
            path: ["sourceClusters", "source", "snapshotInfo", "snapshots"],
            value: {name: "snap2"},
        });
        expect(parse(snapshotAdded.yaml).sourceClusters.source.snapshotInfo.snapshots.snap2).toEqual({
            repoName: "repo",
        });
    });

    it("leaves new references empty when more than one choice is available", () => {
        const added = applyEditOperationToObject({
            sourceClusters: {
                source1: {endpoint: "", version: "ES 7.10.2"},
                source2: {endpoint: "", version: "ES 7.10.2"},
            },
            targetClusters: {
                target1: {endpoint: "https://target1.example.com:9200"},
                target2: {endpoint: "https://target2.example.com:9200"},
            },
            traffic: {
                kafkaClusters: {
                    kafka1: {autoCreate: {}},
                    kafka2: {autoCreate: {}},
                },
                proxies: {},
            },
            snapshotMigrationConfigs: [],
        }, {
            op: "add",
            path: ["traffic", "proxies"],
            value: {name: "capture"},
        },);

        expect(parse(added.yaml).traffic.proxies.capture).toEqual({
            source: "",
            kafka: "",
            kafkaTopic: "",
            proxyConfig: {},
        });
    });

    it("leaves a new tuple snapshot blank when the selected source has multiple choices", () => {
        const added = applyEditOperationToObject({
            sourceClusters: {
                source: {
                    endpoint: "https://source.example.com:9200",
                    version: "ES 7.10.2",
                    snapshotInfo: {
                        snapshots: {
                            first: {
                                repoName: "",
                                config: {externallyManagedSnapshotName: "first"},
                            },
                            second: {
                                repoName: "",
                                config: {externallyManagedSnapshotName: "second"},
                            },
                        },
                    },
                },
            },
            targetClusters: {
                target: {endpoint: "https://target.example.com:9200"},
            },
            snapshotMigrationConfigs: [],
        }, {
            op: "add",
            path: ["snapshotMigrationConfigs"],
            value: {name: "initial"},
        });

        expect(parse(added.yaml).snapshotMigrationConfigs).toEqual([{
            fromSource: "source",
            toTarget: "target",
            fromSnapshot: "",
            slice: "initial",
        }]);
        expect(findNode(
            added.editState.nodes,
            "edit:snapshotMigrationConfigs.0.metadataMigrationConfig:add",
        )?.command?.blockedMessage).toContain("Choose a source, target, and snapshot");
    });

    it("removes configs that depend on a deleted source cluster", () => {
        const result = applyEditOperationToObject({
            sourceClusters: {
                legacy: {endpoint: "https://legacy.example.com:9200", version: "ES 7.10.2",},
                aux: {endpoint: "https://aux.example.com:9200", version: "ES 7.10.2",},
            },
            targetClusters: {prod: {endpoint: "https://prod.example.com:9200"}},
            snapshotMigrationConfigs: [
                {fromSource: "legacy", toTarget: "prod"},
                {fromSource: "aux", toTarget: "prod"},
            ],
            traffic: {
                kafkaClusters: {},
                proxies: {
                    cap: {source: "legacy"},
                    auxcap: {source: "aux"},
                },
                s3Sources: {
                    archive: {sourceLabel: "legacy", s3Uri: "s3://bucket/archive", awsRegion: "us-east-1",},
                },
                replayers: {
                    "replay-cap": {fromCapturedTraffic: "cap", toTarget: "prod"},
                    "replay-archive": {
                        fromCapturedTraffic: "archive",
                        toTarget: "prod",
                        dependsOnSnapshotMigrations: [
                            {source: "legacy", snapshot: "snap1"},
                            {source: "aux", snapshot: "snap2"},
                        ],
                    },
                    "replay-aux": {fromCapturedTraffic: "auxcap", toTarget: "prod"},
                },
            },
        }, {
            op: "removeConfig",
            path: ["sourceClusters", "legacy"],
        },);

        const config = parse(result.yaml);
        expect(config.sourceClusters.legacy).toBeUndefined();
        expect(config.sourceClusters.aux).toBeDefined();
        expect(config.snapshotMigrationConfigs).toEqual([{fromSource: "aux", toTarget: "prod"}]);
        expect(config.traffic.proxies).toEqual({auxcap: {source: "aux"}});
        expect(config.traffic.s3Sources).toEqual({
            archive: {sourceLabel: "legacy", s3Uri: "s3://bucket/archive", awsRegion: "us-east-1",},
        });
        expect(config.traffic.replayers).toEqual({
            "replay-archive": {
                fromCapturedTraffic: "archive",
                toTarget: "prod",
                dependsOnSnapshotMigrations: [{source: "aux", snapshot: "snap2"}],
            },
            "replay-aux": {fromCapturedTraffic: "auxcap", toTarget: "prod"},
        });
        expect(findNode(result.editState.nodes, "edit:snapshotMigrationConfigs.0.fromSource")?.value).toBe("aux");
        expect(findNode(result.editState.nodes, "edit:traffic.proxies.cap")).toBeUndefined();
        expect(findNode(result.editState.nodes, "edit:traffic.replayers.replay-cap")).toBeUndefined();
    });

    it("deletes replayers downstream of a removed capture proxy", () => {
        const result = applyEditOperationToObject({
            sourceClusters: {
                source: {endpoint: "https://source.example.com:9200", version: "ES 7.10.2"},
            },
            targetClusters: {
                target: {endpoint: "https://target.example.com:9200"},
            },
            snapshotMigrationConfigs: [],
            traffic: {
                kafkaClusters: {
                    kafka: {
                        autoCreate: {},
                        topics: {capture: {}},
                    },
                },
                proxies: {
                    capture: {
                        source: "source",
                        kafka: "kafka",
                        kafkaTopic: "capture",
                        proxyConfig: {listenPort: 9200},
                    },
                },
                replayers: {
                    replay: {
                        fromCapturedTraffic: "capture",
                        toTarget: "target",
                    },
                },
            },
        }, {
            op: "removeConfig",
            path: ["traffic", "proxies", "capture"],
        });

        const config = parse(result.yaml);
        expect(config.traffic.proxies.capture).toBeUndefined();
        expect(config.traffic.replayers.replay).toBeUndefined();
        expect(config.traffic.kafkaClusters.kafka).toBeDefined();
        expect(findNode(
            result.editState.nodes,
            "edit:traffic.replayers.replay",
        )).toBeUndefined();
    });

    it("keeps replayers when a removed capture proxy clears its references", () => {
        const result = applyEditOperationToObject({
            sourceClusters: {
                source: {
                    endpoint: "https://source.example.com:9200",
                    version: "ES 7.10.2",
                },
            },
            targetClusters: {
                target: {endpoint: "https://target.example.com:9200"},
            },
            snapshotMigrationConfigs: [],
            traffic: {
                kafkaClusters: {
                    kafka: {
                        autoCreate: {},
                        topics: {capture: {}},
                    },
                },
                proxies: {
                    capture: {
                        source: "source",
                        kafka: "kafka",
                        kafkaTopic: "capture",
                        proxyConfig: {listenPort: 9200},
                    },
                },
                replayers: {
                    replay: {
                        fromCapturedTraffic: "capture",
                        toTarget: "target",
                    },
                },
            },
        }, {
            op: "removeConfig",
            path: ["traffic", "proxies", "capture"],
            referencingResources: "clear-references",
        });

        const config = parse(result.yaml);
        expect(config.traffic.proxies.capture).toBeUndefined();
        expect(config.traffic.replayers.replay).toEqual({
            toTarget: "target",
        });
    });

    it("renames named config entries and updates graph references", () => {
        const baseConfig = {
            sourceClusters: {
                legacy: {
                    endpoint: "https://legacy.example.com:9200",
                    version: "ES 7.10.2",
                    snapshotInfo: {
                        repos: {
                            repo1: {awsRegion: "us-east-1", s3RepoPathUri: "s3://snapshots/repo1/",},
                        },
                        snapshots: {
                            snap1: {repoName: "repo1", config: {externallyManagedSnapshotName: "snap1"},},
                        },
                    },
                },
            },
            targetClusters: {prod: {endpoint: "https://prod.example.com:9200"}},
            snapshotMigrationConfigs: [{
                fromSource: "legacy",
                toTarget: "prod",
                    fromSnapshot: "snap1",
                    slices: {"slice-1": {metadataMigrationConfig: {}}},
                },],
            traffic: {
                kafkaClusters: {default: {autoCreate: {}}},
                proxies: {
                    cap: {source: "legacy", kafka: "default"},
                },
                s3Sources: {
                    archive: {
                        sourceLabel: "legacy",
                        s3Uri: "s3://bucket/path/export.proto.gz",
                        awsRegion: "us-east-1",
                        kafka: "default",
                    },
                },
                replayers: {
                    replay: {
                        fromCapturedTraffic: "cap",
                        toTarget: "prod",
                        dependsOnSnapshotMigrations: [{source: "legacy", snapshot: "snap1"}],
                    },
                },
            },
        };

        const renamedSource = applyEditOperationToObject(baseConfig, {
            op: "renameConfig",
            path: ["sourceClusters", "legacy"],
            newName: "renamed-source",
        });
        let config = parse(renamedSource.yaml);
        expect(config.sourceClusters.legacy).toBeUndefined();
        expect(config.sourceClusters["renamed-source"]).toBeDefined();
        expect(config.snapshotMigrationConfigs[0].fromSource).toBe("renamed-source");
        expect(config.traffic.proxies.cap.source).toBe("renamed-source");
        expect(config.traffic.s3Sources.archive.sourceLabel).toBe("legacy");
        expect(config.traffic.replayers.replay.dependsOnSnapshotMigrations[0].source).toBe("renamed-source");

        const renamedSnapshot = applyEditOperationToObject(config, {
            op: "renameConfig",
            path: ["sourceClusters", "renamed-source", "snapshotInfo", "snapshots", "snap1"],
            newName: "snap2",
        });
        config = parse(renamedSnapshot.yaml);
        expect(config.sourceClusters["renamed-source"].snapshotInfo.snapshots.snap1).toBeUndefined();
        expect(config.sourceClusters["renamed-source"].snapshotInfo.snapshots.snap2).toBeDefined();
        expect(config.snapshotMigrationConfigs[0].fromSnapshot).toBe("snap2");
        expect(config.traffic.replayers.replay.dependsOnSnapshotMigrations[0].snapshot).toBe("snap2");

        const renamedRepo = applyEditOperationToObject(config, {
            op: "renameConfig",
            path: ["sourceClusters", "renamed-source", "snapshotInfo", "repos", "repo1"],
            newName: "repo2",
        });
        config = parse(renamedRepo.yaml);
        expect(config.sourceClusters["renamed-source"].snapshotInfo.repos.repo1).toBeUndefined();
        expect(config.sourceClusters["renamed-source"].snapshotInfo.repos.repo2).toBeDefined();
        expect(config.sourceClusters["renamed-source"].snapshotInfo.snapshots.snap2.repoName).toBe("repo2");

        const renamedKafka = applyEditOperationToObject(config, {
            op: "renameConfig",
            path: ["traffic", "kafkaClusters", "default"],
            newName: "kafka2",
        });
        config = parse(renamedKafka.yaml);
        expect(config.traffic.kafkaClusters.default).toBeUndefined();
        expect(config.traffic.kafkaClusters.kafka2).toBeDefined();
        expect(config.traffic.proxies.cap.kafka).toBe("kafka2");
        expect(config.traffic.s3Sources.archive.kafka).toBe("kafka2");

        const renamedProxy = applyEditOperationToObject(config, {
            op: "renameConfig",
            path: ["traffic", "proxies", "cap"],
            newName: "cap2",
        });
        config = parse(renamedProxy.yaml);
        expect(config.traffic.proxies.cap).toBeUndefined();
        expect(config.traffic.proxies.cap2).toBeDefined();
        expect(config.traffic.replayers.replay.fromCapturedTraffic).toBe("cap2");

        const renamedTarget = applyEditOperationToObject(config, {
            op: "renameConfig",
            path: ["targetClusters", "prod"],
            newName: "prod2",
        });
        config = parse(renamedTarget.yaml);
        expect(config.targetClusters.prod).toBeUndefined();
        expect(config.targetClusters.prod2).toBeDefined();
        expect(config.snapshotMigrationConfigs[0].toTarget).toBe("prod2");
        expect(config.traffic.replayers.replay.toTarget).toBe("prod2");

        const renamedReplayer = applyEditOperationToObject(config, {
            op: "renameConfig",
            path: ["traffic", "replayers", "replay"],
            newName: "replay2",
        });
        config = parse(renamedReplayer.yaml);
        expect(config.traffic.replayers.replay).toBeUndefined();
        expect(config.traffic.replayers.replay2).toBeDefined();
        expect(findNode(renamedReplayer.editState.nodes, "edit:traffic.replayers.replay2")).toBeDefined();

        const renamedSlice = applyEditOperationToObject(config, {
            op: "set",
            path: ["snapshotMigrationConfigs", "0", "slice"],
            value: "metadata-and-documents",
        });
        config = parse(renamedSlice.yaml);
        expect(config.snapshotMigrationConfigs[0]).toMatchObject({
            fromSource: "renamed-source",
            toTarget: "prod2",
            fromSnapshot: "snap2",
            slice: "metadata-and-documents",
            metadataMigrationConfig: {},
        });
        expect(findNode(
            renamedSlice.editState.nodes,
            "edit:snapshotMigrationConfigs.0",
        )?.label).toBe("renamed-source-prod2-snap2-metadata-and-documents");
        expect(findNode(
            renamedSlice.editState.nodes,
            "edit:snapshotMigrationConfigs.0.slice",
        )).toBeUndefined();
    });

    it("reports duplicate complete snapshot migration identities on the named migration", () => {
        const result = applyEditOperationToObject({
            sourceClusters: {},
            targetClusters: {},
            snapshotMigrationConfigs: [
                {
                    fromSource: "source",
                    toTarget: "target",
                    fromSnapshot: "snap1",
                    slice: "slice-0",
                    metadataMigrationConfig: {},
                },
                {
                    fromSource: "source",
                    toTarget: "target",
                    fromSnapshot: "snap1",
                    slice: "slice-1",
                    documentBackfillConfig: {},
                },
            ],
        }, {
            op: "set",
            path: ["snapshotMigrationConfigs", "1", "slice"],
            value: "slice-0",
        });

        expect(findNode(
            result.editState.nodes,
            "edit:snapshotMigrationConfigs.1",
        )).toMatchObject({
            status: "error",
            diagnostics: expect.arrayContaining([
                expect.objectContaining({
                    message: expect.stringContaining("already configured"),
                }),
            ]),
        });
        expect(findNode(
            result.editState.nodes,
            "edit:snapshotMigrationConfigs.1.metadataMigrationConfig:add",
        )?.command?.blockedMessage).toContain("already configured");
        expect(findNode(
            result.editState.nodes,
            "edit:snapshotMigrationConfigs.1.slice",
        )).toBeUndefined();
    });

    it("renders and applies map-backed resource config groups", () => {
        const state = buildEditStateFromObject({
            sourceClusters: {
                legacy: {
                    endpoint: "https://legacy.example.com:9200",
                    version: "ES 7.10.2",
                    snapshotInfo: {
                        repos: {
                            repo1: {awsRegion: "us-east-1", s3RepoPathUri: "s3://snapshots/repo1/",},
                        },
                        snapshots: {
                            snap1: {repoName: "repo1", config: {externallyManagedSnapshotName: "snap1"},},
                            snap2: {repoName: "repo1", config: {externallyManagedSnapshotName: "snap2"},},
                        },
                    },
                },
            },
            targetClusters: {prod: {endpoint: "https://prod.example.com:9200"}},
            traffic: {
                kafkaClusters: {
                    default: {autoCreate: {}},
                },
                proxies: {
                    capture: {source: "legacy"},
                },
                s3Sources: {
                    archive: {s3Uri: "s3://bucket/path/export.proto.gz", awsRegion: "us-east-1", sourceLabel: "legacy",},
                },
                replayers: {
                    replay: {
                        fromCapturedTraffic: "archive",
                        toTarget: "prod",
                        dependsOnSnapshotMigrations: [{source: "legacy", snapshot: "snap1"}],
                    },
                },
            },
            snapshotMigrationConfigs: [{fromSource: "legacy", toTarget: "prod",
                    fromSnapshot: "",
                    slices: [],},],
        });

        expect(cleanLabel(findNode(state.nodes, "edit:traffic.kafkaClusters.default"))).toBe("default");
        expect(findNode(state.nodes, "edit:traffic.kafkaClusters.default.autoCreate.auth")).toMatchObject({
            valueKind: "union",
            value: "unset",
            presence: "optional",
            status: "ok",
            effectiveDefault: {
                label: "scram-sha-512",
            },
        });
        expect(findNode(state.nodes, "edit:traffic.kafkaClusters.default.autoCreate.auth")?.variants?.map(
                (variant) => variant.value,),).toEqual([
            "unset",
            "none",
            "scram-sha-512"
        ]);
        expect(findNode(state.nodes, "edit:traffic.kafkaClusters.default.autoCreate.auth")?.variants?.[0].label).toBe("default (scram-sha-512)",);
        expect(findNode(state.nodes, "edit:traffic.kafkaClusters.default.autoCreate.auth")?.label).toContain("auth: < default: scram-sha-512 >",);
        expect(findNode(state.nodes, "edit:traffic.kafkaClusters.default.autoCreate.clusterSpecOverrides"),).toMatchObject({
            valueKind: "object",
            presence: "optional",
        });
        expect(findNode(state.nodes, "edit:traffic.kafkaClusters.default.autoCreate.nodePoolSpecOverrides"),).toMatchObject({
            valueKind: "object",
            presence: "optional",
        });
        expect(findNode(state.nodes, "edit:traffic.kafkaClusters.default.topics")).toMatchObject({
            valueKind: "record",
            presence: "optional",
        },);
        expect(findNode(state.nodes, "edit:sourceClusters.legacy.snapshotInfo")).toMatchObject({
            valueKind: "object",
            presence: "optional",
            essential: true,
            label: "snapshotInfo: repos 1, snapshots 2",
        });
        expect(findNode(state.nodes, "edit:sourceClusters.legacy.snapshotInfo.repos")).toMatchObject({
            valueKind: "record",
            presence: "optional",
            essential: true,
            inputHint: {
                definitionCollection: {
                    ownerAncestorLevels: 2,
                    navigation: {
                        groupLabel: "Repositories",
                        groupOrder: 0,
                        groupId: "definition-group:edit:sourceClusters.legacy.snapshotInfo.repos",
                    },
                    definition: {
                        typeLabel: "Snapshot repository",
                    },
                },
            },
        });
        expect(findNode(state.nodes, "edit:sourceClusters.legacy.snapshotInfo.repos:add")?.label).toBe(
            "+ Add snapshot repository",
        );
        expect(findNode(state.nodes, "edit:sourceClusters.legacy.snapshotInfo.snapshots")).toMatchObject({
            valueKind: "record",
            presence: "required",
            essential: true,
            status: "ok",
            inputHint: {
                definitionCollection: {
                    ownerAncestorLevels: 2,
                    navigation: {
                        groupLabel: "Snapshots",
                        groupOrder: 1,
                        groupId: "definition-group:edit:sourceClusters.legacy.snapshotInfo.snapshots",
                    },
                    definition: {
                        typeLabel: "Source snapshot",
                    },
                },
            },
        });
        expect(findNode(state.nodes, "edit:sourceClusters.legacy.snapshotInfo.snapshots:add")?.label).toBe(
            "+ Add source snapshot",
        );
        expect(findNode(state.nodes, "edit:sourceClusters.legacy.snapshotInfo.snapshots:add")?.command?.blockedMessage,).toBeUndefined();
        expect(findNode(state.nodes, "edit:sourceClusters.legacy.snapshotInfo.serializeSnapshotCreation"),).toMatchObject({
            valueKind: "boolean",
            presence: "optional",
        });
        expect(cleanLabel(findNode(state.nodes, "edit:traffic.proxies.capture"))).toBe("capture");
        expect(findNode(state.nodes, "edit:traffic.proxies.capture.proxyConfig.resources")).toMatchObject({
            valueKind: "object",
            valueDefaulted: true,
        });
        expect(findNode(state.nodes, "edit:traffic.proxies.capture.proxyConfig.resources.limits.cpu")).toMatchObject({
            valueKind: "scalar",
            status: "ok",
            valueDefaulted: true,
        });
        expect(findNode(state.nodes, "edit:traffic.proxies.capture.proxyConfig.resources.limits.cpu")?.valueAuthored,).toBeUndefined();
        expect(findNode(state.nodes, "edit:traffic.proxies.capture.proxyConfig.resources.requests.memory"),).toMatchObject({
            valueKind: "scalar",
            status: "ok",
            valueDefaulted: true,
        });
        expect(findNode(state.nodes, "edit:traffic.s3Sources")).toMatchObject({
            expert: true,
        });
        expect(findNode(state.nodes, "edit:traffic.s3Sources:add")?.label).toBe(
            "+ Add optional S3 archive source (no capture proxy)",
        );
        expect(cleanLabel(findNode(state.nodes, "edit:traffic.s3Sources.archive"))).toBe("archive");
        expect(cleanLabel(findNode(state.nodes, "edit:traffic.replayers.replay"))).toBe("replay");
        expect(findNode(state.nodes, "edit:snapshotMigrationConfigs.0")?.label).toBe("legacy-prod-<snapshot>-<name>");
        expect(findNode(state.nodes, "edit:snapshotMigrationConfigs.0.fromSource")?.inputHint).toMatchObject({
            kind: "reference",
            options: [{
                label: "legacy",
                value: "legacy",
                editTargetId: "edit:sourceClusters.legacy",
            },],
        });
        expect(findNode(state.nodes, "edit:snapshotMigrationConfigs.0.fromSnapshot")).toMatchObject({
            valueKind: "scalar",
            status: "required",
        });
        expect(findNode(state.nodes, "edit:snapshotMigrationConfigs.0.slice")).toBeUndefined();
        expect(findNode(state.nodes, "edit:snapshotMigrationConfigs:add")).toMatchObject({
            label: "+ Add snapshot migration",
            command: {
                requiresName: true,
                autoEditAdded: true,
            },
        });
        expect(findNode(state.nodes, "edit:traffic.proxies.capture.source")?.inputHint).toMatchObject({
            kind: "reference",
            sourcePath: ["sourceClusters"],
            options: [{
                label: "legacy",
                value: "legacy",
                editTargetId: "edit:sourceClusters.legacy",
            },],
        });
        expect(findNode(state.nodes, "edit:traffic.replayers.replay.fromCapturedTraffic")?.inputHint).toMatchObject({
            kind: "reference",
            sourcePaths: [["traffic", "proxies"], ["traffic", "s3Sources"],],
            options: [
                {
                    label: "archive",
                    value: "archive",
                    editTargetId: "edit:traffic.s3Sources.archive",
                },
                {
                    label: "capture",
                    value: "capture",
                    editTargetId: "edit:traffic.proxies.capture",
                },
            ],
        });
        const capturedTrafficHint = findNode(
            state.nodes,
            "edit:traffic.replayers.replay.fromCapturedTraffic",
        )?.inputHint;
        expect(capturedTrafficHint?.kind).toBe("reference");
        if (capturedTrafficHint?.kind !== "reference") {
            throw new Error("Expected a captured-traffic reference hint");
        }
        expect(capturedTrafficHint.allowCustom).not.toBe(true);
        expect(findNode(state.nodes, "edit:traffic.replayers.replay.toTarget")?.inputHint).toMatchObject({
            kind: "reference",
            sourcePath: ["targetClusters"],
            options: [{label: "prod", value: "prod"}],
        });
        expect(findNode(state.nodes, "edit:traffic.replayers.replay.dependsOnSnapshotMigrations")).toMatchObject({
            valueKind: "array",
            presence: "optional",
            essential: true,
        });
        const snapshotDependency = findNode(state.nodes, "edit:traffic.replayers.replay.dependsOnSnapshotMigrations.0.snapshot",);
        expect(snapshotDependency).toMatchObject({
            valueKind: "scalar",
            value: "snap1",
        });
        expect(snapshotDependency?.inputHint).toMatchObject({
            kind: "reference",
            sourcePathTemplate: [
                "sourceClusters",
                {valueFrom: ["..", "source"]},
                "snapshotInfo",
                "snapshots"
            ],
            options: [
                {
                    label: "snap1",
                    value: "snap1",
                    editTargetId: "edit:sourceClusters.legacy.snapshotInfo.snapshots.snap1",
                },
                {
                    label: "snap2",
                    value: "snap2",
                    editTargetId: "edit:sourceClusters.legacy.snapshotInfo.snapshots.snap2",
                },
            ],
        });
        expect(findNode(state.nodes, "edit:traffic.replayers.replay.replayerConfig")).toMatchObject({
            valueKind: "object",
            presence: "optional",
        });
        expect(findNode(state.nodes, "edit:traffic.replayers.replay.replayerConfig.speedupFactor")).toMatchObject({
            valueKind: "scalar",
            valueType: "number",
            essential: true,
        });
        expect(findNode(state.nodes, "edit:traffic.replayers.replay.replayerConfig.observedPacketConnectionTimeout"),).toMatchObject({
            valueKind: "scalar",
            valueType: "number",
            essential: true,
        });
        expect(findNode(state.nodes, "edit:traffic.replayers.replay.replayerConfig.targetServerResponseTimeoutSeconds"),).toMatchObject({
            valueKind: "scalar",
            valueType: "number",
            essential: true,
        });
        expect(findNode(state.nodes, "edit:traffic.replayers.replay.replayerConfig.requestTransforms")).toMatchObject({
            expert: false,
            essential: true,
        });
        expect(findNode(state.nodes, "edit:traffic.replayers.replay.replayerConfig.tupleTransforms")).toMatchObject({
            expert: false,
            essential: true,
        });
        for (const field of [
            "transformerConfig",
            "transformerConfigEncoded",
            "transformerConfigFile",
            "tupleTransformerConfig",
            "tupleTransformerConfigBase64",
            "tupleTransformerConfigFile",
        ]) {
            expect(findNode(state.nodes, `edit:traffic.replayers.replay.replayerConfig.${field}`)).toMatchObject({
                expert: true,
            });
        }
        expect(findNode(state.nodes, "edit:traffic.replayers.replay.replayerConfig.removeAuthHeader")).toMatchObject({
            valueKind: "boolean",
        });
        expect(findNode(state.nodes, "edit:traffic.replayers.replay.replayerConfig.resources")).toMatchObject({
            valueKind: "object",
            presence: "optional",
            status: "ok",
            valueDefaulted: true,
        });
        expect(findNode(state.nodes, "edit:traffic.replayers.replay.replayerConfig.resources.limits.cpu"),).toMatchObject({
            valueKind: "scalar",
            status: "ok",
        });
        expect(findNode(state.nodes, "edit:traffic.replayers.replay.replayerConfig.resources.limits.cpu")?.required,).not.toBe(true);
    });

    it("renders a flat snapshot migration configuration", () => {
        const state = buildEditStateFromObject({
            sourceClusters: {
                legacy: {
                    endpoint: "https://legacy.example.com:9200",
                    version: "ES 7.10.2",
                    snapshotInfo: {
                        repos: {
                            repo1: {
                                awsRegion: "us-east-1",
                                s3RepoPathUri: "s3://snapshot-bucket/repo1",
                            },
                            repo2: {
                                awsRegion: "us-west-2",
                                s3RepoPathUri: "s3://snapshot-bucket/repo2",
                            },
                        },
                        snapshots: {
                            snap1: {
                                repoName: "repo1",
                                config: {externallyManagedSnapshotName: "snap1"},
                            },
                            snap2: {
                                repoName: "repo2",
                                config: {externallyManagedSnapshotName: "snap2"},
                            },
                        },
                    },
                },
            },
            targetClusters: {prod: {endpoint: "https://prod.example.com:9200"}},
            snapshotMigrationConfigs: [{
                fromSource: "legacy",
                toTarget: "prod",
                fromSnapshot: "snap1",
                slice: "slice-2",
                metadataMigrationConfig: {},
                documentBackfillConfig: {},
            }],
            traffic: {kafkaClusters: {}, proxies: {}, s3Sources: {}, replayers: {}},
        });

        expect(findNode(state.nodes, "edit:snapshotMigrationConfigs.0.fromSnapshot")).toMatchObject({
            valueKind: "scalar",
            value: "snap1",
            inputHint: expect.objectContaining({
                kind: "reference",
                options: expect.arrayContaining([
                    expect.objectContaining({
            label: "snap1",
                        value: "snap1",
                        editTargetId: "edit:sourceClusters.legacy.snapshotInfo.snapshots.snap1",
                    }),
                    expect.objectContaining({
                        label: "snap2",
                        value: "snap2",
                        editTargetId: "edit:sourceClusters.legacy.snapshotInfo.snapshots.snap2",
                    }),
                ]),
            }),
        });
        expect(findNode(state.nodes, "edit:sourceClusters.legacy.snapshotInfo")).toMatchObject({
            valueKind: "object",
            presence: "optional",
            essential: true,
            label: "snapshotInfo: repos 2, snapshots 2",
        });
        expect(findNode(state.nodes, "edit:sourceClusters.legacy.snapshotInfo.snapshots.snap1")).toMatchObject({
            valueKind: "object",
            removable: true,
        });
        expect(findNode(state.nodes, "edit:sourceClusters.legacy.snapshotInfo.snapshots.snap1.repoName")).toMatchObject({
            valueKind: "scalar",
            inputHint: {
                kind: "reference",
                sourcePath: ["sourceClusters", "legacy", "snapshotInfo", "repos"],
                options: [
                    {
                        label: "repo1",
                        value: "repo1",
                        editTargetId: "edit:sourceClusters.legacy.snapshotInfo.repos.repo1",
                    },
                    {
                        label: "repo2",
                        value: "repo2",
                        editTargetId: "edit:sourceClusters.legacy.snapshotInfo.repos.repo2",
                    },
                ],
                message: "Choose a repository defined under sourceClusters.legacy.snapshotInfo.repos.",
            },
        },);
        expect(findNode(
            state.nodes,
            "edit:sourceClusters.legacy.snapshotInfo.snapshots.snap1.repoName"
        )?.description,).toContain("First define repositories under sourceClusters.legacy.snapshotInfo.repos.");
        expect(findNode(state.nodes, "edit:sourceClusters.legacy.snapshotInfo.snapshots.snap1.config")).toMatchObject({
            valueKind: "union",
            value: "externallyManagedSnapshotName",
        });
        expect(findNode(state.nodes, "edit:snapshotMigrationConfigs.0")?.label).toBe(
            "legacy-prod-snap1-slice-2",
        );
        expect(findNode(state.nodes, "edit:snapshotMigrationConfigs.0.slice")).toBeUndefined();
        expect(findNode(state.nodes, "edit:snapshotMigrationConfigs.0.slices")).toBeUndefined();
        expect(findNode(state.nodes, "edit:snapshotMigrationConfigs:add")).toMatchObject({
            valueKind: "command",
            label: "+ Add snapshot migration",
        });
        expect(findNode(state.nodes, "edit:snapshotMigrationConfigs.0.metadataMigrationConfig")).toMatchObject({
            valueKind: "object",
            presence: "optional",
            removable: true,
        },);
        expect(findNode(state.nodes,
                "edit:snapshotMigrationConfigs.0.metadataMigrationConfig.metadataTransforms",),).toMatchObject({
            expert: false,
            essential: true,
        });
        for (const field of ["componentTemplateAllowlist", "indexAllowlist", "indexTemplateAllowlist"]) {
            expect(findNode(
                state.nodes, `edit:snapshotMigrationConfigs.0.metadataMigrationConfig.${field}`),).toMatchObject({
                essential: true,
            });
        }
        for (const field of ["transformerConfigBase64", "transformerConfig", "transformerConfigFile"]) {
            expect(findNode(
                state.nodes, `edit:snapshotMigrationConfigs.0.metadataMigrationConfig.${field}`),).toMatchObject({
                expert: true,
            });
        }
        expect(findNode(state.nodes, "edit:snapshotMigrationConfigs.0.documentBackfillConfig")).toMatchObject({
            valueKind: "object",
            presence: "optional",
            essential: true,
            removable: true,
        });
        expect(findNode(state.nodes, "edit:snapshotMigrationConfigs.0.documentBackfillConfig.documentTransforms"),).toMatchObject({
            expert: false,
            essential: true,
        });
        expect(findNode(
            state.nodes, "edit:snapshotMigrationConfigs.0.documentBackfillConfig.indexAllowlist"),).toMatchObject({
            essential: true,
        });
        for (const field of ["docTransformerConfigBase64", "docTransformerConfig", "docTransformerConfigFile"]) {
            expect(findNode(
                state.nodes, `edit:snapshotMigrationConfigs.0.documentBackfillConfig.${field}`),).toMatchObject({
                expert: true,
            });
        }
        expect(findNode(state.nodes, "edit:snapshotMigrationConfigs.0.documentBackfillConfig.podReplicas"),).toMatchObject({
            valueKind: "scalar",
            valueType: "number",
            essential: true,
            value: 1,
            valueDefaulted: true,
        });
    });

    it("keeps snapshot repoName as a constrained selector when no repos are defined", () => {
        const state = buildEditStateFromObject({
            sourceClusters: {
                legacy: {
                    endpoint: "https://legacy.example.com:9200",
                    version: "ES 7.10.2",
                    snapshotInfo: {
                        snapshots: {
                            snap1: {
                                repoName: "",
                                config: {externallyManagedSnapshotName: "snap1"},
                            },
                        },
                    },
                },
            },
            targetClusters: {prod: {endpoint: "https://prod.example.com:9200"}},
            snapshotMigrationConfigs: [],
            traffic: {kafkaClusters: {}, proxies: {}, s3Sources: {}, replayers: {}},
        });

        expect(findNode(state.nodes, "edit:sourceClusters.legacy.snapshotInfo.snapshots")).toMatchObject({
            valueKind: "record",
            essential: true,
        });
        expect(findNode(state.nodes, "edit:sourceClusters.legacy.snapshotInfo.snapshots:add")).toMatchObject({
            command: {
                blockedMessage: "First define at least one repository under sourceClusters.legacy.snapshotInfo.repos before adding source snapshots.",
            },
        });
        expect(findNode(state.nodes, "edit:sourceClusters.legacy.snapshotInfo.snapshots.snap1.repoName")).toMatchObject({
            valueKind: "scalar",
            inputHint: {
                kind: "reference",
                sourcePath: ["sourceClusters", "legacy", "snapshotInfo", "repos"],
                options: [],
                message: "First define at least one repository under sourceClusters.legacy.snapshotInfo.repos before binding source snapshots.",
            },
        },);
        expect(findNode(
            state.nodes,
            "edit:sourceClusters.legacy.snapshotInfo.snapshots.snap1.repoName"
        )?.description,).toContain("First define repositories under sourceClusters.legacy.snapshotInfo.repos.");
    });

    it("projects Solr repositories and backups as navigable definitions", () => {
        const state = buildEditStateFromObject({
            sourceClusters: {
                solr: {
                    endpoint: "https://solr.example.com:8983",
                    version: "SOLR 9.7.0",
                    snapshotInfo: {
                        repos: {
                            repo1: {
                                repoPathUri: "s3://snapshot-bucket/solr",
                                awsRegion: "us-east-1",
                            },
                        },
                        backups: {
                            backup1: {
                                repoName: "repo1",
                                externalBackupName: "external-backup",
                            },
                        },
                    },
                },
            },
            targetClusters: {
                target: {endpoint: "https://target.example.com:9200"},
            },
            snapshotMigrationConfigs: [{
                fromSource: "solr",
                toTarget: "target",
                    fromSnapshot: "backup1",
                    slices: [{label: "slice-3",documentBackfillConfig: {}}],
                },],
            traffic: {
                kafkaClusters: {},
                proxies: {},
                s3Sources: {},
                replayers: {},
            },
        });

        expect(findNode(state.nodes, "edit:sourceClusters.solr.snapshotInfo.repos")?.inputHint).toMatchObject({
            definitionCollection: {
                navigation: {
                    groupLabel: "Repositories",
                    groupOrder: 0,
                    groupId: "definition-group:edit:sourceClusters.solr.snapshotInfo.repos",
                },
                definition: {typeLabel: "Snapshot repository"},
            },
        });
        expect(findNode(state.nodes, "edit:sourceClusters.solr.snapshotInfo.backups")?.inputHint).toMatchObject({
            definitionCollection: {
                navigation: {
                    groupLabel: "Backups",
                    groupOrder: 1,
                    groupId: "definition-group:edit:sourceClusters.solr.snapshotInfo.backups",
                },
                definition: {typeLabel: "Source backup"},
            },
        });
        expect(findNode(state.nodes, "edit:sourceClusters.solr.snapshotInfo.backups.backup1.repoName")?.inputHint,).toMatchObject({
            options: [{
                label: "repo1",
                value: "repo1",
                editTargetId: "edit:sourceClusters.solr.snapshotInfo.repos.repo1",
            },],
        });
        expect(findNode(state.nodes, "edit:snapshotMigrationConfigs.0.fromSnapshot")?.inputHint).toMatchObject({
            allowCustom: false,
            options: [
                expect.objectContaining({
                    value: "backup1",
                    editTargetId: "edit:sourceClusters.solr.snapshotInfo.backups.backup1",
                }),
            ],
        });
    });

    it("switches a source snapshot from externally managed to create-snapshot without deleting the snapshot", () => {
        const config = {
            sourceClusters: {
                source: {
                    endpoint: "https://source.example.com:9200",
                    version: "ES 7.10.2",
                    snapshotInfo: {
                        repos: {
                            repo: {repoPathUri: "s3://bucket/path", awsRegion: "us-east-1"},
                        },
                        snapshots: {
                            s1: {
                                repoName: "repo",
                                config: {externallyManagedSnapshotName: "external-s1"},
                            },
                        },
                    },
                },
            },
            targetClusters: {target: {endpoint: "https://target.example.com:9200"},},
            snapshotMigrationConfigs: [],
            traffic: {kafkaClusters: {}, proxies: {}, s3Sources: {}, replayers: {}},
        };

        const result = applyEditOperationToObject(config, {
            op: "set",
            path: ["sourceClusters", "source", "snapshotInfo", "snapshots", "s1", "config"],
            value: "createSnapshotConfig",
        });

        const parsed = parse(result.yaml);
        expect(parsed.sourceClusters.source.snapshotInfo.snapshots).toHaveProperty("s1");
        expect(parsed.sourceClusters.source.snapshotInfo.snapshots.s1).toEqual({
            repoName: "repo",
            config: {createSnapshotConfig: {}},
        });
        expect(findNode(
            result.editState.nodes,
            "edit:sourceClusters.source.snapshotInfo.snapshots.s1.config"),).toMatchObject({
            valueKind: "union",
            value: "createSnapshotConfig",
            variants: expect.arrayContaining([
                expect.objectContaining({value: "externallyManagedSnapshotName"}),
                expect.objectContaining({value: "createSnapshotConfig"}),
            ]),
        });
        expect(findNode(
            result.editState.nodes,
            "edit:sourceClusters.source.snapshotInfo.snapshots.s1.config",
        ),).toMatchObject({
            referenceTargetId: "edit:sourceClusters.source.snapshotInfo.snapshots.s1",
            referenceLabel: "Source Snapshot Definition (s1)",
            description: expect.stringContaining(
                "These settings belong to the source snapshot definition 's1'."
            ),
        });
    });

    it("unsets a source snapshot config branch without deleting the snapshot", () => {
        const config = {
            sourceClusters: {
                source: {
                    endpoint: "https://source.example.com:9200",
                    version: "ES 7.10.2",
                    snapshotInfo: {
                        repos: {
                            repo: {repoPathUri: "s3://bucket/path", awsRegion: "us-east-1"},
                        },
                        snapshots: {
                            s1: {
                                repoName: "repo",
                                config: {externallyManagedSnapshotName: "external-s1"},
                            },
                        },
                    },
                },
            },
            targetClusters: {target: {endpoint: "https://target.example.com:9200"},},
            snapshotMigrationConfigs: [],
            traffic: {kafkaClusters: {}, proxies: {}, s3Sources: {}, replayers: {}},
        };

        const result = applyEditOperationToObject(config, {
            op: "unset",
            path: ["sourceClusters", "source", "snapshotInfo", "snapshots", "s1", "config"],
        });

        const parsed = parse(result.yaml);
        expect(parsed.sourceClusters.source.snapshotInfo.snapshots).toHaveProperty("s1");
        expect(parsed.sourceClusters.source.snapshotInfo.snapshots.s1).toEqual({
            repoName: "repo",
        });
        const configNode = findNode(
            result.editState.nodes,
            "edit:sourceClusters.source.snapshotInfo.snapshots.s1.config",
        );
        expect(configNode).toMatchObject({
            valueKind: "union",
            status: "ok",
            value: "unset",
        });
    });

    it("reports an unknown tuple snapshot on the fromSnapshot field", () => {
        const state = buildEditStateFromObject({
            sourceClusters: {
                source: {
                    endpoint: "https://source.example.com:9200",
                    version: "ES 7.10.2",
                    snapshotInfo: {
                        snapshots: {
                            snap1: {
                                repoName: "",
                                config: {externallyManagedSnapshotName: "snap1"},
                            },
                        },
                    },
                },
            },
            targetClusters: {target: {endpoint: "https://target.example.com:9200"},},
            snapshotMigrationConfigs: [{
                fromSource: "source",
                toTarget: "target",
                    fromSnapshot: "a",
                    slices: [{label: "slice-4",documentBackfillConfig: {}}],
                },],
            traffic: {kafkaClusters: {}, proxies: {}, s3Sources: {}, replayers: {}},
        });

        const snapshotNode = findNode(state.nodes, "edit:snapshotMigrationConfigs.0.fromSnapshot");
        expect(snapshotNode).toMatchObject({
            status: "error",
            diagnostics: [
                expect.objectContaining({
                    path: ["snapshotMigrationConfigs", "0", "fromSnapshot"],
                    message: expect.stringContaining("Define sourceClusters.source.snapshotInfo.snapshots.a"),
                }),
            ],
        });
        expect(snapshotNode?.diagnostics?.[0].message).toContain("choose one of: snap1");
    });

    it("adds a flat snapshot migration configuration", () => {
        const addedMigration = applyEditOperationToObject({
            sourceClusters: {
                legacy: {
                    endpoint: "https://legacy.example.com:9200",
                    version: "ES 7.10.2",
                    snapshotInfo: {
                        snapshots: {
                            all: {
                                repoName: "",
                                config: {externallyManagedSnapshotName: "all"},
                            },
                        },
                    },
                },
            },
            targetClusters: {prod: {endpoint: "https://prod.example.com:9200"}},
            snapshotMigrationConfigs: [{
                fromSource: "legacy",
                toTarget: "prod",
                fromSnapshot: "all",
                slice: "slice-3",
                documentBackfillConfig: {},
            }],
            traffic: {kafkaClusters: {}, proxies: {}, s3Sources: {}, replayers: {},},
        }, {
            op: "add",
            path: ["snapshotMigrationConfigs"],
            value: {name: "slice-4"},
        },);
        const addedSnapshotConfig = parse(addedMigration.yaml);

        expect(Array.isArray(addedSnapshotConfig.snapshotMigrationConfigs)).toBe(true);
        expect(addedSnapshotConfig.snapshotMigrationConfigs[1]).toEqual({
            fromSource: "legacy",
            toTarget: "prod",
            fromSnapshot: "all",
            slice: "slice-4",
        });
        expect(findNode(addedMigration.editState.nodes, "edit:snapshotMigrationConfigs.1")).toMatchObject({
            valueKind: "object",
            essential: true,
            status: "required",
            label: "legacy-prod-all-slice-4",
        });
        expect(findNode(
                addedMigration.editState.nodes,
                "edit:snapshotMigrationConfigs.1.metadataMigrationConfig:add",),).toMatchObject({
            valueKind: "command",
            label: "+ Add metadata migration",
            command: {
                requiresName: false,
                autoEditAdded: false,
            },
        });
        expect(findNode(addedMigration.editState.nodes, "edit:snapshotMigrationConfigs.1.documentBackfillConfig:add"),).toMatchObject({
            valueKind: "command",
            label: "+ Add document backfill",
            command: {
                requiresName: false,
                autoEditAdded: false,
            },
        });
    });

    it("selects the sole snapshot and preserves slices when the migration source changes", () => {
        const result = applyEditOperationToObject({
            sourceClusters: {
                legacy: {
                    endpoint: "https://legacy.example.com:9200",
                    version: "ES 7.10.2",
                    snapshotInfo: {
                        snapshots: {
                            snap1: {repoName: "", config: {externallyManagedSnapshotName: "snap1"},},
                        },
                    },
                },
                aux: {
                    endpoint: "https://aux.example.com:9200",
                    version: "ES 7.10.2",
                    snapshotInfo: {
                        snapshots: {
                            auxsnap: {repoName: "", config: {externallyManagedSnapshotName: "auxsnap"},},
                        },
                    },
                },
            },
            targetClusters: {prod: {endpoint: "https://prod.example.com:9200"}},
            snapshotMigrationConfigs: [{
                fromSource: "legacy",
                toTarget: "prod",
                        fromSnapshot: "snap1",
                        slices: [{label: "slice-5",metadataMigrationConfig: {}}],
                },],
            traffic: {kafkaClusters: {}, proxies: {}, s3Sources: {}, replayers: {},},
        }, {
            op: "set",
            path: ["snapshotMigrationConfigs", "0", "fromSource"],
            value: "aux",
        },);

        const config = parse(result.yaml);
        expect(config.snapshotMigrationConfigs[0]).toEqual({
            fromSource: "aux",
            toTarget: "prod",
            fromSnapshot: "auxsnap",
            slice: "slice-5",
            metadataMigrationConfig: {},
        });
        expect(findNode(result.editState.nodes, "edit:snapshotMigrationConfigs.0.fromSnapshot")).toMatchObject({
            value: "auxsnap",
        });
    });

    it("removes migration tuples that reference a deleted source snapshot", () => {
        const result = applyEditOperationToObject({
            sourceClusters: {
                legacy: {
                    endpoint: "https://legacy.example.com:9200",
                    version: "ES 7.10.2",
                    snapshotInfo: {
                        snapshots: {
                            snap1: {repoName: "", config: {externallyManagedSnapshotName: "snap1"},},
                            snap2: {repoName: "", config: {externallyManagedSnapshotName: "snap2"},},
                        },
                    },
                },
            },
            targetClusters: {prod: {endpoint: "https://prod.example.com:9200"}},
            snapshotMigrationConfigs: [{
                fromSource: "legacy",
                toTarget: "prod",
                        fromSnapshot: "snap1",
                        slices: [{label: "slice-6",metadataMigrationConfig: {}}],
                    },
                    {
                        fromSource: "legacy",
                        toTarget: "prod",
                        fromSnapshot: "snap2",
                        slices: [{label: "slice-7",documentBackfillConfig: {}}],
                },],
            traffic: {kafkaClusters: {}, proxies: {}, s3Sources: {}, replayers: {},},
        }, {
            op: "removeConfig",
            path: ["sourceClusters", "legacy", "snapshotInfo", "snapshots", "snap1"],
        },);

        const config = parse(result.yaml);
        expect(config.sourceClusters.legacy.snapshotInfo.snapshots.snap1).toBeUndefined();
        expect(config.snapshotMigrationConfigs).toEqual([{
            fromSource: "legacy",
            toTarget: "prod",
            fromSnapshot: "snap2",
            slice: "slice-7",
            documentBackfillConfig: {},
        },
        ]);
        expect(findNode(result.editState.nodes, "edit:snapshotMigrationConfigs.0.fromSnapshot")).toMatchObject({
            value: "snap2",
        });
    });

    it("renders generic object override fields from the unified JSON schema", () => withUnifiedSchemaFixture(() => {
        const state = buildEditStateFromObject({
            sourceClusters: {legacy: {endpoint: "https://legacy.example.com:9200", version: "ES 7.10.2",},},
            targetClusters: {prod: {endpoint: "https://prod.example.com:9200"}},
            traffic: {
                kafkaClusters: {
                    kafka: {
                        autoCreate: {},
                        topics: {capture: {}},
                    },
                },
            },
            snapshotMigrationConfigs: [],
        });

        expect(findNode(state.nodes, "edit:traffic.kafkaClusters.kafka.autoCreate.clusterSpecOverrides.kafka"),).toMatchObject({
            valueKind: "object",
            presence: "optional",
        });
        expect(findNode(state.nodes, "edit:traffic.kafkaClusters.kafka.autoCreate.clusterSpecOverrides.kafka.config.min.insync.replicas",),).toMatchObject({
            valueKind: "scalar",
            valueType: "number",
        });
        expect(findNode(state.nodes, "edit:traffic.kafkaClusters.kafka.autoCreate.nodePoolSpecOverrides.storage"),).toMatchObject({
            valueKind: "union",
            value: "unset",
        });
        expect(findNode(state.nodes, "edit:traffic.kafkaClusters.kafka.topics.capture.specOverrides"),).toMatchObject({
            valueKind: "object",
            presence: "optional",
        });
        expect(findNode(state.nodes, "edit:traffic.kafkaClusters.kafka.topics.capture.specOverrides:add"),).toBeUndefined();
        expect(findNode(state.nodes, "edit:traffic.kafkaClusters.kafka.topics.capture.specOverrides.partitions"),).toMatchObject({
            valueKind: "scalar",
            valueType: "number",
            value: 1,
            valueDefaulted: true,
            expert: false,
            essential: true,
            validation: {
                minimum: 1,
                integer: true,
            },
        });
        expect(findNode(state.nodes, "edit:traffic.kafkaClusters.kafka.topics.capture.specOverrides.replicas"),).toMatchObject({
            valueKind: "scalar",
            valueType: "number",
            value: 3,
            valueDefaulted: true,
            expert: false,
            essential: true,
            validation: {
                minimum: 1,
                integer: true,
            },
        });
        expect(findNode(state.nodes, "edit:traffic.kafkaClusters.kafka.topics.capture.specOverrides.config"),).toMatchObject({
            expert: true,
        });
        expect(findNode(state.nodes, "edit:traffic.kafkaClusters.kafka.topics.capture.specOverrides.config.cleanup.policy",),).toMatchObject({
            valueKind: "union",
        });
        expect(findNode(state.nodes, "edit:traffic.kafkaClusters.kafka.autoCreate.nodePoolSpecOverrides.roles"),).toMatchObject({
            valueKind: "array",
            presence: "optional",
        });
        expect(findNode(state.nodes, "edit:traffic.kafkaClusters.kafka.autoCreate.nodePoolSpecOverrides.roles:add"),).toMatchObject({
            valueKind: "command",
        });

        const compactTopic = applyEditOperationToObject({
            traffic: {kafkaClusters: {kafka: {
                autoCreate: {},
                topics: {capture: {}},
            }}},
            snapshotMigrationConfigs: [],
        }, {
            op: "set",
            path: ["traffic", "kafkaClusters", "kafka", "topics", "capture", "specOverrides", "config", "cleanup.policy",],
            value: "compact",
        },);
        const persistentStorage = applyEditOperationToObject(parse(compactTopic.yaml), {
            op: "set",
            path: ["traffic", "kafkaClusters", "kafka", "autoCreate", "nodePoolSpecOverrides", "storage"],
            value: "persistent-claim",
        });

        expect(compactTopic.yaml).toContain("cleanup.policy: compact");
        expect(persistentStorage.yaml).toContain("type: persistent-claim");
        expect(findNode(persistentStorage.editState.nodes, "edit:traffic.kafkaClusters.kafka.autoCreate.nodePoolSpecOverrides.storage.size",),).toMatchObject({
            valueKind: "scalar",
            presence: "optional",
        });

        const addedRole = applyEditOperationToObject({
            traffic: {kafkaClusters: {kafka: {autoCreate: {}}}},
            snapshotMigrationConfigs: [],
        }, {
            op: "add",
            path: ["traffic", "kafkaClusters", "kafka", "autoCreate", "nodePoolSpecOverrides", "roles"],
            value: {},
        },);
        const roleItem = findNode(addedRole.editState.nodes, "edit:traffic.kafkaClusters.kafka.autoCreate.nodePoolSpecOverrides.roles.0",);
        expect(roleItem).toMatchObject({
            valueKind: "union",
            status: "required",
            collapsed: true,
        });
        expect(roleItem?.variants?.map((variant) => variant.value)).toEqual(["broker", "controller"]);

        const appendedRole = applyEditOperationToObject({
            traffic: {
                kafkaClusters: {
                    kafka: {
                        autoCreate: {
                            nodePoolSpecOverrides: {roles: ["broker"]},
                        },
                    },
                },
            },
            snapshotMigrationConfigs: [],
        }, {
            op: "add",
            path: ["traffic", "kafkaClusters", "kafka", "autoCreate", "nodePoolSpecOverrides", "roles"],
            value: {},
        },);
        expect(parse(appendedRole.yaml).traffic.kafkaClusters.kafka.autoCreate.nodePoolSpecOverrides.roles).toEqual(["broker", ""],);

        const setRole = applyEditOperationToObject(parse(addedRole.yaml), {
            op: "set",
            path: ["traffic", "kafkaClusters", "kafka", "autoCreate", "nodePoolSpecOverrides", "roles", "0"],
            value: "broker",
        });
        expect(setRole.yaml).toContain("- broker");

        const removedRole = applyEditOperationToObject(parse(setRole.yaml), {
            op: "removeConfig",
            path: ["traffic", "kafkaClusters", "kafka", "autoCreate", "nodePoolSpecOverrides", "roles", "0"],
        });
        expect(parse(removedRole.yaml).traffic.kafkaClusters.kafka.autoCreate.nodePoolSpecOverrides.roles).toEqual([],);
    }));

    it("adds an explicit topic through the Kafka cluster union's shared topics field", () => {
        const result = applyEditOperationToObject({
            traffic: {
                kafkaClusters: {
                    shared: {
                        autoCreate: {},
                        topics: {},
                    },
                },
            },
            snapshotMigrationConfigs: [],
        }, {
            op: "add",
            path: ["traffic", "kafkaClusters", "shared", "topics"],
            value: {name: "capture"},
        });

        expect(parse(result.yaml).traffic.kafkaClusters.shared.topics).toEqual({
            capture: {},
        });
        expect(findNode(
            result.editState.nodes,
            "edit:traffic.kafkaClusters.shared.topics.capture",
        )).toMatchObject({
            label: "capture: {}",
            valueKind: "object",
        });
    });

    it("renders missing capture proxy options as visible required fields", () => {
        const state = buildEditStateFromObject({
            sourceClusters: {source: {endpoint: "", version: "ES 7.10.2"}},
            targetClusters: {},
            traffic: {
                kafkaClusters: {
                    default: {autoCreate: {}},
                },
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
        const suppressHeaderMatch = findNode(state.nodes, "edit:traffic.proxies.cap.proxyConfig.suppressCaptureForHeaderMatch",);
        const suppressMethod = findNode(state.nodes, "edit:traffic.proxies.cap.proxyConfig.suppressCaptureForMethod");
        const suppressUriPath = findNode(state.nodes, "edit:traffic.proxies.cap.proxyConfig.suppressCaptureForUriPath");
        const suppressMethodAndPath = findNode(state.nodes, "edit:traffic.proxies.cap.proxyConfig.suppressMethodAndPath",);
        const addProxy = findNode(state.nodes, "edit:traffic.proxies:add");

        const expectedOptionKeys = [
            ...USER_PROXY_WORKFLOW_OPTION_KEYS,
            ...USER_PROXY_PROCESS_OPTION_KEYS
        ].map(String);
        for (const key of expectedOptionKeys) {
            expect(findNode(state.nodes, `edit:traffic.proxies.cap.proxyConfig.${key}`)).toBeDefined();
        }
        expect(proxy?.status).toBe("required");
        expect(proxy?.statusCounts?.required).toBe(3);
        expect(proxyConfig?.status).toBe("required");
        expect(proxyConfig?.statusCounts?.required).toBe(1);
        expect(proxyConfig?.required).toBe(true);
        expect(proxyConfig?.presence).toBe("required");
        expect(kafka).toMatchObject({status: "required", presence: "required"});
        expect(kafka?.inputHint).toMatchObject({
            kind: "reference",
            message: "Choose a configured Kafka cluster.",
            options: [{
                editTargetId: "edit:traffic.kafkaClusters.default",
                label: "default",
                value: "default",
            }],
            sourcePath: ["traffic", "kafkaClusters"],
        });
        expect(kafka?.inputHint).not.toHaveProperty("createReference");
        expect(kafkaTopic).toMatchObject({
            status: "required",
            presence: "required",
        });
        expect(kafkaTopic?.valueAuthored).toBeUndefined();
        expect(listenPort?.status).toBe("required");
        expect(listenPort?.presence).toBe("required");
        expect(listenPort?.valueType).toBe("number");
        expect(listenPort?.label).toContain("listenPort: <required>");
        expect(podReplicas).toMatchObject({status: "ok", presence: "optional", expert: false, valueType: "number",});
        expect(serviceType).toMatchObject({
            status: "ok",
            presence: "optional",
            expert: true,
            valueKind: "union",
            value: "LoadBalancer",
            valueDefaulted: true,
        });
        expect(serviceType?.variants?.map((variant) => variant.value)).toEqual([
            "unset",
            "LoadBalancer",
            "ClusterIP"
        ]);
        expect(tls).toMatchObject({
            presence: "optional",
            valueKind: "union",
            value: "unset",
            effectiveDefault: {
                label: expect.any(String),
                description: expect.any(String),
            },
        });
        expect(tls?.label).toContain("tls: < default:");
        expect(tls?.variants?.[0]).toMatchObject({
            label: expect.stringMatching(/^default \(.+\)$/),
            value: "unset",
            description: expect.any(String),
        });
        expect(setHeader).toMatchObject({presence: "optional", valueKind: "array",});
        expect(suppressHeaderMatch).toMatchObject({
            presence: "optional",
            valueKind: "record",
            inputHint: {
                kind: "record",
                addLabel: "header match",
            },
        });
        expect(findNode(state.nodes, "edit:traffic.proxies.cap.proxyConfig.suppressCaptureForHeaderMatch:add"),).toMatchObject({
            label: "+ Add header match",
            command: {
                requiresName: true,
                editAdded: true,
            },
            inputHint: {
                kind: "text",
            },
        });
        expect(suppressMethod?.inputHint).toMatchObject({
            kind: "javaRegex",
            testStrings: ["GET", "HEAD", "POST", "PUT", "DELETE"],
        });
        expect(suppressUriPath?.inputHint).toMatchObject({
            kind: "javaRegex",
            testStrings: ["/_cluster/health", "/_cat/indices?v", "/my-index/_search", "/_bulk", "/favicon.ico"],
        });
        expect(suppressMethodAndPath?.inputHint).toMatchObject({
            kind: "javaRegex",
            testStrings: ["GET /_cluster/health", "HEAD /", "POST /my-index/_search", "GET /_cat/indices?v", "POST /_bulk",],
        });
        expect(kafkaTopic?.status).toBe("required");
        expect(kafkaTopic?.label).toContain("kafkaTopic: <required>");
        expect(captureGroup?.statusCounts?.required).toBe(3);
        expect(addProxy?.status).toBe("ok");
        expect(addProxy?.label).toContain("+ Add capture proxy");
    });

    it("edits capture header suppression as a map of header names to regex values", () => {
        const added = applyEditOperationToObject({
            sourceClusters: {source: {endpoint: "", version: "ES 7.10.2"}},
            targetClusters: {},
            traffic: {
                kafkaClusters: {
                    default: {autoCreate: {}},
                },
                proxies: {
                    cap: {source: "source", proxyConfig: {listenPort: 9201}},
                },
                replayers: {},
            },
            snapshotMigrationConfigs: [],
        }, {
            op: "add",
            path: ["traffic", "proxies", "cap", "proxyConfig", "suppressCaptureForHeaderMatch"],
            value: {name: "User-Agent"},
        },);

        const addedHeader = findNode(
            added.editState.nodes,
            "edit:traffic.proxies.cap.proxyConfig.suppressCaptureForHeaderMatch.User-Agent",
        );
        expect(parse(added.yaml).traffic.proxies.cap.proxyConfig.suppressCaptureForHeaderMatch).toEqual({
            "User-Agent": "",
        });
        expect(addedHeader).toMatchObject({
            label: "User-Agent: <required>",
            status: "required",
            valueKind: "scalar",
            inputHint: {
                kind: "javaRegex",
                testStrings: ["healthcheck", "Mozilla/5.0 healthcheck", "curl/8.6.0", "Bearer eyJhbGciOi...", "application/json",],
            },
        });

        const set = applyEditOperationToObject(parse(added.yaml), {
            op: "set",
            path: ["traffic", "proxies", "cap", "proxyConfig", "suppressCaptureForHeaderMatch", "User-Agent"],
            value: ".*healthcheck.*",
        });

        expect(parse(set.yaml).traffic.proxies.cap.proxyConfig.suppressCaptureForHeaderMatch).toEqual({
            "User-Agent": ".*healthcheck.*",
        });
        expect(findNode(
            set.editState.nodes,
            "edit:traffic.proxies.cap.proxyConfig.suppressCaptureForHeaderMatch.User-Agent",
        )?.label,).toBe("User-Agent: .*healthcheck.*");
    });

    it("renders transform specs as mutually exclusive selector trees", () => {
        const baseConfig = {
            sourceClusters: {source: {endpoint: "https://source.example.com:9200", version: "ES 7.10.2",},},
            targetClusters: {target: {endpoint: "https://target.example.com:9200"},},
            traffic: {
                proxies: {
                    cap: {
                        source: "source",
                        proxyConfig: {listenPort: 9201},
                    },
                },
                replayers: {
                    replay: {
                        fromCapturedTraffic: "cap",
                        toTarget: "target",
                        replayerConfig: {
                            requestTransforms: [{}],
                        },
                    },
                },
            },
            snapshotMigrationConfigs: [],
        };
        const transformPath = ["traffic", "replayers", "replay", "replayerConfig", "requestTransforms", "0"];
        const state = buildEditStateFromObject(baseConfig);

        const transform = findNode(state.nodes, "edit:traffic.replayers.replay.replayerConfig.requestTransforms.0");
        expect(transform).toMatchObject({
            valueKind: "union",
            status: "required",
            removable: true,
            variants: [
                expect.objectContaining({value: "entryPoint"}),
                expect.objectContaining({value: "transformName"}),
            ],
        });

        const entryPointSelected = applyEditOperationToObject(baseConfig, {
            op: "set",
            path: transformPath,
            value: "entryPoint",
        });
        expect(parse(entryPointSelected.yaml).traffic.replayers.replay.replayerConfig.requestTransforms[0]).toEqual({
            entryPoint: {},
        });
        const entryPoint = findNode(
            entryPointSelected.editState.nodes,
            "edit:traffic.replayers.replay.replayerConfig.requestTransforms.0.entryPoint",
        );
        expect(entryPoint).toMatchObject({
            valueKind: "union",
            status: "required",
            essential: true,
            variants: [
                expect.objectContaining({label: "inline JavaScript", value: "javascript",}),
                expect.objectContaining({label: "external JavaScript file", value: "javascriptFile",}),
                expect.objectContaining({label: "inline Python", value: "python"}),
                expect.objectContaining({label: "external Python file", value: "pythonFile",}),
            ],
        });
        const context = findNode(
            entryPointSelected.editState.nodes,
            "edit:traffic.replayers.replay.replayerConfig.requestTransforms.0.context",
        );
        expect(context).toMatchObject({
            valueKind: "object",
            label: "context: <unset>",
            essential: true,
            children: [
                expect.objectContaining({
                    id: "edit:traffic.replayers.replay.replayerConfig.requestTransforms.0.context.valueDirectories",
                    valueKind: "array",
                    label: "value directories: 0 items",
                    essential: true,
                }),
                expect.objectContaining({
                    id: "edit:traffic.replayers.replay.replayerConfig.requestTransforms.0.context.values",
                    valueKind: "record",
                    label: "named values: 0 items",
                    essential: true,
                    children: [
                        expect.objectContaining({
                            label: "+ Add context value",
                            inputHint: expect.objectContaining({
                                kind: "text",
                                message: "Name used by transform code to read this context value.",
                            }),
                        }),
                    ],
                }),
            ],
        });

        const contextValueAdded = applyEditOperationToObject(parse(entryPointSelected.yaml), {
            op: "add",
            path: [...transformPath, "context", "values"],
            value: {name: "tenantConfig"},
        });
        expect(parse(contextValueAdded.yaml).traffic.replayers.replay.replayerConfig.requestTransforms[0]).toEqual({
            entryPoint: {},
            context: {
                values: {
                    tenantConfig: {},
                },
            },
        });
        expect(findNode(
            contextValueAdded.editState.nodes,
            "edit:traffic.replayers.replay.replayerConfig.requestTransforms.0.context.values.tenantConfig",
        ),).toMatchObject({
            valueKind: "union",
            variants: [
                expect.objectContaining({label: "inline value", value: "value"}),
                expect.objectContaining({label: "external file", value: "fromFile"}),
            ],
        });

        const javascriptSelected = applyEditOperationToObject(parse(entryPointSelected.yaml), {
            op: "set",
            path: [...transformPath, "entryPoint"],
            value: "javascript",
        });
        expect(parse(javascriptSelected.yaml).traffic.replayers.replay.replayerConfig.requestTransforms[0]).toEqual({
            entryPoint: {javascript: ""},
        });
        expect(findNode(
            javascriptSelected.editState.nodes,
            "edit:traffic.replayers.replay.replayerConfig.requestTransforms.0.entryPoint.javascript",
        ),).toMatchObject({
            valueKind: "scalar",
            status: "required",
            essential: true,
        });

        const javascriptFileSelected = applyEditOperationToObject(parse(entryPointSelected.yaml), {
            op: "set",
            path: [...transformPath, "entryPoint"],
            value: "javascriptFile",
        });
        expect(parse(javascriptFileSelected.yaml).traffic.replayers.replay.replayerConfig.requestTransforms[0]).toEqual({
            entryPoint: {javascriptFile: {}},
        },);
        const javascriptFile = findNode(
            javascriptFileSelected.editState.nodes,
            "edit:traffic.replayers.replay.replayerConfig.requestTransforms.0.entryPoint.javascriptFile",
        );
        expect(javascriptFile).toMatchObject({
            valueKind: "union",
            status: "required",
            essential: true,
            variants: [
                expect.objectContaining({label: "mountable image", value: "image"}),
                expect.objectContaining({label: "ConfigMap key", value: "configMap"}),
            ],
        });

        const transformConfigMapRef = applyEditOperationToObject(parse(javascriptFileSelected.yaml), {
            op: "set",
            path: [...transformPath, "entryPoint", "javascriptFile"],
            value: "configMap",
        });
        expect(parse(transformConfigMapRef.yaml).traffic.replayers.replay.replayerConfig.requestTransforms[0]).toEqual({
            entryPoint: {javascriptFile: {configMap: "", path: ""}},
        });
        expect(findNode(
            transformConfigMapRef.editState.nodes,
            "edit:traffic.replayers.replay.replayerConfig.requestTransforms.0.entryPoint.javascriptFile.configMap",
        ),).toMatchObject({
            valueKind: "scalar",
            required: true,
            externalRef: {
                kind: "kubernetesResource",
                purpose: "file-ref-config-map",
                selection: {
                    target: "fileRefConfigMap",
                    nameField: "configMap",
                    pathField: "path",
                },
                k8s: {
                    resourceTypes: [{group: "", version: "v1", kind: "ConfigMap", namespaced: true}],
                },
            },
        });

        const transformImageRef = applyEditOperationToObject(parse(javascriptFileSelected.yaml), {
            op: "set",
            path: [...transformPath, "entryPoint", "javascriptFile"],
            value: "image",
        });
        expect(parse(transformImageRef.yaml).traffic.replayers.replay.replayerConfig.requestTransforms[0]).toEqual({
            entryPoint: {javascriptFile: {image: "", pullPolicy: "IfNotPresent", path: ""},},
        });
        expect(findNode(
            transformImageRef.editState.nodes,
            "edit:traffic.replayers.replay.replayerConfig.requestTransforms.0.entryPoint.javascriptFile.image",
        ),).toMatchObject({
            valueKind: "scalar",
            required: true,
            validation: {
                pattern: expect.any(String),
            },
            inputHint: {
                kind: "text",
                format: "oci-image-reference",
            },
        });

        const transformNameSelected = applyEditOperationToObject(parse(javascriptSelected.yaml), {
            op: "set",
            path: transformPath,
            value: "transformName",
        });
        expect(parse(transformNameSelected.yaml).traffic.replayers.replay.replayerConfig.requestTransforms[0]).toEqual({
            transformName: "",
        });
        expect(findNode(
            transformNameSelected.editState.nodes,
            "edit:traffic.replayers.replay.replayerConfig.requestTransforms.0.transformName",
        ),).toMatchObject({
            essential: true,
        });
    });

    it("does not require replay config when traffic capture is configured alone", () => {
        const state = buildEditStateFromObject({
            sourceClusters: {source: {endpoint: "https://source.example.com:9200", version: "ES 7.10.2",},},
            targetClusters: {},
            traffic: {
                kafkaClusters: {default: {autoCreate: {}}},
                proxies: {
                    cap: {source: "source", kafka: "default", proxyConfig: {listenPort: 9201}},
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

    it("renders schema-backed settings after adding a named replayer", () => {
        const result = applyEditOperationToObject({
            sourceClusters: {
                source: {
                    endpoint: "https://source.example.com:9200",
                    version: "ES 7.10.2",
                },
            },
            targetClusters: {
                target: {endpoint: "https://target.example.com:9200"},
            },
            traffic: {
                kafkaClusters: {default: {autoCreate: {}}},
                proxies: {
                    capture: {source: "source", kafka: "default", proxyConfig: {}},
                },
                replayers: {},
            },
            snapshotMigrationConfigs: [],
        }, {
            op: "add",
            path: ["traffic", "replayers"],
            value: {name: "replay"},
        },);
        const config = parse(result.yaml);

        expect(config.traffic.replayers.replay).toEqual({
            fromCapturedTraffic: "capture",
            toTarget: "target",
        });
        expect(findNode(result.editState.nodes, "edit:traffic.replayers.replay.fromCapturedTraffic"))
            .toMatchObject({status: "ok", value: "capture",});
        expect(findNode(result.editState.nodes, "edit:traffic.replayers.replay.toTarget"))
            .toMatchObject({status: "ok", value: "target",});
        expect(findNode(result.editState.nodes, "edit:traffic.replayers.replay.replayerConfig.speedupFactor"),)
            .toMatchObject({
                valueKind: "scalar",
                valueDefaulted: true,
            });
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

        expect(tls).toMatchObject({valueKind: "union", value: "existingSecret", status: "required",});
        expect(tls?.variants?.map((variant) => variant.value)).toEqual([
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
                        {group: "cert-manager.io", version: "v1", kind: "Issuer", namespaced: true,},
                        {group: "cert-manager.io", version: "v1", kind: "ClusterIssuer", namespaced: false,},
                        {group: "awspca.cert-manager.io", version: "v1beta1", kind: "AWSPCAClusterIssuer", namespaced: false,},
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
            validation: {
                pattern: DNS_NAME_PATTERN,
                message: expect.stringContaining("Use a DNS name"),
            },
        });
        expect(dnsName?.label).toContain("DNS name 1: cap.default.svc.cluster.local");
        expect(addDnsName).toMatchObject({
            valueKind: "command",
            label: "+ Add DNS name",
            command: {requiresName: false},
        });

        const invalidState = buildEditStateFromObject({
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
                                dnsNames: ["https://cap.default.svc.cluster.local:9201"],
                            },
                        },
                    },
                },
            },
            snapshotMigrationConfigs: [],
        });
        const invalidDnsName = findNode(invalidState.nodes, "edit:traffic.proxies.cap.proxyConfig.tls.dnsNames.0");
        expect(invalidDnsName).toMatchObject({
            valueKind: "scalar",
            status: "error",
            validation: {pattern: DNS_NAME_PATTERN},
            diagnostics: expect.arrayContaining([
                expect.objectContaining({
                    severity: "error",
                    message: expect.stringContaining("Use a DNS name"),
                }),
            ]),
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
        },);

        const tls = findNode(result.editState.nodes, "edit:traffic.proxies.cap.proxyConfig.tls");
        const secretName = findNode(result.editState.nodes, "edit:traffic.proxies.cap.proxyConfig.tls.secretName");

        expect(result.yaml).toContain("mode: existingSecret");
        expect(result.yaml).toContain('secretName: ""');
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
        },);

        const emptyDnsNames = findNode(certManager.editState.nodes, "edit:traffic.proxies.cap.proxyConfig.tls.dnsNames",);
        const addDnsName = findNode(certManager.editState.nodes, "edit:traffic.proxies.cap.proxyConfig.tls.dnsNames:add",);

        expect(parse(certManager.yaml).traffic.proxies.cap.proxyConfig.tls.dnsNames).toEqual([]);
        expect(emptyDnsNames).toMatchObject({
            valueKind: "array",
            status: "required",
            diagnostics: expect.arrayContaining([
                expect.objectContaining({severity: "required"})
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
            "-----END CERTIFICATE-----"
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
            "edit:traffic.proxies.cap.proxyConfig.tls.clientAuth.consoleClientSecretName",
        );

        expect(clientAuth).toMatchObject({valueKind: "union", value: "enabled", status: "ok",});
        expect(clientAuth?.variants?.map((variant) => variant.value)).toEqual(["disabled", "enabled"]);
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
        },);

        const tls = findNode(result.editState.nodes, "edit:traffic.proxies.cap.proxyConfig.tls");
        const clientAuth = findNode(result.editState.nodes, "edit:traffic.proxies.cap.proxyConfig.tls.clientAuth");

        expect(result.yaml).toContain("mode: existingSecret");
        expect(result.yaml).toContain("clientAuth:");
        expect(result.yaml).not.toContain("required:");
        expect(tls?.label).toContain("tls: < existingSecret >");
        expect(clientAuth).toMatchObject({valueKind: "union", value: "enabled"});
    });

    it("renders proxy clientAuth file refs as object unions", () => {
        const enabled = applyEditOperationToObject({
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
        },);

        const fileRef = findNode(
            enabled.editState.nodes,
            "edit:traffic.proxies.cap.proxyConfig.tls.clientAuth.trustedClientCaFile",
        );
        expect(fileRef).toMatchObject({
            valueKind: "union",
            value: "unset",
        });
        expect(fileRef?.variants?.map((variant) => variant.value)).toEqual(["unset", "image", "configMap"]);

        const configMapRef = applyEditOperationToObject(parse(enabled.yaml), {
            op: "set",
            path: ["traffic", "proxies", "cap", "proxyConfig", "tls", "clientAuth", "trustedClientCaFile"],
            value: "configMap",
        });
        const parsed = parse(configMapRef.yaml);
        expect(parsed.traffic.proxies.cap.proxyConfig.tls.clientAuth.trustedClientCaFile).toEqual({
            configMap: "",
            path: "",
        });
        expect(findNode(
            configMapRef.editState.nodes,
            "edit:traffic.proxies.cap.proxyConfig.tls.clientAuth.trustedClientCaFile.configMap",
        ),).toMatchObject({
            valueKind: "scalar",
            required: true,
            externalRef: {
                kind: "kubernetesResource",
                purpose: "file-ref-config-map",
                selection: {
                    target: "fileRefConfigMap",
                    nameField: "configMap",
                    pathField: "path",
                },
                k8s: {
                    resourceTypes: [{group: "", version: "v1", kind: "ConfigMap", namespaced: true}],
                },
            },
        });
        expect(findNode(
            configMapRef.editState.nodes,
            "edit:traffic.proxies.cap.proxyConfig.tls.clientAuth.trustedClientCaFile.path",
        ),).toMatchObject({valueKind: "scalar", required: true});
    });

    it("adds/removes nested traffic resources and switches Kafka mode", () => {
        const config = {
            sourceClusters: {},
            targetClusters: {},
            snapshotMigrationConfigs: [],
        };

        const addedKafka = applyEditOperationToObject(config, {
            op: "add",
            path: ["traffic", "kafkaClusters"],
            value: {name: "default"},
        });
        const existingKafka = applyEditOperationToObject(parse(addedKafka.yaml), {
            op: "set",
            path: ["traffic", "kafkaClusters", "default", "mode"],
            value: "existing",
        });
        const scramKafka = applyEditOperationToObject(parse(existingKafka.yaml), {
            op: "set",
            path: ["traffic", "kafkaClusters", "default", "existing", "auth"],
            value: "scram-sha-512",
        });
        const defaultAuthKafka = applyEditOperationToObject(parse(addedKafka.yaml), {
            op: "set",
            path: ["traffic", "kafkaClusters", "default", "autoCreate", "auth"],
            value: "scram-sha-512",
        });
        const resetDefaultAuthKafka = applyEditOperationToObject(parse(defaultAuthKafka.yaml), {
            op: "set",
            path: ["traffic", "kafkaClusters", "default", "autoCreate", "auth"],
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
        expect(findNode(existingKafka.editState.nodes, "edit:traffic.kafkaClusters.default.existing.kafkaConnection"),).toMatchObject({
            status: "required",
            required: true,
        });
        expect(findNode(
            existingKafka.editState.nodes,
            "edit:traffic.kafkaClusters.default.existing.kafkaTopic",
        )).toBeUndefined();
        expect(findNode(existingKafka.editState.nodes, "edit:traffic.kafkaClusters.default.existing.auth"),).toMatchObject({
            valueKind: "union",
            value: "none",
        });
        expect(scramKafka.yaml).toContain("type: scram-sha-512");
        expect(findNode(scramKafka.editState.nodes, "edit:traffic.kafkaClusters.default.existing.auth.secretName"),).toMatchObject({
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
        expect(findNode(scramKafka.editState.nodes, "edit:traffic.kafkaClusters.default.existing.auth.caSecretName"),).toMatchObject({
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
        expect(findNode(scramKafka.editState.nodes, "edit:traffic.kafkaClusters.default.existing.auth.kafkaUserName"),).toMatchObject({
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
        expect(addedReplayer.yaml).toContain('fromCapturedTraffic: ""');
        expect(removedProxy.yaml).not.toContain("capture:");
    });

    it("requires and applies a snapshot migration name when appending configs", () => {
        const result = applyEditOperationToObject({
            sourceClusters: {},
            targetClusters: {},
            snapshotMigrationConfigs: [],
        }, {
            op: "add",
            path: ["snapshotMigrationConfigs"],
            value: {name: "initial"},
        },);

        expect(result.yaml).toContain('fromSource: ""');
        expect(result.yaml).toContain('toTarget: ""');
        expect(result.yaml).toContain("slice: initial");
        expect(findNode(result.editState.nodes, "edit:snapshotMigrationConfigs.0")?.status).toBe("required");
    });

    it("keeps CLI stdout parseable when validation emits diagnostics", () => withUnifiedSchemaFixture((schemaPath) => {
        const cliPath = path.resolve(__dirname, "../src/cliRouter.ts");
        const samplePath = path.resolve(__dirname, "../scripts/samples/proxyWithoutTlsNoAuth.wf.yaml");
        const result = spawnSync(
            process.execPath,
            ["--import", TSX_IMPORT, cliPath, "editConfig", "state", "--pending-config", samplePath],
            {
                encoding: "utf8",
                env: {...process.env, MIGRATION_UNIFIED_SCHEMA_PATH: schemaPath},
                maxBuffer: 5 * 1024 * 1024,
            },
        );

        expect(result.status).toBe(0);
        expect(result.stdout.trimStart().startsWith("{")).toBe(true);
        expect(() => JSON.parse(result.stdout)).not.toThrow();
        expect(result.stderr).toBe("");
    }));

    it("opens a blank saved configuration as an addable structured draft", () => withUnifiedSchemaFixture((schemaPath) => {
        const tempDir = mkdtempSync(path.join(tmpdir(), "edit-config-blank-"));
        try {
            const configPath = path.join(tempDir, "blank.yaml");
            writeFileSync(configPath, "");
            const cliPath = path.resolve(__dirname, "../src/cliRouter.ts");

            const result = spawnSync(
                process.execPath,
                [
                    "--import",
                    TSX_IMPORT,
                    cliPath,
                    "editConfig",
                    "state",
                    "--pending-config",
                    configPath
                ],
                {
                    encoding: "utf8",
                    env: {...process.env, MIGRATION_UNIFIED_SCHEMA_PATH: schemaPath},
                    maxBuffer: 10 * 1024 * 1024,
                },
            );

            expect(result.status).toBe(0);
            const state = JSON.parse(result.stdout);
            expect(state.provenance).toMatchObject({
                lossy: false,
                mode: "structured",
            });
            expect(findNode(state.nodes, "edit:sourceClusters:add")).toBeDefined();
            expect(findNode(state.nodes, "edit:targetClusters:add")).toBeDefined();
            expect(findNode(state.nodes, "edit:snapshotMigrationConfigs:add")).toBeDefined();
            expect(findNode(state.nodes, "edit:traffic.proxies:add")).toBeDefined();
        } finally {
            rmSync(tempDir, {recursive: true, force: true});
        }
    }));

    it("returns raw repair state instead of failing for invalid YAML", () => withUnifiedSchemaFixture((schemaPath) => {
        const tempDir = mkdtempSync(path.join(tmpdir(), "edit-config-invalid-yaml-"));
        try {
            const configPath = path.join(tempDir, "invalid.yaml");
            writeFileSync(configPath, "sourceClusters:\n  source: [\n");
            const cliPath = path.resolve(__dirname, "../src/cliRouter.ts");

            const result = spawnSync(
                process.execPath,
                [
                    "--import",
                    TSX_IMPORT,
                    cliPath,
                    "editConfig",
                    "state",
                    "--pending-config",
                    configPath
                ],
                {
                    encoding: "utf8",
                    env: {...process.env, MIGRATION_UNIFIED_SCHEMA_PATH: schemaPath},
                },
            );

            expect(result.status).toBe(0);
            const state = JSON.parse(result.stdout);
            expect(state.provenance).toMatchObject({
                source: "pending-yaml",
                lossy: true,
                mode: "raw",
            });
            expect(state.nodes).toEqual([]);
            expect(state.validation.valid).toBe(false);
            expect(state.validation.diagnostics[0]).toMatchObject({
                severity: "error",
                path: [],
            });
        } finally {
            rmSync(tempDir, {recursive: true, force: true});
        }
    }));

    it("uses raw repair state when malformed containers cannot be edited safely", async () => {
        const state = await buildEditStateFromObjectForSubmit({
            sourceClusters: "not-a-record",
            targetClusters: {
                target: {endpoint: "https://target.example.com:9200"},
            },
            snapshotMigrationConfigs: [],
        });

        expect(state.provenance).toMatchObject({
            lossy: true,
            mode: "raw",
        });
        expect(state.nodes).toEqual([]);
        expect(state.validation.valid).toBe(false);
    });

    it.each([
        ["top-level null", null],
        ["top-level scalar", "not-a-workflow"],
        ["top-level array", []],
        [
            "malformed source collection",
            {
                sourceClusters: "not-a-record",
                targetClusters: {},
                snapshotMigrationConfigs: [],
            },
        ],
        [
            "malformed source entry",
            {
                sourceClusters: {source: "not-an-object"},
                targetClusters: {},
                snapshotMigrationConfigs: [],
            },
        ],
        [
            "malformed target entry",
            {
                sourceClusters: {},
                targetClusters: {target: []},
                snapshotMigrationConfigs: [],
            },
        ],
        [
            "malformed traffic object",
            {
                sourceClusters: {},
                targetClusters: {},
                traffic: "not-an-object",
                snapshotMigrationConfigs: [],
            },
        ],
        [
            "malformed traffic collection",
            {
                sourceClusters: {},
                targetClusters: {},
                traffic: {proxies: []},
                snapshotMigrationConfigs: [],
            },
        ],
        [
            "malformed traffic entry",
            {
                sourceClusters: {},
                targetClusters: {},
                traffic: {replayers: {replay: "not-an-object"}},
                snapshotMigrationConfigs: [],
            },
        ],
        [
            "unknown top-level content",
            {
                sourceClusters: {},
                targetClusters: {},
                snapshotMigrationConfigs: [],
                unexpectedWorkflowSection: {preserveMe: true},
            },
        ],
        [
            "unknown nested content",
            {
                sourceClusters: {
                    source: {
                        endpoint: "https://source.example.com:9200",
                        version: "OS 2.19",
                        unexpectedSourceOption: true,
                    },
                },
                targetClusters: {},
                snapshotMigrationConfigs: [],
            },
        ],
    ])("uses raw repair for %s", async (_description, config) => {
        const state = await buildEditStateFromObjectForSubmit(config);

        expect(state.provenance).toMatchObject({
            lossy: true,
            mode: "raw",
        });
        expect(state.nodes).toEqual([]);
        expect(state.validation.valid).toBe(false);
        expect(state.validation.diagnostics?.length).toBeGreaterThan(0);
    });
});
