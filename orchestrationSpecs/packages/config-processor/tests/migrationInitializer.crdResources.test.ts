import { describe, it, expect } from '@jest/globals';
import { MigrationInitializer } from "../src";
import { OVERALL_MIGRATION_CONFIG } from "@opensearch-migrations/schemas";
import { z } from "zod";

describe('migration initializer CRD resource generation', () => {
    it('generates kafka, replay, and approval-gate CR resources with expected names', async () => {
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

        const byKind = (kind: string) =>
            resources.filter((item: any) => item.kind === kind).map((item: any) => item.metadata.name);
        const getResource = (kind: string, name: string) =>
            resources.find((item: any) => item.kind === kind && item.metadata.name === name);

        expect(byKind('KafkaCluster')).toContain('default');
        expect(byKind('CapturedTraffic')).toContain('source-proxy');
        expect(byKind('DataSnapshot')).toContain('source-snap1');
        expect(byKind('SnapshotMigration')).toContain('source-target-snap1');
        expect(byKind('TrafficReplay')).toContain('source-proxy-target-target-replay');
        expect(byKind('ApprovalGate')).toEqual(expect.arrayContaining([
            'source.target.snap1.migration-0.evaluateMetadata',
            'source.target.snap1.migration-0.migrateMetadata',
        ]));

        expect(getResource('KafkaCluster', 'default')?.spec.dependsOn).toEqual([]);
        expect(getResource('CapturedTraffic', 'source-proxy')?.spec.dependsOn).toEqual(['default']);
        expect(getResource('DataSnapshot', 'source-snap1')?.spec.dependsOn).toEqual(['source-proxy']);
        expect(getResource('SnapshotMigration', 'source-target-snap1')?.spec.dependsOn).toEqual(['source-snap1']);
        expect(getResource('TrafficReplay', 'source-proxy-target-target-replay')?.spec.dependsOn).toEqual(['source-proxy']);
    });
});
