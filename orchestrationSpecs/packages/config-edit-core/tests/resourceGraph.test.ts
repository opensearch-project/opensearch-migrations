import {describe, expect, it} from "@jest/globals";

import {
    annotateDraftChanges,
    buildEditStateFromObject,
    configRemovalImpact,
    configReferences,
    groupSnapshotMigrationNavigation,
    projectConfigResourceGraph,
    type ResourceGraphNode,
    type ResourceGraphSnapshot,
} from "../src";


function node(
    id: string,
    kind: string,
    label: string,
    overrides: Partial<ResourceGraphNode> = {},
): ResourceGraphNode {
    return {
        id,
        revision: `${id}:1`,
        parentId: null,
        childIds: [],
        kind,
        label,
        description: null,
        status: "ok",
        phase: null,
        valueSummary: null,
        diagnostics: [],
        capabilities: [],
        details: [],
        relationships: [],
        comparisons: [],
        resourcePlural: null,
        resourceName: null,
        resourceType: null,
        configPresence: {},
        configState: null,
        ...overrides,
    };
}


function emptySnapshot(): ResourceGraphSnapshot {
    return {
        formatVersion: 1,
        revision: "runtime-1",
        rootIds: [],
        nodes: {},
    };
}


function runtimeSource(name = "legacy"): ResourceGraphSnapshot {
    const sectionId = "section:Sources";
    const groupId = "group:Sources:Sources";
    const resourceId = `resource:sourceconfigs:${name}`;
    const stepId = `workflow-step:${resourceId}:apply`;
    return {
        formatVersion: 1,
        revision: "runtime-1",
        rootIds: [sectionId],
        nodes: {
            [sectionId]: node(sectionId, "section", "Sources", {
                childIds: [groupId],
            }),
            [groupId]: node(groupId, "group", "Sources", {
                parentId: sectionId,
                childIds: [resourceId],
            }),
            [resourceId]: node(resourceId, "resource", name, {
                parentId: groupId,
                childIds: [stepId],
                capabilities: [{
                    kind: "edit",
                    editTargetId: `edit:sourceClusters.${name}`,
                    label: `Edit ${name}`,
                }],
                resourcePlural: "sourceconfigs",
                resourceName: name,
                resourceType: "Source cluster",
                configPresence: {deployed: true, pending: true},
            }),
            [stepId]: node(stepId, "workflow-step", "Apply", {
                parentId: resourceId,
            }),
        },
    };
}


function draft(
    baseConfig: unknown,
    config: unknown,
    dirty = true,
) {
    const base = buildEditStateFromObject(baseConfig);
    const current = buildEditStateFromObject(config);
    return {
        draftRevision: "draft-2",
        dirty,
        editState: dirty ? annotateDraftChanges(current, base) : current,
        config,
    };
}


