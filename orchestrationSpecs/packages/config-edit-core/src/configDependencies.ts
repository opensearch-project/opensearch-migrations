export interface ConfigReferenceEdge {
    fromPath: string[];
    fromFieldPath: string[];
    toPath: string[];
    reason: string;
}


function pathKey(path: string[]): string {
    return path.join("\0");
}


function startsWithPath(path: string[], prefix: string[]): boolean {
    return path.length >= prefix.length
        && prefix.every((part, index) => path[index] === part);
}


function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === "object" && value !== null && !Array.isArray(value);
}


function addConfigReference(
    edges: ConfigReferenceEdge[],
    fromPath: string[],
    fromFieldPath: string[],
    toPath: string[],
    reason: string,
): void {
    edges.push({fromPath, fromFieldPath, toPath, reason});
}


export function buildConfigDependencyGraph(config: unknown): ConfigReferenceEdge[] {
    const root = isRecord(config) ? config : {};
    const edges: ConfigReferenceEdge[] = [];
    const traffic = isRecord(root.traffic) ? root.traffic : {};
    const proxies = isRecord(traffic.proxies) ? traffic.proxies : {};
    const s3Sources = isRecord(traffic.s3Sources) ? traffic.s3Sources : {};
    const replayers = isRecord(traffic.replayers) ? traffic.replayers : {};

    if (Array.isArray(root.snapshotMigrationConfigs)) {
        root.snapshotMigrationConfigs.forEach((migrationValue, index) => {
            if (!isRecord(migrationValue)) return;
            const migrationPath = ["snapshotMigrationConfigs", String(index)];
            const fromSource = typeof migrationValue.fromSource === "string"
                ? migrationValue.fromSource
                : "";
            if (fromSource) {
                addConfigReference(
                    edges,
                    migrationPath,
                    [...migrationPath, "fromSource"],
                    ["sourceClusters", fromSource],
                    `fromSource=${fromSource}`,
                );
            }
            const toTarget = typeof migrationValue.toTarget === "string"
                ? migrationValue.toTarget
                : "";
            if (toTarget) {
                addConfigReference(
                    edges,
                    migrationPath,
                    [...migrationPath, "toTarget"],
                    ["targetClusters", toTarget],
                    `toTarget=${toTarget}`,
                );
            }
            const fromSnapshot = typeof migrationValue.fromSnapshot === "string"
                ? migrationValue.fromSnapshot
                : "";
            if (fromSource && fromSnapshot) {
                addConfigReference(
                    edges,
                    migrationPath,
                    [...migrationPath, "fromSnapshot"],
                    [
                        "sourceClusters",
                        fromSource,
                        "snapshotInfo",
                        "snapshots",
                        fromSnapshot,
                    ],
                    `fromSnapshot=${fromSnapshot}`,
                );
            }
        });
    }

    Object.entries(proxies).forEach(([proxyName, proxyValue]) => {
        if (!isRecord(proxyValue)) return;
        const proxyPath = ["traffic", "proxies", proxyName];
        if (typeof proxyValue.source === "string" && proxyValue.source) {
            addConfigReference(
                edges,
                proxyPath,
                [...proxyPath, "source"],
                ["sourceClusters", proxyValue.source],
                `source=${proxyValue.source}`,
            );
        }
        const kafka = typeof proxyValue.kafka === "string" ? proxyValue.kafka : "";
        if (kafka) {
            addConfigReference(
                edges,
                proxyPath,
                [...proxyPath, "kafka"],
                ["traffic", "kafkaClusters", kafka],
                `kafka=${kafka}`,
            );
            const topic = typeof proxyValue.kafkaTopic === "string"
                ? proxyValue.kafkaTopic
                : "";
            if (topic) {
                addConfigReference(
                    edges,
                    proxyPath,
                    [...proxyPath, "kafkaTopic"],
                    ["traffic", "kafkaClusters", kafka, "topics", topic],
                    `kafkaTopic=${topic}`,
                );
            }
        }
    });

    Object.entries(s3Sources).forEach(([sourceName, sourceValue]) => {
        if (!isRecord(sourceValue)) return;
        const sourcePath = ["traffic", "s3Sources", sourceName];
        const kafka = typeof sourceValue.kafka === "string" ? sourceValue.kafka : "";
        if (kafka) {
            addConfigReference(
                edges,
                sourcePath,
                [...sourcePath, "kafka"],
                ["traffic", "kafkaClusters", kafka],
                `kafka=${kafka}`,
            );
            const topic = typeof sourceValue.kafkaTopic === "string"
                ? sourceValue.kafkaTopic
                : "";
            if (topic) {
                addConfigReference(
                    edges,
                    sourcePath,
                    [...sourcePath, "kafkaTopic"],
                    ["traffic", "kafkaClusters", kafka, "topics", topic],
                    `kafkaTopic=${topic}`,
                );
            }
        }
    });

    Object.entries(replayers).forEach(([replayerName, replayerValue]) => {
        if (!isRecord(replayerValue)) return;
        const replayerPath = ["traffic", "replayers", replayerName];
        const captured = typeof replayerValue.fromCapturedTraffic === "string"
            ? replayerValue.fromCapturedTraffic
            : "";
        if (captured) {
            const capturedPath = Object.hasOwn(proxies, captured)
                ? ["traffic", "proxies", captured]
                : Object.hasOwn(s3Sources, captured)
                    ? ["traffic", "s3Sources", captured]
                    : null;
            if (capturedPath) {
                addConfigReference(
                    edges,
                    replayerPath,
                    [...replayerPath, "fromCapturedTraffic"],
                    capturedPath,
                    `fromCapturedTraffic=${captured}`,
                );
            }
        }
        const target = typeof replayerValue.toTarget === "string"
            ? replayerValue.toTarget
            : "";
        if (target) {
            addConfigReference(
                edges,
                replayerPath,
                [...replayerPath, "toTarget"],
                ["targetClusters", target],
                `toTarget=${target}`,
            );
        }
        if (Array.isArray(replayerValue.dependsOnSnapshotMigrations)) {
            replayerValue.dependsOnSnapshotMigrations.forEach(
                (dependencyValue, index) => {
                    if (!isRecord(dependencyValue)) return;
                    const dependencyPath = [
                        ...replayerPath,
                        "dependsOnSnapshotMigrations",
                        String(index),
                    ];
                    const source = typeof dependencyValue.source === "string"
                        ? dependencyValue.source
                        : "";
                    if (source) {
                        addConfigReference(
                            edges,
                            dependencyPath,
                            [...dependencyPath, "source"],
                            ["sourceClusters", source],
                            `source=${source}`,
                        );
                    }
                    const snapshot = typeof dependencyValue.snapshot === "string"
                        ? dependencyValue.snapshot
                        : "";
                    if (source && snapshot) {
                        addConfigReference(
                            edges,
                            dependencyPath,
                            [...dependencyPath, "snapshot"],
                            [
                                "sourceClusters",
                                source,
                                "snapshotInfo",
                                "snapshots",
                                snapshot,
                            ],
                            `snapshot=${snapshot}`,
                        );
                    }
                },
            );
        }
    });

    const sources = isRecord(root.sourceClusters) ? root.sourceClusters : {};
    Object.entries(sources).forEach(([sourceName, sourceValue]) => {
        if (!isRecord(sourceValue)) return;
        const snapshotInfo = isRecord(sourceValue.snapshotInfo)
            ? sourceValue.snapshotInfo
            : {};
        const repositories = isRecord(snapshotInfo.repos)
            ? snapshotInfo.repos
            : {};
        const snapshots = isRecord(snapshotInfo.snapshots)
            ? snapshotInfo.snapshots
            : {};
        if (Object.keys(repositories).length === 0) return;
        Object.entries(snapshots).forEach(([snapshotName, snapshotValue]) => {
            if (
                !isRecord(snapshotValue)
                || typeof snapshotValue.repoName !== "string"
                || !snapshotValue.repoName
            ) {
                return;
            }
            const snapshotPath = [
                "sourceClusters",
                sourceName,
                "snapshotInfo",
                "snapshots",
                snapshotName,
            ];
            addConfigReference(
                edges,
                snapshotPath,
                [...snapshotPath, "repoName"],
                [
                    "sourceClusters",
                    sourceName,
                    "snapshotInfo",
                    "repos",
                    snapshotValue.repoName,
                ],
                `repoName=${snapshotValue.repoName}`,
            );
        });
    });
    return edges;
}


export function downstreamConfigReferences(
    config: unknown,
    targetPath: string[],
): ConfigReferenceEdge[] {
    const references = buildConfigDependencyGraph(config);
    const removedPaths = new Map([[pathKey(targetPath), targetPath]]);
    const selected: ConfigReferenceEdge[] = [];
    let changed = true;
    while (changed) {
        changed = false;
        for (const reference of references) {
            // Entries nested inside the selected target disappear with it and
            // are not separate downstream deletions.
            if (startsWithPath(reference.fromPath, targetPath)) {
                continue;
            }
            const affected = [...removedPaths.values()].some((removedPath) => (
                startsWithPath(reference.toPath, removedPath)
            ));
            const fromKey = pathKey(reference.fromPath);
            if (!affected || removedPaths.has(fromKey)) {
                continue;
            }
            selected.push(reference);
            removedPaths.set(fromKey, reference.fromPath);
            changed = true;
        }
    }
    return selected;
}
