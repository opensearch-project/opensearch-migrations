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
            'source.target.snap1.migration-0.evaluatemetadata',
            'source.target.snap1.migration-0.migratemetadata',
            // Kafka VAP retry gates
            'default.kafkacluster.vapretry',
            'default.kafkanodepool.vapretry',
            'default.kafkauser.vapretry',
            'default.kafkatopic.source-proxy.vapretry',
            // Root KafkaCluster CR reconcile gate
            'default.vapretry',
            // Topic and proxy VAP retry gates
            'source-proxy-topic.capturedtraffic.vapretry',
            'source-proxy.captureproxy.vapretry',
            // Replay VAP retry gate
            'source-proxy-target-target-replay.trafficreplay.vapretry',
        ]));

        expect(getResource('KafkaCluster', 'default')?.spec.dependsOn).toBeUndefined();
        expect(getResource('CapturedTraffic', 'source-proxy-topic')?.spec.dependsOn).toEqual(['default']);
        expect(getResource('CaptureProxy', 'source-proxy')?.spec.dependsOn).toEqual(['source-proxy-topic']);
        expect(getResource('DataSnapshot', 'source-snap1')?.spec.dependsOn).toEqual(['source-proxy']);
        expect(getResource('SnapshotMigration', 'source-target-snap1-migration-0')?.spec.dependsOn).toBeUndefined();
        expect(getResource('TrafficReplay', 'source-proxy-target-target-replay')?.spec.dependsOn).toEqual(['source-proxy']);
        expect(getResource('ApprovalGate', 'source.target.snap1.migration-0.evaluateMetadata')?.spec.dependsOn).toBeUndefined();
        expect(getResource('ApprovalGate', 'source.target.snap1.migration-0.migrateMetadata')?.spec.dependsOn).toBeUndefined();

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
            snapshotMigrationConfigs: [{ fromSource: "source", toTarget: "target" }]
        };

        const initializer = new MigrationInitializer();
        const bundle = await initializer.generateMigrationBundle(config, 'my-workflow');
        const gates = bundle.crdResources.items.filter((item: any) => item.kind === 'ApprovalGate');

        // All gates carry the workflow label
        for (const gate of gates) {
            expect(gate.metadata.labels).toEqual({
                [MigrationInitializer.APPROVAL_GATE_LABEL_KEY]: 'my-workflow'
            });
        }

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
            snapshotMigrationConfigs: [{ fromSource: "source", toTarget: "target" }]
        };

        const initializer = new MigrationInitializer();
        const bundle = await initializer.generateMigrationBundle(config);
        const gates = bundle.crdResources.items.filter((item: any) => item.kind === 'ApprovalGate');

        // Gates have no labels
        for (const gate of gates) {
            expect(gate.metadata.labels).toBeUndefined();
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
            "snapshot_migration_source_target_snap1_migration_0=\"$(kubectl get snapshotmigrations.migrations.opensearch.org/source-target-snap1-migration-0 -o jsonpath='{.metadata.uid}')\""
        );
        expect(enrichScript).toContain('snapshotMigrations: {');
        expect(enrichScript).toContain('"source-target-snap1-migration-0": $snapshot_migration_source_target_snap1_migration_0');
        expect(enrichScript).toContain(
            '.snapshotMigrations |= ((. // []) | map(. + {resourceUid: $uids.snapshotMigrations[(.sourceLabel + "-" + .targetConfig.label + "-" + .label + "-" + .migrationLabel)]}))'
        );
    });
});