describe("configuration resource graph projection", () => {
    it("adds configured resources, marks removed resources, and strips workflow steps", () => {
        const base = {
            sourceClusters: {
                legacy: {
                    endpoint: "https://legacy.example.com:9200",
                    version: "ES 7.10",
                },
            },
            targetClusters: {},
            snapshotMigrationConfigs: [],
        };
        const configured = {
            sourceClusters: {
                modern: {
                    endpoint: "https://modern.example.com:9200",
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

        const projected = projectConfigResourceGraph(
            runtimeSource(),
            draft(base, configured),
        );

        expect(projected.rootIds).toEqual([
            "section:Sources",
            "section:Targets",
            "section:Snapshot Migration",
            "section:Live Traffic Migration",
        ]);
        expect(projected.nodes[
            "workflow-step:resource:sourceconfigs:legacy:apply"
        ]).toBeUndefined();
        expect(projected.nodes["resource:sourceconfigs:legacy"]).toMatchObject({
            childIds: [],
            status: "removed",
            valueSummary: "Marked for removal",
        });
        expect(projected.nodes["resource:sourceconfigs:modern"]).toMatchObject({
            parentId: "group:Sources:Sources",
            resourceType: "Source cluster",
            configPresence: {deployed: false, pending: true},
        });
        expect(projected.nodes["resource:targetconfigs:target"]).toMatchObject({
            parentId: "group:Targets:Targets",
            resourceType: "Target cluster",
        });
    });

    it("does not mark another source's snapshot removed when a source is deleted", () => {
        const snapshot = runtimeSource("retired");
        const group = snapshot.nodes["group:Sources:Sources"];
        const sourceId = "resource:sourceconfigs:source";
        const snapshotId = "resource:datasnapshots:source-snap";
        group.childIds = [...(group.childIds ?? []), sourceId];
        snapshot.nodes[sourceId] = node(sourceId, "resource", "source", {
            parentId: group.id,
            childIds: [snapshotId],
            capabilities: [{
                kind: "edit",
                editTargetId: "edit:sourceClusters.source",
                label: "Edit source",
            }],
            resourcePlural: "sourceconfigs",
            resourceName: "source",
            resourceType: "Source cluster",
            configPresence: {deployed: true, pending: true},
        });
        snapshot.nodes[snapshotId] = node(
            snapshotId,
            "resource",
            "source-snap",
            {
                parentId: sourceId,
                capabilities: [{
                    kind: "edit",
                    editTargetId: (
                        "edit:sourceClusters.source.snapshotInfo.snapshots."
                        + "snap.config.createSnapshotConfig"
                    ),
                    label: "Edit source-snap",
                }],
                resourcePlural: "datasnapshots",
                resourceName: "source-snap",
                resourceType: "Data snapshot",
                configPresence: {deployed: true, pending: true},
            },
        );
        const retainedSource = {
            endpoint: "https://source.example.com:9200",
            version: "ES 7.10",
            snapshotInfo: {
                snapshots: {
                    snap: {
                        repoName: "",
                        config: {createSnapshotConfig: {}},
                    },
                },
            },
        };
        const base = {
            sourceClusters: {
                retired: {
                    endpoint: "https://retired.example.com:9200",
                    version: "ES 7.10",
                },
                source: retainedSource,
            },
            targetClusters: {},
            snapshotMigrationConfigs: [],
        };
        const configured = {
            ...base,
            sourceClusters: {source: retainedSource},
        };

        const projected = projectConfigResourceGraph(
            snapshot,
            draft(base, configured),
        );

        expect(projected.nodes["resource:sourceconfigs:retired"]).toMatchObject({
            status: "removed",
            valueSummary: "Marked for removal",
        });
        expect(projected.nodes[snapshotId]).toMatchObject({
            status: "ok",
            valueSummary: null,
        });
    });

    it("marks a generated snapshot removed when its create variant is replaced", () => {
        const snapshot = runtimeSource("source");
        const sourceId = "resource:sourceconfigs:source";
        const snapshotId = "resource:datasnapshots:source-snap";
        snapshot.nodes[sourceId].childIds = [snapshotId];
        snapshot.nodes[snapshotId] = node(
            snapshotId,
            "resource",
            "source-snap",
            {
                parentId: sourceId,
                capabilities: [{
                    kind: "edit",
                    editTargetId: (
                        "edit:sourceClusters.source.snapshotInfo.snapshots."
                        + "snap.config.createSnapshotConfig"
                    ),
                    label: "Edit source-snap",
                }],
                resourcePlural: "datasnapshots",
                resourceName: "source-snap",
                resourceType: "Data snapshot",
                configPresence: {deployed: true, pending: true},
            },
        );
        const source = {
            endpoint: "https://source.example.com:9200",
            version: "ES 7.10",
        };
        const base = {
            sourceClusters: {
                source: {
                    ...source,
                    snapshotInfo: {
                        snapshots: {
                            snap: {
                                repoName: "",
                                config: {createSnapshotConfig: {}},
                            },
                        },
                    },
                },
            },
            targetClusters: {},
            snapshotMigrationConfigs: [],
        };
        const configured = {
            sourceClusters: {
                source: {
                    ...source,
                    snapshotInfo: {
                        snapshots: {
                            snap: {
                                repoName: "",
                                config: {
                                    externallyManagedSnapshotName: "existing",
                                },
                            },
                        },
                    },
                },
            },
            targetClusters: {},
            snapshotMigrationConfigs: [],
        };

        const projected = projectConfigResourceGraph(
            snapshot,
            draft(base, configured),
        );

        expect(projected.nodes[snapshotId]).toMatchObject({
            status: "removed",
            valueSummary: "Marked for removal",
        });
    });

    it("places definitions under their owning resource", () => {
        const config = {
            sourceClusters: {
                source: {
                    endpoint: "https://source.example.com:9200",
                    version: "OS 2.19",
                    snapshotInfo: {
                        repos: {
                            repository: {
                                repoUri: "s3://snapshots",
                                awsRegion: "us-east-1",
                            },
                        },
                        snapshots: {
                            nightly: {
                                repoName: "repository",
                            },
                        },
                    },
                },
            },
            targetClusters: {},
            snapshotMigrationConfigs: [],
        };

        const projected = projectConfigResourceGraph(
            emptySnapshot(),
            draft(config, config, false),
        );
        const source = projected.nodes["resource:sourceconfigs:source"];
        const repositoryGroup = projected.nodes[
            "definition-group:edit:sourceClusters.source.snapshotInfo.repos"
        ];
        const snapshotGroup = projected.nodes[
            "definition-group:edit:sourceClusters.source.snapshotInfo.snapshots"
        ];

        expect(source.childIds).toEqual([
            repositoryGroup.id,
            snapshotGroup.id,
        ]);
        expect(projected.nodes[repositoryGroup.childIds?.[0] ?? ""]).toMatchObject({
            kind: "config-definition",
            label: "repository",
            resourceType: "Snapshot repository",
        });
        expect(projected.nodes[snapshotGroup.childIds?.[0] ?? ""]).toMatchObject({
            kind: "config-definition",
            label: "nightly",
            resourceType: "Source snapshot",
        });
    });

    it("preserves the runtime-backed Kafka topic in its edit location", () => {
        const liveSection = "section:Live Traffic Migration";
        const bufferGroup = "group:Live Traffic Migration:Buffer";
        const kafkaGroup = `${bufferGroup}:Kafka Clusters`;
        const clusterId = "resource:kafkaclusters:main-k";
        const topicsGroup =
            "definition-group:edit:traffic.kafkaClusters.main-k.topics";
        const captureGroup = "group:Live Traffic Migration:Capture";
        const capturedId = "resource:capturedtraffics:c-topic";
        const proxyId = "resource:captureproxies:c";
        const snapshot = emptySnapshot();
        snapshot.rootIds = [liveSection];
        snapshot.nodes = {
            [liveSection]: node(
                liveSection,
                "section",
                "Live Traffic Migration",
                {childIds: [bufferGroup, captureGroup]},
            ),
            [bufferGroup]: node(bufferGroup, "group", "Buffer", {
                parentId: liveSection,
                childIds: [kafkaGroup],
            }),
            [kafkaGroup]: node(
                kafkaGroup,
                "group",
                "Kafka Clusters",
                {
                    parentId: bufferGroup,
                    childIds: [clusterId],
                },
            ),
            [clusterId]: node(clusterId, "resource", "main-k", {
                parentId: kafkaGroup,
                childIds: [topicsGroup],
                capabilities: [{
                    kind: "edit",
                    editTargetId: "edit:traffic.kafkaClusters.main-k",
                }],
                resourcePlural: "kafkaclusters",
                resourceName: "main-k",
                resourceType: "Kafka cluster",
            }),
            [topicsGroup]: node(topicsGroup, "group", "Topics", {
                parentId: clusterId,
                childIds: [capturedId],
            }),
            [captureGroup]: node(captureGroup, "group", "Capture", {
                parentId: liveSection,
                childIds: [proxyId],
            }),
            [capturedId]: node(capturedId, "resource", "c", {
                parentId: topicsGroup,
                capabilities: [{
                    kind: "edit",
                    editTargetId:
                        "edit:traffic.kafkaClusters.main-k.topics.c",
                }],
                resourcePlural: "capturedtraffics",
                resourceName: "c-topic",
                resourceType: "Kafka topic",
                configPresence: {deployed: true, pending: true},
            }),
            [proxyId]: node(proxyId, "resource", "c", {
                parentId: captureGroup,
                capabilities: [{
                    kind: "edit",
                    editTargetId: "edit:traffic.proxies.c",
                }],
                resourcePlural: "captureproxies",
                resourceName: "c",
                resourceType: "Capture proxy",
                configPresence: {deployed: true, pending: true},
            }),
        };
        const config = {
            sourceClusters: {
                source: {
                    endpoint: "https://source.example.com:9200",
                    version: "ES 7.10",
                },
            },
            targetClusters: {},
            snapshotMigrationConfigs: [],
            traffic: {
                kafkaClusters: {
                    "main-k": {
                        autoCreate: {},
                        topics: {
                            c: {},
                        },
                    },
                },
                proxies: {
                    c: {
                        source: "source",
                        kafka: "main-k",
                        kafkaTopic: "c",
                        proxyConfig: {},
                    },
                },
                replayers: {},
            },
        };

        const projected = projectConfigResourceGraph(
            snapshot,
            draft(config, config, false),
        );

        expect(projected.nodes[capturedId]).toMatchObject({
            label: "c",
            parentId: topicsGroup,
            resourceName: "c-topic",
            resourceType: "Kafka topic",
            capabilities: [expect.objectContaining({
                editTargetId:
                    "edit:traffic.kafkaClusters.main-k.topics.c",
            })],
        });
        expect(projected.nodes[proxyId]).toMatchObject({
            capabilities: [expect.objectContaining({
                editTargetId: "edit:traffic.proxies.c",
            })],
        });
        expect(projected.nodes[
            "definition:edit:traffic.kafkaClusters.main-k.topics.c"
        ]).toBeUndefined();
    });

    it("rebinds snapshot migration resources by semantic identity after compaction", () => {
        const sectionId = "section:Snapshot Migration";
        const groupId = "group:Snapshot Migration:Backfill";
        const zeroId = "resource:snapshotmigrations:source-target-snap-slice-0";
        const oneId = "resource:snapshotmigrations:source-target-snap-slice-1";
        const snapshot = emptySnapshot();
        snapshot.rootIds = [sectionId];
        snapshot.nodes = {
            [sectionId]: node(sectionId, "section", "Snapshot Migration", {
                childIds: [groupId],
            }),
            [groupId]: node(groupId, "group", "Backfill", {
                parentId: sectionId,
                childIds: [zeroId, oneId],
            }),
            [zeroId]: node(zeroId, "resource", "slice-0", {
                parentId: groupId,
                capabilities: [{
                    kind: "edit",
                    editTargetId: "edit:snapshotMigrationConfigs.0",
                }],
                resourcePlural: "snapshotmigrations",
                resourceName: "source-target-snap-slice-0",
                resourceType: "Snapshot migration",
                configPresence: {deployed: true, pending: true},
                navigationKey: ["source", "target", "snap", "slice-0"],
            }),
            [oneId]: node(oneId, "resource", "slice-1", {
                parentId: groupId,
                capabilities: [{
                    kind: "edit",
                    editTargetId: "edit:snapshotMigrationConfigs.1",
                }],
                resourcePlural: "snapshotmigrations",
                resourceName: "source-target-snap-slice-1",
                resourceType: "Snapshot migration",
                configPresence: {deployed: true, pending: true},
                navigationKey: ["source", "target", "snap", "slice-1"],
            }),
        };
        const base = {
            sourceClusters: {},
            targetClusters: {},
            snapshotMigrationConfigs: [
                {
                    fromSource: "source",
                    toTarget: "target",
                    fromSnapshot: "snap",
                    slice: "slice-0",
                    metadataMigrationConfig: {},
                },
                {
                    fromSource: "source",
                    toTarget: "target",
                    fromSnapshot: "snap",
                    slice: "slice-1",
                    metadataMigrationConfig: {},
                },
            ],
        };
        const configured = {
            ...base,
            snapshotMigrationConfigs: [base.snapshotMigrationConfigs[1]],
        };

        const projected = projectConfigResourceGraph(
            snapshot,
            draft(base, configured),
        );

        expect(projected.nodes[zeroId].status).toBe("removed");
        expect(projected.nodes[zeroId].capabilities).toEqual([]);
        expect(projected.nodes[oneId].status).toBe("ok");
        expect(projected.nodes[oneId].capabilities).toContainEqual(
            expect.objectContaining({
                editTargetId: "edit:snapshotMigrationConfigs.0",
            }),
        );
        expect(projected.nodes["config:snapshotMigrationConfigs:0"]).toBeUndefined();
    });

    it("groups only useful snapshot migration prefixes with natural ordering", () => {
        const groupId = "group:Snapshot Migration:Backfill";
        const snapshot = emptySnapshot();
        snapshot.rootIds = [SNAPSHOT_SECTION_ID];
        snapshot.nodes = {
            [SNAPSHOT_SECTION_ID]: node(
                SNAPSHOT_SECTION_ID,
                "section",
                "Snapshot Migration",
                {childIds: [groupId]},
            ),
            [groupId]: node(groupId, "group", "Backfill", {
                parentId: SNAPSHOT_SECTION_ID,
                childIds: ["a", "b", "c", "d"],
            }),
            a: node("a", "resource", "a", {
                parentId: groupId,
                status: "warning",
                resourcePlural: "snapshotmigrations",
                navigationKey: ["source", "target-a", "snap-a", "slice-10"],
            }),
            b: node("b", "resource", "b", {
                parentId: groupId,
                resourcePlural: "snapshotmigrations",
                navigationKey: ["source", "target-a", "snap-a", "slice-2"],
            }),
            c: node("c", "resource", "c", {
                parentId: groupId,
                status: "error",
                resourcePlural: "snapshotmigrations",
                navigationKey: ["source", "target-a", "snap-b", "slice-3"],
            }),
            d: node("d", "resource", "d", {
                parentId: groupId,
                resourcePlural: "snapshotmigrations",
                navigationKey: ["source", "target-b", "snap-c", "slice-4"],
            }),
        };

        const grouped = groupSnapshotMigrationNavigation(snapshot);
        const source = grouped.nodes["snapshot-navigation:1:source"];
        const target = grouped.nodes[
            "snapshot-navigation:2:source:target-a"
        ];

        expect(source.status).toBe("error");
        expect(target.childIds).toEqual(["b", "a", "c"]);
        expect(grouped.nodes.b.label).toBe("snap-a-slice-2");
        expect(grouped.nodes.d.label).toBe("target-b-snap-c-slice-4");
    });

    it("derives dependency links and transitive removal impact from schema references", () => {
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
            traffic: {
                kafkaClusters: {},
                proxies: {
                    capture: {
                        source: "source",
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
        const editState = buildEditStateFromObject(config);
        const references = configReferences(config);

        expect(references).toEqual(expect.arrayContaining([
            expect.objectContaining({
                fromTargetId: "edit:traffic.proxies.capture",
                toTargetId: "edit:sourceClusters.source",
            }),
            expect.objectContaining({
                fromTargetId: "edit:traffic.replayers.replay",
                toTargetId: "edit:traffic.proxies.capture",
            }),
        ]));
        const removalImpact = configRemovalImpact(
            config,
            ["sourceClusters", "source"],
        );
        expect(removalImpact).toEqual(expect.arrayContaining([
                expect.objectContaining({
                    path: ["traffic", "proxies", "capture"],
                    direct: true,
                }),
                expect.objectContaining({
                    path: ["traffic", "replayers", "replay"],
                    direct: false,
                }),
            ]));

        const projected = projectConfigResourceGraph(
            emptySnapshot(),
            draft(config, config, false),
        );
        expect(projected.nodes["resource:captureproxies:capture"].relationships)
            .toEqual(expect.arrayContaining([
                expect.objectContaining({
                    direction: "requires",
                    targetId: "resource:sourceconfigs:source",
                }),
                expect.objectContaining({
                    direction: "required-by",
                    targetId: "resource:trafficreplays:replay",
                }),
            ]));
    });

    it("replaces stale server dependency links with the current draft references", () => {
        const snapshot = runtimeSource("old");
        const sourceGroup = snapshot.nodes["group:Sources:Sources"];
        sourceGroup.childIds = [
            ...(sourceGroup.childIds ?? []),
            "resource:sourceconfigs:new",
        ];
        snapshot.nodes["resource:sourceconfigs:new"] = node(
            "resource:sourceconfigs:new",
            "resource",
            "new",
            {
                parentId: sourceGroup.id,
                capabilities: [{
                    kind: "edit",
                    editTargetId: "edit:sourceClusters.new",
                }],
                resourcePlural: "sourceconfigs",
                resourceName: "new",
                resourceType: "Source cluster",
                configPresence: {deployed: true, pending: true},
            },
        );
        snapshot.nodes["resource:captureproxies:capture"] = node(
            "resource:captureproxies:capture",
            "resource",
            "capture",
            {
                relationships: [{
                    kind: "runtime-dependency",
                    direction: "requires",
                    targetId: "resource:sourceconfigs:old",
                    targetName: "old",
                    targetStatus: "ok",
                }],
                resourcePlural: "captureproxies",
                resourceName: "capture",
                resourceType: "Capture proxy",
                capabilities: [{
                    kind: "edit",
                    editTargetId: "edit:traffic.proxies.capture",
                }],
            },
        );
        const config = {
            sourceClusters: {
                old: {
                    endpoint: "https://old.example.com:9200",
                    version: "ES 7.10",
                },
                new: {
                    endpoint: "https://new.example.com:9200",
                    version: "ES 7.10",
                },
            },
            targetClusters: {},
            snapshotMigrationConfigs: [],
            traffic: {
                proxies: {
                    capture: {
                        source: "new",
                    },
                },
            },
        };

        const projected = projectConfigResourceGraph(
            snapshot,
            draft(config, config, false),
        );
        const relationships = projected.nodes[
            "resource:captureproxies:capture"
        ].relationships;

        expect(relationships).toContainEqual(expect.objectContaining({
            direction: "requires",
            targetId: "resource:sourceconfigs:new",
        }));
        expect(relationships).not.toContainEqual(expect.objectContaining({
            direction: "requires",
            targetId: "resource:sourceconfigs:old",
        }));
    });

    it("rebinds stale runtime edit targets to the current configuration paths", () => {
        const snapshot = emptySnapshot();
        const replayGroupId = "group:Live Traffic Migration:Replay";
        const replayId = "resource:trafficreplays:replay";
        snapshot.rootIds = [replayGroupId];
        snapshot.nodes[replayGroupId] = node(
            replayGroupId,
            "group",
            "Replay",
            {childIds: [replayId]},
        );
        snapshot.nodes[replayId] = node(
            replayId,
            "resource",
            "replay",
            {
                parentId: replayGroupId,
                resourcePlural: "trafficreplays",
                resourceName: "replay",
                resourceType: "Traffic replayer",
                capabilities: [{
                    kind: "edit",
                    editTargetId: "edit:trafficreplays:replay",
                }],
            },
        );
        const config = {
            sourceClusters: {},
            targetClusters: {
                target: {endpoint: "https://target.example.com:9200"},
            },
            snapshotMigrationConfigs: [],
            traffic: {
                replayers: {
                    replay: {
                        fromCapturedTraffic: "capture",
                        toTarget: "target",
                    },
                },
            },
        };

        const projected = projectConfigResourceGraph(
            snapshot,
            draft(config, config, false),
        );

        expect(projected.nodes[replayId].capabilities).toContainEqual({
            kind: "edit",
            editTargetId: "edit:traffic.replayers.replay",
            label: "Edit replay",
        });
    });

    it("does not infer configured resources from a raw-repair draft", () => {
        const snapshot = runtimeSource();
        const editState = buildEditStateFromObject({});
        editState.provenance.mode = "raw";

        const projected = projectConfigResourceGraph(snapshot, {
            draftRevision: "raw-1",
            dirty: true,
            editState,
            repairYaml: "sourceClusters: [",
        });

        expect(projected.rootIds).toEqual(snapshot.rootIds);
        expect(projected.nodes["resource:sourceconfigs:legacy"].status).toBe("ok");
        expect(projected.nodes[
            "workflow-step:resource:sourceconfigs:legacy:apply"
        ]).toBeUndefined();
    });
});


const SNAPSHOT_SECTION_ID = "section:Snapshot Migration";
