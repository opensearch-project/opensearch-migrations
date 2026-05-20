import { describe, it, expect } from '@jest/globals';
import { MigrationInitializer } from "../src";
import { OVERALL_MIGRATION_CONFIG } from "@opensearch-migrations/schemas";
import { z } from "zod";

describe('migration initializer CRD resource generation', () => {
    it('generates CR resources and UID enrichment script entries with expected names', async () => {
        const config: z.infer<typeof OVERALL_MIGRATION_CONFIG> = {
            sourceClusters: {
                source: {
                    endpoint: "https://source.example.com",
                    allowInsecure: true,
                    version: "ES 7.10.2",
                    authConfig: {
                        basic: {
                            secretName: "source-creds"
                        }
                    },
                    snapshotInfo: {
                        repos: {
                            default: {
                                awsRegion: "us-east-2",
                                s3RepoPathUri: "s3://bucket/path"
                            }
                        },
                        snapshots: {
                            snap1: {
                                repoName: "default",
                                config: {
                                    createSnapshotConfig: { snapshotPrefix: "snap1" }
                                }
                            }
                        }
                    }
                }
            },
            targetClusters: {
                target: {
                    endpoint: "https://target.example.com",
                    allowInsecure: true,
                    authConfig: {
                        basic: {
                            secretName: "target-creds"
                        }
                    }
                }
            },
            traffic: {
                proxies: {
                    "source-proxy": {
                        source: "source",
                        proxyConfig: {
                            listenPort: 9200,
                            resources: {
                                requests: {
                                    cpu: "100m",
                                    memory: "256Mi"
                                },
                                limits: {
                                    cpu: "100m",
                                    memory: "256Mi"
                                }
                            }
                        }
                    }
                },
                replayers: {
                    "target-replay": {
                        fromProxy: "source-proxy",
                        toTarget: "target"
                    }
                }
            },
            snapshotMigrationConfigs: [
                {
                    fromSource: "source",
                    toTarget: "target",
                    perSnapshotConfig: {
                        "snap1": [{
                            metadataMigrationConfig: {
                                skipEvaluateApproval: true,
                                skipMigrateApproval: true
                            }
                        }]
                    }
                }
            ]
        };

        const initializer = new MigrationInitializer();
        const bundle = await initializer.generateMigrationBundle(config);
        const resources = bundle.crdResources.items;
        const enrichScript = (initializer as any).generateWorkflowUidEnrichmentScript(bundle.workflows);

        const byKind = (kind: string) =>
            resources.filter((item: any) => item.kind === kind).map((item: any) => item.metadata.name);
        const getResource = (kind: string, name: string) =>
            resources.find((item: any) => item.kind === kind && item.metadata.name === name);

        expect(byKind('KafkaCluster')).toContain('default');
        expect(byKind('CapturedTraffic')).toContain('source-proxy-topic');
        expect(byKind('CaptureProxy')).toContain('source-proxy');
        expect(byKind('DataSnapshot')).toContain('source-snap1');
        expect(byKind('SnapshotMigration')).toContain('source-target-snap1-migration-0');
        expect(byKind('TrafficReplay')).toContain('source-proxy-target-target-replay');
        expect(byKind('ApprovalGate')).toEqual(expect.arrayContaining([
            'evaluatemetadata.source-target-snap1-migration-0',
            'migratemetadata.source-target-snap1-migration-0',
            // Root KafkaCluster CR reconcile gate (only Kafka gate — sub-ops
            // are Strimzi-managed and don't go through the migrations VAP)
            'kafkacluster.default.vapretry',
            // Topic and proxy VAP retry gates
            'capturedtraffic.source-proxy-topic.vapretry',
            'captureproxy.source-proxy.vapretry',
            // Replay VAP retry gate
            'trafficreplay.source-proxy-target-target-replay.vapretry',
        ]));

        expect(getResource('KafkaCluster', 'default')?.spec.dependsOn).toBeUndefined();
        expect(getResource('CapturedTraffic', 'source-proxy-topic')?.spec.dependsOn).toEqual(['default']);
        expect(getResource('CaptureProxy', 'source-proxy')?.spec.dependsOn).toEqual(['source-proxy-topic']);
        expect(getResource('DataSnapshot', 'source-snap1')?.spec.dependsOn).toEqual(['source-proxy']);
        expect(getResource('SnapshotMigration', 'source-target-snap1-migration-0')?.spec.dependsOn).toBeUndefined();
        expect(getResource('TrafficReplay', 'source-proxy-target-target-replay')?.spec.dependsOn).toEqual(['source-proxy']);
        expect(getResource('ApprovalGate', 'evaluatemetadata.source-target-snap1-migration-0')?.spec.dependsOn).toBeUndefined();
        expect(getResource('ApprovalGate', 'migratemetadata.source-target-snap1-migration-0')?.spec.dependsOn).toBeUndefined();

        expect(enrichScript).toContain(
            "data_snapshot_source_snap1=\"$(kubectl get datasnapshots.migrations.opensearch.org/source-snap1 -o jsonpath='{.metadata.uid}')\""
        );
        expect(enrichScript).toContain('dataSnapshots: {');
        expect(enrichScript).toContain('"source-snap1": $data_snapshot_source_snap1');
        expect(enrichScript).toContain(
            '.snapshots |= ((. // []) | map(. as $snapshot | .createSnapshotConfig |= ((. // []) | map(. + {resourceUid: $uids.dataSnapshots[($snapshot.sourceConfig.label + "-" + .label)]}))))'
        );
        expect(enrichScript).toContain(
            "snapshot_migration_source_target_snap1_migration_0=\"$(kubectl get snapshotmigrations.migrations.opensearch.org/source-target-snap1-migration-0 -o jsonpath='{.metadata.uid}')\""
        );
        expect(enrichScript).toContain('snapshotMigrations: {');
        expect(enrichScript).toContain('"source-target-snap1-migration-0": $snapshot_migration_source_target_snap1_migration_0');
        expect(enrichScript).toContain(
            '.snapshotMigrations |= ((. // []) | map(. + {resourceUid: $uids.snapshotMigrations[(.sourceLabel + "-" + .targetConfig.label + "-" + .label + "-" + .migrationLabel)]}))'
        );
    });

    it('labels approval gates with workflow name and generates cleanup script', async () => {
        const config: z.infer<typeof OVERALL_MIGRATION_CONFIG> = {
            sourceClusters: {
                source: {
                    endpoint: "https://source.example.com",
                    version: "ES 7.10.2",
                    snapshotInfo: {
                        repos: { default: { awsRegion: "us-east-2", s3RepoPathUri: "s3://bucket/path" } },
                        snapshots: { snap1: { repoName: "default", config: { createSnapshotConfig: {} } } }
                    }
                }
            },
            targetClusters: { target: { endpoint: "https://target.example.com" } },
            snapshotMigrationConfigs: [{
                fromSource: "source",
                toTarget: "target",
                perSnapshotConfig: {
                    "snap1": [{
                        metadataMigrationConfig: {
                            skipEvaluateApproval: true,
                            skipMigrateApproval: true
                        }
                    }]
                }
            }]
        };

        const initializer = new MigrationInitializer();
        const bundle = await initializer.generateMigrationBundle(config, 'my-workflow');
        const gates = bundle.crdResources.items.filter((item: any) => item.kind === 'ApprovalGate');

        // All gates carry the workflow label plus per-gate context
        for (const gate of gates) {
            expect(gate.metadata.labels[MigrationInitializer.APPROVAL_GATE_LABEL_KEY]).toBe('my-workflow');
            expect(gate.metadata.labels[MigrationInitializer.GATE_LABEL_RESOURCE_KIND]).toBeDefined();
            expect(gate.metadata.labels[MigrationInitializer.GATE_LABEL_RESOURCE_NAME]).toBeDefined();
        }

        // SnapshotMigration gates carry source/target/snapshot/migration labels
        const migGate = gates.find((g: any) => g.metadata.name === 'snapshotmigration.source-target-snap1-migration-0.vapretry');
        expect(migGate.metadata.labels).toEqual({
            [MigrationInitializer.APPROVAL_GATE_LABEL_KEY]: 'my-workflow',
            [MigrationInitializer.GATE_LABEL_RESOURCE_KIND]: 'SnapshotMigration',
            [MigrationInitializer.GATE_LABEL_RESOURCE_NAME]: 'source-target-snap1-migration-0',
            [MigrationInitializer.GATE_LABEL_SOURCE]: 'source',
            [MigrationInitializer.GATE_LABEL_TARGET]: 'target',
            [MigrationInitializer.GATE_LABEL_SNAPSHOT]: 'snap1',
            [MigrationInitializer.GATE_LABEL_MIGRATION]: 'migration-0',
        });

        // Cleanup script does label-based delete then per-name fallback
        const cleanup = initializer.generateApprovalGateCleanupScript(bundle.crdResources);
        expect(cleanup).toContain(
            `kubectl delete approvalgates.${MigrationInitializer.CRD_GROUP} -l '${MigrationInitializer.APPROVAL_GATE_LABEL_KEY}=my-workflow' --ignore-not-found`
        );
        for (const gate of gates) {
            expect(cleanup).toContain(
                `kubectl delete approvalgates.${MigrationInitializer.CRD_GROUP}/${gate.metadata.name} --ignore-not-found`
            );
        }
    });

    it('omits labels and label-based cleanup when no workflow name provided', async () => {
        const config: z.infer<typeof OVERALL_MIGRATION_CONFIG> = {
            sourceClusters: {
                source: {
                    endpoint: "https://source.example.com",
                    version: "ES 7.10.2",
                    snapshotInfo: {
                        repos: { default: { awsRegion: "us-east-2", s3RepoPathUri: "s3://bucket/path" } },
                        snapshots: { snap1: { repoName: "default", config: { createSnapshotConfig: {} } } }
                    }
                }
            },
            targetClusters: { target: { endpoint: "https://target.example.com" } },
            snapshotMigrationConfigs: [{
                fromSource: "source",
                toTarget: "target",
                perSnapshotConfig: {
                    "snap1": [{
                        metadataMigrationConfig: {
                            skipEvaluateApproval: true,
                            skipMigrateApproval: true
                        }
                    }]
                }
            }]
        };

        const initializer = new MigrationInitializer();
        const bundle = await initializer.generateMigrationBundle(config);
        const gates = bundle.crdResources.items.filter((item: any) => item.kind === 'ApprovalGate');

        // Without a workflow name, gates have resource-context labels but
        // no workflow label.
        for (const gate of gates) {
            expect(gate.metadata.labels[MigrationInitializer.APPROVAL_GATE_LABEL_KEY]).toBeUndefined();
            expect(gate.metadata.labels[MigrationInitializer.GATE_LABEL_RESOURCE_KIND]).toBeDefined();
        }

        // Cleanup script still has per-name fallback but no label-based delete
        const cleanup = initializer.generateApprovalGateCleanupScript(bundle.crdResources);
        expect(cleanup).not.toContain('-l ');
        for (const gate of gates) {
            expect(cleanup).toContain(
                `kubectl delete approvalgates.${MigrationInitializer.CRD_GROUP}/${gate.metadata.name} --ignore-not-found`
            );
        }
    });

    it('generates snapshot migration UID enrichment script even without kafka, proxies, or replays', async () => {
        const config: z.infer<typeof OVERALL_MIGRATION_CONFIG> = {
            sourceClusters: {
                source: {
                    endpoint: "https://source.example.com",
                    allowInsecure: true,
                    version: "ES 7.10.2",
                    authConfig: {
                        basic: {
                            secretName: "source-creds"
                        }
                    },
                    snapshotInfo: {
                        repos: {
                            default: {
                                awsRegion: "us-east-2",
                                s3RepoPathUri: "s3://bucket/path"
                            }
                        },
                        snapshots: {
                            snap1: {
                                repoName: "default",
                                config: {
                                    createSnapshotConfig: { snapshotPrefix: "snap1" }
                                }
                            }
                        }
                    }
                }
            },
            targetClusters: {
                target: {
                    endpoint: "https://target.example.com",
                    allowInsecure: true,
                    authConfig: {
                        basic: {
                            secretName: "target-creds"
                        }
                    }
                }
            },
            snapshotMigrationConfigs: [
                {
                    fromSource: "source",
                    toTarget: "target",
                    perSnapshotConfig: {
                        "snap1": [{
                            metadataMigrationConfig: {
                                skipEvaluateApproval: true,
                                skipMigrateApproval: true
                            }
                        }]
                    }
                }
            ]
        };

        const initializer = new MigrationInitializer();
        const bundle = await initializer.generateMigrationBundle(config);
        const enrichScript = (initializer as any).generateWorkflowUidEnrichmentScript(bundle.workflows);

        expect(enrichScript).not.toBeNull();
        expect(enrichScript).toContain(
            "data_snapshot_source_snap1=\"$(kubectl get datasnapshots.migrations.opensearch.org/source-snap1 -o jsonpath='{.metadata.uid}')\""
        );
        expect(enrichScript).toContain(
            "snapshot_migration_source_target_snap1_migration_0=\"$(kubectl get snapshotmigrations.migrations.opensearch.org/source-target-snap1-migration-0 -o jsonpath='{.metadata.uid}')\""
        );
        expect(enrichScript).toContain('snapshotMigrations: {');
        expect(enrichScript).toContain('"source-target-snap1-migration-0": $snapshot_migration_source_target_snap1_migration_0');
        expect(enrichScript).toContain(
            '.snapshotMigrations |= ((. // []) | map(. + {resourceUid: $uids.snapshotMigrations[(.sourceLabel + "-" + .targetConfig.label + "-" + .label + "-" + .migrationLabel)]}))'
        );
    });
});
